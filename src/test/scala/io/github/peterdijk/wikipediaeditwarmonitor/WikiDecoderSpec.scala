package io.github.peterdijk.wikipediaeditwarmonitor

import io.circe.jawn.decode
import io.github.peterdijk.wikipediaeditwarmonitor.WikiTypes.WikiEdit
import munit.FunSuite

import WikiDecoder.given

class WikiDecoderSimpleSpec extends FunSuite:

  test("WikiDecoder decodes simple valid JSON") {
    val json = """{
      "meta": {
        "id": "simple-123"
      },
      "title": "Simple Article",
      "user": "SimpleUser",
      "bot": false,
      "timestamp": 1640995200,
      "comment": "Simple edit",
      "server_name": "simple.wikipedia.org"
    }"""

    val result = decode[WikiEdit](json)
    val expected = WikiEdit(
      id = "simple-123",
      title = "Simple Article",
      user = "SimpleUser",
      bot = false,
      timestamp = 1640995200L,
      comment = "Simple edit",
      serverName = "simple.wikipedia.org"
    )

    assertEquals(result, Right(expected))
  }

  test("WikiDecoder handles bot user") {
    val json = """{
      "meta": {"id": "bot-456"},
      "title": "Bot Edit",
      "user": "AutoBot",
      "bot": true,
      "timestamp": 1640995300,
      "comment": "Automated cleanup",
      "server_name": "en.wikipedia.org"
    }"""

    val result = decode[WikiEdit](json)
    assert(result.isRight)

    val isBot = result.map(_.bot)
    assertEquals(isBot, Right(true))

    val user = result.map(_.user)
    assertEquals(user, Right("AutoBot"))
  }

  test("WikiDecoder handles empty strings") {
    val json = """{
      "meta": {"id": ""},
      "title": "",
      "user": "",
      "bot": false,
      "timestamp": 0,
      "comment": "",
      "server_name": ""
    }"""

    val result = decode[WikiEdit](json)

    assert(result.isRight)
    val title = result.map(_.title)
    assertEquals(title, Right(""))
  }

  test("WikiDecoder fails on missing required fields") {
    val jsonMissingTitle = """{
      "meta": {"id": "missing-title"},
      "user": "User",
      "bot": false,
      "timestamp": 1640995500,
      "comment": "Comment",
      "server_name": "test.org"
    }"""

    val result = decode[WikiEdit](jsonMissingTitle)
    assert(result.isLeft)
  }

  test("WikiDecoder fails on wrong data types") {
    val jsonWrongTypes = """{
      "meta": {"id": "type-test"},
      "title": "Title",
      "user": "User",
      "bot": "not-a-boolean",
      "timestamp": 1640995700,
      "comment": "Comment",
      "server_name": "test.org"
    }"""

    val result = decode[WikiEdit](jsonWrongTypes)
    assert(result.isLeft)
  }

  test("WikiDecoder ignores extra fields") {
    val jsonWithExtras = """{
      "meta": {"id": "extra-fields"},
      "title": "Extra Fields Test",
      "user": "ExtraUser",
      "bot": false,
      "timestamp": 1640995900,
      "comment": "Has extra fields",
      "server_name": "extra.org",
      "extra_field1": "ignored",
      "extra_field2": 42,
      "nested_extra": {"ignored": true}
    }"""

    val result = decode[WikiEdit](jsonWithExtras)
    val expected = WikiEdit(
      id = "extra-fields",
      title = "Extra Fields Test",
      user = "ExtraUser",
      bot = false,
      timestamp = 1640995900L,
      comment = "Has extra fields",
      serverName = "extra.org"
    )

    assertEquals(result, Right(expected))
  }
