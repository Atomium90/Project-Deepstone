package roguelite.game

import munit.FunSuite
import roguelite.engine.Direction

class MinimapBuilderSuite extends FunSuite:

  // --- Fixtures ------------------------------------------------------------

  private val floor = Vector.fill(3)(Vector.fill(3)(Tile.Floor))

  private def room(id: String,
                   roomType: RoomType = RoomType.Combat,
                   theme: String = "dungeon",
                   entities: List[Entity] = Nil
  ): Room =
    Room(id, roomType, theme, 3, 3, floor, entities)

  private def forwardDoor(to: String, direction: Direction = Direction.Down, branch: Option[String] = None): Door =
    Door(s"to_$to", x = 1, y = 1, direction = direction, link = DoorLink.Resolved(ConnectorRole.Next, branch, to))

  private def dungeon(rooms: Room*): Dungeon =
    Dungeon(rooms.map(r => r.id -> r).toMap, rooms.head.id)

  /** One room of every type in a row, so each type can be checked while the player is at the start. */
  private val everyType: Dungeon =
    val types = List(RoomType.Combat, RoomType.Loot, RoomType.Rest, RoomType.Fork, RoomType.MiniBoss, RoomType.Boss, RoomType.Sanctuary)
    val ids   = types.map(t => s"${t.toString.toLowerCase}_001")
    dungeon(
      types.zip(ids).zipWithIndex.map { case ((t, id), i) =>
        room(id, t, entities = ids.lift(i + 1).map(forwardDoor(_)).toList)
      }*
    )

  // --- What the player sees ---------------------------------------------------

  test("the room the player is in and the rooms already left show their type, the rest do not"):
    val start = dungeon(
      room("a", entities = List(forwardDoor("b"))),
      room("b", RoomType.Loot, entities = List(forwardDoor("c"))),
      room("c", RoomType.Loot)
    )
    val view = MinimapBuilder.build(start.navigateTo("b").toOption.get)
    assertEquals(view.nodes.map(n => (n.visited, n.current, n.roomType)),
                 List((true, false, Some("combat")), (true, true, Some("loot")), (false, false, None))
    )

  test("a fresh dungeon marks only its starting room as visited and current"):
    val view = MinimapBuilder.build(everyType)
    assertEquals(view.nodes.count(_.visited), 1)
    assertEquals(view.nodes.filter(_.current).map(_.column), List(0))

  test("Boss, MiniBoss and Sanctuary rooms show their type before the player gets there"):
    val view = MinimapBuilder.build(everyType)
    assertEquals(view.nodes.map(_.roomType),
                 List(Some("combat"), None, None, None, Some("miniboss"), Some("boss"), Some("sanctuary"))
    )

  test("a revealed room that has not been visited is still marked as not visited"):
    val boss = MinimapBuilder.build(everyType).nodes.find(_.roomType.contains("boss"))
    assertEquals(boss.map(_.visited), Some(false))

  test("going back to a room leaves the map showing every room visited so far"):
    val start = dungeon(room("a", entities = List(forwardDoor("b"))), room("b", entities = List(forwardDoor("c"))), room("c"))
    val moved = start.navigateTo("b").flatMap(_.navigateTo("c")).flatMap(_.navigateTo("b")).toOption.get
    val view  = MinimapBuilder.build(moved)
    assertEquals(view.nodes.map(_.visited), List(true, true, true))
    assertEquals(view.nodes.map(_.current), List(false, true, false))

  // --- Node ids ----------------------------------------------------------------

  test("node ids are made up and say nothing about the room"):
    val view = MinimapBuilder.build(everyType)
    assertEquals(view.nodes.map(_.id), (0 until view.nodes.size).map(i => s"n$i").toList)
    assert(view.nodes.forall(n => !everyType.rooms.contains(n.id)), "a node id is a real room id")

  test("edges join node ids of the map, always forward"):
    val view = MinimapBuilder.build(everyType)
    val byId = view.nodes.map(n => n.id -> n).toMap
    assertEquals(view.edges.size, 6)
    assert(view.edges.forall(e => byId.contains(e.from) && byId.contains(e.to)))
    // A Rest ends a section, so the edge out of it goes to column 0 of the next one.
    def position(id: String): (Int, Int) = (byId(id).section, byId(id).column)
    assert(view.edges.forall(e => Ordering[(Int, Int)].gt(position(e.to), position(e.from))))

  // --- Fork exits ---------------------------------------------------------------

  private val forkDungeon: Dungeon =
    dungeon(
      room("f", RoomType.Fork, entities = List(forwardDoor("a1", Direction.Down, Some("1")), forwardDoor("b1", Direction.Right, Some("2")))),
      room("a1", entities = List(forwardDoor("m"))),
      room("b1", entities = List(forwardDoor("m"))),
      room("m")
    )

  test("only the edges leaving a fork name the wall of their door"):
    val view  = MinimapBuilder.build(forkDungeon)
    val forks = view.edges.filter(_.exit.isDefined)
    assertEquals(forks.size, 2)
    assertEquals(forks.flatMap(_.exit).toSet, Set("DOWN", "RIGHT"))
    assert(forks.forall(e => view.nodes.find(_.id == e.from).exists(_.column == 0)), "an exit was not on the fork")

  test("the two branches of a fork sit on different lanes, the upper one behind the door that comes first"):
    val view  = MinimapBuilder.build(forkDungeon)
    val lanes = view.nodes.filter(_.column == 1).map(n => n.id -> n.lane).toMap
    assertEquals(view.edges.find(_.exit.contains("RIGHT")).map(e => lanes(e.to)), Some(0))
    assertEquals(view.edges.find(_.exit.contains("DOWN")).map(e => lanes(e.to)), Some(1))

  // --- Sections -------------------------------------------------------------------

  test("each section is listed with its theme"):
    val twoSections = dungeon(
      room("a", entities = List(forwardDoor("r"))),
      room("r", RoomType.Rest, entities = List(forwardDoor("b"))),
      room("b", theme = "darkDungeon")
    )
    val view = MinimapBuilder.build(twoSections)
    assertEquals(view.sections.map(s => (s.index, s.theme)), List((0, "dungeon"), (1, "darkDungeon")))
    assertEquals(view.nodes.map(_.section), List(0, 0, 1))

  test("a dungeon of a single room is a map of one current node"):
    val view = MinimapBuilder.build(dungeon(room("only")))
    assertEquals(view.nodes.map(n => (n.visited, n.current, n.roomType)), List((true, true, Some("combat"))))
    assertEquals(view.edges, Nil)

  test("no node is current while the player is in a room that is off the map"):
    val withVault = dungeon(
      room("a", entities = List(forwardDoor("b"))),
      room("b"),
      room("vault", RoomType.Vault)
    )
    val view = MinimapBuilder.build(withVault.copy(currentRoomId = "vault"))
    assertEquals(view.nodes.count(_.current), 0)
    assertEquals(view.nodes.size, 2)
