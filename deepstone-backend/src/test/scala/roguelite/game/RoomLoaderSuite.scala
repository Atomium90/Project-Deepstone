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

  test("at least one chest in the real room pool is trapped"):
    for rooms <- RoomLoader.loadAll()
    yield
      val allChests = rooms.values.flatMap(_.entities.collect { case c: Chest => c })
      assert(allChests.exists(_.trapped), "expected at least one trapped chest in rooms.json")

  test("loadAll includes a Vault room"):
    for rooms <- RoomLoader.loadAll()
    yield assert(rooms.values.exists(_.roomType == RoomType.Vault), "expected a Vault room in rooms.json")

  test("locked doors have direction and targetRoomId"):
    for rooms <- RoomLoader.loadAll()
    yield
      val lockedDoors = rooms.values.flatMap(_.entities.collect { case d: LockedDoor => d })
      assert(lockedDoors.nonEmpty, "expected at least one locked_door in rooms.json")
      lockedDoors.foreach(d => assert(d.targetRoomId.nonEmpty))

  test("every LockedDoor's targetRoomId resolves to a room in the pool"):
    for rooms <- RoomLoader.loadAll()
    yield
      val lockedDoors = rooms.values.flatMap(_.entities.collect { case d: LockedDoor => d })
      lockedDoors.foreach:
        d => assert(rooms.contains(d.targetRoomId), s"LockedDoor '${d.id}' targets unknown room '${d.targetRoomId}'")

  test("at least one door in the real room pool is trapped"):
    for rooms <- RoomLoader.loadAll()
    yield
      val allDoors = rooms.values.flatMap(_.entities.collect { case d: Door => d })
      assert(allDoors.exists(_.doorKind == DoorKind.Trapped), "expected at least one trapped door in rooms.json")

  test("at least one door in the real room pool is secret and starts unrevealed"):
    for rooms <- RoomLoader.loadAll()
    yield
      val allDoors = rooms.values.flatMap(_.entities.collect { case d: Door => d })
      assert(allDoors.exists(d => d.doorKind == DoorKind.Secret && !d.revealed),
             "expected at least one unrevealed secret door in rooms.json"
      )

  test("normal doors default to revealed = true"):
    for rooms <- RoomLoader.loadAll()
    yield
      val normalDoors = rooms.values.flatMap(_.entities.collect { case d: Door if d.doorKind == DoorKind.Normal => d })
      assert(normalDoors.nonEmpty)
      assert(normalDoors.forall(_.revealed), "expected all normal doors to be revealed")

  test("npcs have non-empty id and name"):
    for rooms <- RoomLoader.loadAll()
    yield rooms.values.foreach {
      r =>
        r.entities
          .collect {
            case n: Npc => n
          }
          .foreach {
            n =>
              assert(n.id.nonEmpty)
              assert(n.name.nonEmpty)
          }
    }

  test("loadAll includes at least one Npc"):
    for rooms <- RoomLoader.loadAll()
    yield
      val allNpcs = rooms.values.flatMap(_.entities.collect { case n: Npc => n })
      assert(allNpcs.nonEmpty, "expected at least one npc in rooms.json")
