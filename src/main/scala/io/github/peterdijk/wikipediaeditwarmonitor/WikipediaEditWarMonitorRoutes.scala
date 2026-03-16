package io.github.peterdijk.wikipediaeditwarmonitor

import cats.effect.Sync
import cats.implicits.*
import org.http4s.HttpRoutes
import org.http4s.dsl.Http4sDsl
import cats.effect.Async
import org.http4s.server.staticcontent.fileService
import org.http4s.server.staticcontent.FileService.Config
import fs2.io.file.Files
import org.http4s.websocket.WebSocketFrame
import org.http4s.server.websocket.WebSocketBuilder2
import fs2.concurrent.Topic
import io.github.peterdijk.wikipediaeditwarmonitor.WikiTypes.{TracedWikiEdit, WikiCountsSnapshot, WikiRevertsSnapshot}
import io.circe.syntax.*
import org.http4s.circe.*
import io.github.peterdijk.wikipediaeditwarmonitor.WikiDecoder.given

object WikipediaEditWarMonitorRoutes:

  def healthRoutes[F[_]: Sync](J: Jokes[F]): HttpRoutes[F] =
    val dsl = new Http4sDsl[F]{}
    import dsl._
    HttpRoutes.of[F] {
      case GET -> Root / "health" =>
        for {
          joke <- J.get
          resp <- Ok(joke)
        } yield resp
    }

  def staticFileRoutes[F[_]: Async: Files](systemPath: String, urlPrefix: String): HttpRoutes[F] =
    fileService(Config(systemPath = systemPath, pathPrefix = urlPrefix))

  def webSocketRoutes[F[_]: Async](
      wsb: WebSocketBuilder2[F],
      broadcastHub: Topic[F, WikiCountsSnapshot],
      broadcastEditWarCount: Topic[F, WikiRevertsSnapshot],
  ): HttpRoutes[F] =
    val dsl = new Http4sDsl[F] {}
    import dsl.*
    HttpRoutes.of[F] {
      case GET -> Root / "ws" =>
        val countStream = broadcastHub
          .subscribe(100)
          .map(event => WebSocketFrame.Text(event.asJson.noSpaces))
        val warStream = broadcastEditWarCount
          .subscribe(100)
          .map(event => WebSocketFrame.Text(event.asJson.noSpaces))

        val combinedStream = countStream.merge(warStream)
        wsb.build(combinedStream, _.drain)
    }
