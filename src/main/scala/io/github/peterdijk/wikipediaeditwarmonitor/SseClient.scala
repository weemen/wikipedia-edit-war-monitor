package io.github.peterdijk.wikipediaeditwarmonitor

import cats.effect.Temporal
import fs2.{Pull, RaiseThrowable, Stream}
import org.http4s._
import org.http4s.ServerSentEvent.EventId
import org.http4s.client.Client
import org.http4s.headers.{Accept, `Cache-Control`}
import org.typelevel.ci.CIStringSyntax
import org.typelevel.otel4s.trace.{SpanKind, Tracer}

import scala.concurrent.duration._
import SseClient._
import org.typelevel.otel4s.trace.Tracer
import org.typelevel.otel4s.trace.Tracer.Implicits.noop

final class SseClient[F[_]] private (
    httpClient: Client[F],
    maxRetries: Option[Int],
    initialRetryInterval: FiniteDuration
)(using temporal: Temporal[F], rt: RaiseThrowable[F], tracer: Tracer[F]) {

  def stream(uri: Uri): Stream[F, ServerSentEvent] = autoReconnectStream(uri)

  private def autoReconnectStream(uri: Uri): Stream[F, ServerSentEvent] = {
    def handleStreamElement(
        stream: Stream[F, ServerSentEvent],
        metadata: SseMetadata
    ): Pull[F, ServerSentEvent, Unit] =
      // pull one element
      stream.pull.uncons1
        .flatMap {
          case Some((event, tail)) =>
            val newMetadata = metadata.copy(
              eventId = event.id.fold(metadata.eventId)(id =>
                if (id != EventId.reset) Some(id.value) else None
              ),
              // The SSE origin server can add a certain retry value to instruct the client:
              // wait with reconnection for xx time.
              retry = event.retry.fold(metadata.retry)(Some(_))
            )
            val metadataChanged = newMetadata != metadata
            // Emit one element to output
            Pull.output1(event) >> {
              // Add a new error handler only if the metadata changed.
              if (metadataChanged)
                handleStreamEventWithErrorHandled(tail, newMetadata)
              else handleStreamElement(tail, metadata)
            }
          case None =>
            reconnect(metadata, None)
        }

    def reconnect(
        metadata: SseMetadata,
        err: Option[Throwable]
    ): Pull[F, ServerSentEvent, Unit] = {
      val delay = metadata.retry.getOrElse(initialRetryInterval)
      val newMetadata = metadata.copy(attempts = metadata.attempts + 1)

      maxRetries match {
        case Some(max) if newMetadata.attempts > max =>
          Pull.raiseError[F](MaxRetriesReached(err))
        case _ =>
          handleStreamEventWithErrorHandled(
            newStream(uri, newMetadata.eventId).delayBy(delay),
            newMetadata
          )
      }
    }

    def handleStreamEventWithErrorHandled(
        stream: Stream[F, ServerSentEvent],
        metadata: SseMetadata
    ): Pull[F, ServerSentEvent, Unit] =
      handleStreamElement(stream, metadata).handleErrorWith {
        case e: MaxRetriesReached => Pull.raiseError[F](e)
        case other                => reconnect(metadata, Some(other))
      }

    handleStreamEventWithErrorHandled(
      newStream(uri, None),
      SseMetadata(None, None, 1)
    ).stream
  }

  private def newStream(
      uri: Uri,
      eventId: Option[String]
  ): Stream[F, ServerSentEvent] =
    httpClient
      .stream(
        Request(
          uri = uri,
          headers = BaseHeaders ++ Headers(
            eventId.map(Header.Raw(ci"Last-Event-ID", _)).toList
          )
        )
      )
      .flatMap(_.body)
      .through(ServerSentEvent.decoder[F])
      .filter(_.data.nonEmpty)
}

object SseClient {
  val DefaultRetryInterval: FiniteDuration = 5.seconds
  val DefaultMaxRetries: Option[Int] = Some(10)

  def apply[F[_]](
      httpClient: Client[F],
      maxRetries: Option[Int] = DefaultMaxRetries,
      initialRetryInterval: FiniteDuration = DefaultRetryInterval
  )(using temporal: Temporal[F], rt: RaiseThrowable[F]): SseClient[F] =
    new SseClient(httpClient, maxRetries, initialRetryInterval)

  private case class SseMetadata(
      eventId: Option[String],
      retry: Option[FiniteDuration],
      attempts: Int
  )
  private case class MaxRetriesReached(underlying: Option[Throwable])
      extends Exception("Reached maximum number of retries", underlying.orNull)

  private val BaseHeaders: Headers = Headers.apply(
    `Cache-Control`(CacheDirective.`no-cache`()),
    Accept(MediaType.`text/event-stream`)
  )
}
