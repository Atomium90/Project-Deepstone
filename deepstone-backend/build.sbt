val scala3Version = "3.3.7"
val http4sVersion = "0.23.23"
val circeVersion  = "0.14.6"
val munitVersion  = "0.7.29"
val doobieVersion = "1.0.0-RC4"

ThisBuild / scalaVersion := scala3Version
ThisBuild / organization := "roguelite"
ThisBuild / version      := "0.1.2"

lazy val root = (project in file("."))
  .settings(
    name := "deepstone-backend",
    libraryDependencies ++= Seq(
      // HTTP + WebSocket server
      "org.http4s" %% "http4s-ember-server" % http4sVersion,
      "org.http4s" %% "http4s-dsl"          % http4sVersion,
      "org.http4s" %% "http4s-circe"        % http4sVersion,

      // JSON
      "io.circe" %% "circe-core"    % circeVersion,
      "io.circe" %% "circe-generic" % circeVersion,
      "io.circe" %% "circe-parser"  % circeVersion,

      // Doobie (functional JDBC) + SQLite driver
      "org.tpolecat" %% "doobie-core"   % doobieVersion,
      "org.tpolecat" %% "doobie-hikari" % doobieVersion,
      "org.xerial"    % "sqlite-jdbc"   % "3.45.1.0",

      // Logging
      "org.typelevel" %% "log4cats-slf4j"  % "2.6.0",
      "ch.qos.logback" % "logback-classic" % "1.4.11",

      // Testing
      "org.scalameta" %% "munit"               % munitVersion % Test,
      "org.typelevel" %% "munit-cats-effect-3" % "1.0.7"      % Test
    ),

    // Circe automatic derivation
    scalacOptions ++= Seq("-Xmax-inlines", "64")
  )
