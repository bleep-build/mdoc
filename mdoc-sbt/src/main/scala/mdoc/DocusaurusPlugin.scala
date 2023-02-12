package bleep.plugin.mdoc

import bleep.internal.FileUtils
import bleep.logging.Logger
import bleep.packaging.{JarType, ManifestCreator, createJar}
import bleep.plugin.mdoc.sbtdocusaurus.internal.Relativize
import bleep.{PathOps, cli}

import java.nio.file.{Files, Path}
import scala.concurrent.duration.Duration
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.util.Try

class DocusaurusPlugin(
    website: Path,
    mdoc: MdocPlugin,
    // The siteConfig.js `projectName` setting value
    docusaurusProjectName: String,
    // should ensure `npm` and `node` is available in `PATH`
    env: List[(String, String)],
    logger: Logger,
    isDocusaurus2: Boolean
) {
  def gitUser(): String =
    sys.env.getOrElse(
      "GIT_USER", {
        import scala.sys.process._
        Try("git config user.email".!!.trim)
          .getOrElse("docusaurus@scalameta.org")
      }
    )
  def installSsh: String =
    s"""|#!/usr/bin/env bash
        |
        |set -eu
        |DEPLOY_KEY=$${GIT_DEPLOY_KEY:-$${GITHUB_DEPLOY_KEY:-}}
        |set-up-ssh() {
        |  echo "Setting up ssh..."
        |  mkdir -p $$HOME/.ssh
        |  ssh-keyscan -t rsa github.com >> ~/.ssh/known_hosts
        |  git config --global user.name "Docusaurus bot"
        |  git config --global user.email "$${MDOC_EMAIL:-mdoc@docusaurus}"
        |  git config --global push.default simple
        |  DEPLOY_KEY_FILE=$$HOME/.ssh/id_rsa
        |  echo "$$DEPLOY_KEY" | base64 --decode > $${DEPLOY_KEY_FILE}
        |  chmod 600 $${DEPLOY_KEY_FILE}
        |  eval "$$(ssh-agent -s)"
        |  ssh-add $${DEPLOY_KEY_FILE}
        |}
        |
        |if [[ -n "$${DEPLOY_KEY:-}" ]]; then
        |  set-up-ssh
        |else
        |  echo "No deploy key found. Attempting to auth with ssh key saved in ssh-agent. To use a deploy key instead, set the GIT_DEPLOY_KEY environment variable."
        |fi
        |
        |npm install
        |USE_SSH=true npm run ${if (isDocusaurus2) "deploy" else "publish-gh-pages"}
    """.stripMargin

  def installSshWindows: String =
    s"""|@echo off
        |call npm install
        |set USE_SSH=true
        |call npm run ${if (isDocusaurus2) "deploy" else "publish-gh-pages"}
    """.stripMargin

  val mdocInternalVariables: List[(String, String)] = List(
    "js-out-prefix" -> "assets"
  )

  // Publish docusaurus site to GitHub pages
  def docusaurusPublishGhpages(mdocArgs: List[String]): Unit = {
    mdoc.mdoc(mdocInternalVariables, mdocArgs)

    val tmp =
      if (scala.util.Properties.isWin) {
        val tmp = Files.createTempFile("docusaurus", "install_ssh.bat")
        Files.write(tmp, installSshWindows.getBytes())
        tmp
      } else {
        val tmp = Files.createTempFile("docusaurus", "install_ssh.sh")
        Files.write(tmp, installSsh.getBytes())
        tmp
      }

    tmp.toFile.setExecutable(true)
    cli(
      action = "install_ssh",
      cwd = website,
      cmd = List(tmp.toString),
      logger = logger,
      out = cli.Out.ViaLogger(logger),
      env = env ++ List("GIT_USER" -> gitUser(), "USE_SSH" -> "true")
    )
    ()
  }

  // Create static build of docusaurus site
  def docusaurusCreateSite(mdocArgs: List[String]): Path = {
    mdoc.mdoc(mdocInternalVariables, mdocArgs)
    cli(action = "npm install", cwd = website, cmd = List("npm", "install"), logger = logger, out = cli.Out.ViaLogger(logger), env = env)
    cli(action = "npm run build", cwd = website, cmd = List("npm", "run", "build"), logger = logger, out = cli.Out.ViaLogger(logger), env = env)
    val redirectUrl = docusaurusProjectName + "/index.html"
    val html = redirectHtml(redirectUrl)
    val out = website / "build"
    FileUtils.writeString(logger, None, out / "index.html", html)
    out
  }

  def cleanFiles(): Seq[Path] = {
    val buildFolder = website / "build"
    val nodeModules = website / "node_modules"
    val docusaurusFolders = Seq(buildFolder, nodeModules).filter(FileUtils.exists)
    docusaurusFolders
  }

  def doc(mdocArgs: List[String]): Path = {
    val out = docusaurusCreateSite(mdocArgs)
    Relativize.htmlSite(out)
    out
  }

  def dev(implicit ec: ExecutionContext): Unit = {
    Await.result(
      Future.firstCompletedOf(
        List(
          Future(mdoc.mdoc(mdocInternalVariables, List("--watch"))),
          Future {
            cli(action = "npm install", cwd = website, cmd = List("npm", "install"), logger = logger, out = cli.Out.ViaLogger(logger), env = env)
            cli(action = "npm run start", cwd = website, cmd = List("npm", "run", "start"), logger = logger, out = cli.Out.ViaLogger(logger), env = env)
          }
        )
      ),
      Duration.Inf
    )
    ()
  }

  def packageDoc(target: Path, mdocArgs: List[String]): Path = {
    val directory = doc(mdocArgs)
    val bytes = createJar(JarType.DocsJar, ManifestCreator.default, List(directory))
    val jar = target / "docusaurus.jar"
    Files.write(jar, bytes)
    jar
  }

  private def redirectHtml(url: String): String =
    s"""
       |<!DOCTYPE HTML>
       |<html lang="en-US">
       |    <head>
       |        <meta charset="UTF-8">
       |        <meta http-equiv="refresh" content="0; url=$url">
       |        <script type="text/javascript">
       |            window.location.href = "$url"
       |        </script>
       |        <title>Page Redirection</title>
       |    </head>
       |    <body>
       |        If you are not redirected automatically, follow this <a href='$url'>link</a>.
       |    </body>
       |</html>
      """.stripMargin
}
