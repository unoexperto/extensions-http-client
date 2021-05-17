//addSbtPlugin("org.foundweekends" % "sbt-bintray" % "0.5.6")
//addSbtPlugin("com.dwolla.sbt" % "sbt-s3-publisher" % "1.3.1")
addSbtPlugin("com.frugalmechanic" % "fm-sbt-s3-resolver" % "0.19.0")
// this pluigin was moved from Bintray to Sonatype but I can't figure how to load it from there.
//addSbtPlugin("cf.janga" % "sbts3" % "0.10.15")

//resolvers += "FrugalMechanic Snapshots" at "s3://maven.frugalmechanic.com/snapshots"
//resolvers += Resolver.url("sbts3 ivy resolver", url("https://dl.bintray.com/emersonloureiro/sbt-plugins"))(Resolver.ivyStylePatterns)
resolvers += Resolver.url("sonatype snapshots", url("https://oss.sonatype.org/content/repositories/snapshots"))(Resolver.ivyStylePatterns)

//resolvers ++= Seq(
//  Resolver.bintrayIvyRepo("dwolla", "sbt-plugins"),
//  Resolver.bintrayIvyRepo("dwolla", "maven")
//)
