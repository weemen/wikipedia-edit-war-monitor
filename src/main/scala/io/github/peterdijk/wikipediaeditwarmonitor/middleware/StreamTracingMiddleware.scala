package io.github.peterdijk.wikipediaeditwarmonitor.middleware

import cats.effect.Async
import cats.syntax.all._
import org.http4s.client.Client
import org.http4s.implicits.*

import scala.concurrent.duration.*
import org.http4s.ServerSentEvent
import fs2.concurrent.Topic
import io.github.peterdijk.wikipediaeditwarmonitor.WikiTypes.{WikiEdit, TracedWikiEdit}
import org.typelevel.otel4s.trace.{SpanKind, Tracer}
import org.typelevel.otel4s.Attribute

object StreamTracingMiddleware {

  /** Creates a span for each WikiEdit and captures the span context.
    * The span stays open while we capture its context, then we wrap
    * the event with that context so downstream can create child spans.
    */
  def streamTracingMiddleware[F[_]: Async](using tracer: Tracer[F]): fs2.Pipe[F, WikiEdit, TracedWikiEdit] =
    (stream: fs2.Stream[F, WikiEdit]) =>
      stream.evalMap { wikiEdit =>
        tracer
          .spanBuilder("process_wiki_edit")
          .withSpanKind(SpanKind.Consumer)
          .addAttribute(Attribute("wiki.title", wikiEdit.title))
          .addAttribute(Attribute("wiki.user", wikiEdit.user))
          .addAttribute(Attribute("wiki.bot", wikiEdit.bot))
          .addAttribute(Attribute("wiki.server", wikiEdit.serverName))
          .build
          .use { span =>
            // Capture the span context while the span is active
            // span.context returns SpanContext directly (not wrapped in F)
            val spanContext = span.context
            Async[F].pure(TracedWikiEdit(wikiEdit, spanContext))
            // Span ends here, but we've captured its context
          }
      }
}
