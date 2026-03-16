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
import io.github.peterdijk.wikipediaeditwarmonitor.WikiTypes.{WikiCountsSnapshot, TracedWikiEdit, WikiEdit, WikiRevertsSnapshot}
import org.typelevel.otel4s.oteljava.OtelJava
import org.typelevel.otel4s.trace.Tracer
import io.github.peterdijk.wikipediaeditwarmonitor.middleware.HttpTracingMiddleware
import com.typesafe.config.ConfigFactory
import io.github.peterdijk.wikipediaeditwarmonitor.consumers.StatsConsumer
import io.github.peterdijk.wikipediaeditwarmonitor.consumers.EditWarConsumer

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
      broadcastHubWikiCount <- Resource.eval(Topic[F, WikiCountsSnapshot])
      broadcastEditWarCount <- Resource.eval(Topic[F, WikiRevertsSnapshot])
    } yield (client, tracer, broadcastHub, broadcastHubWikiCount, broadcastEditWarCount)

    resources.use { case (client, tracer, broadcastHub, broadcastHubWikiCount, broadcastEditWarCount) =>
      given Tracer[F] = tracer

      // both use the same tracer instance, but different spans will be created in each
      // they are concurrently running, but the spans will be separate and can be
      // correlated via attributes if needed
      val wikiStream = WikiStream.impl[F](client, broadcastHub)
      val wikiEventLogger = WikiEventLogger(broadcastHub)
      val statsConsumer = StatsConsumer(broadcastHub, broadcastHubWikiCount)
      val editWarConsumer = EditWarConsumer(broadcastHub, broadcastEditWarCount)

      val jokeAlg = Jokes.impl[F](client)

      for {
        outDir <- loadOutDir
        _ <- Async[F].start(wikiEventLogger.subscribeAndLog)
        _ <- Async[F].start(wikiStream.start)
        _ <- Async[F].start(statsConsumer.countEdits)
        _ <- Async[F].start(editWarConsumer.countEditWars)

        server <- EmberServerBuilder
          .default[F]
          .withHost(ipv4"0.0.0.0")
          .withPort(port"8080")
          .withHttpWebSocketApp { (wsb: org.http4s.server.websocket.WebSocketBuilder2[F]) =>
            val routes =
                WikipediaEditWarMonitorRoutes.healthRoutes[F](jokeAlg) <+>
                WikipediaEditWarMonitorRoutes.webSocketRoutes[F](wsb, broadcastHubWikiCount, broadcastEditWarCount) <+>
                WikipediaEditWarMonitorRoutes.staticFileRoutes[F](outDir, "/")

            val tracedRoutes = HttpTracingMiddleware[F](routes)
            val httpApp = tracedRoutes.orNotFound
            Logger.httpApp(true, true)(httpApp)
          }
          .build
          .useForever
      } yield ()
    }.flatMap(_ => Async[F].never[Nothing])
  }
