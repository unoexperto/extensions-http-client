# extensions-http-client

Maven artifacts are published on AWS S3.

```
lazy val root = (project in file("."))
  .settings(
    name := "test_scala",
    resolvers ++= Seq(
      "walkmind maven" at "https://walkmind-maven.s3.amazonaws.com/"
    ),
    libraryDependencies ++= Seq(
      "com.walkmind.extensions" %% "http-client" % "1.8" withSources()
    )
  )
```
