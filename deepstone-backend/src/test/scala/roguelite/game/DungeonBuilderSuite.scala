package roguelite.game

import munit.FunSuite
import roguelite.engine.Direction

import scala.util.Random

class DungeonBuilderSuite extends FunSuite:

  // ---------------------------------------------
  // Test pool fixtures
  // ---------------------------------------------

  def makeTiles(w: Int = 8, h: Int = 6): Vector[Vector[Tile]] =
    Vector.tabulate(h, w):
      (row, col) =>
        if row == 0 || row == h - 1 || col == 0 || col == w - 1 then Tile.Wall else Tile.Floor

  def exitDoor(id: String = "door_exit"): Door =
    Door(id = id, x = 4, y = 5, direction = Direction.Down, targetRoomId = "NEXT")

  def entranceDoor(id: String = "door_entrance"): Door =
    Door(id = id, x = 4, y = 0, direction = Direction.Up, targetRoomId = "PREV")

  def makeRoom(
      id: String,
      roomType: RoomType,
      entities: List[Entity] = Nil
  ): Room =
    Room(id = id,
         roomType = roomType,
         width = 8,
         height = 6,
         tiles = makeTiles(),
         entities = entities
    )

  /** A minimal pool: 2 combat rooms, 1 loot room, 1 rest room, 2 boss rooms. */
  def testPool: Map[String, Room] = Map(
    "c1" -> makeRoom("c1", RoomType.Combat, List(exitDoor())),
    "c2" -> makeRoom("c2", RoomType.Combat, List(entranceDoor(), exitDoor())),
    "l1" -> makeRoom("l1", RoomType.Loot, List(entranceDoor(), exitDoor())),
    "r1" -> makeRoom("r1", RoomType.Rest, List(entranceDoor(), exitDoor())),
    "b1" -> makeRoom("b1", RoomType.Boss, List(entranceDoor())),
    "b2" -> makeRoom("b2", RoomType.Boss, List(entranceDoor()))
  )

  def builder(seed: Long = 42L): DungeonBuilder = DungeonBuilder(testPool, Random(seed))

  // ---------------------------------------------
  // Structure
  // ---------------------------------------------

  test("build returns Right for a valid pool"):
    assert(builder().build(totalRooms = 4).isRight)

  test("built dungeon has the requested number of rooms"):
    val dungeon = builder().build(totalRooms = 4).getOrElse(fail("build failed"))
    assertEquals(dungeon.rooms.size, 4)

  test("first room is a combat room"):
    val dungeon     = builder().build(totalRooms = 4).getOrElse(fail("build failed"))
    val currentRoom = dungeon.currentRoom
    assertEquals(currentRoom.roomType, RoomType.Combat)

  test("last room is a boss room"):
    val dungeon    = builder().build(totalRooms = 4).getOrElse(fail("build failed"))
    val bossRoomId = dungeon.rooms.values.find(_.roomType == RoomType.Boss).map(_.id)
    assert(bossRoomId.isDefined, "Expected a boss room in the dungeon")

  test("build with totalRooms = 2 produces entrance + boss only"):
    val dungeon = builder().build(totalRooms = 2).getOrElse(fail("build failed"))
    assertEquals(dungeon.rooms.size, 2)
    assert(dungeon.rooms.values.exists(_.roomType == RoomType.Boss))

  test("no room id is repeated in the dungeon"):
    val dungeon = builder().build(totalRooms = 4).getOrElse(fail("build failed"))
    assertEquals(dungeon.rooms.size, dungeon.rooms.keys.toSet.size)

  // ---------------------------------------------
  // Door wiring
  // ---------------------------------------------

  test("exit doors no longer point to NEXT after wiring (except last room)"):
    val dungeon     = builder().build(totalRooms = 4).getOrElse(fail("build failed"))
    val middleRooms = dungeon.rooms.values.toList.dropRight(1)
    val middleDoors = middleRooms.flatMap(_.entities).collect {
      case d: Door => d
    }
    assert(
      middleDoors.forall(_.targetRoomId != "NEXT"),
      s"Found unwired NEXT door: ${middleDoors.filter(_.targetRoomId == "NEXT").map(_.id).mkString(", ")}"
    )

  test("entrance doors no longer point to PREV after wiring (except first room)"):
    val dungeon     = builder().build(totalRooms = 4).getOrElse(fail("build failed"))
    val middleRooms = dungeon.rooms.values.toList.drop(1)
    val middleDoors = middleRooms.flatMap(_.entities).collect {
      case d: Door => d
    }
    assert(
      middleDoors.forall(_.targetRoomId != "PREV"),
      s"Found unwired PREV door: ${middleDoors.filter(_.targetRoomId == "PREV").map(_.id).mkString(", ")}"
    )

  test("exit door of first room points to a room that exists in the dungeon"):
    val dungeon   = builder().build(totalRooms = 4).getOrElse(fail("build failed"))
    val firstRoom = dungeon.currentRoom
    val exitDoors = firstRoom.entities.collect {
      case d: Door if d.direction == Direction.Down => d
    }
    assert(exitDoors.nonEmpty, "First room should have an exit door")
    assert(dungeon.rooms.contains(exitDoors.head.targetRoomId),
           "Exit door target should exist in dungeon"
    )

  // ---------------------------------------------
  // Error cases
  // ---------------------------------------------

  test("build returns Left when pool has no combat room"):
    val noCombatPool = testPool.filterNot(_._2.roomType == RoomType.Combat)
    val result       = DungeonBuilder(noCombatPool).build()
    assert(result.isLeft)

  test("build returns Left when pool has no boss room"):
    val noBossPool = testPool.filterNot(_._2.roomType == RoomType.Boss)
    val result     = DungeonBuilder(noBossPool).build()
    assert(result.isLeft)

  // ---------------------------------------------
  // Reproducibility
  // ---------------------------------------------

  test("same seed produces the same dungeon structure"):
    val d1 =
      DungeonBuilder(testPool, Random(99L)).build(totalRooms = 4).getOrElse(fail("build 1 failed"))
    val d2 =
      DungeonBuilder(testPool, Random(99L)).build(totalRooms = 4).getOrElse(fail("build 2 failed"))
    assertEquals(d1.rooms.keys.toSet, d2.rooms.keys.toSet)
    assertEquals(d1.currentRoomId, d2.currentRoomId)
