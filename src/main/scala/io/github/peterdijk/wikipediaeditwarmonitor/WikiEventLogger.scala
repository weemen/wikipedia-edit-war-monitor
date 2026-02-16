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

  private def logWithStats: fs2.Pipe[F, WikiEdit, Unit] = { stream =>
    for {
      startTime <- Stream.eval(Async[F].monotonic)
      counterRef <- Stream.eval(Ref[F].of(0))
      _ <- stream.parEvalMap(10) { event =>
        (Async[F].monotonic, counterRef.updateAndGet(_ + 1)).mapN {
          (currentTime, count) =>
            val elapsedTime = currentTime - startTime
            formatOutput(count, elapsedTime, event)
        }
      }
    } yield ()
  }

  def subscribeAndLog: F[Unit] =
    broadcastHub
      .subscribe(1000)
      .through(logWithStats)
      // add additional operations on the stream
      .compile
      .drain
