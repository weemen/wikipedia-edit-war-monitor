package io.github.peterdijk.wikipediaeditwarmonitor

import cats.effect.{ExitCode, IO, IOApp}
import fs2.io.net.Network
import fs2.io.file.Files

object Main extends IOApp:
  given Files[IO] = Files.forAsync[IO]
  def run(args: List[String]): IO[ExitCode] =
    WikipediaEditWarMonitorServer.run[IO].as(ExitCode.Success)
