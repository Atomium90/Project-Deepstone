import sbt._
import sbt.librarymanagement.ModuleReport

import java.io.File
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files

/** Writes THIRD-PARTY-NOTICES.txt, the file that goes with the libraries the package ships.
  *
  * The libraries come from the dependency report of the build, so the list always matches what is
  * really in the package's lib folder. Each library's license is read from its own description and
  * named by its SPDX identifier, and the full text of every license in use is copied in from
  * src/notices/licenses. The build stops, rather than write an incomplete notice, when a library
  * declares no license, declares one this file does not know, or when the text of a license is
  * missing from src/notices/licenses.
  */
object ThirdPartyNotices {

  /** The SPDX identifier for a license as a library's description names it, or None if unknown. */
  def spdxId(name: String, url: Option[String]): Option[String] = {
    val n = name.toLowerCase
    val u = url.getOrElse("").toLowerCase
    if (n.contains("apache") || u.contains("apache.org/licenses/license-2.0")) Some("Apache-2.0")
    else if (n == "mit" || n.contains("mit license") || u.contains("opensource.org/licenses/mit") || u.contains("opensource.org/license/mit")) Some("MIT")
    else if (n.startsWith("bsd-3") || n.startsWith("bsd 3") || u.contains("opensource.org/licenses/bsd-3-clause")) Some("BSD-3-Clause")
    else if (n == "epl-2.0" || u.contains("eclipse.org/legal/epl-v20")) Some("EPL-2.0")
    else if (n == "lgpl-2.1-only" || u.contains("gnu.org/licenses/old-licenses/lgpl-2.1")) Some("LGPL-2.1-only")
    else None
  }

  private final case class Library(coordinates: String, version: String, licenses: List[String])

  private def libraries(modules: Seq[ModuleReport]): List[Library] =
    modules.toList
      .map { m =>
        val coordinates = m.module.organization + ":" + m.module.name
        if (m.licenses.isEmpty)
          sys.error("No license is declared for " + coordinates + " " + m.module.revision + ". Read it from the project and handle it in project/ThirdPartyNotices.scala.")
        val ids = m.licenses.toList.map { case (name, url) =>
          spdxId(name, url).getOrElse(
            sys.error("Unknown license '" + name + "' (" + url.getOrElse("no link") + ") for " + coordinates + ". Add it to project/ThirdPartyNotices.scala and its text to src/notices/licenses.")
          )
        }.distinct
        Library(coordinates, m.module.revision, ids)
      }
      .sortBy(l => (l.coordinates, l.version))

  private def read(file: File): String = new String(Files.readAllBytes(file.toPath), UTF_8).trim

  /** Writes the notices to `out` and returns it. `noticesDir` holds `licenses/<SPDX id>.txt` and
    * `frontend-libraries.txt`.
    */
  def write(out: File, version: String, modules: Seq[ModuleReport], noticesDir: File): File = {
    val libs     = libraries(modules)
    val used     = libs.flatMap(_.licenses).distinct.sorted
    val textsDir = noticesDir / "licenses"
    val texts = used.map { id =>
      val file = textsDir / (id + ".txt")
      if (!file.exists) sys.error("The text of the " + id + " license is missing: " + file)
      id -> read(file)
    }

    val rule = "=" * 78
    val list = libs.map(l => l.coordinates + " " + l.version + "  -  " + l.licenses.mkString(" / ")).mkString("\n")

    val body =
      s"""Deepstone $version - third-party notices
         |$rule
         |
         |Deepstone's own code is released under the MIT license (the LICENSE file next to this
         |one). The package also contains the free software listed below, each under its own license,
         |whose full text follows the list. The art and audio of the game, and their licenses, are
         |listed in the game (Character screen, Credits tab) and in CREDITS.md of the source
         |repository.
         |
         |When a library lists two licenses, it is offered under both. The copyright of each library
         |belongs to its authors, as stated by the project of that library.
         |
         |1. The libraries of the game server (the lib folder)
         |$rule
         |
         |$list
         |
         |sqlite-jdbc also contains the SQLite database engine, which is in the public domain.
         |
         |2. The library of the game page (served to the browser)
         |$rule
         |
         |${read(noticesDir / "frontend-libraries.txt")}
         |
         |3. The Java runtime (the jre folder)
         |$rule
         |
         |The package carries its own Java runtime: Eclipse Temurin, a build of OpenJDK, under the GNU
         |General Public License version 2 with the Classpath Exception. Its license texts and notices
         |are in the jre/legal folder. The jrt-fs.jar file of the lib folder belongs to it as well.
         |
         |4. License texts
         |$rule
         |""".stripMargin

    val licenseSections = texts.map { case (id, text) =>
      "\n----- " + id + " " + ("-" * math.max(3, 70 - id.length)) + "\n\n" + text + "\n"
    }.mkString

    Files.createDirectories(out.getParentFile.toPath)
    Files.write(out.toPath, (body + licenseSections).getBytes(UTF_8))
    out
  }
}
