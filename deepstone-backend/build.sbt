val scala3Version = "3.3.7"
val http4sVersion = "0.23.23"
val circeVersion  = "0.14.6"
val munitVersion  = "0.7.29"
val doobieVersion = "1.0.0-RC4"

ThisBuild / scalaVersion := scala3Version
ThisBuild / organization := "roguelite"
ThisBuild / version      := "0.5.5"

lazy val root = (project in file("."))
  .enablePlugins(JavaAppPackaging, JlinkPlugin)
  .settings(
    name := "deepstone-backend",
    jlinkOptions ++= Seq("--strip-debug", "--no-header-files", "--no-man-pages", "--compress=2"),
    // jdeps flags these as missing because they're optional integrations none of our
    // dependencies actually exercise at runtime (Servlet/SMTP/conditional-config appenders
    // in Logback, Dropwizard/Micrometer/Prometheus/Hibernate/javassist paths in HikariCP,
    // fs2's Unix-domain-socket backend, sqlite-jdbc's GraalVM native-image hook, and a
    // self-referential Scala 3 quotes/macros false positive). Listed by exact prefix pair
    // rather than a blanket ignore so a genuinely new missing dependency still fails the build.
    jlinkIgnoreMissingDependency := JlinkIgnore.byPackagePrefix(
      "ch.qos.logback"        -> "jakarta.servlet",
      "ch.qos.logback"        -> "jakarta.mail",
      "ch.qos.logback"        -> "org.codehaus.janino",
      "ch.qos.logback"        -> "org.codehaus.commons.compiler",
      "com.zaxxer.hikari"     -> "com.codahale.metrics",
      "com.zaxxer.hikari"     -> "org.hibernate",
      "com.zaxxer.hikari"     -> "io.micrometer.core.instrument",
      "com.zaxxer.hikari"     -> "io.prometheus.client",
      "com.zaxxer.hikari"     -> "javassist",
      "fs2.io.net.unixsocket" -> "jnr.unixsocket",
      "org.sqlite.nativeimage" -> "org.graalvm.nativeimage.hosted",
      "scala.quoted"          -> "scala"
    ),
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
