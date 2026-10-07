package roguelite.game

import munit.FunSuite
import roguelite.engine.Direction
import roguelite.game.MinimapLayout.{ Link, Slot }

class MinimapLayoutSuite extends FunSuite:

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

  /** A fork `f` whose two exits (wall and branch tag) lead to two branches of two rooms, `a1, a2` and
    * `b1, b2`, which merge on `m`, followed by `end`.
    */
  private def forkDungeon(exitA: (Direction, String), exitB: (Direction, String)): Dungeon =
    dungeon(
      room("f", RoomType.Fork, entities = List(forwardDoor("a1", exitA._1, Some(exitA._2)), forwardDoor("b1", exitB._1, Some(exitB._2)))),
      room("a1", entities = List(forwardDoor("a2"))),
      room("a2", entities = List(forwardDoor("m"))),
      room("b1", entities = List(forwardDoor("b2"))),
      room("b2", entities = List(forwardDoor("m"))),
      room("m", entities = List(forwardDoor("end"))),
      room("end", RoomType.Boss)
    )

  // --- Rows and columns -------------------------------------------------------

  test("a chain of rooms fills one row, a column each, on the main lane"):
    val layout = MinimapLayout.of(
      dungeon(room("a", entities = List(forwardDoor("b"))), room("b", entities = List(forwardDoor("c"))), room("c"))
    )
    assertEquals(layout.slots, Map("a" -> Slot(0, 0, 0), "b" -> Slot(0, 1, 0), "c" -> Slot(0, 2, 0)))
    assertEquals(layout.links, List(Link("a", "b", None), Link("b", "c", None)))

  test("a single room is placed alone"):
    val layout = MinimapLayout.of(dungeon(room("a")))
    assertEquals(layout.slots, Map("a" -> Slot(0, 0, 0)))
    assertEquals(layout.links, Nil)
    assertEquals(layout.themes, Map(0 -> "dungeon"))

  test("a Rest closes its section and the room after it starts the next one at column 0"):
    val layout = MinimapLayout.of(
      dungeon(
        room("a", entities = List(forwardDoor("r"))),
        room("r", RoomType.Rest, entities = List(forwardDoor("b"))),
        room("b", theme = "darkDungeon", entities = List(forwardDoor("c"))),
        room("c", theme = "darkDungeon")
      )
    )
    assertEquals(
      layout.slots,
      Map("a" -> Slot(0, 0, 0), "r" -> Slot(0, 1, 0), "b" -> Slot(1, 0, 0), "c" -> Slot(1, 1, 0))
    )
    assertEquals(layout.themes, Map(0 -> "dungeon", 1 -> "darkDungeon"))

  test("the layout does not depend on where the player is"):
    val d = forkDungeon((Direction.Down, "1"), (Direction.Right, "2"))
    assertEquals(MinimapLayout.of(d.copy(currentRoomId = "m")), MinimapLayout.of(d))

  // --- Forks ---------------------------------------------------------------------

  test("a merge room sits one column past the longest branch, back on the main lane"):
    val layout = MinimapLayout.of(forkDungeon((Direction.Down, "1"), (Direction.Right, "2")))
    assertEquals(layout.slots("f"), Slot(0, 0, 0))
    assertEquals(layout.slots("a2"), Slot(0, 2, 1))
    assertEquals(layout.slots("m"), Slot(0, 3, 0))
    assertEquals(layout.slots("end"), Slot(0, 4, 0))

  test("a merge room follows the longest branch when the branches differ in length"):
    val layout = MinimapLayout.of(
      dungeon(
        room("f", RoomType.Fork, entities = List(forwardDoor("a1", Direction.Right, Some("1")), forwardDoor("b1", Direction.Down, Some("2")))),
        room("a1", entities = List(forwardDoor("m"))),
        room("b1", entities = List(forwardDoor("b2"))),
        room("b2", entities = List(forwardDoor("b3"))),
        room("b3", entities = List(forwardDoor("m"))),
        room("m")
      )
    )
    assertEquals(layout.slots("m").column, 4)

  test("the upper lane goes to the door that comes first in up, right, down, left, whatever the branch tags"):
    val walls = List(Direction.Up, Direction.Right, Direction.Down, Direction.Left)
    for
      (first, index) <- walls.zipWithIndex
      second         <- walls.drop(index + 1)
    do
      // Branch a carries the lower tag but the later wall, so the tags would put it on top.
      val layout = MinimapLayout.of(forkDungeon((second, "1"), (first, "2")))
      assertEquals(layout.slots("b1").lane, 0, s"$first should be above $second")
      assertEquals(layout.slots("a1").lane, 1, s"$second should be below $first")

  test("two doors on the same wall are told apart by their branch tag"):
    val layout = MinimapLayout.of(forkDungeon((Direction.Down, "2"), (Direction.Down, "1")))
    assertEquals(layout.slots("b1").lane, 0)
    assertEquals(layout.slots("a1").lane, 1)

  test("only the links leaving a fork carry the wall of their door"):
    val layout = MinimapLayout.of(forkDungeon((Direction.Down, "1"), (Direction.Right, "2")))
    assertEquals(layout.links.size, 7)
    assertEquals(
      layout.links.filter(_.exit.isDefined),
      List(Link("f", "b1", Some(Direction.Right)), Link("f", "a1", Some(Direction.Down)))
    )

  test("two doors leading to the same room make one link and no fork"):
    val layout = MinimapLayout.of(
      dungeon(
        room("a", entities = List(forwardDoor("b"), forwardDoor("b").copy(id = "second_door_to_b"))),
        room("b")
      )
    )
    assertEquals(layout.links, List(Link("a", "b", None)))

  // --- Rooms that are left out ---------------------------------------------------

  test("a Vault room and the doors into it are left out"):
    val layout = MinimapLayout.of(
      dungeon(
        room("a", entities = List(forwardDoor("b"), LockedDoor("lock", 1, 1, Direction.Right, targetRoomId = "vault"))),
        room("b"),
        room("vault", RoomType.Vault, entities = List(forwardDoor("b")))
      )
    )
    assertEquals(layout.slots, Map("a" -> Slot(0, 0, 0), "b" -> Slot(0, 1, 0)))
    assertEquals(layout.links, List(Link("a", "b", None)))

  test("rooms that only lead into each other in a cycle are left out"):
    val layout = MinimapLayout.of(
      dungeon(
        room("r", entities = List(forwardDoor("x"))),
        room("x", entities = List(forwardDoor("y"))),
        room("y", entities = List(forwardDoor("x")))
      )
    )
    assertEquals(layout.slots, Map("r" -> Slot(0, 0, 0)))
    assertEquals(layout.links, Nil)
