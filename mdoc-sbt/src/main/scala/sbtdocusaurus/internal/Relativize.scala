package bleep.plugin.mdoc.sbtdocusaurus.internal

import org.jsoup.Jsoup
import org.jsoup.nodes.Element

import java.net.URI
import java.nio.charset.StandardCharsets
import java.nio.file._
import java.nio.file.attribute.BasicFileAttributes
import scala.collection.JavaConverters._

object Relativize {

  def htmlSite(site: Path): Unit = {
    Files.walkFileTree(
      site,
      new SimpleFileVisitor[Path] {
        override def visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult = {
          if (file.getFileName.toString.endsWith(".html")) {
            processHtmlFile(site, file)
          }
          super.visitFile(file, attrs)
        }
      }
    )
    ()
  }

  // actual host name doesn't matter
  private val baseUri = URI.create("http://example.com/")

  def processHtmlFile(site: Path, file: Path): Unit = {
    val originRelativeUri = relativeUri(site.relativize(file))
    val originUri = baseUri.resolve(originRelativeUri)
    val originPath = Paths.get(originUri.getPath).getParent
    def relativizeAttribute(element: Element, attribute: String): Unit = {
      val absoluteHref = URI.create(element.attr(s"abs:$attribute"))
      if (absoluteHref.getHost == baseUri.getHost) {
        val hrefPath = Paths.get(absoluteHref.getPath)
        val relativeHref = {
          val relativePath = originPath.relativize(hrefPath)
          val absolutePath = file.getParent.resolve(relativePath)
          val isDirectory = Files.isDirectory(absolutePath)
          if (isDirectory) relativePath.resolve("index.html")
          else relativePath
        }
        val fragment =
          if (absoluteHref.getFragment == null) ""
          else "#" + absoluteHref.getFragment
        val newHref = relativeUri(relativeHref).toString + fragment
        element.attr(attribute, newHref)
        ()
      } else if (element.attr(attribute).startsWith("//")) {
        // We force "//hostname" links to become "https://hostname" in order to make
        // the site browsable without file server. If we keep "//hostname"  unchanged
        // then users will try to load "file://hostname" which results in 404.
        // We hardcode https instead of http because it's OK to load https from http
        // but not the other way around.
        element.attr(attribute, "https:" + element.attr(attribute))
        ()
      }
    }
    val doc = Jsoup.parse(file.toFile, StandardCharsets.UTF_8.name(), originUri.toString)
    val attributesToRelativize = List("href", "src")
    attributesToRelativize.foreach { attribute =>
      doc.select(s"[$attribute]").forEach { element => relativizeAttribute(element, attribute) }
      ()
    }
    val renderedHtml = doc.outerHtml()
    Files.write(file, renderedHtml.getBytes(StandardCharsets.UTF_8))
    ()
  }

  private def relativeUri(relativePath: Path): URI = {
    require(!relativePath.isAbsolute, relativePath)
    val names = relativePath.iterator().asScala
    val uris = names.map { name => new URI(null, null, name.toString, null) }
    URI.create(uris.mkString("", "/", ""))
  }
}
