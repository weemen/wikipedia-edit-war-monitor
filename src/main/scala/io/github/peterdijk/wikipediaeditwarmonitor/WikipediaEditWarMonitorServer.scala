package io.github.peterdijk.wikipediaeditwarmonitor

import cats.effect.{Async, LiftIO, Resource}
import cats.Parallel
import cats.syntax.all.*
import com.comcast.ip4s.*
import fs2.io.net.Network
import org.http4s.ember.client.EmberClientBuilder
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.implicits.*
import org.http4s.server.middleware.Logger
import fs2.concurrent.Topic
import io.github.peterdijk.wikipediaeditwarmonitor.WikiTypes.{TracedWikiEdit, WikiEdit}
import org.typelevel.otel4s.oteljava.OtelJava
import org.typelevel.otel4s.trace.Tracer
import io.github.peterdijk.wikipediaeditwarmonitor.middleware.HttpTracingMiddleware
import com.typesafe.config.ConfigFactory
import io.github.peterdijk.wikipediaeditwarmonitor.consumers.StatsConsumer

object WikipediaEditWarMonitorServer:

  def run[F[_]: Async: Network: LiftIO: Parallel]: F[Nothing] = {
    val loadOutDir: F[String] = Async[F].delay {
      val conf = ConfigFactory.load()
      if (conf.hasPath("app.outDir")) conf.getString("app.outDir")
      else "./src/main/resources/out/"
    }

    val resources = for {
      client <- EmberClientBuilder.default[F].build
      otel <- OtelJava.autoConfigured[F]()
      tracer <- Resource.eval(otel.tracerProvider.get(sys.env.getOrElse("OTEL_SERVICE_NAME", "")))
      broadcastHub <- Resource.eval(Topic[F, TracedWikiEdit])
    } yield (client, tracer, broadcastHub)

    resources.use { case (client, tracer, broadcastHub) =>
      given Tracer[F] = tracer

      // both use the same tracer instance, but different spans will be created in each
      // they are concurrently running, but the spans will be separate and can be
      // correlated via attributes if needed
      val wikiStream = WikiStream.impl[F](client, broadcastHub)
      val wikiEventLogger = WikiEventLogger(broadcastHub)
      val statsConsumer = StatsConsumer(broadcastHub)

      val helloWorldAlg = HelloWorld.impl[F]
      val jokeAlg = Jokes.impl[F](client)

      for {
        outDir <- loadOutDir
        routes =
          WikipediaEditWarMonitorRoutes.helloWorldRoutes[F](helloWorldAlg) <+>
            WikipediaEditWarMonitorRoutes.jokeRoutes[F](jokeAlg) <+>
            WikipediaEditWarMonitorRoutes.staticFileRoutes[F](outDir, "/")

        tracedRoutes = HttpTracingMiddleware[F](routes)

        httpApp = tracedRoutes.orNotFound
        // With Middlewares in place
        finalHttpApp = Logger.httpApp(true, true)(httpApp)

        // Start background fibers and HTTP server in parallel
        _ <- Async[F].start(wikiEventLogger.subscribeAndLog)
        _ <- Async[F].start(wikiStream.start)
        _ <- Async[F].start(statsConsumer.countEdits)
        _ <- EmberServerBuilder
          .default[F]
          .withHost(ipv4"0.0.0.0")
          .withPort(port"8080")
          .withHttpApp(finalHttpApp)
          .build
          .useForever
      } yield ()
    }.flatMap(_ => Async[F].never[Nothing])
  }
