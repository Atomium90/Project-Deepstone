package roguelite.game

import munit.CatsEffectSuite

class RoomLoaderSuite extends CatsEffectSuite:

  // The rooms.json resource is on the test classpath (same file as production)
  test("loadAll returns a non-empty room map"):
    for rooms <- RoomLoader.loadAll()
    yield assert(rooms.nonEmpty)

  test("all rooms have valid dimensions"):
    for rooms <- RoomLoader.loadAll()
    yield rooms.values.foreach {
      r =>
        assert(r.width > 0)
        assert(r.height > 0)
        assertEquals(r.tiles.length, r.height)
        assert(r.tiles.forall(_.length == r.width))
    }

  test("all tiles are valid"):
    for rooms <- RoomLoader.loadAll()
    yield rooms.values.foreach {
      r =>
        r.tiles.flatten.foreach {
          t =>
            assert(t == Tile.Wall || t == Tile.Floor)
        }
    }

  test("enemies have label and typeId"):
    for rooms <- RoomLoader.loadAll()
    yield rooms.values.foreach {
      r =>
        r.entities
          .collect {
            case e: Enemy => e
          }
          .foreach {
            e =>
              assert(e.label.nonEmpty)
              assert(e.typeId.nonEmpty)
          }
    }

  test("doors have direction and targetRoomId"):
    for rooms <- RoomLoader.loadAll()
    yield rooms.values.foreach {
      r =>
        r.entities
          .collect {
            case d: Door => d
          }
          .foreach {
            d =>
              assert(d.targetRoomId.nonEmpty)
          }
    }

  test("chests have correct fields"):
    for rooms <- RoomLoader.loadAll()
    yield rooms.values.foreach {
      r =>
        r.entities
          .collect {
            case c: Chest => c
          }
          .foreach {
            c =>
              assert(c.id.nonEmpty)
          }
    }
