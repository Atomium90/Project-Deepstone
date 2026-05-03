package roguelite.game

import munit.CatsEffectSuite

class RoomLoaderSuite extends CatsEffectSuite:

  // The rooms.json resource is on the test classpath (same file as production)
  test("loadAll returns a non-empty room map"):
    for
      rooms <- RoomLoader.loadAll()
    yield assert(rooms.nonEmpty, "Expected at least one room to be loaded")

  test("loadAll parses room ids correctly"):
    for
      rooms <- RoomLoader.loadAll()
    yield
      assert(rooms.contains("room_001"), "Expected room_001")
      assert(rooms.contains("room_002"), "Expected room_002")
      assert(rooms.contains("room_003"), "Expected room_003")

  test("loadAll parses room types correctly"):
    for
      rooms <- RoomLoader.loadAll()
    yield
      assertEquals(rooms("room_001").roomType, RoomType.Combat)
      assertEquals(rooms("room_002").roomType, RoomType.Loot)
      assertEquals(rooms("room_003").roomType, RoomType.Boss)

  test("loaded rooms have correct dimensions"):
    for
      rooms <- RoomLoader.loadAll()
    yield
      val r1 = rooms("room_001")
      assertEquals(r1.width, 10)
      assertEquals(r1.height, 8)
      assertEquals(r1.tiles.length, 8)
      assertEquals(r1.tiles.head.length, 10)

  test("loaded rooms have border walls and inner floors"):
    for
      rooms <- RoomLoader.loadAll()
    yield
      val r1 = rooms("room_001")
      assertEquals(r1.tileAt(0, 0), Tile.Wall)   // top-left corner
      assertEquals(r1.tileAt(1, 1), Tile.Floor)  // inner tile

  test("enemies are parsed with correct label"):
    for
      rooms <- RoomLoader.loadAll()
    yield
      val entities = rooms("room_001").entities
      val goblins  = entities.collect { case e: Enemy => e }
      assert(goblins.nonEmpty, "Expected at least one enemy in room_001")
      assert(goblins.forall(_.label == "Goblin"))

  test("doors are parsed with direction and targetRoomId"):
    for
      rooms <- RoomLoader.loadAll()
    yield
      val doors = rooms("room_001").entities.collect { case d: Door => d }
      assert(doors.nonEmpty, "Expected at least one door in room_001")
      assertEquals(doors.head.targetRoomId, "room_002")

  test("chests are parsed correctly"):
    for
      rooms <- RoomLoader.loadAll()
    yield
      val chests = rooms("room_002").entities.collect { case c: Chest => c }
      assertEquals(chests.length, 2)