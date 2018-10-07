import microsites.ExtraMdFileConfig

val core = project
  .in(file("core"))
  .settings(commonSettings("core"))
  .settings(publishSettings)
  .settings(
    libraryDependencies ++= Seq(
      "org.typelevel"   %% "cats-core" % "1.5.0",
      "org.apache.avro" % "avro"       % "1.8.2",
      "com.chuusai"     %% "shapeless" % "2.3.3"
    ),
    coverageExcludedPackages := "formulation.*RecordN",
    sourceGenerators in Compile += (sourceManaged in Compile).map(Boilerplate.gen).taskValue
  )

val refined = project
  .in(file("refined"))
  .settings(commonSettings("refined"))
  .settings(publishSettings)
  .settings(
    libraryDependencies ++= Seq(
      "eu.timepit" %% "refined" % "0.9.3"
    )
  )
  .dependsOn(core)

val schemaRegistry = project
  .in(file("schema-registry"))
  .settings(commonSettings("schema-registry"))
  .settings(publishSettings)
  .dependsOn(core)

val schemaRegistryConfluentSttp = project
  .in(file("schema-registry-confluent-sttp"))
  .settings(commonSettings("schema-registry-confluent-sttp"))
  .settings(publishSettings)
  .settings(
    libraryDependencies ++= Seq(
      "com.softwaremill.sttp" %% "core"     % "1.5.0",
      "org.spire-math"        %% "jawn-ast" % "0.13.0"
    )
  )
  .dependsOn(schemaRegistry)

val schemaRegistryScalacache = project
  .in(file("schema-registry-scalacache"))
  .settings(commonSettings("schema-registry-scalacache"))
  .settings(publishSettings)
  .settings(
    libraryDependencies ++= Seq(
      "com.github.cb372" %% "scalacache-core" % "0.26.0"
    )
  )
  .dependsOn(schemaRegistry)

val akkaStreams = project
  .in(file("akka-streams"))
  .settings(commonSettings("akka-streams"))
  .settings(publishSettings)
  .settings(
    libraryDependencies ++= Seq(
      "com.typesafe.akka" %% "akka-stream" % "2.5.18"
    )
  )
  .dependsOn(core)

val akkaSerializer = project
  .in(file("akka-serializer"))
  .settings(commonSettings("akka-serializer"))
  .settings(publishSettings)
  .settings(
    libraryDependencies ++= Seq(
      "com.typesafe.akka" %% "akka-actor" % "2.5.18"
    )
  )
  .dependsOn(core, schemaRegistry)

val rpc = project
  .in(file("rpc"))
  .settings(commonSettings("rpc"))
  .settings(publishSettings)
  .settings(
    libraryDependencies ++= Seq(
      "org.scodec"        %% "scodec-core"             % "1.10.3",
      "org.scodec"        %% "scodec-stream"           % "1.2.0",
      "co.fs2"            %% "fs2-core"                % "1.0.0",
      "co.fs2"            %% "fs2-io"                  % "1.0.0",
      "io.chrisdavenport" %% "log4cats-core"           % "0.1.1",
      "io.chrisdavenport" %% "log4cats-slf4j"          % "0.1.1",
      "io.prometheus"     % "simpleclient"             % "0.5.0",
      "io.prometheus"     % "simpleclient_pushgateway" % "0.5.0",
      "io.prometheus"     % "simpleclient_hotspot"     % "0.5.0",
      "ch.qos.logback"    % "logback-classic"          % "1.2.3"
    )
  )
  .dependsOn(core)

val tests = project
  .in(file("tests"))
  .settings(noPublishSettings)
  .settings(commonSettings("tests"))
  .settings(
    libraryDependencies ++= Seq(
      "org.scalacheck"    %% "scalacheck"          % "1.13.5" % Test,
      "org.scalatest"     %% "scalatest"           % "3.0.4"  % Test,
      "com.typesafe.akka" %% "akka-stream-testkit" % "2.5.18" % Test,
      "com.github.cb372"  %% "scalacache-caffeine" % "0.26.0" % Test
    )
  )
  .dependsOn(core, refined, schemaRegistry, schemaRegistryConfluentSttp, schemaRegistryScalacache, akkaStreams, akkaSerializer, rpc)

val benchmark = project
  .in(file("benchmark"))
  .settings(noPublishSettings)
  .settings(commonSettings("benchmark"))
  .settings(
    coverageExcludedPackages := "formulation.*",
    libraryDependencies ++= Seq(
      "io.circe"            %% "circe-core"    % "0.9.0",
      "io.circe"            %% "circe-generic" % "0.9.0",
      "io.circe"            %% "circe-parser"  % "0.9.0",
      "com.sksamuel.avro4s" %% "avro4s-core"   % "1.8.0"
    )
  )
  .dependsOn(core, akkaStreams, schemaRegistry, akkaSerializer)
  .enablePlugins(JmhPlugin)

val docs = project
  .in(file("docs"))
  .settings(noPublishSettings)
  .settings(commonSettings("docs"))
  .settings(
    libraryDependencies ++= Seq(
      "com.github.cb372" %% "scalacache-caffeine" % "0.26.0"
    ),
    scalacOptions := Seq("-language:higherKinds"),
    micrositeName := "formulation",
    micrositeDescription := "Describe Avro data types",
    micrositeBaseUrl := "/formulation",
    micrositeDocumentationUrl := "/formulation/docs/",
    micrositeGitterChannel := false,
    micrositeGithubOwner := "vectos",
    micrositeGithubRepo := "formulation",
    micrositeGithubToken := sys.env.get("GITHUB_TOKEN"),
    micrositeOrganizationHomepage := "http://vectos.net",
    micrositeAuthor := "Vectos",
    micrositeTwitterCreator := "@mark_dj",
    micrositeExtraMdFiles := Map(
      file("CHANGELOG.md") -> ExtraMdFileConfig(
        "changelog.md",
        "page",
        Map("title" -> "Changelog", "section" -> "docs", "position" -> "5")
      )
    )
  )
  .dependsOn(core, refined, schemaRegistry, schemaRegistryConfluentSttp, schemaRegistryScalacache, akkaStreams, akkaSerializer)
  .enablePlugins(MicrositesPlugin)

lazy val noPublishSettings = Seq(
  publish := {},
  publishLocal := {},
  publishArtifact := false
)

def commonSettings(n: String) = Seq(
  name := s"formulation-$n",
  version := "0.4.3",
  organization := "net.vectos",
  crossScalaVersions := Seq("2.11.12", "2.12.6"),
  scalaVersion := "2.12.6",
  scalacOptions := scalacOpts(scalaVersion.value),
  addCompilerPlugin("org.spire-math" %% "kind-projector" % "0.9.9")
)

lazy val publishSettings = Seq(
//  publishMavenStyle := false,
  publishArtifact in Test := false,
  publishArtifact in (Compile, packageDoc) := false,
  publishArtifact in (Compile, packageSrc) := false,
  resolvers += Resolver.jcenterRepo,
  resolvers += Resolver.bintrayRepo("fristi", "maven"),
  updateOptions := updateOptions.value.withCachedResolution(true),
  licenses += ("MIT", url("http://opensource.org/licenses/MIT")),
  releaseEarlyEnableSyncToMaven := false,
  developers := List(Developer("mark_dj", "Mark de Jong", "mark@vectos.net", url("http://vectos.net"))),
  homepage := Some(url("https://vectos.net/formulation")),
  scmInfo := Some(ScmInfo(url("http://github.com/vectos/formulation"), "scm:git:git@github.com:vectos/formulation.git")),
  releaseEarlyWith := BintrayPublisher,
  licenses += ("MIT", url("http://opensource.org/licenses/MIT")),
  pgpPublicRing := file("./travis/local.pubring.asc"),
  pgpSecretRing := file("./travis/local.secring.asc")
)

def scalacOpts(ver: String) = CrossVersion.partialVersion(ver) match {
  case Some((2, scalaMajor)) if scalaMajor == 12 => scalacOptions212
  case Some((2, scalaMajor)) if scalaMajor == 11 => scalacOptions211
  case _                                         => Seq.empty
}

val scalacOptions211 = Seq(
  "-encoding",
  "UTF-8", // yes, this is 2 args
  "-feature",
  "-language:existentials",
  "-language:higherKinds",
  "-language:implicitConversions",
  "-unchecked",
  "-Xlint",
  "-Yno-adapted-args",
  "-Ywarn-dead-code", // N.B. doesn't work well with the ??? hole
  "-Ywarn-numeric-widen",
  "-Ywarn-value-discard",
  "-Xfuture",
  "-Ywarn-unused-import" // 2.11 only
)

val scalacOptions212 = Seq(
  "-encoding",
  "utf-8", // Specify character encoding used by source files.
  "-explaintypes", // Explain type errors in more detail.
  "-feature", // Emit warning and location for usages of features that should be imported explicitly.
  "-language:existentials", // Existential types (besides wildcard types) can be written and inferred
  "-language:experimental.macros", // Allow macro definition (besides implementation and application)
  "-language:higherKinds", // Allow higher-kinded types
  "-language:implicitConversions", // Allow definition of implicit functions called views
  "-unchecked", // Enable additional warnings where generated code depends on assumptions.
  "-Xcheckinit", // Wrap field accessors to throw an exception on uninitialized access.
  "-Xfuture", // Turn on future language features.
  "-Xlint:adapted-args", // Warn if an argument list is modified to match the receiver.
  "-Xlint:by-name-right-associative", // By-name parameter of right associative operator.
  "-Xlint:constant", // Evaluation of a constant arithmetic expression results in an error.
  "-Xlint:delayedinit-select", // Selecting member of DelayedInit.
  "-Xlint:doc-detached", // A Scaladoc comment appears to be detached from its element.
  "-Xlint:inaccessible", // Warn about inaccessible types in method signatures.
  "-Xlint:infer-any", // Warn when a type argument is inferred to be `Any`.
  "-Xlint:missing-interpolator", // A string literal appears to be missing an interpolator id.
  "-Xlint:nullary-override", // Warn when non-nullary `def f()' overrides nullary `def f'.
  "-Xlint:nullary-unit", // Warn when nullary methods return Unit.
  "-Xlint:option-implicit", // Option.apply used implicit view.
  "-Xlint:package-object-classes", // Class or object defined in package object.
  "-Xlint:poly-implicit-overload", // Parameterized overloaded implicit methods are not visible as view bounds.
  "-Xlint:private-shadow", // A private field (or class parameter) shadows a superclass field.
  "-Xlint:stars-align", // Pattern sequence wildcard must align with sequence component.
  "-Xlint:type-parameter-shadow", // A local type parameter shadows a type already in scope.
  "-Xlint:unsound-match", // Pattern match may not be typesafe.
  "-Yno-adapted-args", // Do not adapt an argument list (either by inserting () or creating a tuple) to match the receiver.
  "-Ypartial-unification", // Enable partial unification in type constructor inference
  "-Ywarn-dead-code", // Warn when dead code is identified.
  "-Ywarn-extra-implicit", // Warn when more than one implicit parameter section is defined.
  "-Ywarn-inaccessible", // Warn about inaccessible types in method signatures.
  "-Ywarn-infer-any", // Warn when a type argument is inferred to be `Any`.
  "-Ywarn-nullary-override", // Warn when non-nullary `def f()' overrides nullary `def f'.
  "-Ywarn-nullary-unit", // Warn when nullary methods return Unit.
  "-Ywarn-numeric-widen", // Warn when numerics are widened.
  "-Ywarn-unused:implicits", // Warn if an implicit parameter is unused.
  "-Ywarn-unused:imports", // Warn if an import selector is not referenced.
  "-Ywarn-unused:locals", // Warn if a local definition is unused.
  "-Ywarn-unused:patvars", // Warn if a variable bound in a pattern is unused.
  "-Ywarn-unused:privates" // Warn if a private member is unused.
)

val root = project
  .in(file("."))
  .settings(commonSettings("root") ++ noPublishSettings)
  .aggregate(core, refined, schemaRegistry, schemaRegistryConfluentSttp, schemaRegistryScalacache, akkaStreams, akkaSerializer, rpc)
