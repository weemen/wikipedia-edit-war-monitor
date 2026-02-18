package io.github.peterdijk.wikipediaeditwarmonitor

import cats.effect.IO
import fs2.Stream
import munit.CatsEffectSuite
import org.http4s.ServerSentEvent
import io.github.peterdijk.wikipediaeditwarmonitor.WikiTypes.WikiEdit

class WikiStreamSpec extends CatsEffectSuite:

  test("sseEventToWikiEdit parse ServerSentEvent to WikiEdit") {
    val validJson = """{"$schema":"/mediawiki/recentchange/1.0.0","meta":{"uri":"https://commons.wikimedia.org/wiki/Category:Deletion_requests_February_2026","request_id":"904fa6c6-3f82-4b9f-99e1-d1594d525697","id":"a5ceba11-1c2c-4a2f-9d5d-a21114d936a9","domain":"commons.wikimedia.org","stream":"mediawiki.recentchange","dt":"2026-02-18T14:54:14.407Z","topic":"codfw.mediawiki.recentchange","partition":0,"offset":1992673513},"id":3202826349,"type":"categorize","namespace":14,"title":"Category:Deletion requests February 2026","title_url":"https://commons.wikimedia.org/wiki/Category:Deletion_requests_February_2026","comment":"[[:File:Ensemble officiel de danses populaires de l'U.R.S.S. - btv1b106029063 (592 of 745).jpg]] added to category","timestamp":1771426452,"user":"Günther Frager","bot":false,"notify_url":"https://commons.wikimedia.org/w/index.php?diff=1168190180&oldid=1023815488&rcid=3202826349","server_url":"https://commons.wikimedia.org","server_name":"commons.wikimedia.org","server_script_path":"/w","wiki":"commonswiki","parsedcomment":"<a href=\"/wiki/File:Ensemble_officiel_de_danses_populaires_de_l%27U.R.S.S._-_btv1b106029063_(592_of_745).jpg\" title=\"File:Ensemble officiel de danses populaires de l&#039;U.R.S.S. - btv1b106029063 (592 of 745).jpg\">File:Ensemble officiel de danses populaires de l&#039;U.R.S.S. - btv1b106029063 (592 of 745).jpg</a> added to category"}"""
    val sseEvent = ServerSentEvent(data = Some(validJson), eventType = Some("message"))
    val stream = Stream.emit(sseEvent)

    val result = stream
      .through(WikiStream.sseEventToWikiEdit[IO])
      .compile
      .toList

    val expected = WikiEdit(
      id = "a5ceba11-1c2c-4a2f-9d5d-a21114d936a9",
      title = "Category:Deletion requests February 2026",
      user = "Günther Frager",
      bot = false,
      timestamp = 1771426452L,
      comment = "[[:File:Ensemble officiel de danses populaires de l'U.R.S.S. - btv1b106029063 (592 of 745).jpg]] added to category",
      serverName = "commons.wikimedia.org"
    )

    assertIO(result, List(expected))
  }
