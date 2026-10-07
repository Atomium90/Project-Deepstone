package roguelite.game

import munit.FunSuite

class DungeonSuite extends FunSuite:

  def makeRoom(id: String, roomType: RoomType = RoomType.Combat, entities: List[Entity] = Nil): Room =
    val tiles = Vector.tabulate(6, 8): (row, col) =>
      if row == 0 || row == 5 || col == 0 || col == 7 then Tile.Wall else Tile.Floor
    Room(id = id, roomType = roomType, theme = "dungeon", width = 8, height = 6, tiles = tiles, entities = entities)

  def simpleDungeon: Dungeon =
    val r1 = makeRoom("r1")
    val r2 = makeRoom("r2")
    Dungeon(rooms = Map("r1" -> r1, "r2" -> r2), currentRoomId = "r1")

  // -- construction ----------------------------------------------------------

  test("fromRooms sets the first room as current"):
    val r1  = makeRoom("r1")
    val r2  = makeRoom("r2")
    val dun = Dungeon.fromRooms(List(r1, r2))
    assertEquals(dun.map(_.currentRoomId), Right("r1"))

  test("fromRooms with empty list returns Left"):
    assert(Dungeon.fromRooms(Nil).isLeft)

  // -- currentRoom -----------------------------------------------------------

  test("currentRoom returns the room matching currentRoomId"):
    val dun = simpleDungeon
    assertEquals(dun.currentRoom.id, "r1")

  // -- navigateTo ------------------------------------------------------------

  test("navigateTo a valid room returns Right with updated currentRoomId"):
    val result = simpleDungeon.navigateTo("r2")
    assertEquals(result.map(_.currentRoomId), Right("r2"))

  test("navigateTo an unknown room returns Left"):
    val result = simpleDungeon.navigateTo("nonexistent")
    assert(result.isLeft)

  test("navigateTo does not mutate the original dungeon"):
    val original = simpleDungeon
    original.navigateTo("r2")
    assertEquals(original.currentRoomId, "r1")

  // -- visited rooms ---------------------------------------------------------

  def chainDungeon: Dungeon =
    Dungeon(rooms = List("r1", "r2", "r3").map(id => id -> makeRoom(id)).toMap, currentRoomId = "r1")

  test("a fresh dungeon counts only its starting room as visited"):
    assertEquals(chainDungeon.visitedRoomIds, Set("r1"))

  test("navigateTo keeps the room it leaves in the visited set"):
    val result = chainDungeon.navigateTo("r2").flatMap(_.navigateTo("r3"))
    assertEquals(result.map(_.visitedRoomIds), Right(Set("r1", "r2", "r3")))

  test("going back to a room does not add anything new to the visited set"):
    val result = chainDungeon.navigateTo("r2").flatMap(_.navigateTo("r1"))
    assertEquals(result.map(_.visitedRoomIds), Right(Set("r1", "r2")))

  test("a failed navigateTo leaves the visited set untouched"):
    assertEquals(chainDungeon.navigateTo("nonexistent").isLeft, true)
    assertEquals(chainDungeon.visitedRoomIds, Set("r1"))

  // -- cameFrom --------------------------------------------------------------

  test("a forward navigateTo records the room it left as the way back"):
    val result = chainDungeon.navigateTo("r2", forward = true).flatMap(_.navigateTo("r3", forward = true))
    assertEquals(result.map(_.cameFrom), Right(Map("r2" -> "r1", "r3" -> "r2")))

  test("a navigateTo that is not forward never writes cameFrom"):
    val result = chainDungeon.navigateTo("r2")
    assertEquals(result.map(_.cameFrom), Right(Map.empty[String, String]))

  test("going back leaves the earlier room's cameFrom entry as it was"):
    val forward = chainDungeon.navigateTo("r2", forward = true).flatMap(_.navigateTo("r3", forward = true))
    val back    = forward.flatMap(_.navigateTo("r2"))
    assertEquals(back.map(_.cameFrom), Right(Map("r2" -> "r1", "r3" -> "r2")))

  test("entering a room again by going forward replaces its cameFrom entry"):
    val rooms = List("a", "b", "merge").map(id => id -> makeRoom(id)).toMap
    val dun   = Dungeon(rooms = rooms, currentRoomId = "a")
    val result = dun
      .navigateTo("merge", forward = true)
      .flatMap(_.navigateTo("b"))
      .flatMap(_.navigateTo("merge", forward = true))
    assertEquals(result.map(_.cameFrom.get("merge")), Right(Some("b")))

  // -- isAtBoss --------------------------------------------------------------

  test("isAtBoss is false for a combat room"):
    assert(!simpleDungeon.isAtBoss)

  test("isAtBoss is true when current room is boss type"):
    val boss = makeRoom("boss", RoomType.Boss)
    val dun  = Dungeon(rooms = Map("boss" -> boss), currentRoomId = "boss")
    assert(dun.isAtBoss)