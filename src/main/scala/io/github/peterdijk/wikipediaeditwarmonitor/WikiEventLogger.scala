package io.github.peterdijk.wikipediaeditwarmonitor

import fs2.concurrent.Topic
import fs2.Stream
import cats.effect.{Async, Ref}
import cats.syntax.all._
import scala.concurrent.duration.FiniteDuration

import io.github.peterdijk.wikipediaeditwarmonitor.WikiTypes.{WikiEdit, TracedWikiEdit}
import org.typelevel.otel4s.Attribute
import org.typelevel.otel4s.trace.{Tracer, SpanKind}

final case class WikiEventLogger[F[_]: Async](
    broadcastHub: Topic[F, TracedWikiEdit]
)(using tracer: Tracer[F]):

  private def formatOutput(
      count: Int,
      elapsedTime: FiniteDuration,
      event: WikiEdit
  ) = println(
    s"Event #$count | (elapsed: ${elapsedTime.toSeconds}s) | Average rate: ${count.toDouble / elapsedTime.toSeconds} events/s | WikiEdit: ${event.title} by ${event.user} at ${event.timestamp}"
  )

  private def logWithStats: fs2.Pipe[F, TracedWikiEdit, Unit] = { stream =>
    for {
      startTime <- Stream.eval(Async[F].monotonic)
      counterRef <- Stream.eval(Ref[F].of(0))
      _ <- stream.parEvalMap(10) { tracedEvent =>
        // Create a child span by setting the parent context
        tracer
          .spanBuilder("log_wiki_edit")
          .withParent(tracedEvent.spanContext)
          .withSpanKind(SpanKind.Internal)
          .addAttribute(Attribute("wiki.title", tracedEvent.edit.title))
          .addAttribute(Attribute("wiki.user", tracedEvent.edit.user))
          .build
          .use { span =>
            for {
              currentTime <- Async[F].monotonic
              count <- counterRef.updateAndGet(_ + 1)
              elapsedTime = currentTime - startTime

              // Add logging-specific attributes to the span
              _ <- span.addAttribute(Attribute("log.count", count.toLong))
              _ <- span.addAttribute(Attribute("log.elapsed_seconds", elapsedTime.toSeconds))

              // Format and print output
              _ <- Async[F].delay(formatOutput(count, elapsedTime, tracedEvent.edit))
              // Child span automatically ends when .use block completes
            } yield ()
          }
      }
    } yield ()
  }

  def subscribeAndLog: F[Unit] =
    broadcastHub
      .subscribe(1000)
      .through(logWithStats)
      .compile
      .drain
