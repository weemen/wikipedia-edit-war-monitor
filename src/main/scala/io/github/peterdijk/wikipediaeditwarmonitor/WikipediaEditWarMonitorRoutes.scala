package io.github.peterdijk.wikipediaeditwarmonitor

import cats.effect.Sync
import cats.implicits._
import org.http4s.HttpRoutes
import org.http4s.dsl.Http4sDsl
import fs2.io.file.Files
import org.http4s.headers.`Content-Type`
import org.http4s.MediaType

object WikipediaEditWarMonitorRoutes:

  def jokeRoutes[F[_]: Sync](J: Jokes[F]): HttpRoutes[F] =
    val dsl = new Http4sDsl[F]{}
    import dsl._
    HttpRoutes.of[F] {
      case GET -> Root / "joke" =>
        for {
          joke <- J.get
          resp <- Ok(joke)
        } yield resp
    }

  def statsRoutes[F[_]: Sync: Files]: HttpRoutes[F] =
    val dsl = new Http4sDsl[F]{}
    import dsl._
    HttpRoutes.of[F] {
      case GET -> Root / "stats" =>
        Ok(Files[F].readAll(fs2.io.file.Path("stats.html")))
          .map(_.withContentType(`Content-Type`(MediaType.text.html)))
    }

  def helloWorldRoutes[F[_]: Sync](H: HelloWorld[F]): HttpRoutes[F] =
    val dsl = new Http4sDsl[F]{}
    import dsl._
    HttpRoutes.of[F] {
      case GET -> Root / "hello" / name =>
        for {
          greeting <- H.hello(HelloWorld.Name(name))
          resp <- Ok(greeting)
        } yield resp
    }
