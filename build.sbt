import sbt._

val Http4sVersion = "0.23.32"
val JansiVersion = "1.18"
val LogbackVersion = "1.4.14"
val MunitVersion = "1.2.2"
val MunitCatsEffectVersion = "2.1.0"
val Otel4s = "0.15.1"
val OpenTelemetryJava = "1.59.0"

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
      "org.typelevel" %% "otel4s-core" % Otel4s,
      "org.typelevel" %% "otel4s-oteljava" % Otel4s,
      "io.opentelemetry" % "opentelemetry-exporter-otlp" % OpenTelemetryJava % Runtime,
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
    // Assembly merge strategy for handling conflicts
    assembly / assemblyMergeStrategy := {
      case "module-info.class" => MergeStrategy.discard
      case PathList("META-INF", "versions", "9", "module-info.class") => MergeStrategy.discard
      case PathList("META-INF", xs @ _*) => xs match {
        case "MANIFEST.MF" :: Nil => MergeStrategy.discard
        case "services" :: _ => MergeStrategy.concat
        case _ => MergeStrategy.discard
      }
      case x if x.endsWith("/module-info.class") => MergeStrategy.discard
      case x =>
        val oldStrategy = (assembly / assemblyMergeStrategy).value
        oldStrategy(x)
    },
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
