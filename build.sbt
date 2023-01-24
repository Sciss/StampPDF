lazy val baseName       = "StampPDF"
//lazy val baseNameL      = baseName.toLowerCase
lazy val projectVersion = "0.1.0-SNAPSHOT"
lazy val gitHost        = "codeberg.org"
lazy val gitUser        = "sciss"
lazy val gitRepo        = baseName

lazy val commonSettings = Seq(
  version      := projectVersion,
  homepage     := Some(url(s"https://$gitHost/$gitUser/$gitRepo")),
  scalaVersion := "3.1.3", // "2.13.8",
  scalacOptions ++= Seq("-deprecation"),
  licenses     := Seq("AGPL v3+" -> url("http://www.gnu.org/licenses/agpl-3.0.txt")),
  run / fork   := true,
  libraryDependencies ++= Seq(
    "com.itextpdf" %  "itextpdf"  % deps.common.itext,        // PDF reader/writer
    "de.sciss"    %% "fileutil"   % deps.common.fileUtil,   // utility functions
    "de.sciss"    %% "model"      % deps.common.model,      // events
    "de.sciss"    %% "numbers"    % deps.common.numbers,    // numeric utilities
    "de.sciss"    %% "swingplus"  % deps.common.swingPlus,  // user interface
    "org.rogach"  %% "scallop"    % deps.common.scallop     // command line option parsing
  )
) ++ assemblySettings

lazy val root = project.in(file("."))
  .settings(commonSettings)
  .settings(assemblySettings)
  .settings(
    name := baseName,
    description  := "A utility to stamp an image onto a PDF document"
  )

lazy val deps = new {
  val common = new {
    val fileUtil        = "1.1.5"
    val itext           = "5.5.13.2"
    val model           = "0.3.5"
    val numbers         = "0.2.1"
    val scallop         = "4.1.0"
    val swingPlus       = "0.5.0"
  }
}

lazy val assemblySettings = Seq(
  // ---- assembly ----
  assembly / test            := {},
  assembly / target          := baseDirectory.value,
  ThisBuild / assemblyMergeStrategy := {
    case "logback.xml" => MergeStrategy.last
    case PathList("org", "xmlpull", _ @ _*)              => MergeStrategy.first
    case PathList("org", "w3c", "dom", "events", _ @ _*) => MergeStrategy.first // Apache Batik
    case p@PathList(ps@_*) if ps.last endsWith "module-info.class" =>
//      println(s"DISCARD: $p")
      MergeStrategy.discard // Jackson, Pi4J
    case p @ PathList(ps @ _*) if ps.last endsWith ".proto" =>
//      println(s"DISCARD: $p")
      MergeStrategy.discard // Akka vs Google protobuf what the hell
    case x =>
      val old = (ThisBuild / assemblyMergeStrategy).value
      old(x)
  }
//  assembly / fullClasspath := (Test / fullClasspath).value // https://github.com/sbt/sbt-assembly/issues/27
)
