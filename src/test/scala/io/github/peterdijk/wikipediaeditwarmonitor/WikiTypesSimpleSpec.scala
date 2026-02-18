package io.github.peterdijk.wikipediaeditwarmonitor

import munit.FunSuite
import io.github.peterdijk.wikipediaeditwarmonitor.WikiTypes.{WikiEdit, TracedWikiEdit}
import org.typelevel.otel4s.trace.SpanContext

class WikiTypesSimpleSpec extends FunSuite:

  test("WikiEdit creates with all fields") {
    val edit = WikiEdit(
      id = "12345",
      title = "Test Article",
      user = "TestUser",
      bot = false,
      timestamp = 1638360000L,
      comment = "Added some content",
      serverName = "en.wikipedia.org"
    )

    assertEquals(edit.id, "12345")
    assertEquals(edit.title, "Test Article")
    assertEquals(edit.user, "TestUser")
    assertEquals(edit.bot, false)
    assertEquals(edit.timestamp, 1638360000L)
    assertEquals(edit.comment, "Added some content")
    assertEquals(edit.serverName, "en.wikipedia.org")
  }

  test("WikiEdit handles bot user") {
    val edit = WikiEdit(
      id = "bot123",
      title = "Automated Edit",
      user = "BotUser",
      bot = true,
      timestamp = 1638360001L,
      comment = "Automated cleanup",
      serverName = "commons.wikimedia.org"
    )

    assertEquals(edit.bot, true)
    assertEquals(edit.user, "BotUser")
  }

  test("WikiEdit handles empty strings") {
    val edit = WikiEdit(
      id = "",
      title = "",
      user = "",
      bot = false,
      timestamp = 0L,
      comment = "",
      serverName = ""
    )

    assertEquals(edit.id, "")
    assertEquals(edit.title, "")
    assertEquals(edit.user, "")
    assertEquals(edit.comment, "")
    assertEquals(edit.serverName, "")
  }

  test("TracedWikiEdit creates with WikiEdit and SpanContext") {
    val edit = WikiEdit("1", "Title", "User", false, 123L, "Comment", "server")
    val spanContext = SpanContext.invalid
    val tracedEdit = TracedWikiEdit(edit, spanContext)

    assertEquals(tracedEdit.edit, edit)
    assertEquals(tracedEdit.spanContext, spanContext)
  }

  test("TracedWikiEdit allows access to nested WikiEdit fields") {
    val edit = WikiEdit("nested-1", "Nested Title", "NestedUser", true, 456L, "Nested comment", "nested.server")
    val tracedEdit = TracedWikiEdit(edit, SpanContext.invalid)

    assertEquals(tracedEdit.edit.id, "nested-1")
    assertEquals(tracedEdit.edit.title, "Nested Title")
    assertEquals(tracedEdit.edit.user, "NestedUser")
    assertEquals(tracedEdit.edit.bot, true)
    assertEquals(tracedEdit.edit.timestamp, 456L)
  }
