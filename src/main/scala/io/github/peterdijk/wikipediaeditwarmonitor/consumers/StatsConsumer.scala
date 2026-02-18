package io.github.peterdijk.wikipediaeditwarmonitor.consumers
import cats.effect.{Async, Ref}
import com.typesafe.config.ConfigFactory
import fs2.concurrent.Topic
import io.github.peterdijk.wikipediaeditwarmonitor.WikiTypes.TracedWikiEdit
import cats.syntax.all.*

import scala.concurrent.duration.*
import fs2.io.file.Files
import fs2.Stream

final case class StatsConsumer[F[_]: Async: cats.Parallel: Files](
                                                                   broadcastHub: Topic[F, TracedWikiEdit]
):

  def countEdits: F[Unit] = {
    val stream = broadcastHub.subscribe(1000)

    // Load output directory from configuration (application.conf: app.outDir)
    val loadOutDir: F[String] = Async[F].delay {
      val conf = ConfigFactory.load()
      if (conf.hasPath("app.outDir")) conf.getString("app.outDir")
      else ""
    }

    def incrementUser(user: String, ref: Ref[F, Map[String, Int]]): F[Unit] =
      ref.updateAndGet { counts =>
        val newCount = counts.getOrElse(user, 0) + 1
        counts + (user -> newCount)
      }.flatMap(m => Async[F].delay(println(s"Current user counts: $m")))

    def incrementTitle(title: String, ref: Ref[F, Map[String, Int]]): F[Unit] =
      ref.updateAndGet { counts =>
        val newCount = counts.getOrElse(title, 0) + 1
        counts + (title -> newCount)
      }.flatMap(m => Async[F].delay(println(s"Current page counts: $m")))

    def incrementBot(bot: Boolean, ref: Ref[F, Map[Boolean, Int]]): F[Unit] =
      ref.updateAndGet { counts =>
        val newCount = counts.getOrElse(bot, 0) + 1
        counts + (bot -> newCount)
      }.flatMap(m => Async[F].delay(println(s"Current bot counts: $m")))

    for {
      userCountRef <- Ref.of[F, Map[String, Int]](Map.empty)
      titleCountRef <- Ref.of[F, Map[String, Int]](Map.empty)
      botCountRef <- Ref.of[F, Map[Boolean, Int]](Map.empty)

      htmlWriter = Stream
        .awakeEvery[F](10.seconds)
        .evalMap { _ =>
          for {
            outDir <- loadOutDir
            // ensure the directory exists
            _ <- Files[F].createDirectories(fs2.io.file.Path(outDir))
            userCounts <- userCountRef.get
            titleCounts <- titleCountRef.get
            botCounts <- botCountRef.get
            top20Users = userCounts.toList.sortBy(-_._2).take(20)
            top20Titles = titleCounts.toList.sortBy(-_._2).take(20)
            botCount = botCounts.getOrElse(true, 0)
            humanCount = botCounts.getOrElse(false, 0)

            // Read the template file from resources
            template <- Async[F].delay {
              val is = getClass.getClassLoader.getResourceAsStream("templates/wiki.template.html")
              if (is != null) {
                scala.io.Source.fromInputStream(is).mkString
              } else {
                "__REPLACE_ME__" // Fallback if template is missing
              }
            }

            html =
              s"""<div class='wikirow'>
                 |<div class='wikicolumn'>
                 |<h2>Top 20 User Edit Counts</h1>
                 |<ul>
                 |${top20Users.map((user, count) => s"<li>$user: $count</li>").mkString("\n")}
                 |</ul>
                 |</div>
                 |<div class='wikicolumn'>
                 |<h2>Top 20 Page Title Edit Counts</h1>
                 |<ul>
                 |${top20Titles.map((title, count) => s"<li>$title: $count</li>").mkString("\n")}
                 |</ul>
                 |</div>
                 |<div class='wikicolumn'>
                 |<h2>Bot count ratio Human vs Bot</h1>
                 |<ul>
                 |<li>Human: $humanCount</li>
                 |<li>Bot: $botCount</li>
                 |</ul>
                 |</div>
                 |</div>""".stripMargin

            finalHtml = template.replace("__REPLACE_ME__", html)

            _ <- Stream
              .emit(finalHtml)
              .through(fs2.text.utf8.encode)
              .through(Files[F].writeAll(fs2.io.file.Path(outDir).resolve("stats.html")))
              .compile
              .drain
          } yield ()
        }

      counter = stream
        .parEvalMap(10) { event =>
          (
            incrementUser(event.edit.user, userCountRef),
            incrementTitle(event.edit.title, titleCountRef),
            incrementBot(event.edit.bot, botCountRef)
          ).parTupled.void
        }

      _ <- Stream(counter, htmlWriter.drain).parJoinUnbounded.compile.drain
    } yield ()
  }