package io.github.peterdijk.wikipediaeditwarmonitor

import cats.effect.{Async, LiftIO, Resource}
import cats.syntax.all.*
import com.comcast.ip4s.*
import fs2.io.net.Network
import org.http4s.ember.client.EmberClientBuilder
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.implicits.*
import org.http4s.server.middleware.Logger
import fs2.concurrent.Topic
import io.github.peterdijk.wikipediaeditwarmonitor.WikiTypes.{WikiEdit, TracedWikiEdit}
import org.typelevel.otel4s.oteljava.OtelJava
import org.typelevel.otel4s.trace.Tracer
import cats.effect.LiftIO
import io.github.peterdijk.wikipediaeditwarmonitor.middleware.HttpTracingMiddleware

object WikipediaEditWarMonitorServer:

  def run[F[_]: Async: Network: LiftIO]: F[Nothing] = {
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

      val jokeAlg = Jokes.impl[F](client)
      val routes =
          WikipediaEditWarMonitorRoutes.healthRoutes[F](jokeAlg)

      val tracedRoutes = HttpTracingMiddleware[F](routes)

      val httpApp = tracedRoutes.orNotFound
      // With Middlewares in place
      val finalHttpApp = Logger.httpApp(true, true)(httpApp)

      // Start background fibers and HTTP server in parallel
      Async[F].start(wikiEventLogger.subscribeAndLog) *>
        Async[F].start(wikiStream.start) *>
        EmberServerBuilder
          .default[F]
          .withHost(ipv4"0.0.0.0")
          .withPort(port"8080")
          .withHttpApp(finalHttpApp)
          .build
          .useForever
    }
  }
