package io.github.peterdijk.wikipediaeditwarmonitor

import cats.effect.Async
import cats.syntax.all._
import com.comcast.ip4s._
import fs2.io.net.Network
import fs2.io.file.Files
import org.http4s.ember.client.EmberClientBuilder
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.implicits._
import org.http4s.server.middleware.Logger
import fs2.concurrent.Topic

import io.github.peterdijk.wikipediaeditwarmonitor.WikiTypes.WikiEdit

object WikipediaEditWarMonitorServer:

  def run[F[_]: Async: Network: cats.Parallel: Files]: F[Nothing] = {
    EmberClientBuilder.default[F].build.use { client =>
      Topic[F, WikiEdit].flatMap { broadcastHub =>
        val wikiStream = WikiStream.impl[F](client, broadcastHub)
        val wikiEventLogger = WikiEventLogger(broadcastHub)
        val wikiPageCounter = WikiPageCounter(broadcastHub)

        val helloWorldAlg = HelloWorld.impl[F]
        val jokeAlg = Jokes.impl[F](client)

        val httpApp = (
          WikipediaEditWarMonitorRoutes.helloWorldRoutes[F](helloWorldAlg) <+>
            WikipediaEditWarMonitorRoutes.jokeRoutes[F](jokeAlg) <+>
            WikipediaEditWarMonitorRoutes.statsRoutes[F]
        ).orNotFound

        // With Middlewares in place
        val finalHttpApp = Logger.httpApp(true, true)(httpApp)

        val loggingFiber = Async[F].start(wikiEventLogger.subscribeAndLog)
        val counterFiber = Async[F].start(wikiPageCounter.countEdits)
        val ingestionFiber = Async[F].start(wikiStream.start)

        ingestionFiber *> loggingFiber *> counterFiber *>
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
