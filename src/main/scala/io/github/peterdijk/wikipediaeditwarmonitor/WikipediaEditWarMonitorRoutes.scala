package io.github.peterdijk.wikipediaeditwarmonitor

import cats.effect.Sync
import cats.implicits._
import org.http4s.HttpRoutes
import org.http4s.dsl.Http4sDsl

object WikipediaEditWarMonitorRoutes:

  def healthRoutes[F[_]: Sync](J: Jokes[F]): HttpRoutes[F] =
    val dsl = new Http4sDsl[F]{}
    import dsl._
    HttpRoutes.of[F] {
      case GET -> Root / "health" =>
        for {
          joke <- J.get
          resp <- Ok(joke)
        } yield resp
    }

