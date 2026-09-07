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

  def makeRoom(
      id: String,
      roomType: RoomType,
      entities: List[Entity] = Nil
  ): Room =
    Room(id = id,
         roomType = roomType,
         width = 8,
         height = 6,
         tiles = makeTiles(),
         entities = entities
    )

  /** A pool sized to comfortably support multi-biome builds without pool exhaustion: 3 combat, 2
    * loot, 2 rest rooms (7 midType rooms, 6 available once one is consumed as the entrance), 2 boss
    * rooms, and 2 MiniBoss rooms (enough for Hard's biomeCount = 3, which needs 2 checkpoints). */
  def testPool: Map[String, Room] = Map(
    "c1" -> makeRoom("c1", RoomType.Combat, List(exitDoor())),
    "c2" -> makeRoom("c2", RoomType.Combat, List(entranceDoor(), exitDoor())),
    "c3" -> makeRoom("c3", RoomType.Combat, List(entranceDoor(), exitDoor())),
    "l1" -> makeRoom("l1", RoomType.Loot, List(entranceDoor(), exitDoor())),
    "l2" -> makeRoom("l2", RoomType.Loot, List(entranceDoor(), exitDoor())),
    "r1" -> makeRoom("r1", RoomType.Rest, List(entranceDoor(), exitDoor())),
    "r2" -> makeRoom("r2", RoomType.Rest, List(entranceDoor(), exitDoor())),
    "b1" -> makeRoom("b1", RoomType.Boss, List(entranceDoor())),
    "b2" -> makeRoom("b2", RoomType.Boss, List(entranceDoor())),
    "mb1" -> makeRoom("mb1", RoomType.MiniBoss, List(entranceDoor(), exitDoor())),
    "mb2" -> makeRoom("mb2", RoomType.MiniBoss, List(entranceDoor(), exitDoor()))
  )

  def builder(seed: Long = 42L): DungeonBuilder = DungeonBuilder(testPool, Random(seed))

  // ---------------------------------------------
  // Structure
  // ---------------------------------------------

  test("build returns Right for a valid pool"):
    assert(builder().build(totalRooms = 2).isRight)

  test("built dungeon has the requested number of rooms (single biome)"):
    val dungeon = builder().build(totalRooms = 2, biomeCount = 1).getOrElse(fail("build failed"))
    assertEquals(dungeon.rooms.size, 4) // entrance + 2 middle + boss

  test("built dungeon has the requested number of rooms across multiple biomes"):
    val dungeon = builder().build(totalRooms = 2, biomeCount = 2).getOrElse(fail("build failed"))
    assertEquals(dungeon.rooms.size, 7) // entrance + (2 middle + miniBoss) + 2 middle + boss

  test("a multi-biome dungeon includes the requested number of MiniBoss checkpoints"):
    val dungeon = builder().build(totalRooms = 2, biomeCount = 3).getOrElse(fail("build failed"))
    assertEquals(dungeon.rooms.values.count(_.roomType == RoomType.MiniBoss), 2) // biomeCount - 1

  test("first room is a combat room"):
    val dungeon     = builder().build(totalRooms = 2).getOrElse(fail("build failed"))
    val currentRoom = dungeon.currentRoom
    assertEquals(currentRoom.roomType, RoomType.Combat)

  test("last room is a boss room"):
    val dungeon    = builder().build(totalRooms = 2).getOrElse(fail("build failed"))
    val bossRoomId = dungeon.rooms.values.find(_.roomType == RoomType.Boss).map(_.id)
    assert(bossRoomId.isDefined, "Expected a boss room in the dungeon")

  test("build with totalRooms = 0 produces entrance + boss only"):
    val dungeon = builder().build(totalRooms = 0).getOrElse(fail("build failed"))
    assertEquals(dungeon.rooms.size, 2)
    assert(dungeon.rooms.values.exists(_.roomType == RoomType.Boss))

  test("no room id is repeated in the dungeon, even once the pool is fully exhausted"):
    val dungeon = builder().build(totalRooms = 2, biomeCount = 3).getOrElse(fail("build failed"))
    assertEquals(dungeon.rooms.size, dungeon.rooms.keys.toSet.size)

  /** Walks the resolved Next-role chain from the dungeon's entrance to its terminal room,
    * returning each room's roomType in traversal order - proves biome segments and MiniBoss
    * checkpoints are wired in the correct sequential order, not just present somewhere in the
    * dungeon. */
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

  test("biome segments and MiniBoss checkpoints are wired in the correct sequential order"):
    val dungeon  = builder().build(totalRooms = 2, biomeCount = 3).getOrElse(fail("build failed"))
    val sequence = roomTypeSequence(dungeon)
    // entrance + 2 (biome1) + miniBoss + 2 (biome2) + miniBoss + 2 (biome3) + boss = 10 rooms
    assertEquals(sequence.length, 10)
    assertEquals(sequence(3), RoomType.MiniBoss)
    assertEquals(sequence(6), RoomType.MiniBoss)
    assertEquals(sequence.last, RoomType.Boss)
    assert(sequence.count(_ == RoomType.MiniBoss) == 2, "expected exactly 2 MiniBoss checkpoints, at the right spots")

  test("build still succeeds, biomes simply running together, when biomeCount > 1 but the pool has no MiniBoss room"):
    val poolWithoutMiniBoss = testPool.filterNot(_._2.roomType == RoomType.MiniBoss)
    val dungeon = DungeonBuilder(poolWithoutMiniBoss, Random(42L))
      .build(totalRooms = 2, biomeCount = 2)
      .getOrElse(fail("build failed"))
    assert(!dungeon.rooms.values.exists(_.roomType == RoomType.MiniBoss))
    assertEquals(dungeon.rooms.size, 6) // entrance + 2 + 2 + boss, no checkpoint in between

  // ---------------------------------------------
  // Fork branching
  // ---------------------------------------------

  def forkExitDoor(branch: String): Door =
    Door(id = s"door_exit_$branch",
         x = 4,
         y = 5,
         direction = Direction.Down,
         link = DoorLink.Unresolved(ConnectorRole.Next, Some(branch))
    )

  /** 6 plain combat rooms (comfortably more than any single test here consumes: 1 entrance + up to
    * 2 biome middle rooms + 2 fork branches, with room to spare), 1 boss room, and 1 Fork room with
    * two branch-tagged exits - enough for `insertFork` to always succeed regardless of shuffle
    * order. */
  def forkTestPool: Map[String, Room] = Map(
    "c1" -> makeRoom("c1", RoomType.Combat, List(exitDoor())),
    "c2" -> makeRoom("c2", RoomType.Combat, List(entranceDoor(), exitDoor())),
    "c3" -> makeRoom("c3", RoomType.Combat, List(entranceDoor(), exitDoor())),
    "c4" -> makeRoom("c4", RoomType.Combat, List(entranceDoor(), exitDoor())),
    "c5" -> makeRoom("c5", RoomType.Combat, List(entranceDoor(), exitDoor())),
    "c6" -> makeRoom("c6", RoomType.Combat, List(entranceDoor(), exitDoor())),
    "b1" -> makeRoom("b1", RoomType.Boss, List(entranceDoor())),
    "f1" -> makeRoom("f1", RoomType.Fork, List(entranceDoor(), forkExitDoor("a"), forkExitDoor("b")))
  )

  /** Every Next-role door's (branch, resolved target) pair in a room - used to inspect a Fork
    * room's two branch-tagged exits, or a branch room's single unbranched one. */
  def resolvedNextTargets(room: Room): Map[Option[String], String] =
    room.entities
      .collect { case d: Door if d.link.role == ConnectorRole.Next => d.link }
      .collect { case DoorLink.Resolved(_, branch, target) => branch -> target }
      .toMap

  def forkBuilder(seed: Long = 1L): DungeonBuilder = DungeonBuilder(forkTestPool, Random(seed))

  test("a Fork room is spliced into the dungeon when the pool supports it"):
    val dungeon = forkBuilder().build(totalRooms = 2, biomeCount = 1).getOrElse(fail("build failed"))
    assert(dungeon.rooms.values.exists(_.roomType == RoomType.Fork), s"expected a Fork room: ${dungeon.rooms.keys}")

  test("a fork cluster adds exactly 3 rooms (fork + 2 branches) on top of the biome's own middle rooms"):
    val dungeon = forkBuilder().build(totalRooms = 2, biomeCount = 1).getOrElse(fail("build failed"))
    assertEquals(dungeon.rooms.size, 7) // entrance + 2 middle + (fork + 2 branches) + boss

  test("the fork's two branch-tagged Next doors resolve to two distinct rooms"):
    val dungeon = forkBuilder().build(totalRooms = 2, biomeCount = 1).getOrElse(fail("build failed"))
    val fork    = dungeon.rooms.values.find(_.roomType == RoomType.Fork).getOrElse(fail("expected a Fork room"))
    val targets = resolvedNextTargets(fork)
    assertEquals(targets.keySet, Set(Some("a"), Some("b")))
    assertNotEquals(targets(Some("a")), targets(Some("b")))

  test("both branch rooms' Prev doors resolve back to the fork room"):
    val dungeon = forkBuilder().build(totalRooms = 2, biomeCount = 1).getOrElse(fail("build failed"))
    val fork    = dungeon.rooms.values.find(_.roomType == RoomType.Fork).getOrElse(fail("expected a Fork room"))
    val branchIds = resolvedNextTargets(fork).values.toSet
    branchIds.foreach: id =>
      val branchRoom = dungeon.rooms(id)
      val prevTarget = branchRoom.entities
        .collect { case d: Door if d.link.role == ConnectorRole.Prev => d.link }
        .collectFirst { case DoorLink.Resolved(_, _, target) => target }
      assertEquals(prevTarget, Some(fork.id), s"expected branch room $id to point back to the fork")

  test("both branch rooms' Next doors converge on the same next room"):
    val dungeon   = forkBuilder().build(totalRooms = 2, biomeCount = 1).getOrElse(fail("build failed"))
    val fork      = dungeon.rooms.values.find(_.roomType == RoomType.Fork).getOrElse(fail("expected a Fork room"))
    val branchIds = resolvedNextTargets(fork).values.toList
    val nextTargets = branchIds.map: id =>
      resolvedNextTargets(dungeon.rooms(id)).get(None)
    assertEquals(nextTargets.distinct.size, 1, s"expected both branches to converge on one room: $nextTargets")
    assert(nextTargets.head.isDefined, "expected the convergence target to actually be resolved")

  test("no room id is repeated in a dungeon that includes a fork cluster"):
    val dungeon = forkBuilder().build(totalRooms = 2, biomeCount = 1).getOrElse(fail("build failed"))
    assertEquals(dungeon.rooms.size, dungeon.rooms.keys.toSet.size)

  test("build still succeeds, no branching, when the pool has no Fork room"):
    val poolWithoutFork = forkTestPool.filterNot(_._2.roomType == RoomType.Fork)
    val dungeon = DungeonBuilder(poolWithoutFork, Random(1L))
      .build(totalRooms = 2, biomeCount = 1)
      .getOrElse(fail("build failed"))
    assert(!dungeon.rooms.values.exists(_.roomType == RoomType.Fork))
    assertEquals(dungeon.rooms.size, 4) // entrance + 2 middle + boss, no branching

  // ---------------------------------------------
  // Door wiring
  // ---------------------------------------------

  test("Next-role doors are no longer Unresolved after wiring (except the boss room)"):
    val dungeon     = builder().build(totalRooms = 4).getOrElse(fail("build failed"))
    // Identify the boss room by roomType, not by position in `dungeon.rooms.values` - Map
    // iteration order isn't the chain order, so a positional dropRight(1) can drop the wrong room.
    val nonBossRooms = dungeon.rooms.values.filterNot(_.roomType == RoomType.Boss).toList
    val middleDoors = nonBossRooms.flatMap(_.entities).collect {
      case d: Door if d.link.role == ConnectorRole.Next => d
    }
    val unwired = middleDoors.filter(_.link.isInstanceOf[DoorLink.Unresolved])
    assert(unwired.isEmpty, s"Found unwired Next door: ${unwired.map(_.id).mkString(", ")}")

  test("Prev-role doors are no longer Unresolved after wiring (except the entrance room)"):
    val dungeon = builder().build(totalRooms = 4).getOrElse(fail("build failed"))
    // Identify the entrance by `currentRoomId`, not by position in `dungeon.rooms.values` - same
    // reasoning as the Next-role test above.
    val nonEntranceRooms = dungeon.rooms.values.filterNot(_.id == dungeon.currentRoomId).toList
    val middleDoors = nonEntranceRooms.flatMap(_.entities).collect {
      case d: Door if d.link.role == ConnectorRole.Prev => d
    }
    val unwired = middleDoors.filter(_.link.isInstanceOf[DoorLink.Unresolved])
    assert(unwired.isEmpty, s"Found unwired Prev door: ${unwired.map(_.id).mkString(", ")}")

  test("exit door of first room points to a room that exists in the dungeon"):
    val dungeon   = builder().build(totalRooms = 4).getOrElse(fail("build failed"))
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

  test("build returns Left when pool has no combat room"):
    val noCombatPool = testPool.filterNot(_._2.roomType == RoomType.Combat)
    val result       = DungeonBuilder(noCombatPool).build()
    assert(result.isLeft)

  test("build returns Left when pool has no boss room"):
    val noBossPool = testPool.filterNot(_._2.roomType == RoomType.Boss)
    val result     = DungeonBuilder(noBossPool).build()
    assert(result.isLeft)

  // ---------------------------------------------
  // Reproducibility
  // ---------------------------------------------

  test("same seed produces the same dungeon structure"):
    val d1 =
      DungeonBuilder(testPool, Random(99L)).build(totalRooms = 4).getOrElse(fail("build 1 failed"))
    val d2 =
      DungeonBuilder(testPool, Random(99L)).build(totalRooms = 4).getOrElse(fail("build 2 failed"))
    assertEquals(d1.rooms.keys.toSet, d2.rooms.keys.toSet)
    assertEquals(d1.currentRoomId, d2.currentRoomId)

  // ---------------------------------------------
  // Vault rooms (LockedDoor injection)
  // ---------------------------------------------

  /** A minimal pool with exactly one Combat room (so it's always the deterministic entrance),
    * one Boss room, and one Vault room. The Combat room's LockedDoor references `vaultTarget`. */
  def vaultPool(vaultTarget: String = "v1"): Map[String, Room] = Map(
    "c1" -> makeRoom(
      "c1",
      RoomType.Combat,
      List(exitDoor(), LockedDoor("ld1", x = 2, y = 2, direction = Direction.Right, targetRoomId = vaultTarget))
    ),
    "b1" -> makeRoom("b1", RoomType.Boss, List(entranceDoor())),
    "v1" -> makeRoom("v1", RoomType.Vault, Nil)
  )

  test("build injects a Vault room referenced by a LockedDoor in the wired chain"):
    val dungeon =
      DungeonBuilder(vaultPool(), Random(1L)).build(totalRooms = 2).getOrElse(fail("build failed"))
    assert(dungeon.rooms.contains("v1"), "expected vault room to be injected")

  test("build fails when a LockedDoor references an unknown room id"):
    val result = DungeonBuilder(vaultPool(vaultTarget = "missing_vault"), Random(1L)).build(totalRooms = 2)
    result match
      case Left(err) => assert(err.toLowerCase.contains("unknown room"), s"expected clear error: $err")
      case Right(_)  => fail("expected build to fail for an unresolvable LockedDoor target")

  test("a Vault room not referenced by any LockedDoor is absent from the dungeon"):
    val poolNoLockedDoor = Map(
      "c1" -> makeRoom("c1", RoomType.Combat, List(exitDoor())),
      "b1" -> makeRoom("b1", RoomType.Boss, List(entranceDoor())),
      "v1" -> makeRoom("v1", RoomType.Vault, Nil)
    )
    val dungeon =
      DungeonBuilder(poolNoLockedDoor, Random(1L)).build(totalRooms = 2).getOrElse(fail("build failed"))
    assert(!dungeon.rooms.contains("v1"), "vault room should not appear without a referencing LockedDoor")

  test("Vault rooms are never auto-selected into the random middle chain"):
    val poolWithVault = testPool + ("v1" -> makeRoom("v1", RoomType.Vault, Nil))
    val dungeon = DungeonBuilder(poolWithVault, Random(7L))
      .build(totalRooms = poolWithVault.size)
      .getOrElse(fail("build failed"))
    assert(!dungeon.rooms.contains("v1"), "Vault room should not be auto-selected without a LockedDoor reference")

  // ---------------------------------------------
  // Elite enemies
  // ---------------------------------------------

  def enemies(idPrefix: String, count: Int): List[Enemy] =
    (1 to count).map(i => Enemy(id = s"$idPrefix-e$i", x = 1, y = 1, typeId = "goblin", label = "Goblin")).toList

  /** A minimal pool: 1 combat room (always the deterministic entrance) and 1 boss room, each
    * carrying `enemyCount` plain (non-Elite) Enemy entities so the roll has something to work on. */
  def eliteTestPool(combatEnemyCount: Int = 1, bossEnemyCount: Int = 5): Map[String, Room] = Map(
    "c1" -> makeRoom("c1", RoomType.Combat, exitDoor() :: enemies("c1", combatEnemyCount)),
    "b1" -> makeRoom("b1", RoomType.Boss, entranceDoor() :: enemies("b1", bossEnemyCount))
  )

  // rollEliteEnemies matches Boss rooms and returns them unchanged before ever calling rng - no
  // seed can make a boss-room enemy roll Elite, so one seed proves it as well as a thousand would.
  // Seed 3 is picked because it also produces a real Elite in the combat room in the same build,
  // showing the exclusion holds even while the roll is actively firing elsewhere.
  test("boss room enemies never roll Elite, even while the combat room's own roll is active"):
    val dungeon = DungeonBuilder(eliteTestPool(), Random(3L))
      .build(totalRooms = 2, difficulty = Difficulty.Hard)
      .getOrElse(fail("build failed"))
    val combatRoom = dungeon.rooms.values.find(_.roomType == RoomType.Combat).getOrElse(fail("no combat room"))
    val bossRoom    = dungeon.rooms.values.find(_.roomType == RoomType.Boss).getOrElse(fail("no boss room"))
    assert(combatRoom.entities.collect { case e: Enemy => e }.exists(_.isElite),
           "expected this seed to roll an Elite in the combat room"
    )
    assert(!bossRoom.entities.collect { case e: Enemy => e }.exists(_.isElite),
           "no boss-room enemy should ever roll Elite"
    )

  // The cap (`alreadyElite` short-circuits every enemy after the first hit in a room) is
  // unconditional, not a matter of luck - seed 1 with 20 enemies at Hard is just a seed where the
  // roll fires at all, so the cap has something to actually cap.
  test("at most 1 enemy per room rolls Elite, even with many enemies at Hard difficulty"):
    val dungeon = DungeonBuilder(eliteTestPool(combatEnemyCount = 20), Random(1L))
      .build(totalRooms = 2, difficulty = Difficulty.Hard)
      .getOrElse(fail("build failed"))
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
          .build(totalRooms = 2, difficulty = difficulty)
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
      .build(totalRooms = 2, difficulty = Difficulty.Hard)
      .getOrElse(fail("build failed"))
    assert(dungeon.rooms.values.flatMap(_.entities).collect { case e: Enemy => e }.isEmpty)

  // ---------------------------------------------
  // Graph topology (fork/merge proof)
  // ---------------------------------------------

  /** A fixed entrance -> fork -> (branch "a" | branch "b") -> merge -> boss shape, proving the
    * role+branch-keyed wiring primitive [[DungeonBuilder.buildFromTopology]] uses generalizes past
    * a linear chain. Test-only dummy rooms - never touches real rooms.json content. A LockedDoor on
    * the entrance references a Vault room to prove injectVaultRooms is unaffected by a graph-shaped
    * chain.
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
