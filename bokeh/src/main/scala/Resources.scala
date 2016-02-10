package io.continuum.bokeh

import java.io.{File,IOException}
import java.nio.file.{Paths,Files}
import java.nio.charset.StandardCharsets.UTF_8
import java.net.URL
import java.util.Properties

import scalatags.Text.short.Tag

sealed abstract class ResourceComponent(val name: String) {
    val js = true
    val css = true
}
case object BokehCore extends ResourceComponent("bokeh")
case object BokehWidgets extends ResourceComponent("bokeh-widgets")
case object BokehCompiler extends ResourceComponent("bokeh-compiler") {
    override val css = false
}

case class Bundle(scripts: Seq[Tag], styles: Seq[Tag])

sealed trait Resources {
    def minified: Boolean = true

    def logLevel: LogLevel = LogLevel.Info

    val indent = 0

    def stringify[T:Json.Writer](obj: T): String = {
        Json.write(obj, indent=indent)
    }

    def wrap(code: String): String = s"Bokeh.$$(function() {\n$code\n});"

    protected def logLevelScript: Tag = {
        s"Bokeh.set_log_level('$logLevel');".asScript
    }

    def bundle(refs: List[Model]): Bundle = {
        val components = (Some(BokehCore) :: useWidgets(refs) :: useCompiler(refs) :: Nil).flatten

        val scripts = components.filter(_.js == true).map(resolveScript) ++ List(logLevelScript)
        val styles = components.filter(_.css == true).map(resolveStyle)

        Bundle(scripts, styles)
    }

    def useWidgets(refs: List[Model]): Option[ResourceComponent] = {
        refs.collectFirst { case ref: widgets.Widget => ref }
            .map { _ => BokehWidgets }
    }

    def useCompiler(refs: List[Model]): Option[ResourceComponent] = {
        refs.collect { case ref: CustomModel => ref.implementation }
            .collectFirst { case impl@CoffeeScript(_) => impl }
            .map { _ => BokehCompiler }
    }

    protected def resolveScript(component: ResourceComponent): Tag
    protected def resolveStyle(component: ResourceComponent): Tag

    protected def resourceName(component: ResourceComponent, ext: String, version: Boolean=false) = {
        val ver = if (version) s"-$Version" else ""
        val min = if (minified) ".min" else ""
        s"${component.name}$ver$min.$ext"
    }
}

trait DevResources { self: Resources =>
    override val minified = false

    override val logLevel = LogLevel.Debug

    override val indent = 2
}

trait ResolvableResources extends Resources {
    protected def getResource(path: String): URL = {
        val res = getClass.getClassLoader.getResource(path)
        if (res != null) res else throw new IOException(s"resource '$path' not found")
    }

    protected def loadResource(path: String): String = {
        new String(Files.readAllBytes(Paths.get(getResource(path).toURI)), UTF_8)
    }
}

trait InlineResources extends ResolvableResources {
    def resolveScript(component: ResourceComponent): Tag = {
        loadResource("js/" + resourceName(component, "js")).asScript
    }

    def resolveStyle(component: ResourceComponent): Tag = {
        loadResource("css/" + resourceName(component, "css")).asStyle
    }
}

trait LocalResources extends ResolvableResources {
    protected def resolveFile(file: File): File

    protected def getFile(path: String): File = {
        val resource = getResource(path)
        resource.getProtocol match {
            case "file"   => resolveFile(new File(resource.getPath))
            case protocol => sys.error(s"unable to load $path due to invalid protocol: $protocol")
        }
    }

    def resolveScript(component: ResourceComponent) = {
        new File(getFile("js"), resourceName(component, "js")).asScript
    }

    def resolveStyle(component: ResourceComponent) = {
        new File(getFile("css"), resourceName(component, "css")).asStyle
    }
}

trait RelativeResources extends LocalResources {
    def resolveFile(file: File): File = {
        val rootDir = new File(System.getProperty("user.dir"))
        new File(rootDir.toURI.relativize(file.toURI).getPath)
    }
}

trait AbsoluteResources extends LocalResources {
    def resolveFile(file: File): File = file.getAbsoluteFile()
}

abstract class RemoteResources(url: URL) extends Resources {
    def resolveScript(component: ResourceComponent) = {
        new URL(url, "./" + resourceName(component, "js", true)).asScript
    }

    def resolveStyle(component: ResourceComponent) = {
        new URL(url, "./" + resourceName(component, "css", true)).asStyle
    }
}

abstract class CDNResources extends RemoteResources(new URL("http://cdn.pydata.org/bokeh/release/"))

object Resources {
    case object CDN    extends CDNResources
    case object CDNDev extends CDNResources with DevResources

    case object Inline    extends InlineResources
    case object InlineDev extends InlineResources with DevResources

    case object Relative    extends RelativeResources
    case object RelativeDev extends RelativeResources with DevResources

    case object Absolute    extends AbsoluteResources
    case object AbsoluteDev extends AbsoluteResources with DevResources

    private val fromStringPF: PartialFunction[String, Resources] = {
        case "cdn"          => CDN
        case "cdn-dev"      => CDNDev
        case "inline"       => Inline
        case "inline-dev"   => InlineDev
        case "relative"     => Relative
        case "relative-dev" => RelativeDev
        case "absolute"     => Absolute
        case "absolute-dev" => AbsoluteDev
    }

    def fromString(string: String): Option[Resources] = fromStringPF.lift(string)

    val default = CDN
}
