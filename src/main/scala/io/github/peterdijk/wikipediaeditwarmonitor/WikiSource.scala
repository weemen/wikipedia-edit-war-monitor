package io.github.peterdijk.wikipediaeditwarmonitor

import cats.effect.{Concurrent, Async}
import cats.implicits._
import org.http4s._
import org.http4s.implicits._
import org.http4s.client.Client
import org.http4s.client.dsl.Http4sClientDsl
import org.http4s.Method._

trait WikiSource[F[_]]:
  def streamEvents: F[Unit]

object WikiSource:
  def apply[F[_]](implicit ev: WikiSource[F]): WikiSource[F] = ev

  private final case class WikiStreamError(e: Throwable) extends RuntimeException

  def impl[F[_]: Async](client: Client[F]): WikiSource[F] = new WikiSource[F]:
    val dsl: Http4sClientDsl[F] = new Http4sClientDsl[F] {}
    import dsl.*
    
    def streamEvents: F[Unit] =
      val request = GET(uri"https://stream.wikimedia.org/v2/stream/recentchange")
      
      client.stream(request).flatMap { response =>
        response.body
          .through(fs2.text.utf8.decode)
          .through(fs2.text.lines)
          .filter(_.nonEmpty) // Filter out empty lines
          .filter(!_.startsWith(":")) // Filter out SSE comment lines (heartbeats)
          .filter(_.startsWith("data: ")) // Only process data lines
          .map(_.stripPrefix("data: "))
          .evalMap { line =>
            Concurrent[F].delay(println(line))
          }
      }.compile.drain
        .adaptError { case t => WikiStreamError(t) }
