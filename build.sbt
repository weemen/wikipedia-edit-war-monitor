import sbt._

val Http4sVersion = "0.23.32"
val MunitVersion = "1.2.2"
val LogbackVersion = "1.4.14"
val MunitCatsEffectVersion = "2.1.0"
val JansiVersion = "1.18"

lazy val root = (project in file("."))
  .settings(
    organization := "io.github.peterdijk",
    name := "wikipedia-edit-war-monitor",
    version := "0.0.1-SNAPSHOT",
    scalaVersion := "3.3.7",
    libraryDependencies ++= Seq(
      "org.http4s" %% "http4s-ember-server" % Http4sVersion,
      "org.http4s" %% "http4s-ember-client" % Http4sVersion,
      "org.http4s" %% "http4s-circe" % Http4sVersion,
      "org.http4s" %% "http4s-dsl" % Http4sVersion,
      "org.scalameta" %% "munit" % MunitVersion % Test,
      "org.typelevel" %% "munit-cats-effect" % MunitCatsEffectVersion % Test,
      "ch.qos.logback" % "logback-classic" % LogbackVersion,
      "org.fusesource.jansi" % "jansi" % JansiVersion,
      "io.circe" %% "circe-core" % "0.14.5"
    ),
    testFrameworks += new TestFramework("munit.Framework"),
    scalacOptions ++= Seq(
      "-Wconf:msg=unused:s" // Silence all unused warnings (imports, values, params, etc.)
    ),
    Compile / run / fork := true,
    // Enable color support in forked JVM
    Compile / run / javaOptions ++= Seq(
      "-Dorg.fusesource.jansi.Ansi.disable=false",
      "-Djansi.force=true"
    ),
    // Set environment to force color/TTY detection
    Compile / run / envVars := Map(
      "TERM" -> "xterm-256color"
    ),
    // Preserve stdin/stdout for colors and configure output
    Compile / run / connectInput := true,
    Compile / run / outputStrategy := Some(StdoutOutput)
  )
