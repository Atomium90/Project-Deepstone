package roguelite

import cats.effect.{ IO, Resource }
import munit.CatsEffectSuite
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger

import java.nio.file.{ Files, Path, Paths }
import scala.jdk.CollectionConverters.*

class SaveLocationSuite extends CatsEffectSuite:

  given Logger[IO] = Slf4jLogger.getLogger[IO]

  private val home = Paths.get("home", "player")

  test("on Windows the save folder is Deepstone under APPDATA"):
    assertEquals(
      SaveLocation.dataDirectory("Windows 11", Map("APPDATA" -> "C:\\Users\\player\\AppData\\Roaming"), home),
      Paths.get("C:\\Users\\player\\AppData\\Roaming").resolve("Deepstone")
    )

  test("on Windows without APPDATA it falls back to the same folder under the home directory"):
    val expected = home.resolve("AppData").resolve("Roaming").resolve("Deepstone")
    assertEquals(SaveLocation.dataDirectory("Windows 10", Map.empty, home), expected)
    assertEquals(SaveLocation.dataDirectory("Windows 10", Map("APPDATA" -> ""), home), expected)

  test("on macOS the save folder is under Library/Application Support"):
    assertEquals(
      SaveLocation.dataDirectory("Mac OS X", Map.empty, home),
      home.resolve("Library").resolve("Application Support").resolve("Deepstone")
    )

  test("on Linux the save folder follows XDG_DATA_HOME, else ~/.local/share"):
    assertEquals(
      SaveLocation.dataDirectory("Linux", Map("XDG_DATA_HOME" -> "/data"), home),
      Paths.get("/data").resolve("deepstone")
    )
    assertEquals(
      SaveLocation.dataDirectory("Linux", Map.empty, home),
      home.resolve(".local").resolve("share").resolve("deepstone")
    )

  private def write(file: Path, content: String): Unit =
    Files.createDirectories(file.getParent)
    Files.writeString(file, content)

  private def deleteTree(dir: Path): Unit =
    val walk = Files.walk(dir)
    try walk.iterator().asScala.toList.reverse.foreach(Files.delete)
    finally walk.close()

  /** A scratch folder holding a launch folder and a data folder side by side, deleted afterwards. */
  private val folders = ResourceFixture(
    Resource.make(IO.blocking(Files.createTempDirectory("save-location-suite")))(
      dir => IO.blocking(deleteTree(dir))
    )
  )

  folders.test("an explicit --db path is used as given, and nothing is created or copied"):
    root =>
      val launch = root.resolve("launch")
      val data   = root.resolve("data")
      write(launch.resolve("deepstone.db"), "old save")
      SaveLocation.resolve(Some("elsewhere/my.db"), launch, data).map:
        path =>
          assertEquals(path, Paths.get("elsewhere/my.db"))
          assert(!Files.exists(data))

  folders.test("with no --db the save is in the data folder, which is created"):
    root =>
      val data = root.resolve("data").resolve("Deepstone")
      SaveLocation.resolve(None, root.resolve("launch"), data).map:
        path =>
          assertEquals(path, data.resolve("deepstone.db"))
          assert(Files.isDirectory(data))
          assert(!Files.exists(path), "a new player has no save file yet, the database creates it")

  folders.test("a save left in the launch folder is copied to the data folder, and the old one stays"):
    root =>
      val launch = root.resolve("launch")
      val data   = root.resolve("data")
      write(launch.resolve("deepstone.db"), "old save")
      SaveLocation.resolve(None, launch, data).map:
        path =>
          assertEquals(Files.readString(path), "old save")
          assertEquals(Files.readString(launch.resolve("deepstone.db")), "old save")

  folders.test("the side files of an unfinished write travel with the save"):
    root =>
      val launch = root.resolve("launch")
      val data   = root.resolve("data")
      write(launch.resolve("deepstone.db"), "old save")
      write(launch.resolve("deepstone.db-journal"), "pending write")
      SaveLocation.resolve(None, launch, data).map:
        path =>
          assertEquals(Files.readString(Paths.get(path.toString + "-journal")), "pending write")
          assert(!Files.exists(Paths.get(path.toString + "-wal")))

  folders.test("a copy cut short earlier leaves nothing behind and is simply redone"):
    root =>
      val launch = root.resolve("launch")
      val data   = root.resolve("data")
      write(launch.resolve("deepstone.db"), "old save")
      write(data.resolve("deepstone.db.copying"), "half a sav")
      SaveLocation.resolve(None, launch, data).map:
        path =>
          assertEquals(Files.readString(path), "old save")
          assert(!Files.exists(data.resolve("deepstone.db.copying")))

  folders.test("a save already in the data folder is never overwritten by an older one"):
    root =>
      val launch = root.resolve("launch")
      val data   = root.resolve("data")
      write(launch.resolve("deepstone.db"), "old save")
      write(data.resolve("deepstone.db"), "current save")
      SaveLocation.resolve(None, launch, data).map:
        path => assertEquals(Files.readString(path), "current save")

  folders.test("resolving twice copies once and leaves the same file"):
    root =>
      val launch = root.resolve("launch")
      val data   = root.resolve("data")
      write(launch.resolve("deepstone.db"), "old save")
      for
        first  <- SaveLocation.resolve(None, launch, data)
        _      <- IO.blocking(Files.writeString(first, "played since"))
        second <- SaveLocation.resolve(None, launch, data)
      yield
        assertEquals(second, first)
        assertEquals(Files.readString(second), "played since")
