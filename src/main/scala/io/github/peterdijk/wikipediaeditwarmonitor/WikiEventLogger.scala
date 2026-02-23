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

  private def addSpan: fs2.Pipe[F, TracedWikiEdit, TracedWikiEdit] = _.parEvalMap(10) { tracedEvent =>
    tracer
      .spanBuilder("log_wiki_edit")
      .withParent(tracedEvent.spanContext)
      .withSpanKind(SpanKind.Internal)
      .addAttribute(Attribute("wiki.title", tracedEvent.edit.title))
      .addAttribute(Attribute("wiki.user", tracedEvent.edit.user))
      .build
      .use { span =>
        Async[F].pure(tracedEvent)
      }
  }

  private def addStats: fs2.Pipe[F, TracedWikiEdit, (TracedWikiEdit, Int, FiniteDuration)] = { stream =>
    Stream.eval(Async[F].monotonic).flatMap { startTime =>
      Stream.eval(Ref[F].of(0)).flatMap { counterRef =>
        stream.parEvalMap(10) { tracedEvent =>
          for {
            currentTime <- Async[F].monotonic
            count <- counterRef.updateAndGet(_ + 1)
            elapsedTime = currentTime - startTime
          } yield (tracedEvent, count, elapsedTime)
        }
      }
    }
  }

  private def printLogs: fs2.Pipe[F, (TracedWikiEdit, Int, FiniteDuration), Unit] = _.evalMap {
    case (tracedEvent, count, elapsedTime) =>
      Async[F].delay(formatOutput(count, elapsedTime, tracedEvent.edit))
  }

  private def formatOutput(
      count: Int,
      elapsedTime: FiniteDuration,
      event: WikiEdit
  ) = println(
    s"Event #$count | (elapsed: ${elapsedTime.toSeconds}s) | Average rate: ${count.toDouble / elapsedTime.toSeconds} events/s | WikiEdit: ${event.title} by ${event.user} at ${event.timestamp}"
  )

  def subscribeAndLog: F[Unit] =
    broadcastHub
      .subscribe(1000)
      .through(addSpan)
      .through(addStats)
      .through(printLogs)
      .compile
      .drain
