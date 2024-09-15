package bleep.plugin.mdoc

import bleep.*
import bleep.nosbt.io.IO
import bloop.config.Config.Platform
import coursier.core.{ModuleName, Organization}

import java.io.File
import java.nio.file.{Files, Path}
import scala.collection.immutable.SortedSet

class MdocPlugin(started: Started, crossProjectName: model.CrossProjectName, mdocVersion: String = "2.3.2") {
  // Site variables that can be referenced from markdown with @VERSION@.
  def mdocVariables: Map[String, String] = Map.empty
  // Input directory or source file containing markdown to be processed by mdoc.
  // Defaults to the toplevel docs/ directory.
  def mdocIn: Path = started.buildPaths.buildDir / "docs"
  // Output directory or output file name for mdoc generated markdown. Defaults to the target/mdoc directory of this project. If this is a file name, it assumes your `in` was also an individual file
  def mdocOut: Path = started.projectPaths(crossProjectName).targetDir / "mdoc"
  // Additional command-line arguments to pass on every mdoc invocation. For example, add '--no-link-hygiene' to disable link hygiene.
  def mdocExtraArguments: Seq[String] = Nil
  // Optional Scala.js classpath and compiler options to use for the mdoc:js modifier. To use this setting, set the value to `mdocJS := Some(jsproject)` where `jsproject` must be a Scala.js project.
  def mdocJS: Option[model.CrossId] = None
  // Additional local JavaScript files to load before loading the mdoc compiled Scala.js bundle. If using scalajs-bundler, set this key to `webpack.in(<mdocJS project>, Compile, fullOptJS).value`.
  def mdocJSLibraries: Seq[Path] = Nil

  if (mdocIn == started.buildPaths.buildDir) {
    throw new MdocException(
      s"mdocIn and baseDirectory cannot have the same value '$mdocIn'. To fix this problem, either customize the project baseDirectory with `in(file('myproject-docs'))` or move `mdocIn` somewhere else."
    )
  }

  def mdocDependency: model.Dep.ScalaDependency = {
    val suffix = if (mdocJS.isDefined) "-js" else ""
    model.Dep.Scala("org.scalameta", s"mdoc$suffix", mdocVersion)
  }

  // Run mdoc to generate markdown sources. Supports arguments like --watch to start the file watcher with livereload.
  def mdoc(
      // Additional site variables that are added by mdoc plugins. Not intended for public use.
      mdocInternalVariables: List[(String, String)] = Nil,
      args: List[String]
  ): Unit = {
    val outDir = Files.createTempDirectory("bleep-mdoc")
    val bloopProject = started.bloopProject(crossProjectName)
    val explodedProject = started.build.explodedProjects(crossProjectName)

    val versionCombo = getVersionCombo(explodedProject)

    val out = outDir / "mdoc.properties"
    val props = new java.util.Properties()
    mdocVariables.foreach { case (key, value) => props.put(key, value) }
    mdocInternalVariables.foreach { case (key, value) => props.put(key, value) }

    mdocJS.foreach { jsCrossId =>
      val jsCrossProjectName = crossProjectName.copy(crossId = Some(jsCrossId))
      val jsBloopProject = started.bloopProject(jsCrossProjectName)
      val jsPlatform: Platform.Js = jsBloopProject.platform match {
        case Some(js: Platform.Js) => js
        case other                 => throw new BleepException.Text(s"Expected Scala.js project, got $other")
      }
      val jsExplodedProject = started.build.explodedProjects(jsCrossProjectName)

      val jsVersionCombo = getVersionCombo(jsExplodedProject)

      props.put(s"js-scalac-options", jsBloopProject.scala.map(_.options).getOrElse(Nil).mkString(" "))
      props.put(s"js-classpath", jsBloopProject.classpath.mkString(File.pathSeparator))
      props.put(
        s"js-linker-classpath", {
          val linkerJars = getJars(jsVersionCombo, linkerDependency(jsPlatform.config.version))
          val workerClasspath = mdocJSWorkerClasspath.getOrElse(getJars(jsVersionCombo, mdocJSDependency))
          (linkerJars ++ workerClasspath).mkString(File.pathSeparator)
        }
      )
      props.put(s"js-libraries", mdocJSLibraries.mkString(File.pathSeparator))
      props.put(s"js-module-kind", jsPlatform.config.kind.id)
    }

    props.put("in", mdocIn.toString)
    props.put("out", mdocOut.toString)
    props.put("scalacOptions", bloopProject.scala.map(_.options).getOrElse(Nil).mkString(" "))
    props.put("classpath", fixedClasspath.apply(bloopProject).mkString(java.io.File.pathSeparator))

    IO.write(props, "mdoc properties", out.toFile)
    started.logger.info(s"wrote $out")

    Files.createDirectories(mdocOut)
    val cp = outDir :: getJars(versionCombo, mdocDependency)
    cli(
      action = "mdoc",
      cwd = started.buildPaths.cwd,
      cmd = List(List(started.jvmCommand.toString, "-cp", cp.mkString(File.pathSeparator), "mdoc.Main"), mdocExtraArguments, args).flatten,
      logger = started.logger,
      out = cli.Out.ViaLogger(started.logger),
    ).discard()
  }

  // Optional classpath to use for Mdoc.js worker - if not provided, the classpath will be formed by resolving the worker dependency
  val mdocJSWorkerClasspath: Option[Seq[Path]] = None

  def linkerDependency(version: String) = model.Dep.ScalaDependency(
    Organization("org.scala-js"),
    ModuleName("scalajs-linker"),
    version,
    fullCrossVersion = false,
    for3Use213 = true
  )

  val mdocJSDependency =
    model.Dep.Scala("org.scala-js", "mdoc-js-worker", mdocVersion)

  def getJars(scalaCombo: model.VersionCombo.Scala, deps: model.Dep*): List[Path] =
    started.resolver.force(deps.toSet, scalaCombo, libraryVersionSchemes = SortedSet.empty, "booting mdoc").jars

  def getVersionCombo(explodedProject: model.Project): model.VersionCombo.Scala =
    model.VersionCombo.fromExplodedProject(explodedProject) match {
      case Left(err)                                   => throw new BleepException.Text(s"Invalid project for mdoc: $err")
      case Right(model.VersionCombo.Java)              => throw new BleepException.Text(s"Invalid project for mdoc: was java project")
      case Right(scalaCombo: model.VersionCombo.Scala) => scalaCombo
    }
}
