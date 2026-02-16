package io.github.peterdijk.wikipediaeditwarmonitor

import fs2.concurrent.Topic
import fs2.Stream
import cats.effect.{Async, Ref}
import cats.syntax.all._
import scala.concurrent.duration.FiniteDuration

import io.github.peterdijk.wikipediaeditwarmonitor.WikiTypes.WikiEdit

final case class WikiEventLogger[F[_]: Async](
    broadcastHub: Topic[F, WikiEdit]
):

  private def formatOutput(
      count: Int,
      elapsedTime: FiniteDuration,
      event: WikiEdit
  ) = println(
    s"Event #$count | (elapsed: ${elapsedTime.toSeconds}s) | Average rate: ${count.toDouble / elapsedTime.toSeconds} events/s | WikiEdit: ${event.title} by ${event.user} at ${event.timestamp}"
  )

  def subscribeAndLog: F[Unit] =
    val stream = broadcastHub.subscribe(1000)

    val log = for {
      startTime <- Stream.eval(Async[F].monotonic)
      counterRef <- Stream.eval(Ref[F].of(0))
      _ <- stream.parEvalMap(10) { event =>
        (Async[F].monotonic, counterRef.updateAndGet(_ + 1)).mapN {
          (currentTime, count) =>
            formatOutput(count, currentTime - startTime, event)
        }
      }
    } yield ()
    log.compile.drain
