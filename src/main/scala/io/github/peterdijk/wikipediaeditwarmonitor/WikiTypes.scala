package io.github.peterdijk.wikipediaeditwarmonitor

import org.typelevel.otel4s.trace.SpanContext

object WikiTypes {

  enum EditType {
      case `categorize`
      case `edit`
      case `log`
      case `new`
  }
  case class WikiEdit(
      id: String,
      title: String,
      title_url: String,
      user: String,
      bot: Boolean,
      timestamp: Long,
      comment: String,
      serverName: String,
      editType: EditType,
  )

  case class TracedWikiEdit(
      edit: WikiEdit,
      spanContext: SpanContext
  )

  case class WikiPage(
      title: String,
      title_url: String,
  )

  case class WikiCountsSnapshot(
      users: Map[String, Int],
      titles: Map[WikiPage, Int],
      bots: Map[Boolean, Int]
  )

  case class WikiRevertsSnapshot(
      revertsCount: Map[WikiPage, Int]
  )
}
