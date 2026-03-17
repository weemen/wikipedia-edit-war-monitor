package io.github.peterdijk.wikipediaeditwarmonitor.consumers
import cats.effect.{Async, Ref}
import com.typesafe.config.ConfigFactory
import fs2.concurrent.Topic
import io.github.peterdijk.wikipediaeditwarmonitor.WikiTypes.{TracedWikiEdit, WikiCountsSnapshot, WikiPage}
import cats.syntax.all.*

import scala.concurrent.duration.*
import fs2.io.file.Files
import fs2.Stream
import org.typelevel.otel4s.Attribute
import org.typelevel.otel4s.trace.Tracer

final case class StatsConsumer[F[_]: Async: cats.Parallel: Files](
    broadcastHub: Topic[F, TracedWikiEdit],
    broadcastHubWikiCounts: Topic[F, WikiCountsSnapshot]
)(using tracer: Tracer[F]):

  def countEdits: F[Unit] = {
    val stream = broadcastHub.subscribe(1000)

    // Load output directory from configuration (application.conf: app.outDir)
    val loadOutDir: F[String] = Async[F].delay {
      val conf = ConfigFactory.load()
      if (conf.hasPath("app.outDir")) conf.getString("app.outDir")
      else ""
    }

    for {
      userCountRef  <- Ref.of[F, Map[String, Int]](Map.empty)
      titleCountRef <- Ref.of[F, Map[WikiPage, Int]](Map.empty)
      botCountRef   <- Ref.of[F, Map[Boolean, Int]](Map.empty)

      producer = Stream
        .awakeEvery[F](2.seconds)
        .evalMap { _ =>
          for {
            userCounts  <- userCountRef.get
            titleCounts <- titleCountRef.get
            botCounts   <- botCountRef.get
          } yield WikiCountsSnapshot(userCounts, titleCounts, botCounts)
        }
        .through(broadcastHubWikiCounts.publish)

      counter = stream
        .parEvalMap(10) { event =>
          incrementCounts(event, userCountRef, titleCountRef, botCountRef)
        }
      _ <- Stream(counter, producer.drain).parJoinUnbounded.compile.drain
    } yield ()
  }

  def incrementCounts(
      event: TracedWikiEdit,
      userCountRef: Ref[F, Map[String, Int]],
      titleCountRef: Ref[F, Map[WikiPage, Int]],
      botCountRef: Ref[F, Map[Boolean, Int]]
  ): F[Unit] = {
    val suffix = if (event.edit.bot) then "Bot" else "Human"
    tracer
      .spanBuilder("increment_counts")
      .withParent(event.spanContext)
      .addAttribute(Attribute("wiki.bot", event.edit.bot))
      .build
      .use { _ =>
        (
          incrementCount(s"${event.edit.user} ($suffix)", userCountRef, "user"),
          incrementTitlesCount(WikiPage(event.edit.title, event.edit.title_url), titleCountRef, "page"),
          incrementCount(event.edit.bot, botCountRef, "bot")
        ).parTupled.void
      }
  }

  private def incrementCount[K](key: K, ref: Ref[F, Map[K, Int]], label: String): F[Unit] =
    ref.updateAndGet { counts =>
      val newCount = counts.getOrElse(key, 0) + 1
      counts + (key -> newCount)
    }.void

  private def incrementTitlesCount[K](key: WikiPage, ref: Ref[F, Map[WikiPage, Int]], label: String): F[Unit] =
    ref.updateAndGet { counts =>
      val newCount = counts.getOrElse(key, 0) + 1
      counts + (key -> newCount)
    }.void
