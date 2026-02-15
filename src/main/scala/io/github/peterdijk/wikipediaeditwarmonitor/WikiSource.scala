package io.github.peterdijk.wikipediaeditwarmonitor

import cats.effect.{Async, Concurrent, Ref}
import cats.implicits.*

import fs2.Stream
import org.http4s.*
// TODO: use http4s.ServerSentEvent
// for example: https://gist.github.com/izeigerman/09cf83a44a590e734873e552ae060fc4
// import org.http4s.ServerSentEvent
import org.http4s.implicits.*
import org.http4s.client.Client
import org.http4s.client.dsl.Http4sClientDsl
import org.http4s.Method.*
import org.typelevel.ci.CIStringSyntax
import scala.concurrent.duration.FiniteDuration

trait WikiSource[F[_]]:
  def streamEvents: F[Unit]

object WikiSource:
  def apply[F[_]](implicit ev: WikiSource[F]): WikiSource[F] = ev

  private final case class WikiStreamError(e: Throwable)
      extends RuntimeException
  case class SSEEvent(id: Option[String], data: String)

  /*
  In Scala 3, when you write new WikiSource[F]:, it's creating an anonymous class that
  implements the trait. This is valid syntax - the colon (:) after the trait name
  indicates that the implementation follows using indentation-based syntax.
   */

  def impl[F[_]: Async](client: Client[F]): WikiSource[F] = new WikiSource[F]:
    val dsl: Http4sClientDsl[F] = new Http4sClientDsl[F] {}
    import dsl.*

    // Parse SSE events with id and data fields.
    def processResponseLines(response: Response[F]): Stream[F, SSEEvent] =
      response.body
        .through((input: Stream[F, Byte]) => {
          fs2.text.utf8.decode(input)
        })
        .through((input: Stream[[x] =>> F[x], String]) => {
          fs2.text.lines(input)
        })
        .filter(!_.startsWith(":")) // Filter out SSE comment lines (heartbeats)
        .split(_.isEmpty) // Split on blank lines (event boundaries)
        .map { lines =>
          var eventId: Option[String] = None
          /* List.newBuilder[String] is used for performance - it's the efficient way to build
          a List when you don't know the size upfront and need to add elements imperatively.
          List.empty + append line creates and returns a new list each time. Infefficient.
          // Efficient - O(1) per addition
            val dataLines = List.newBuilder[String]
            lines.foreach { line =>
              dataLines += line  // O(1) - just adds to internal buffer
            }
            val result = dataLines.result()  // O(n) - builds final list once
           */
          val dataLines = List.newBuilder[String]

          lines.foreach { line =>
            if line.startsWith("id: ") then
              eventId = Some(line.stripPrefix("id: ").trim)
            else if line.startsWith("data: ") then
              dataLines += line.stripPrefix("data: ")
          }

          SSEEvent(eventId, dataLines.result().mkString("\n"))
        }
        .filter(_.data.nonEmpty) // Only emit events with data

    // TODO: make into Decoder
    def formatOutput(
        count: Int,
        retries: Int,
        elapsedTime: FiniteDuration,
        event: SSEEvent
    ) = println(
      s"Event #$count | retries: $retries | id: ${event.id.getOrElse("none")} (elapsed: ${elapsedTime.toSeconds}s) | Average rate: ${count.toDouble / elapsedTime.toSeconds} events/s | Data: ${event.data.take(80)}"
    )

    def streamEvents: F[Unit] =
      def makeStream(
          retries: Int,
          firstStartTime: Option[FiniteDuration],
          firstCounter: Option[Ref[F, Int]],
          lastEventId: Option[String]
      ): Stream[F, Unit] =
        val request = lastEventId match
          case Some(eventId) =>
            GET(
              uri"https://stream.wikimedia.org/v2/stream/recentchange",
              Header.Raw(ci"Last-Event-Id", eventId)
            )
          case None =>
            GET(uri"https://stream.wikimedia.org/v2/stream/recentchange")

        for {
          startTime <- Stream.eval(
            firstStartTime.fold(Async[F].monotonic)(Async[F].pure)
          )
          counterRef <- Stream.eval(
            firstCounter.fold(Ref[F].of(0))(Async[F].pure)
          )
          lastIdRef <- Stream.eval(Ref[F].of(lastEventId))
          _ <- client
            .stream(request)
            .flatMap { response =>
              processResponseLines(response)
                .parEvalMap(1)(
                  event => // is there still backpressure if its places after processResponseLines?
                    for {
                      _ <- event.id.fold(Async[F].unit)(id =>
                        lastIdRef.set(Some(id))
                      )
                      count <- counterRef.updateAndGet(_ + 1)
                      currentTime <- Async[F].monotonic
                      elapsedTime = currentTime - startTime
                      _ <- Concurrent[F]
                        .delay(formatOutput(count, retries, elapsedTime, event))
                    } yield ()
                )
            }
            .handleErrorWith { _ =>
              Stream.eval(lastIdRef.get).flatMap { lastId =>
                makeStream(
                  retries + 1,
                  Some(startTime),
                  Some(counterRef),
                  lastId
                )
              }
            }
        } yield ()

      makeStream(0, None, None, None).compile.drain
