// appkit is published for Scala 2.12 only, so the wallet uses the same scala-library
ThisBuild / scalaVersion := "2.12.20"
ThisBuild / organization := "vn.erg"
ThisBuild / version := "0.1.0"

lazy val root = (project in file("."))
  .settings(
    name := "ergo-simple-wallet",
    scalacOptions ++= Seq("-deprecation", "-feature", "-unchecked"),
    libraryDependencies ++= Seq(
      "org.ergoplatform" %% "ergo-appkit" % "6.0.1",
      "io.circe" %% "circe-parser" % "0.14.15",
      "com.formdev" % "flatlaf" % "3.5.4",
      "com.google.code.gson" % "gson" % "2.11.0",
      "com.google.zxing" % "javase" % "3.5.3",
      "com.nulab-inc" % "zxcvbn" % "1.9.0",
      "org.scalatest" %% "scalatest" % "3.2.19" % Test
    ),
    // ergo-wallet 6.0.0 declares circe 0.13, sigma-state 6.0.6 needs 0.14: appkit itself runs on 0.14 (Maven picked it too)
    libraryDependencySchemes += "io.circe" %% "circe-core" % VersionScheme.Always,
    libraryDependencySchemes += "io.circe" %% "circe-parser" % VersionScheme.Always,
    libraryDependencySchemes += "io.circe" %% "circe-generic" % VersionScheme.Always,
    Compile / mainClass := Some("vn.erg.wallet.WalletApp"),
    // Swing app that calls System.exit: run it in its own JVM, not inside sbt
    run / fork := true,

    // sbt assembly -> target/scala-2.12/ergo-simple-wallet.jar, runnable with java -jar
    assembly / mainClass := Some("vn.erg.wallet.WalletApp"),
    assembly / assemblyJarName := "ergo-simple-wallet.jar",
    assembly / packageOptions += Package.ManifestAttributes("Multi-Release" -> "true"),
    assembly / assemblyMergeStrategy := {
      // signatures of signed jars (BouncyCastle) are invalid once merged
      case PathList("META-INF", file) if Seq(".SF", ".DSA", ".RSA", ".EC").exists(file.toUpperCase.endsWith) => MergeStrategy.discard
      case PathList("META-INF", "services", _*) => MergeStrategy.filterDistinctLines
      case PathList(ps @ _*) if ps.last == "module-info.class" => MergeStrategy.discard
      case "reference.conf" => MergeStrategy.concat
      case x => (assembly / assemblyMergeStrategy).value(x)
    }
  )
