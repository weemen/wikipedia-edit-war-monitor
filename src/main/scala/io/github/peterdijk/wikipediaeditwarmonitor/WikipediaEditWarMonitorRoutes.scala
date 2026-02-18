package io.github.peterdijk.wikipediaeditwarmonitor

import cats.effect.Sync
import cats.implicits._
import org.http4s.HttpRoutes
import org.http4s.dsl.Http4sDsl
import cats.effect.Async
import org.http4s.server.staticcontent.fileService
import org.http4s.server.staticcontent.FileService.Config
import fs2.io.file.Files

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

  def staticFileRoutes[F[_]: Async: Files](systemPath: String, urlPrefix: String): HttpRoutes[F] =
    fileService(Config(systemPath = systemPath, pathPrefix = urlPrefix))
