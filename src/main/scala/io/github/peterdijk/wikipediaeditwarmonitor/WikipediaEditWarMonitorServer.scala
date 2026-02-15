package io.github.peterdijk.wikipediaeditwarmonitor

import cats.effect.Async
import cats.syntax.all._
import com.comcast.ip4s._
import fs2.io.net.Network
import org.http4s.ember.client.EmberClientBuilder
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.implicits._
import org.http4s.server.middleware.Logger
import org.http4s.ServerSentEvent
import fs2.concurrent.Topic

object WikipediaEditWarMonitorServer:

  def run[F[_]: Async: Network]: F[Nothing] = {
    EmberClientBuilder.default[F].build.use { client =>
      Topic[F, ServerSentEvent].flatMap { broadcastHub =>
        val wikiStream = WikiStream.impl[F](client, broadcastHub)
        val wikiEventLogger = WikiEventLogger(broadcastHub)

        val helloWorldAlg = HelloWorld.impl[F]
        val jokeAlg = Jokes.impl[F](client)

        val httpApp = (
          WikipediaEditWarMonitorRoutes.helloWorldRoutes[F](helloWorldAlg) <+>
            WikipediaEditWarMonitorRoutes.jokeRoutes[F](jokeAlg)
        ).orNotFound

        // With Middlewares in place
        val finalHttpApp = Logger.httpApp(true, true)(httpApp)

        val loggingFiber = Async[F].start(wikiEventLogger.subscribeAndLog)
        val inputStreamFiber = Async[F].start(wikiStream.start)

        inputStreamFiber *> loggingFiber *>
          EmberServerBuilder
            .default[F]
            .withHost(ipv4"0.0.0.0")
            .withPort(port"8080")
            .withHttpApp(finalHttpApp)
            .build
            .useForever
      }
    }
  }
