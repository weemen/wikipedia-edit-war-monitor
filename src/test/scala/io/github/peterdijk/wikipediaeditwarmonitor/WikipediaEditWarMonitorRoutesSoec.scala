package io.github.peterdijk.wikipediaeditwarmonitor

import cats.effect.IO
import org.http4s._
import org.http4s.implicits._
import munit.CatsEffectSuite
import io.github.peterdijk.wikipediaeditwarmonitor.Jokes.Joke

class MockJokes(jokeText: String) extends Jokes[IO]:
    def get: IO[Joke] = IO.pure(Joke(jokeText))

class WikipediaEditWarMonitorRoutesSpec extends CatsEffectSuite:

  test("health endpoint returns status code 200") {
    assertIO(retHelloWorld.map(_.status) ,Status.Ok)
  }

  test("health endpoint returns a joke") {
    assertIO(retHelloWorld.flatMap(_.as[String]), "{\"joke\":\"Test joke\"}")
  }

  private[this] val retHelloWorld: IO[Response[IO]] =
    val getHW = Request[IO](Method.GET, uri"/health")
    val mockJokes = new MockJokes("Test joke")
    WikipediaEditWarMonitorRoutes.healthRoutes(mockJokes).orNotFound(getHW)
