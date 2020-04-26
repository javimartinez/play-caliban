name := "play-caliban"
organization := "com.jmartinez"

version := "1.0-SNAPSHOT"

lazy val root = (project in file(".")).enablePlugins(PlayScala)

scalaVersion := "2.12.11"

libraryDependencies += guice
libraryDependencies += "org.scalatestplus.play" %% "scalatestplus-play" % "5.0.0" % Test
libraryDependencies ++= Seq(
    "com.github.ghostdogpr" %% "caliban" % "0.7.6+3-2c760400+20200426-1934", // Caliban with akka-stream version 2.6.3 
    "com.github.ghostdogpr" %% "caliban-akka-http" % "0.7.6+3-2c760400+20200426-1934", 
    "de.heikoseeberger" %% "akka-http-play-json" % "1.31.0"
)

// Adds additional packages into Twirl
//TwirlKeys.templateImports += "com.jmartinez.controllers._"

// Adds additional packages into conf/routes
// play.sbt.routes.RoutesKeys.routesImport += "com.jmartinez.binders._"
