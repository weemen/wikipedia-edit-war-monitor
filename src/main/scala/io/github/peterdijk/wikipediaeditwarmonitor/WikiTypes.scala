package io.github.peterdijk.wikipediaeditwarmonitor

import org.typelevel.otel4s.trace.SpanContext

object WikiTypes {
  case class WikiEdit(
      id: String,
      title: String,
      user: String,
      bot: Boolean,
      timestamp: Long,
      comment: String,
      serverName: String
  )
  
  // Wrapper to carry span context through Topic boundaries
  case class TracedWikiEdit(
      edit: WikiEdit,
      spanContext: SpanContext
  )
}
