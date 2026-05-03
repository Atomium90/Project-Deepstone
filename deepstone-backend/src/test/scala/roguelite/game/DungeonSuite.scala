package roguelite.game

import munit.FunSuite

class DungeonSuite extends FunSuite:

  def makeRoom(id: String, roomType: RoomType = RoomType.Combat, entities: List[Entity] = Nil): Room =
    val tiles = Vector.tabulate(6, 8): (row, col) =>
      if row == 0 || row == 5 || col == 0 || col == 7 then Tile.Wall else Tile.Floor
    Room(id = id, roomType = roomType, width = 8, height = 6, tiles = tiles, entities = entities)

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

  // -- isAtBoss --------------------------------------------------------------

  test("isAtBoss is false for a combat room"):
    assert(!simpleDungeon.isAtBoss)

  test("isAtBoss is true when current room is boss type"):
    val boss = makeRoom("boss", RoomType.Boss)
    val dun  = Dungeon(rooms = Map("boss" -> boss), currentRoomId = "boss")
    assert(dun.isAtBoss)