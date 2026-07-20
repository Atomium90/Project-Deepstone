package roguelite.game

import munit.FunSuite
import roguelite.engine.Direction

class RoomSuite extends FunSuite:

  /** A small 4×4 room with walls on the border and floor inside. */
  def testRoom(entities: List[Entity] = Nil): Room =
    val tiles = Vector.tabulate(4, 4): (row, col) =>
      if row == 0 || row == 3 || col == 0 || col == 3 then Tile.Wall else Tile.Floor
    Room(id = "test_room", roomType = RoomType.Combat, width = 4, height = 4, tiles = tiles, entities = entities)

  // -- tileAt ----------------------------------------------------------------

  test("tileAt returns Wall for border tiles"):
    val room = testRoom()
    assertEquals(room.tileAt(0, 0), Tile.Wall)
    assertEquals(room.tileAt(3, 3), Tile.Wall)

  test("tileAt returns Floor for inner tiles"):
    val room = testRoom()
    assertEquals(room.tileAt(1, 1), Tile.Floor)
    assertEquals(room.tileAt(2, 2), Tile.Floor)

  test("tileAt returns Wall for out-of-bounds coordinates"):
    val room = testRoom()
    assertEquals(room.tileAt(-1, 0), Tile.Wall)
    assertEquals(room.tileAt(99, 99), Tile.Wall)

  // -- isWalkable ------------------------------------------------------------

  test("isWalkable is true for inner floor tiles"):
    assert(testRoom().isWalkable(1, 1))

  test("isWalkable is false for wall tiles"):
    assert(!testRoom().isWalkable(0, 0))

  // -- entity lookup ---------------------------------------------------------

  test("entityById finds an existing entity"):
    val enemy = Enemy("e1", x = 2, y = 2, typeId = "goblin", label = "Goblin")
    val room  = testRoom(entities = List(enemy))
    assertEquals(room.entityById("e1"), Some(enemy))

  test("entityById returns None for unknown id"):
    assertEquals(testRoom().entityById("nope"), None)

  test("entityAt finds an entity at the given position"):
    val chest = Chest("c1", x = 2, y = 1)
    val room  = testRoom(entities = List(chest))
    assertEquals(room.entityAt(2, 1), Some(chest))

  test("entityAt returns None when no entity is at that position"):
    assertEquals(testRoom().entityAt(1, 1), None)

  // -- removeEntity ----------------------------------------------------------

  test("removeEntity removes the entity with the given id"):
    val enemy = Enemy("e1", x = 2, y = 2, typeId = "goblin", label = "Goblin")
    val room  = testRoom(entities = List(enemy))
    val after = room.removeEntity("e1")
    assertEquals(after.entities, Nil)

  test("removeEntity leaves other entities untouched"):
    val e1   = Enemy("e1", x = 2, y = 2, typeId = "goblin", label = "Goblin")
    val e2   = Enemy("e2", x = 1, y = 1, typeId = "orc", label = "Orc")
    val room = testRoom(entities = List(e1, e2))
    val after = room.removeEntity("e1")
    assertEquals(after.entities, List(e2))

  // -- toView ----------------------------------------------------------------

  test("toView sets correct player position"):
    val view = testRoom().toView(playerX = 2, playerY = 1)
    assertEquals(view.playerX, 2)
    assertEquals(view.playerY, 1)

  test("toView serializes floor tiles as 'floor' strings"):
    val view = testRoom().toView(1, 1)
    assertEquals(view.tiles(1)(1), "floor")

  test("toView serializes wall tiles as 'wall' strings"):
    val view = testRoom().toView(1, 1)
    assertEquals(view.tiles(0)(0), "wall")

  test("toView includes all entity views"):
    val enemy = Enemy("e1", x = 2, y = 2, typeId = "goblin", label = "Goblin")
    val view  = testRoom(entities = List(enemy)).toView(1, 1)
    assertEquals(view.entities.length, 1)
    assertEquals(view.entities.head.kind, "enemy")

  // -- withEntities ------------------------------------------------------------

  test("withEntities appends new entities to the room"):
    val e1    = Enemy("e1", x = 1, y = 1, typeId = "goblin", label = "Goblin")
    val e2    = Enemy("e2", x = 2, y = 2, typeId = "orc", label = "Orc")
    val room  = testRoom(entities = List(e1))
    val after = room.withEntities(List(e2))
    assertEquals(after.entities, List(e1, e2))

  // -- nearbyFreeTiles ---------------------------------------------------------

  test("nearbyFreeTiles returns the origin tile itself if it is free"):
    assertEquals(testRoom().nearbyFreeTiles(1, 1, count = 1), List((1, 1)))

  test("nearbyFreeTiles skips excluded tiles and searches outward"):
    val found = testRoom().nearbyFreeTiles(1, 1, count = 1, exclude = Set((1, 1)))
    assertEquals(found, List((1, 2)))

  test("nearbyFreeTiles skips tiles occupied by another entity"):
    val chest = Chest("c1", x = 1, y = 1)
    val found = testRoom(entities = List(chest)).nearbyFreeTiles(1, 1, count = 1)
    assertEquals(found, List((1, 2)))

  test("nearbyFreeTiles never returns wall tiles"):
    val room  = testRoom()
    val found = room.nearbyFreeTiles(0, 0, count = 4)
    assert(found.forall { case (x, y) => room.isWalkable(x, y) }, s"expected only walkable tiles: $found")

  test("nearbyFreeTiles returns fewer tiles than requested when the room runs out of space"):
    // testRoom only has 4 floor tiles total: (1,1), (1,2), (2,1), (2,2)
    val found = testRoom().nearbyFreeTiles(1, 1, count = 10)
    assertEquals(found.size, 4)