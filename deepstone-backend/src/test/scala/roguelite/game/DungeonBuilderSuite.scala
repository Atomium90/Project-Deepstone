package roguelite.game

import munit.FunSuite
import roguelite.engine.{ Difficulty, Direction }

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
    Door(id = id, x = 4, y = 5, direction = Direction.Down, link = DoorLink.Unresolved(ConnectorRole.Next))

  def entranceDoor(id: String = "door_entrance"): Door =
    Door(id = id, x = 4, y = 0, direction = Direction.Up, link = DoorLink.Unresolved(ConnectorRole.Prev))

  def forkExitDoor(branch: String): Door =
    Door(id = s"door_exit_$branch",
         x = 4,
         y = 5,
         direction = Direction.Down,
         link = DoorLink.Unresolved(ConnectorRole.Next, Some(branch))
    )

  def makeRoom(
      id: String,
      roomType: RoomType,
      entities: List[Entity] = Nil,
      theme: String = "dungeon"
  ): Room =
    Room(id = id,
         roomType = roomType,
         theme = theme,
         width = 8,
         height = 6,
         tiles = makeTiles(),
         entities = entities
    )

  /** `count` rooms of `roomType`, each with both an entrance and exit door - the normal shape for
    * a room that can sit anywhere in the middle of a section (lead-in, reconverge, or a fork
    * branch's own interior). */
  def midRooms(prefix: String, roomType: RoomType, count: Int): List[(String, Room)] =
    (1 to count).map(i => s"$prefix$i" -> makeRoom(s"$prefix$i", roomType, List(entranceDoor(), exitDoor()))).toList

  def forkRoom(id: String): Room = makeRoom(id, RoomType.Fork, List(entranceDoor(), forkExitDoor("a"), forkExitDoor("b")))

  /** No Fork room at all, so every section's branching cluster gracefully skips every time,
    * leaving a clean linear sequence - used for every test that isn't specifically about fork
    * branching. Sized generously for a full Hard build (3 sections): each section's fixed
    * template needs up to 3 Combat (2 lead-in + 1 reconverge) and 1 Loot (reconverge) when its fork
    * cluster can't assemble, plus 1 Boss; 2 sections need a Rest between them. Comfortable margin
    * added on top so no test here is ever one room short.
    */
  def testPool: Map[String, Room] =
    (midRooms("c", RoomType.Combat, 12) :::
      midRooms("l", RoomType.Loot, 5) :::
      midRooms("b", RoomType.Boss, 4) :::
      midRooms("r", RoomType.Rest, 3) :::
      List("sanctuary" -> makeRoom("sanctuary", RoomType.Sanctuary, List(entranceDoor())))
    ).toMap

  def builder(seed: Long = 42L): DungeonBuilder = DungeonBuilder(testPool, Random(seed))

  // ---------------------------------------------
  // Structure
  // ---------------------------------------------

  test("build returns Right for a valid pool"):
    assert(builder().build()().isRight)

  test("a fully-populated section (no fork cluster available) produces the expected room count"):
    val dungeon = builder().build()(biomeCount = 1).getOrElse(fail("build failed"))
    // (2 lead-in Combat + 1 reconverge Combat + 1 reconverge Loot + 1 Boss) + sanctuary - no
    // separately-tracked entrance anymore, section 1's own first room doubles as the start
    assertEquals(dungeon.rooms.size, 6)

  test("built dungeon has the requested room count across multiple sections"):
    val dungeon = builder().build()(biomeCount = 2).getOrElse(fail("build failed"))
    // section(5) + rest + section(5) + sanctuary
    assertEquals(dungeon.rooms.size, 12)

  test("first room is a combat room"):
    val dungeon = builder().build()().getOrElse(fail("build failed"))
    assertEquals(dungeon.currentRoom.roomType, RoomType.Combat)

  test("every section has its own boss room"):
    val dungeon = builder().build()(biomeCount = 3).getOrElse(fail("build failed"))
    assertEquals(dungeon.rooms.values.count(_.roomType == RoomType.Boss), 3)

  test("every section but the last is followed by a guaranteed Rest room"):
    val dungeon = builder().build()(biomeCount = 3).getOrElse(fail("build failed"))
    assertEquals(dungeon.rooms.values.count(_.roomType == RoomType.Rest), 2)

  test("build with no optional content available degrades to entrance + boss + sanctuary only"):
    val minimalPool = Map(
      "c1"        -> makeRoom("c1", RoomType.Combat, List(exitDoor())),
      "b1"        -> makeRoom("b1", RoomType.Boss, List(entranceDoor(), exitDoor())),
      "sanctuary" -> makeRoom("sanctuary", RoomType.Sanctuary, List(entranceDoor()))
    )
    val dungeon = DungeonBuilder(minimalPool).build()(biomeCount = 1).getOrElse(fail("build failed"))
    assertEquals(dungeon.rooms.size, 3)
    assert(dungeon.rooms.values.exists(_.roomType == RoomType.Boss))
    assert(dungeon.rooms.values.exists(_.roomType == RoomType.Sanctuary))

  test("no room id is repeated in the dungeon, even at Hard"):
    val dungeon = builder().build()(biomeCount = 3).getOrElse(fail("build failed"))
    assertEquals(dungeon.rooms.size, dungeon.rooms.keys.toSet.size)

  /** Walks the resolved Next-role chain from the dungeon's entrance to its terminal room,
    * returning each room's roomType in traversal order - only meaningful against a fork-less pool
    * (a Fork room has 2 Next doors, so this simple walk would just follow whichever one happens
    * to come first in entity order, not a real "path"). Proves the fixed template's non-fork slots
    * fire in the right order, not just present somewhere in the dungeon.
    */
  def roomTypeSequence(dungeon: Dungeon): List[RoomType] =
    def nextIdOf(room: Room): Option[String] =
      room.entities
        .collectFirst { case d: Door if d.link.role == ConnectorRole.Next => d.link }
        .collect { case DoorLink.Resolved(_, _, target) => target }

    @annotation.tailrec
    def loop(currentId: String, acc: List[RoomType]): List[RoomType] =
      val room       = dungeon.rooms(currentId)
      val updatedAcc = acc :+ room.roomType
      nextIdOf(room) match
        case Some(nextId) => loop(nextId, updatedAcc)
        case None         => updatedAcc

    loop(dungeon.currentRoomId, Nil)

  test("a section's fixed template is wired in the correct order, with Rest between sections"):
    val dungeon  = builder().build()(biomeCount = 2).getOrElse(fail("build failed"))
    val sequence = roomTypeSequence(dungeon)
    // section 1 (2 lead-in Combat, reconverge Combat, reconverge Loot, Boss - no separately-tracked
    // entrance, section 1's own first room doubles as the start), Rest, section 2 (same 5-room
    // shape), Sanctuary
    assertEquals(sequence.length, 12)
    assertEquals(sequence.take(5), List(RoomType.Combat, RoomType.Combat, RoomType.Combat, RoomType.Loot, RoomType.Boss))
    assertEquals(sequence(5), RoomType.Rest)
    assertEquals(sequence.slice(6, 11), List(RoomType.Combat, RoomType.Combat, RoomType.Combat, RoomType.Loot, RoomType.Boss))
    assertEquals(sequence.last, RoomType.Sanctuary)

  test("build still succeeds, sections simply running together, when biomeCount > 1 but the pool has no Rest room"):
    val poolWithoutRest = testPool.filterNot(_._2.roomType == RoomType.Rest)
    val dungeon = DungeonBuilder(poolWithoutRest, Random(42L)).build()(biomeCount = 2).getOrElse(fail("build failed"))
    assert(!dungeon.rooms.values.exists(_.roomType == RoomType.Rest))
    assertEquals(dungeon.rooms.size, 11) // section(5) + section(5) + sanctuary, no rest between

  // ---------------------------------------------
  // Theme-aware generation
  // ---------------------------------------------

  /** A second theme ("other"), sized like a slimmed-down testPool with no Fork/MiniBoss content -
    * fork testing stays isolated to forkTestPool below, this is only for theme-matching/selection
    * behavior. Has its own Sanctuary room too, same as "dungeon" does, so a test isn't at the mercy
    * of which theme a given seed happens to pick for the last section (see the hard Sanctuary/theme
    * match requirement - a theme with no Sanctuary at all is its own, separately-tested failure
    * mode, not something every other theme-matching test should risk tripping over by accident). */
  def otherThemeRooms: Map[String, Room] =
    (midRooms("oc", RoomType.Combat, 4) :::
      midRooms("ol", RoomType.Loot, 3) :::
      midRooms("ob", RoomType.Boss, 2) :::
      midRooms("or", RoomType.Rest, 2) :::
      List("other_sanctuary" -> makeRoom("other_sanctuary", RoomType.Sanctuary, List(entranceDoor())))
    ).map { case (id, room) => id -> room.copy(theme = "other") }.toMap

  def multiThemePool: Map[String, Room] = testPool ++ otherThemeRooms

  /** Same traversal as [[roomTypeSequence]], but returns each room's theme instead of its type -
    * only meaningful against a fork-less pool, same caveat as roomTypeSequence's own doc. */
  def roomThemeSequence(dungeon: Dungeon): List[String] =
    def nextIdOf(room: Room): Option[String] =
      room.entities
        .collectFirst { case d: Door if d.link.role == ConnectorRole.Next => d.link }
        .collect { case DoorLink.Resolved(_, _, target) => target }

    @annotation.tailrec
    def loop(currentId: String, acc: List[String]): List[String] =
      val room       = dungeon.rooms(currentId)
      val updatedAcc = acc :+ room.theme
      nextIdOf(room) match
        case Some(nextId) => loop(nextId, updatedAcc)
        case None         => updatedAcc

    loop(dungeon.currentRoomId, Nil)

  test("every room in a section is drawn from the same theme"):
    val dungeon = DungeonBuilder(multiThemePool, Random(1L)).build()(biomeCount = 1).getOrElse(fail("build failed"))
    val themesUsed = dungeon.rooms.values.filterNot(_.roomType == RoomType.Sanctuary).map(_.theme).toSet
    assertEquals(themesUsed.size, 1, s"expected a single section to use exactly one theme, found: $themesUsed")

  test("pickNextTheme exhausts every theme at least once before any theme repeats"):
    // 2 themes, 2 sections - the exhaust-first policy guarantees both get used, regardless of seed,
    // rather than leaving "other" completely unused while "dungeon" repeats.
    val dungeon = DungeonBuilder(multiThemePool, Random(5L)).build()(biomeCount = 2).getOrElse(fail("build failed"))
    val sequence = roomThemeSequence(dungeon)
    assertEquals(Set(sequence(4), sequence(10)), Set("dungeon", "other"), s"expected both themes used: $sequence")

  test("a Rest room matches the theme of the section that just ended, not the one about to start"):
    val dungeon  = DungeonBuilder(multiThemePool, Random(5L)).build()(biomeCount = 2).getOrElse(fail("build failed"))
    val sequence = roomThemeSequence(dungeon)
    // index 4 = section 1's own Boss (last room of section 1), index 5 = the Rest right after it
    assertEquals(sequence(5), sequence(4), s"expected Rest to match section 1's theme: $sequence")

  test("the Sanctuary matches the last section's theme"):
    val dungeon  = DungeonBuilder(multiThemePool, Random(5L)).build()(biomeCount = 2).getOrElse(fail("build failed"))
    val sequence = roomThemeSequence(dungeon)
    // index 10 = section 2's own Boss (last room of section 2), last = the Sanctuary
    assertEquals(sequence.last, sequence(10), s"expected the Sanctuary to match section 2's theme: $sequence")

  // ---------------------------------------------
  // Fork branching
  // ---------------------------------------------

  /** Has a Fork room plus enough Combat/Loot/MiniBoss content for its 2-room branches (and the
    * section's other slots) to always succeed, regardless of shuffle order. */
  def forkTestPool: Map[String, Room] =
    (midRooms("c", RoomType.Combat, 8) :::
      midRooms("l", RoomType.Loot, 6) :::
      midRooms("mb", RoomType.MiniBoss, 3) :::
      midRooms("b", RoomType.Boss, 2) :::
      List("f1" -> forkRoom("f1"), "sanctuary" -> makeRoom("sanctuary", RoomType.Sanctuary, List(entranceDoor())))
    ).toMap

  /** Every Next-role door's (branch, resolved target) pair in a room - used to inspect a Fork
    * room's two branch-tagged exits, or a branch room's single unbranched one. */
  def resolvedNextTargets(room: Room): Map[Option[String], String] =
    room.entities
      .collect { case d: Door if d.link.role == ConnectorRole.Next => d.link }
      .collect { case DoorLink.Resolved(_, branch, target) => branch -> target }
      .toMap

  def forkBuilder(seed: Long = 1L): DungeonBuilder = DungeonBuilder(forkTestPool, Random(seed))

  test("a Fork room is spliced into the dungeon when the pool supports it"):
    val dungeon = forkBuilder().build()(biomeCount = 1).getOrElse(fail("build failed"))
    assert(dungeon.rooms.values.exists(_.roomType == RoomType.Fork), s"expected a Fork room: ${dungeon.rooms.keys}")

  test("a fork cluster adds exactly 5 rooms (fork + 2-room branch A + 2-room branch B)"):
    val dungeon = forkBuilder().build()(biomeCount = 1).getOrElse(fail("build failed"))
    // (2 lead-in + fork cluster(5) + reconverge(2) + boss) + sanctuary
    assertEquals(dungeon.rooms.size, 11)

  test("the fork's two branch-tagged Next doors resolve to each branch's first room"):
    val dungeon = forkBuilder().build()(biomeCount = 1).getOrElse(fail("build failed"))
    val fork    = dungeon.rooms.values.find(_.roomType == RoomType.Fork).getOrElse(fail("expected a Fork room"))
    val targets = resolvedNextTargets(fork)
    assertEquals(targets.keySet, Set(Some("a"), Some("b")))
    assertNotEquals(targets(Some("a")), targets(Some("b")))

  test("both branch heads' Prev doors resolve back to the fork room"):
    val dungeon = forkBuilder().build()(biomeCount = 1).getOrElse(fail("build failed"))
    val fork    = dungeon.rooms.values.find(_.roomType == RoomType.Fork).getOrElse(fail("expected a Fork room"))
    val headIds = resolvedNextTargets(fork).values.toSet
    headIds.foreach: id =>
      val headRoom = dungeon.rooms(id)
      val prevTarget = headRoom.entities
        .collect { case d: Door if d.link.role == ConnectorRole.Prev => d.link }
        .collectFirst { case DoorLink.Resolved(_, _, target) => target }
      assertEquals(prevTarget, Some(fork.id), s"expected branch head $id to point back to the fork")

  test("each branch's first room leads to a distinct second room before reconverging"):
    val dungeon = forkBuilder().build()(biomeCount = 1).getOrElse(fail("build failed"))
    val fork    = dungeon.rooms.values.find(_.roomType == RoomType.Fork).getOrElse(fail("expected a Fork room"))
    val heads   = resolvedNextTargets(fork)
    val headA   = dungeon.rooms(heads(Some("a")))
    val headB   = dungeon.rooms(heads(Some("b")))
    val secondA = resolvedNextTargets(headA).get(None)
    val secondB = resolvedNextTargets(headB).get(None)
    assert(secondA.isDefined, "expected branch A's first room to lead to a second room")
    assert(secondB.isDefined, "expected branch B's first room to lead to a second room")
    assertNotEquals(secondA, secondB)

  test("both branches converge on the same room after their second room"):
    val dungeon = forkBuilder().build()(biomeCount = 1).getOrElse(fail("build failed"))
    val fork    = dungeon.rooms.values.find(_.roomType == RoomType.Fork).getOrElse(fail("expected a Fork room"))
    val heads   = resolvedNextTargets(fork)
    val headA   = dungeon.rooms(heads(Some("a")))
    val headB   = dungeon.rooms(heads(Some("b")))
    val secondA = resolvedNextTargets(headA)(None)
    val secondB = resolvedNextTargets(headB)(None)
    val convergeA = resolvedNextTargets(dungeon.rooms(secondA)).get(None)
    val convergeB = resolvedNextTargets(dungeon.rooms(secondB)).get(None)
    assert(convergeA.isDefined, "expected branch A's second room to lead onward")
    assertEquals(convergeA, convergeB, "expected both branches to converge on the same next room")

  test("no room id is repeated in a dungeon that includes a fork cluster"):
    val dungeon = forkBuilder().build()(biomeCount = 1).getOrElse(fail("build failed"))
    assertEquals(dungeon.rooms.size, dungeon.rooms.keys.toSet.size)

  test("build still succeeds, no branching, when the pool has no Fork room"):
    val poolWithoutFork = forkTestPool.filterNot(_._2.roomType == RoomType.Fork)
    val dungeon = DungeonBuilder(poolWithoutFork, Random(1L)).build()(biomeCount = 1).getOrElse(fail("build failed"))
    assert(!dungeon.rooms.values.exists(_.roomType == RoomType.Fork))

  test("a missing MiniBoss room skips the whole fork cluster, not just branch B (all-or-nothing)"):
    val poolWithoutMiniBoss = forkTestPool.filterNot(_._2.roomType == RoomType.MiniBoss)
    val dungeon = DungeonBuilder(poolWithoutMiniBoss, Random(1L)).build()(biomeCount = 1).getOrElse(fail("build failed"))
    assert(!dungeon.rooms.values.exists(_.roomType == RoomType.Fork),
           "expected the Fork room itself to be skipped too, not a partial fork with no branch B"
    )

  // ---------------------------------------------
  // Door wiring
  // ---------------------------------------------

  test("Next-role doors are no longer Unresolved after wiring (except the sanctuary room)"):
    val dungeon = builder().build()(biomeCount = 2).getOrElse(fail("build failed"))
    // Identify the sanctuary room by roomType, not by position in `dungeon.rooms.values` - Map
    // iteration order isn't the chain order, so a positional dropRight(1) can drop the wrong room.
    // Boss rooms are *not* excluded here anymore - they're no longer a dead end, the sanctuary is,
    // so a Boss room's own Next door must resolve just like any other mid-chain room's.
    val nonSanctuaryRooms = dungeon.rooms.values.filterNot(_.roomType == RoomType.Sanctuary).toList
    val middleDoors = nonSanctuaryRooms.flatMap(_.entities).collect {
      case d: Door if d.link.role == ConnectorRole.Next => d
    }
    val unwired = middleDoors.filter(_.link.isInstanceOf[DoorLink.Unresolved])
    assert(unwired.isEmpty, s"Found unwired Next door: ${unwired.map(_.id).mkString(", ")}")

  test("Prev-role doors are no longer Unresolved after wiring (except the entrance room)"):
    val dungeon = builder().build()(biomeCount = 2).getOrElse(fail("build failed"))
    // Identify the entrance by `currentRoomId`, not by position in `dungeon.rooms.values` - same
    // reasoning as the Next-role test above.
    val nonEntranceRooms = dungeon.rooms.values.filterNot(_.id == dungeon.currentRoomId).toList
    val middleDoors = nonEntranceRooms.flatMap(_.entities).collect {
      case d: Door if d.link.role == ConnectorRole.Prev => d
    }
    val unwired = middleDoors.filter(_.link.isInstanceOf[DoorLink.Unresolved])
    assert(unwired.isEmpty, s"Found unwired Prev door: ${unwired.map(_.id).mkString(", ")}")

  /** A pool with a single Combat room guarantees it's picked as the entrance regardless of seed -
    * c1 is authored with both an entranceDoor() (Prev, never wired to anything) and an exitDoor()
    * (Next, wired below), same shared-pool shape a real mid-chain Combat room has. With only 1
    * Combat room and no Loot/Fork room at all, every optional template slot gracefully skips,
    * leaving the entrance wired directly to the Boss room. */
  def minimalEntranceCleanupPool: Map[String, Room] = Map(
    "c1"        -> makeRoom("c1", RoomType.Combat, List(entranceDoor(), exitDoor())),
    "b1"        -> makeRoom("b1", RoomType.Boss, List(entranceDoor(), exitDoor())),
    "sanctuary" -> makeRoom("sanctuary", RoomType.Sanctuary, List(entranceDoor()))
  )

  test("the entrance's own leftover Prev door is stripped, not left dangling Unresolved"):
    val dungeon  = DungeonBuilder(minimalEntranceCleanupPool).build()(biomeCount = 1).getOrElse(fail("build failed"))
    val entrance = dungeon.currentRoom
    assertEquals(entrance.id, "c1")
    val prevDoors = entrance.entities.collect { case d: Door if d.link.role == ConnectorRole.Prev => d }
    assert(prevDoors.isEmpty, s"expected the entrance's Prev door to be stripped, found: ${prevDoors.map(_.id)}")

  test("the entrance's own wired Next door survives the cleanup pass untouched"):
    val dungeon   = DungeonBuilder(minimalEntranceCleanupPool).build()(biomeCount = 1).getOrElse(fail("build failed"))
    val nextDoors = dungeon.currentRoom.entities.collect { case d: Door if d.link.role == ConnectorRole.Next => d }
    assertEquals(nextDoors.size, 1, "expected the entrance's own exit door to still be present")
    nextDoors.head.link match
      case DoorLink.Resolved(_, _, roomId) => assertEquals(roomId, "b1")
      case DoorLink.Unresolved(_, _)       => fail("expected the entrance's Next door to be resolved")

  test("exit door of first room points to a room that exists in the dungeon"):
    val dungeon   = builder().build()(biomeCount = 2).getOrElse(fail("build failed"))
    val firstRoom = dungeon.currentRoom
    val exitDoors = firstRoom.entities.collect {
      case d: Door if d.link.role == ConnectorRole.Next => d
    }
    assert(exitDoors.nonEmpty, "First room should have an exit door")
    exitDoors.head.link match
      case DoorLink.Resolved(_, _, roomId) =>
        assert(dungeon.rooms.contains(roomId), "Exit door target should exist in dungeon")
      case DoorLink.Unresolved(_, _) =>
        fail("Exit door should be resolved after wiring")

  // ---------------------------------------------
  // Error cases
  // ---------------------------------------------

  test("build still succeeds, degrading every Combat-dependent slot, when the pool has no combat room"):
    // Combat is no longer a hard requirement anywhere - there's no separately-tracked entrance to
    // need one for anymore (see the class doc). Every lead-in/reconverge Combat slot just degrades
    // gracefully like any other optional slot; Loot/Boss/Sanctuary are untouched by its absence.
    val noCombatPool = testPool.filterNot(_._2.roomType == RoomType.Combat)
    val dungeon       = DungeonBuilder(noCombatPool).build()().getOrElse(fail("build failed"))
    assert(!dungeon.rooms.values.exists(_.roomType == RoomType.Combat))
    assert(dungeon.rooms.values.exists(_.roomType == RoomType.Boss))
    assert(dungeon.rooms.values.exists(_.roomType == RoomType.Sanctuary))

  test("build returns Left when pool has no boss room"):
    val noBossPool = testPool.filterNot(_._2.roomType == RoomType.Boss)
    val result     = DungeonBuilder(noBossPool).build()()
    assert(result.isLeft)

  test("build returns Left when pool has no sanctuary room"):
    val noSanctuaryPool = testPool.filterNot(_._2.roomType == RoomType.Sanctuary)
    val result           = DungeonBuilder(noSanctuaryPool).build()()
    assert(result.isLeft)

  test("build returns Left when one of several themes has no boss room of its own"):
    // "dungeon" (testPool) has Boss rooms; "other" doesn't. With exactly 2 themes and 2 sections,
    // pickNextTheme's exhaust-first policy guarantees both themes get used exactly once, so
    // whichever section lands on "other" fails its Boss pick regardless of seed - a theme's own
    // Boss supply matters, not just whether *some* theme in the pool has one. Replaces an older
    // test here that checked a single theme's Boss pool running out across sections - no longer a
    // real failure mode once the same room can safely be reused across sections (see
    // deduplicateRoomIds and the "same room reused" test below).
    val otherThemeNoBoss = Map(
      "other_c1" -> makeRoom("other_c1", RoomType.Combat, theme = "other"),
      "other_l1" -> makeRoom("other_l1", RoomType.Loot, theme = "other")
    )
    val result = DungeonBuilder(testPool ++ otherThemeNoBoss).build()(biomeCount = 2)
    assert(result.isLeft)

  test("the same room can be reused across different sections without corrupting the dungeon"):
    // Only 1 Boss room total, but biomeCount = 2 - both sections independently pick it (exclude
    // resets per section), so deduplicateRoomIds must give the second placement its own runtime id
    // rather than letting the two collide into a single Dungeon.rooms entry.
    val singleBossPool = testPool.filterNot(_._2.roomType == RoomType.Boss) +
      ("b1" -> makeRoom("b1", RoomType.Boss, List(entranceDoor(), exitDoor())))
    val dungeon   = DungeonBuilder(singleBossPool, Random(1L)).build()(biomeCount = 2).getOrElse(fail("build failed"))
    val bossRooms = dungeon.rooms.values.filter(_.roomType == RoomType.Boss).toList
    assertEquals(bossRooms.size, 2, "expected 2 distinct Boss room placements")
    assertEquals(bossRooms.map(_.id).toSet.size, 2, "expected 2 distinct ids, not a collision")
    assert(bossRooms.exists(_.id == "b1"), "expected the first placement to keep its original id")
    assert(bossRooms.exists(_.id == "b1#2"), "expected the second placement to get a synthetic runtime id")

  // ---------------------------------------------
  // Reproducibility
  // ---------------------------------------------

  test("same seed produces the same dungeon structure"):
    val d1 =
      DungeonBuilder(testPool, Random(99L)).build()(biomeCount = 2).getOrElse(fail("build 1 failed"))
    val d2 =
      DungeonBuilder(testPool, Random(99L)).build()(biomeCount = 2).getOrElse(fail("build 2 failed"))
    assertEquals(d1.rooms.keys.toSet, d2.rooms.keys.toSet)
    assertEquals(d1.currentRoomId, d2.currentRoomId)

  // ---------------------------------------------
  // Vault rooms (LockedDoor injection)
  // ---------------------------------------------

  /** A minimal pool with exactly one Combat room (so it's always the deterministic entrance),
    * one Boss room, one Sanctuary room, and one Vault room. The Combat room's LockedDoor
    * references `vaultTarget`. */
  def vaultPool(vaultTarget: String = "v1"): Map[String, Room] = Map(
    "c1" -> makeRoom(
      "c1",
      RoomType.Combat,
      List(exitDoor(), LockedDoor("ld1", x = 2, y = 2, direction = Direction.Right, targetRoomId = vaultTarget))
    ),
    "b1"        -> makeRoom("b1", RoomType.Boss, List(entranceDoor(), exitDoor())),
    "sanctuary" -> makeRoom("sanctuary", RoomType.Sanctuary, List(entranceDoor())),
    "v1"        -> makeRoom("v1", RoomType.Vault, Nil)
  )

  test("build injects a Vault room referenced by a LockedDoor in the wired chain"):
    val dungeon =
      DungeonBuilder(vaultPool(), Random(1L)).build()(biomeCount = 1).getOrElse(fail("build failed"))
    assert(dungeon.rooms.contains("v1"), "expected vault room to be injected")

  test("build fails when a LockedDoor references an unknown room id"):
    val result = DungeonBuilder(vaultPool(vaultTarget = "missing_vault"), Random(1L)).build()(biomeCount = 1)
    result match
      case Left(err) => assert(err.toLowerCase.contains("unknown room"), s"expected clear error: $err")
      case Right(_)  => fail("expected build to fail for an unresolvable LockedDoor target")

  test("a Vault room not referenced by any LockedDoor is absent from the dungeon"):
    val poolNoLockedDoor = Map(
      "c1"        -> makeRoom("c1", RoomType.Combat, List(exitDoor())),
      "b1"        -> makeRoom("b1", RoomType.Boss, List(entranceDoor(), exitDoor())),
      "sanctuary" -> makeRoom("sanctuary", RoomType.Sanctuary, List(entranceDoor())),
      "v1"        -> makeRoom("v1", RoomType.Vault, Nil)
    )
    val dungeon =
      DungeonBuilder(poolNoLockedDoor, Random(1L)).build()(biomeCount = 1).getOrElse(fail("build failed"))
    assert(!dungeon.rooms.contains("v1"), "vault room should not appear without a referencing LockedDoor")

  test("Vault rooms are never auto-selected into the random middle chain"):
    val poolWithVault = testPool + ("v1" -> makeRoom("v1", RoomType.Vault, Nil))
    val dungeon = DungeonBuilder(poolWithVault, Random(7L))
      .build()(biomeCount = 3)
      .getOrElse(fail("build failed"))
    assert(!dungeon.rooms.contains("v1"), "Vault room should not be auto-selected without a LockedDoor reference")

  // ---------------------------------------------
  // Elite enemies
  // ---------------------------------------------

  def enemies(idPrefix: String, count: Int): List[Enemy] =
    (1 to count).map(i => Enemy(id = s"$idPrefix-e$i", x = 1, y = 1, typeId = "goblin", label = "Goblin")).toList

  /** A minimal pool: 1 combat room (always the deterministic entrance) and 1 boss room, each
    * carrying `enemyCount` plain (non-Elite) Enemy entities so the roll has something to work on,
    * plus 1 sanctuary room (required, but never carries enemies). */
  def eliteTestPool(combatEnemyCount: Int = 1, bossEnemyCount: Int = 5): Map[String, Room] = Map(
    "c1"        -> makeRoom("c1", RoomType.Combat, exitDoor() :: enemies("c1", combatEnemyCount)),
    "b1"        -> makeRoom("b1", RoomType.Boss, entranceDoor() :: exitDoor() :: enemies("b1", bossEnemyCount)),
    "sanctuary" -> makeRoom("sanctuary", RoomType.Sanctuary, List(entranceDoor()))
  )

  /** Finds the first seed (starting at 1) whose build against `pool` at `difficulty` satisfies
    * `predicate` - avoids hardcoding a magic seed number that's sensitive to DungeonBuilder's
    * exact internal pick-call order, which shifts whenever its generation logic changes (as it
    * just did for this very rewrite). Still a single deterministic assertion once found, not a
    * statistical trial - `Random(seed)` always produces the same sequence, so the same seed is
    * found every time this runs. */
  def firstSeedWhere(pool: Map[String, Room], difficulty: Difficulty, maxSeed: Int = 200)(
      predicate: Dungeon => Boolean
  ): Long =
    (1L to maxSeed.toLong)
      .find(seed => DungeonBuilder(pool, Random(seed)).build(difficulty = difficulty)(biomeCount = 1).toOption.exists(predicate))
      .getOrElse(fail(s"no seed up to $maxSeed satisfied the condition"))

  def combatRoomHasElite(d: Dungeon): Boolean =
    d.rooms.values.find(_.roomType == RoomType.Combat).exists(_.entities.collect { case e: Enemy => e }.exists(_.isElite))

  test("boss room enemies never roll Elite, even while the combat room's own roll is active"):
    val seed = firstSeedWhere(eliteTestPool(), Difficulty.Hard)(combatRoomHasElite)
    val dungeon = DungeonBuilder(eliteTestPool(), Random(seed))
      .build(difficulty = Difficulty.Hard)(biomeCount = 1)
      .getOrElse(fail("build failed"))
    val bossRoom = dungeon.rooms.values.find(_.roomType == RoomType.Boss).getOrElse(fail("no boss room"))
    assert(combatRoomHasElite(dungeon), "expected this seed to roll an Elite in the combat room")
    assert(!bossRoom.entities.collect { case e: Enemy => e }.exists(_.isElite),
           "no boss-room enemy should ever roll Elite"
    )

  test("at most 1 enemy per room rolls Elite, even with many enemies at Hard difficulty"):
    val pool = eliteTestPool(combatEnemyCount = 20)
    val seed = firstSeedWhere(pool, Difficulty.Hard)(combatRoomHasElite)
    val dungeon = DungeonBuilder(pool, Random(seed)).build(difficulty = Difficulty.Hard)(biomeCount = 1).getOrElse(fail("build failed"))
    val combatRoom = dungeon.rooms.values.find(_.roomType == RoomType.Combat).getOrElse(fail("no combat room"))
    assertEquals(combatRoom.entities.collect { case e: Enemy => e }.count(_.isElite),
                 1,
                 "expected the cap to allow exactly 1 Elite, not 0 or more than 1"
    )

  test("Hard difficulty rolls Elite enemies at least as often as Easy, same seeds"):
    val trials = 2000
    def eliteRate(difficulty: Difficulty): Double =
      val eliteCount = (1 to trials).count { seed =>
        val dungeon = DungeonBuilder(eliteTestPool(combatEnemyCount = 1), Random(seed.toLong))
          .build(difficulty = difficulty)(biomeCount = 1)
          .getOrElse(fail("build failed"))
        dungeon.rooms.values.flatMap(_.entities).collect { case e: Enemy => e }.exists(_.isElite)
      }
      eliteCount.toDouble / trials

    val easyRate = eliteRate(Difficulty.Easy)
    val hardRate = eliteRate(Difficulty.Hard)
    assert(hardRate >= easyRate, s"expected Hard's elite rate ($hardRate) >= Easy's ($easyRate)")
    // Sanity bounds around the configured 8%/12% thresholds (generous tolerance to avoid flakiness).
    assert(easyRate > 0.03 && easyRate < 0.15, s"Easy elite rate out of expected range: $easyRate")
    assert(hardRate > 0.06 && hardRate < 0.20, s"Hard elite rate out of expected range: $hardRate")

  test("with zero enemies in a room, the roll is a no-op (no crash, no elites)"):
    val dungeon = DungeonBuilder(eliteTestPool(combatEnemyCount = 0, bossEnemyCount = 0), Random(3L))
      .build(difficulty = Difficulty.Hard)(biomeCount = 1)
      .getOrElse(fail("build failed"))
    assert(dungeon.rooms.values.flatMap(_.entities).collect { case e: Enemy => e }.isEmpty)

  // ---------------------------------------------
  // Graph topology (fork/merge proof)
  // ---------------------------------------------

  /** A fixed entrance -> fork -> (branch "a" | branch "b") -> merge -> boss shape, proving the
    * role+branch-keyed wiring primitive [[DungeonBuilder.buildFromTopology]] uses generalizes past
    * a linear chain. Test-only dummy rooms - never touches real rooms.json content. A LockedDoor on
    * the entrance references a Vault room to prove injectVaultRooms is unaffected by a graph-shaped
    * chain. Unaffected by this file's broader rewrite - buildFromTopology never calls build/
    * buildSection at all.
    */
  def topologyPool(vaultTarget: String = "vault1"): Map[String, Room] = Map(
    "entrance" -> makeRoom(
      "entrance",
      RoomType.Combat,
      List(
        Door("door_exit", x = 4, y = 5, direction = Direction.Down, link = DoorLink.Unresolved(ConnectorRole.Next)),
        LockedDoor("ld1", x = 2, y = 2, direction = Direction.Right, targetRoomId = vaultTarget)
      )
    ),
    "fork1" -> makeRoom(
      "fork1",
      RoomType.Combat,
      List(
        Door("door_prev", x = 4, y = 0, direction = Direction.Up, link = DoorLink.Unresolved(ConnectorRole.Prev)),
        Door("door_next_a",
             x = 2,
             y = 5,
             direction = Direction.Down,
             link = DoorLink.Unresolved(ConnectorRole.Next, Some("a"))
        ),
        Door("door_next_b",
             x = 6,
             y = 5,
             direction = Direction.Down,
             link = DoorLink.Unresolved(ConnectorRole.Next, Some("b"))
        )
      )
    ),
    "branchA" -> makeRoom(
      "branchA",
      RoomType.Combat,
      List(
        Door("door_prev", x = 4, y = 0, direction = Direction.Up, link = DoorLink.Unresolved(ConnectorRole.Prev)),
        Door("door_next", x = 4, y = 5, direction = Direction.Down, link = DoorLink.Unresolved(ConnectorRole.Next))
      )
    ),
    "branchB" -> makeRoom(
      "branchB",
      RoomType.Combat,
      List(
        Door("door_prev", x = 4, y = 0, direction = Direction.Up, link = DoorLink.Unresolved(ConnectorRole.Prev)),
        Door("door_next", x = 4, y = 5, direction = Direction.Down, link = DoorLink.Unresolved(ConnectorRole.Next))
      )
    ),
    "merge" -> makeRoom(
      "merge",
      RoomType.Combat,
      List(
        Door("door_prev_a",
             x = 2,
             y = 0,
             direction = Direction.Up,
             link = DoorLink.Unresolved(ConnectorRole.Prev, Some("a"))
        ),
        Door("door_prev_b",
             x = 6,
             y = 0,
             direction = Direction.Up,
             link = DoorLink.Unresolved(ConnectorRole.Prev, Some("b"))
        ),
        Door("door_next", x = 4, y = 5, direction = Direction.Down, link = DoorLink.Unresolved(ConnectorRole.Next))
      )
    ),
    "boss" -> makeRoom(
      "boss",
      RoomType.Boss,
      List(Door("door_prev", x = 4, y = 0, direction = Direction.Up, link = DoorLink.Unresolved(ConnectorRole.Prev)))
    ),
    "vault1" -> makeRoom("vault1", RoomType.Vault, Nil)
  )

  /** Every edge wired both ways (A's Next -> B, B's Prev -> A), mirroring `wire`'s own convention
    * for the linear case. */
  def topologyEdges: List[TopologyEdge] = List(
    TopologyEdge("entrance", ConnectorRole.Next, None, "fork1"),
    TopologyEdge("fork1", ConnectorRole.Prev, None, "entrance"),
    TopologyEdge("fork1", ConnectorRole.Next, Some("a"), "branchA"),
    TopologyEdge("branchA", ConnectorRole.Prev, None, "fork1"),
    TopologyEdge("fork1", ConnectorRole.Next, Some("b"), "branchB"),
    TopologyEdge("branchB", ConnectorRole.Prev, None, "fork1"),
    TopologyEdge("branchA", ConnectorRole.Next, None, "merge"),
    TopologyEdge("merge", ConnectorRole.Prev, Some("a"), "branchA"),
    TopologyEdge("branchB", ConnectorRole.Next, None, "merge"),
    TopologyEdge("merge", ConnectorRole.Prev, Some("b"), "branchB"),
    TopologyEdge("merge", ConnectorRole.Next, None, "boss"),
    TopologyEdge("boss", ConnectorRole.Prev, None, "merge")
  )

  def topologyBuilder(pool: Map[String, Room] = topologyPool()): DungeonBuilder = DungeonBuilder(pool)

  test("buildFromTopology succeeds for a fork/merge shape"):
    assert(topologyBuilder().buildFromTopology(topologyEdges, entranceId = "entrance").isRight)

  test("buildFromTopology's two branch rooms are distinct rooms"):
    val dungeon =
      topologyBuilder().buildFromTopology(topologyEdges, entranceId = "entrance").getOrElse(fail("build failed"))
    assert(dungeon.rooms.contains("branchA"))
    assert(dungeon.rooms.contains("branchB"))
    assertNotEquals(dungeon.rooms("branchA").id, dungeon.rooms("branchB").id)

  test("each branch's Next door resolves to the merge room"):
    val dungeon =
      topologyBuilder().buildFromTopology(topologyEdges, entranceId = "entrance").getOrElse(fail("build failed"))
    def nextTarget(roomId: String): Option[String] =
      dungeon
        .rooms(roomId)
        .entities
        .collectFirst { case d: Door if d.link.role == ConnectorRole.Next => d.link }
        .collect { case DoorLink.Resolved(_, _, target) => target }
    assertEquals(nextTarget("branchA"), Some("merge"))
    assertEquals(nextTarget("branchB"), Some("merge"))

  test("the merge room's two Prev doors resolve back to the correct branch by matching branch tag"):
    val dungeon =
      topologyBuilder().buildFromTopology(topologyEdges, entranceId = "entrance").getOrElse(fail("build failed"))
    val prevDoors = dungeon.rooms("merge").entities.collect { case d: Door if d.link.role == ConnectorRole.Prev => d }
    val byBranch = prevDoors.flatMap { d =>
      d.link match
        case DoorLink.Resolved(_, branch, target) => branch.map(_ -> target)
        case _                                     => None
    }.toMap
    assertEquals(byBranch.get("a"), Some("branchA"))
    assertEquals(byBranch.get("b"), Some("branchB"))

  test("buildFromTopology produces no repeated room id"):
    val dungeon =
      topologyBuilder().buildFromTopology(topologyEdges, entranceId = "entrance").getOrElse(fail("build failed"))
    assertEquals(dungeon.rooms.size, dungeon.rooms.keys.toSet.size)

  test("buildFromTopology still injects a Vault room referenced by a LockedDoor in a graph-shaped chain"):
    val dungeon =
      topologyBuilder().buildFromTopology(topologyEdges, entranceId = "entrance").getOrElse(fail("build failed"))
    assert(dungeon.rooms.contains("vault1"), "expected vault room to be injected even in a graph-shaped dungeon")

  test("buildFromTopology fails when an edge references an unknown room id"):
    val badEdges = topologyEdges :+ TopologyEdge("entrance", ConnectorRole.Next, Some("ghost"), "nonexistent_room")
    val result   = topologyBuilder().buildFromTopology(badEdges, entranceId = "entrance")
    result match
      case Left(err) => assert(err.toLowerCase.contains("unknown room"), s"expected clear error: $err")
      case Right(_)  => fail("expected build to fail for an edge referencing an unknown room")
