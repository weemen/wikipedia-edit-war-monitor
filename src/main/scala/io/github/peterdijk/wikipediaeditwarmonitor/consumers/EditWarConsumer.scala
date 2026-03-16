package io.github.peterdijk.wikipediaeditwarmonitor.consumers
import cats.effect.{Async, Ref}
import com.typesafe.config.ConfigFactory
import fs2.concurrent.Topic
import io.github.peterdijk.wikipediaeditwarmonitor.WikiTypes.{EditType, WikiRevertsSnapshot, TracedWikiEdit, WikiPage}
import cats.syntax.all.*

import scala.concurrent.duration.*
import fs2.io.file.Files
import fs2.Stream

final case class EditWarConsumer[F[_]: Async](
    broadcastHub: Topic[F, TracedWikiEdit],
    broadcastEditWarCount: Topic[F, WikiRevertsSnapshot]
):

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
    val suffix = if (event.edit.bot) then "Bot" else "Human"
    (
      incrementTitlesCount(WikiPage(event.edit.title, event.edit.title_url), titleCountRef, "page")
    ).void
  }

  private def incrementTitlesCount[K](key: WikiPage, ref: Ref[F, Map[WikiPage, Int]], label: String): F[Unit] =
    ref.updateAndGet { counts =>
      val newCount = counts.getOrElse(key, 0) + 1
      counts + (key -> newCount)
    }.void

  private def filterTypeEdit: fs2.Pipe[F, TracedWikiEdit, TracedWikiEdit] =
    _.filter(_.edit.editType == EditType.edit)

  def filterReverts[F[_]: Async]: fs2.Pipe[F, TracedWikiEdit, TracedWikiEdit] = {
    _.filter(_.edit.comment.toLowerCase.contains("revert"))
    // val revertPatterns = List(
    //   "revert".r,
    //   "undid".r,
    //   "revertides".r
    //   // TODO: add more languages
    // )
    // _.filter { edit =>
    //   revertPatterns.exists(_.findFirstIn(edit.edit.comment).isDefined)
    // }
  }
