package io.github.peterdijk.wikipediaeditwarmonitor.middleware

import cats.data.{Kleisli, OptionT}
import org.http4s.{HttpRoutes, Request}
import org.typelevel.otel4s.Attribute
import org.typelevel.otel4s.trace.{SpanKind, Tracer}

object HttpTracingMiddleware {
  def apply[F[_]](
      routes: HttpRoutes[F]
  )(using tracer: Tracer[F]): HttpRoutes[F] = {
    Kleisli { (req: Request[F]) =>
      val spanName = s"${req.method.name} ${req.uri.path.renderString}"
      OptionT(
        tracer
          .spanBuilder(spanName)
          .withSpanKind(SpanKind.Server)
          .addAttribute(Attribute("http.method", req.method.name))
          .addAttribute(Attribute("http.url", req.uri.renderString))
          .build
          .use { _ =>
            routes(req).value
          }
      )
    }
  }
}
