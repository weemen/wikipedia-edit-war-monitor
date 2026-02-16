package io.github.peterdijk.wikipediaeditwarmonitor

import io.circe.Decoder.Result
import io.circe.{Decoder, HCursor}

case class WikiEdit(
    id: String,
    title: String,
    user: String,
    bot: Boolean,
    timestamp: Long,
    comment: String,
    serverName: String
)

object WikiDecoder {
  given wikiEditDecoder: Decoder[WikiEdit] = new Decoder[WikiEdit] {
    override def apply(c: HCursor): Result[WikiEdit] =
      for {
        id <- c.downField("meta").downField("id").as[String]
        title <- c.downField("title").as[String]
        user <- c.downField("user").as[String]
        bot <- c.downField("bot").as[Boolean]
        timestamp <- c.downField("timestamp").as[Long]
        comment <- c.downField("comment").as[String]
        serverName <- c.downField("server_name").as[String]
      } yield {
        WikiEdit(id, title, user, bot, timestamp, comment, serverName)
      }
  }
}
