import sbt._
import Keys._

import scala.util.Try

object BokehJS {
    object BokehJSKeys {
        val nodeBinary = taskKey[String]("Detected node.js binary, either node or nodejs")
        val bokehjsUpdate = taskKey[Unit]("Resolve BokehJS dependencies (i.e. run `npm install`)")
        val bokehjsSources = settingKey[PathFinder]("BokehJS source file filter")
        val bokehjsBuildDir = settingKey[File]("BokehJS target build directory")
        val bokehjsBuild = taskKey[Unit]("Build BokehJS using gulp build system")
    }

    import BokehJSKeys._

    lazy val bokehjsSettings = Seq(
        nodeBinary <<= Def.task {
            val nodes = "node" :: "nodejs" :: Nil
            nodes.view.find(node => Try { s"$node --version" !! }.isSuccess) getOrElse {
                sys.error("could not detect node.js on this system")
            }
        },
        sourceDirectory in Compile := baseDirectory.value / "src",
        unmanagedResourceDirectories in Compile += baseDirectory.value / "build",
        bokehjsUpdate in Compile <<= Def.task {
            val prefix = baseDirectory.value
            val log = streams.value.log
            val ret = s"npm install --prefix=$prefix --spin=false" ! log
            if (ret != 0) sys.error("npm install failed")
        },
        bokehjsSources in Compile := {
            val all = (sourceDirectory in Compile).value.***
            all.filter(_.isFile).filter(!_.isHidden)
        },
        bokehjsBuildDir in Compile := (classDirectory in Compile).value,
        bokehjsBuild in Compile <<= Def.task {
            def gulp(args: String*) = {
                val node = nodeBinary.value
                val prefix = baseDirectory.value
                val gulp = prefix / "node_modules" / "gulp" / "bin" / "gulp.js"
                val gulpfile = prefix / "gulpfile.js"
                val log = streams.value.log
                val ret = s"$node $gulp --gulpfile $gulpfile ${args.mkString(" ")}" ! log
                if (ret != 0) sys.error("gulp build failed")
            }

            val sources = (bokehjsSources in Compile).value
            val base = (baseDirectory in Compile).value

            val all =
                sources +++
                (base / "gulp" ***) +++
                (base / "gulpfile.js") +++
                (base / "package.json")

            val watched = all.filter(_.isFile).filter(!_.isHidden).get
            val lastModified = watched.map(_.lastModified).max

            val buildDir = (bokehjsBuildDir in Compile).value

            def mkTargets(ext: String, comps: List[String]) = {
                val exts = List(s".$ext", s".$ext.map", s".min.$ext")
                exts.flatMap(ext => comps.map(_ + ext)).map(buildDir / ext / _)
            }

            val targets =
                mkTargets("js",  "bokeh" :: "bokeh-widgets" :: "bokeh-compiler" :: Nil) ++
                mkTargets("css", "bokeh" :: "bokeh-widgets" :: Nil)

            val prevBuild = targets.map(_.lastModified).min
            val numModified = watched.count(_.lastModified > prevBuild)

            if (!targets.forall(_.exists) || numModified > 0) {
                val suffix = numModified match {
                    case 0 => ""
                    case _ => s" for ${numModified} file${if (numModified > 1) "s" else ""}"
                }
                streams.value.log.info(s"Running gulp build$suffix")
                gulp("build", "--build-dir", buildDir.getPath)
            }
        } dependsOn(bokehjsUpdate in Compile),
        watchSources <++= Def.task { (bokehjsSources in Compile).value.get },
        compile in Compile <<= (compile in Compile).dependsOn(bokehjsBuild in Compile))
}
