package roguelite.engine

import cats.effect.{ IO, Resource }
import munit.CatsEffectSuite

import java.nio.file.{ Files, NoSuchFileException, Path }
import scala.jdk.CollectionConverters.*

class DebugRoomsSuite extends CatsEffectSuite:

  private def write(file: Path, content: String): Unit =
    Files.createDirectories(file.getParent)
    Files.writeString(file, content)

  /** A temp folder shaped like `debug-rooms/` once it mirrors the Tiled project: a room at the top,
    * rooms in subfolders (one of them two levels down), and files that are not rooms.
    */
  private def createTree(): Path =
    val dir = Files.createTempDirectory("debug-rooms-suite")
    write(dir.resolve("flat_room.json"), "{\"id\":\"flat_room\"}")
    write(dir.resolve("darkDungeon/darkDungeon_boss_001.json"), "{\"id\":\"darkDungeon_boss_001\"}")
    write(dir.resolve("dungeon/deeper/dungeon_boss_001.json"), "{\"id\":\"dungeon_boss_001\"}")
    write(dir.resolve("dungeon/flat_room.json"), "{\"id\":\"flat_room\"}")
    write(dir.resolve("dungeon/notes.txt"), "not a room")
    write(dir.resolve("darkDungeon/readme.md"), "not a room")
    dir

  private def deleteTree(dir: Path): Unit =
    val walk = Files.walk(dir)
    try walk.iterator().asScala.toList.reverse.foreach(Files.delete)
    finally walk.close()

  val tree = ResourceFixture(Resource.make(IO.blocking(createTree()))(dir => IO.blocking(deleteTree(dir))))

  tree.test("lists every room file at any depth by its bare name, sorted, once each"):
    dir =>
      GameSession
        .listDebugRoomsIn(dir)
        .assertEquals(List("darkDungeon_boss_001", "dungeon_boss_001", "flat_room"))

  tree.test("a folder that does not exist lists no room"):
    dir => GameSession.listDebugRoomsIn(dir.resolve("missing")).assertEquals(Nil)

  tree.test("reads a room that sits in a subfolder by its bare id"):
    dir =>
      GameSession
        .readDebugRoomFileIn(dir, "dungeon_boss_001")
        .assertEquals("{\"id\":\"dungeon_boss_001\"}")

  tree.test("an id that is not in any folder fails as a missing file"):
    dir => interceptIO[NoSuchFileException](GameSession.readDebugRoomFileIn(dir, "nope"))

  tree.test("an id carrying a folder is not a room id"):
    dir =>
      interceptIO[NoSuchFileException](GameSession.readDebugRoomFileIn(dir, "darkDungeon/darkDungeon_boss_001")) *>
        interceptIO[NoSuchFileException](GameSession.readDebugRoomFileIn(dir, "../flat_room"))

  tree.test("a file that is not json is never a room"):
    dir => interceptIO[NoSuchFileException](GameSession.readDebugRoomFileIn(dir, "notes"))
