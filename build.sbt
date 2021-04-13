import sbt.Keys._
import sbt._

lazy val commonSettings = Seq(
  name := "http-client",
  organization := "com.walkmind.extensions",
  version := "1.8",

  licenses := Seq("Apache-2.0" -> url("http://opensource.org/licenses/Apache-2.0")),
  scalacOptions := Seq(
    "-unchecked",
    "-deprecation",
    "-encoding", "utf8",
    "-language:implicitConversions",
    "-language:postfixOps",
    "-language:higherKinds",
    "-Xcheckinit"),

  scalaVersion := "2.12.13",
  crossScalaVersions := Seq("2.12.13", "2.13.5")
)

lazy val publishSettings = {
  Seq(
    Test / publishArtifact := false,
    publishArtifact := true,

    scmInfo := Some(ScmInfo(url("https://github.com/unoexperto/extensions-http-client.git"), "git@github.com:unoexperto/extensions-http-client.git")),
    developers += Developer("unoexperto",
      "ruslan",
      "unoexperto.support@mailnull.com",
      url("https://github.com/unoexperto")),
    pomIncludeRepository := (_ => false),
    publishMavenStyle := true,
    publishTo := Some("Walkmind Repo" at "s3://walkmind-maven/")
  )
}

lazy val root = (project in file(".")).
  settings(commonSettings: _*).
  settings(publishSettings: _*).
  settings(
    libraryDependencies ++= {
      Seq(
        "com.typesafe.akka" %% "akka-stream" % "2.6.3",
        "com.typesafe.akka" %% "akka-http" % "10.1.11",

        "org.scala-lang.modules" %% "scala-collection-compat" % "2.1.4",

        "io.spray" %% "spray-json" % "1.3.5",
        "org.jsoup" % "jsoup" % "1.13.1",
        "org.asynchttpclient" % "async-http-client" % "2.11.0",

        "org.typelevel" %% "cats-core" % "2.1.1",
        "org.typelevel" %% "cats-effect" % "2.1.2"
      )
    }
  )
  .enablePlugins(S3ResolverPlugin)
