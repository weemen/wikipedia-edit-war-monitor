package io.github.peterdijk.wikipediaeditwarmonitor.consumers
import cats.effect.{Async, Ref}
import com.typesafe.config.ConfigFactory
import fs2.concurrent.Topic
import io.github.peterdijk.wikipediaeditwarmonitor.WikiTypes.{EditType, TracedWikiEdit, WikiPage, WikiRevertsSnapshot}
import cats.syntax.all.*

import scala.util.matching.Regex
import scala.concurrent.duration.*
import fs2.io.file.Files
import fs2.Stream
import org.typelevel.otel4s.trace.Tracer
import org.typelevel.otel4s.Attribute

final case class EditWarConsumer[F[_]: Async](
    broadcastHub: Topic[F, TracedWikiEdit],
    broadcastEditWarCount: Topic[F, WikiRevertsSnapshot]
)(using tracer: Tracer[F]):

  def countEditWars: F[Unit] = {
    val stream = broadcastHub.subscribe(1000)

    // Load output directory from configuration (application.conf: app.outDir)
    val loadOutDir: F[String] = Async[F].delay {
      val conf = ConfigFactory.load()
      if (conf.hasPath("app.outDir")) conf.getString("app.outDir")
      else ""
    }

    for {
      titleCountRef <- Ref.of[F, Map[WikiPage, Int]](Map.empty)

      producer = Stream
        .awakeEvery[F](2.seconds)
        .evalMap { _ =>
          for {
            titleCounts <- titleCountRef.get
          } yield WikiRevertsSnapshot(titleCounts)
        }
        .through(broadcastEditWarCount.publish)

      counter = stream
        .through(filterTypeEdit)
        .through(filterReverts)
        .parEvalMap(10) { event =>
          incrementCounts(event, titleCountRef)
        }
      _ <- Stream(counter, producer.drain).parJoinUnbounded.compile.drain
    } yield ()
  }

  def incrementCounts(
      event: TracedWikiEdit,
      titleCountRef: Ref[F, Map[WikiPage, Int]]
  ): F[Unit] = {
    tracer
      .spanBuilder("process_edit_war_event")
      .withParent(event.spanContext)
      .addAttribute(Attribute("wiki.title", event.edit.title))
      .addAttribute(Attribute("wiki.user", event.edit.user))
      .build
      .use { _ =>
        incrementTitlesCount(
          WikiPage(event.edit.title, event.edit.title_url),
          titleCountRef,
          "page"
        ).void
      }
  }

  private def incrementTitlesCount[K](key: WikiPage, ref: Ref[F, Map[WikiPage, Int]], label: String): F[Unit] =
    ref.updateAndGet { counts =>
      val newCount = counts.getOrElse(key, 0) + 1
      counts + (key -> newCount)
    }.void

  private def filterTypeEdit: fs2.Pipe[F, TracedWikiEdit, TracedWikiEdit] =
    _.filter(_.edit.editType == EditType.edit)

  private val revertKeywordsRegex: Regex = List(
    // English
    "revert", "undid", "undo", "rollback", "rv",
    // Spanish
    "deshacer",
    // French
    "annuler", "révocation", "rétablir", "annulé", "révoqué",
    // German
    "rückgängig", "zurücksetzen", "rückgängigmachen",
    // Italian
    "annullare", "ripristinare", "annullato", "ripristinato",
    // Portuguese
    "desfazer", "revertido", "desfeito",
    // Dutch
    "ongedaan", "herstellen", "terugdraaien", "ongedaan maken",
    // Russian
    "отменить", "откатить", "отмена", "откат",
    // Polish
    "cofnij", "przywróć", "cofnięcia", "przywrócenie",
    // Swedish
    "återställ", "ångra", "återställning",
    // Norwegian
    "tilbakestill", "angre", "tilbakestilling",
    // Danish
    "fortryd", "gendanne", "tilbagerulning",
    // Finnish
    "kumoa", "palauta", "kumoaminen", "palautus",
    // Japanese
    "差し戻し", "取り消し", "巻き戻し",
    // Chinese (Simplified & Traditional)
    "回退", "撤销", "恢复", "復原", "撤銷",
    // Arabic
    "تراجع", "إلغاء", "استرجاع",
    // Hebrew
    "ביטול", "החזרה", "שחזור",
    // Korean
    "되돌리기", "취소", "복구",
    // Turkish
    "geri al", "iptal", "geri alma"
  ).mkString("|").r

  private def filterReverts: fs2.Pipe[F, TracedWikiEdit, TracedWikiEdit] =
    _.parEvalMapUnordered(10) { event =>
      tracer
        .spanBuilder("filter_revert_check")
        .withParent(event.spanContext)
        .addAttribute(Attribute("wiki.title", event.edit.title))
        .addAttribute(Attribute("wiki.comment", event.edit.comment))
        .build
        .use { span =>
          val isRevert = revertKeywordsRegex.findFirstMatchIn(event.edit.comment.toLowerCase).isDefined
          span.addAttribute(Attribute("wiki.is_revert", isRevert)).as(Option.when(isRevert)(event))
        }
    }.collect { case Some(event) => event }

