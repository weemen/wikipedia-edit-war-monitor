package io.github.peterdijk.wikipediaeditwarmonitor

import cats.effect.Async
import org.http4s.client.Client
import org.http4s.implicits.*

import scala.concurrent.duration.*
import org.http4s.ServerSentEvent
import fs2.concurrent.Topic
import io.github.peterdijk.wikipediaeditwarmonitor.middleware.StreamTracingMiddleware

// For the Decoder
import io.github.peterdijk.wikipediaeditwarmonitor.WikiTypes.{WikiEdit, TracedWikiEdit}
import WikiDecoder.given
import io.circe.jawn.decode

import org.typelevel.otel4s.trace.Tracer

trait WikiStream[F[_]]:
  def start: F[Unit]

object WikiStream:
  def apply[F[_]](implicit evidence: WikiStream[F]): WikiStream[F] = evidence

  def sseEventToWikiEdit[F[_]: Async]: fs2.Pipe[F, ServerSentEvent, WikiEdit] =
    (stream: fs2.Stream[F, ServerSentEvent]) =>
      stream.evalMap { sse =>
        val decoded = decode[WikiEdit](sse.data.mkString("\n"))
        Async[F].fromEither(
          decoded.left.map(err =>
            println(
              s"Failed to decode SSE data: ${sse.data.mkString("\n")}"
            )
            err
          )
        )
      }

  def impl[F[_]: Async](
      httpClient: Client[F],
      broadcastHub: Topic[F, TracedWikiEdit]
  )(using tracer: Tracer[F]): WikiStream[F] = new WikiStream[F]:
    def start: F[Unit] =
      val sseClient = SseClient(httpClient, None, 1.second)
      sseClient
        .stream(
          uri"https://stream.wikimedia.org/v2/stream/recentchange"
        )
        .through(sseEventToWikiEdit)
        .through(StreamTracingMiddleware[F])
        .through(broadcastHub.publish)
        .compile
        .drain
