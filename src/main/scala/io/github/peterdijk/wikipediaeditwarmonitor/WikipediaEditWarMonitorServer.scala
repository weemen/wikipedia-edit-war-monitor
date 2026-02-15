package io.github.peterdijk.wikipediaeditwarmonitor

import cats.effect.{Async, Resource, LiftIO}
import cats.syntax.all.*
import com.comcast.ip4s.*
import fs2.io.net.Network
import io.github.peterdijk.wikipediaeditwarmonitor.middleware.HttpTracingMiddleware
import org.http4s.ember.client.EmberClientBuilder
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.implicits.*
import org.http4s.server.middleware.Logger
import org.typelevel.otel4s.oteljava.OtelJava
import org.typelevel.otel4s.trace.Tracer

object WikipediaEditWarMonitorServer:

  def run[F[_]: Async: Network: LiftIO]: F[Nothing] = {
    val resources = for {
      client <- EmberClientBuilder.default[F].build
      otel <- OtelJava.autoConfigured[F]()
      tracer <- Resource.eval(otel.tracerProvider.get("WikipediaEditWarMonitor"))
    } yield (client, tracer)

    resources.use { case (client, tracer) =>
      given Tracer[F] = tracer
      val wikiAlgebra = WikiSource.impl[F](client)
      val helloWorldAlg = HelloWorld.impl[F]
      val jokeAlg = Jokes.impl[F](client)

      // Combine Service Routes into an HttpApp.
      // Can also be done via a Router if you
      // want to extract a segments not checked
      // in the underlying routes.
      val routes =
        WikipediaEditWarMonitorRoutes.helloWorldRoutes[F](helloWorldAlg) <+>
        WikipediaEditWarMonitorRoutes.jokeRoutes[F](jokeAlg)

      val tracedRoutes = HttpTracingMiddleware[F](routes)
      val httpApp = tracedRoutes.orNotFound

      // With Middlewares in place
      val finalHttpApp = Logger.httpApp(true, true)(httpApp)

      // Start the Wikipedia SSE stream as a background fiber
      Async[F].start(wikiAlgebra.streamEvents) *>
        EmberServerBuilder.default[F]
          .withHost(ipv4"0.0.0.0")
          .withPort(port"8080")
          .withHttpApp(finalHttpApp)
          .build
          .useForever
    }
  }
