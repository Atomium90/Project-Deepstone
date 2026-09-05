package roguelite.game

import munit.CatsEffectSuite

/** Tests for [[RoomLoader]]. The "real room pool" tests below deliberately stay coupled to
  * `data/rooms.json` - they already assert size-independent facts (at least one of a feature
  * exists), not exact ids or counts, so they don't have the exact-content coupling problem the
  * other loader suites had. The fixture-based tests at the top cover entity-kind decode paths and
  * error paths (unknown kind, missing required field) that the real, always-well-formed catalog
  * never exercises, via [[JsonResourceLoader.loadAllFromJson]].
  */
class RoomLoaderSuite extends CatsEffectSuite:

  private val fixture =
    """[
      |  {
      |    "id":"test_room",
      |    "type":"combat",
      |    "width":3,
      |    "height":2,
      |    "tiles":[["wall","floor","wall"],["floor","floor","floor"]],
      |    "entities":[
      |      {"kind":"enemy","id":"e1","x":1,"y":1,"typeId":"goblin","label":"Goblin"},
      |      {"kind":"chest","id":"c1","x":0,"y":0,"trapped":true},
      |      {"kind":"door","id":"d1","x":2,"y":0,"direction":"up","role":"next","doorKind":"trapped"},
      |      {"kind":"door","id":"d2","x":2,"y":1,"direction":"down","role":"next","doorKind":"secret","revealed":false},
      |      {"kind":"door","id":"d3","x":1,"y":0,"direction":"left","role":"prev"},
      |      {"kind":"locked_door","id":"ld1","x":0,"y":1,"direction":"left","targetRoomId":"vault_test","doorTag":"gold"},
      |      {"kind":"npc","id":"n1","x":1,"y":1,"name":"Test Npc"}
      |    ]
      |  }
      |]""".stripMargin

  test("loadAllFromJson decodes dimensions and the tile grid"):
    for rooms <- RoomLoader.loadAllFromJson(fixture)
    yield
      val r = rooms("test_room")
      assertEquals(r.width, 3)
      assertEquals(r.height, 2)
      assertEquals(r.tiles, Vector(Vector(Tile.Wall, Tile.Floor, Tile.Wall), Vector(Tile.Floor, Tile.Floor, Tile.Floor)))

  test("enemy entity decodes typeId and label"):
    for rooms <- RoomLoader.loadAllFromJson(fixture)
    yield
      val e = rooms("test_room").entities.collectFirst { case e: Enemy => e }.get
      assertEquals(e.typeId, "goblin")
      assertEquals(e.label, "Goblin")

  test("chest entity decodes its trapped flag"):
    for rooms <- RoomLoader.loadAllFromJson(fixture)
    yield assert(rooms("test_room").entities.collectFirst { case c: Chest => c }.get.trapped)

  test("door entity decodes direction, link, and doorKind"):
    for rooms <- RoomLoader.loadAllFromJson(fixture)
    yield
      val doors = rooms("test_room").entities.collect { case d: Door => d }
      val d1    = doors.find(_.id == "d1").get
      assertEquals(d1.direction, roguelite.engine.Direction.Up)
      assertEquals(d1.link, DoorLink.Unresolved(ConnectorRole.Next))
      assertEquals(d1.doorKind, DoorKind.Trapped)

  test("a door's role is independent of its direction"):
    for rooms <- RoomLoader.loadAllFromJson(fixture)
    yield
      // d1 is direction "up" but role "next" - role no longer doubles as an implicit direction.
      val d1 = rooms("test_room").entities.collect { case d: Door => d }.find(_.id == "d1").get
      assertEquals(d1.direction, roguelite.engine.Direction.Up)
      assertEquals(d1.link.role, ConnectorRole.Next)

  test("an omitted doorKind defaults to Normal, and an omitted revealed defaults from doorKind"):
    for rooms <- RoomLoader.loadAllFromJson(fixture)
    yield
      val doors = rooms("test_room").entities.collect { case d: Door => d }
      val d1    = doors.find(_.id == "d1").get // trapped, revealed omitted
      val d2    = doors.find(_.id == "d2").get // secret, revealed explicitly false
      val d3    = doors.find(_.id == "d3").get // doorKind omitted -> Normal
      assert(d1.revealed, "a trapped door with no explicit 'revealed' should default to revealed")
      assert(!d2.revealed)
      assertEquals(d3.doorKind, DoorKind.Normal)
      assert(d3.revealed, "a normal door with no explicit 'revealed' should default to revealed")

  test("locked_door entity decodes direction, targetRoomId, and doorTag"):
    for rooms <- RoomLoader.loadAllFromJson(fixture)
    yield
      val ld = rooms("test_room").entities.collectFirst { case d: LockedDoor => d }.get
      assertEquals(ld.targetRoomId, "vault_test")
      assertEquals(ld.doorTag, Some("gold"))

  test("npc entity decodes its name"):
    for rooms <- RoomLoader.loadAllFromJson(fixture)
    yield assertEquals(rooms("test_room").entities.collectFirst { case n: Npc => n }.get.name, "Test Npc")

  test("an unknown tile character fails to parse"):
    val bad = """[{"id":"x","type":"combat","width":1,"height":1,"tiles":[["lava"]],"entities":[]}]"""
    RoomLoader.loadAllFromJson(bad).attempt.map(r => assert(r.isLeft, "expected a parse failure"))

  test("an unknown room type fails to parse"):
    val bad = """[{"id":"x","type":"bogus","width":1,"height":1,"tiles":[["wall"]],"entities":[]}]"""
    RoomLoader.loadAllFromJson(bad).attempt.map(r => assert(r.isLeft, "expected a parse failure"))

  test("an unknown entity kind fails to parse"):
    val bad =
      """[{"id":"x","type":"combat","width":1,"height":1,"tiles":[["floor"]],"entities":[{"kind":"bogus","id":"e1","x":0,"y":0}]}]"""
    RoomLoader.loadAllFromJson(bad).attempt.map(r => assert(r.isLeft, "expected a parse failure"))

  test("an enemy entity missing 'typeId' fails to parse"):
    val bad =
      """[{"id":"x","type":"combat","width":1,"height":1,"tiles":[["floor"]],"entities":[{"kind":"enemy","id":"e1","x":0,"y":0,"label":"L"}]}]"""
    RoomLoader.loadAllFromJson(bad).attempt.map(r => assert(r.isLeft, "expected a parse failure"))

  test("a door entity missing 'role' fails to parse"):
    val bad =
      """[{"id":"x","type":"combat","width":1,"height":1,"tiles":[["floor"]],"entities":[{"kind":"door","id":"d1","x":0,"y":0,"direction":"up"}]}]"""
    RoomLoader.loadAllFromJson(bad).attempt.map(r => assert(r.isLeft, "expected a parse failure"))

  test("a door entity with an unknown 'role' fails to parse"):
    val bad =
      """[{"id":"x","type":"combat","width":1,"height":1,"tiles":[["floor"]],"entities":[{"kind":"door","id":"d1","x":0,"y":0,"direction":"up","role":"sideways"}]}]"""
    RoomLoader.loadAllFromJson(bad).attempt.map(r => assert(r.isLeft, "expected a parse failure"))

  test("a door entity's role is resolved even without a targetRoomId (Unresolved stub)"):
    val ok =
      """[{"id":"x","type":"combat","width":1,"height":1,"tiles":[["floor"]],"entities":[{"kind":"door","id":"d1","x":0,"y":0,"direction":"up","role":"next"}]}]"""
    for rooms <- RoomLoader.loadAllFromJson(ok)
    yield
      val d = rooms("x").entities.collectFirst { case d: Door => d }.get
      assertEquals(d.link, DoorLink.Unresolved(ConnectorRole.Next))

  test("an npc entity missing 'name' fails to parse"):
    val bad = """[{"id":"x","type":"combat","width":1,"height":1,"tiles":[["floor"]],"entities":[{"kind":"npc","id":"n1","x":0,"y":0}]}]"""
    RoomLoader.loadAllFromJson(bad).attempt.map(r => assert(r.isLeft, "expected a parse failure"))

  // ---------------------------------------------
  // Real room pool: size-independent facts about data/rooms.json
  // ---------------------------------------------

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

  test("doors decode with a well-formed link (an Unresolved stub, or a Resolved non-empty target)"):
    for rooms <- RoomLoader.loadAll()
    yield rooms.values.foreach {
      r =>
        r.entities
          .collect {
            case d: Door => d
          }
          .foreach {
            d =>
              d.link match
                case DoorLink.Unresolved(_, _)       => () // expected before DungeonBuilder wires it
                case DoorLink.Resolved(_, _, roomId) => assert(roomId.nonEmpty)
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
