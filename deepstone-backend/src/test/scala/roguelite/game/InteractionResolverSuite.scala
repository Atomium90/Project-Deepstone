package roguelite.game

import munit.FunSuite
import roguelite.engine.*

import scala.util.Random

class InteractionResolverSuite extends FunSuite:

  // --- Fixtures ------------------------------------------------------------

  def makeTiles(w: Int = 8, h: Int = 6): Vector[Vector[Tile]] =
    Vector.tabulate(h, w):
      (row, col) =>
        if row == 0 || row == h - 1 || col == 0 || col == w - 1 then Tile.Wall else Tile.Floor

  def makeRoom(id: String,
               w: Int = 8,
               h: Int = 6,
               roomType: RoomType = RoomType.Combat,
               entities: List[Entity] = Nil
  ): Room =
    Room(id, roomType, w, h, makeTiles(w, h), entities)

  /** Same as makeRoom, but with a single interior tile forced to Wall, used to verify that
    * reveal/unlock actually flips a tile rather than assuming it was already Floor. */
  def roomWithWallAt(id: String, wx: Int, wy: Int, entities: List[Entity]): Room =
    val tiles = makeTiles()
    Room(id, RoomType.Combat, 8, 6, tiles.updated(wy, tiles(wy).updated(wx, Tile.Wall)), entities)

  def door(from: String, to: String): Door =
    Door(s"door_${from}_to_$to", x = 4, y = 5, direction = Direction.Down, targetRoomId = to)

  val goblinStats: EnemyStats = EnemyStats(
    typeId = "goblin",
    label = "Goblin",
    maxHp = 20,
    attack = 5,
    defense = 0,
    xpReward = 15,
    actions = List(EnemyActionWeight("ATTACK", 100))
  )

  val enemyStatsMap: Map[String, EnemyStats] = Map("goblin" -> goblinStats)

  def resolver(itemDefs: Map[String, Item] = Map.empty,
               rng: Random = Random(),
               npcDialogueDefs: Map[String, NpcDialogueDef] = Map.empty,
               clock: () => Long = () => System.currentTimeMillis()
  ): InteractionResolver =
    InteractionResolver(enemyStatsMap, itemDefs, rng, npcDialogueDefs, clock)

  def dungeonWith(entities: List[Entity] = Nil, extraRooms: Map[String, Room] = Map.empty): Dungeon =
    val r1 = makeRoom("r1", entities = entities)
    val r2 = makeRoom("r2")
    Dungeon(Map("r1" -> r1, "r2" -> r2) ++ extraRooms, "r1")

  def explorationAt(x: Int,
                    y: Int,
                    entities: List[Entity] = Nil,
                    extraRooms: Map[String, Room] = Map.empty
  ): ExplorationState =
    ExplorationState(PlayerFixtures.startingPlayer(ClassId.Warrior),
                     dungeonWith(entities, extraRooms),
                     x,
                     y
    )

  def testKey(id: String = "k1", keyKind: KeyKind = KeyKind.Generic): Key =
    Key(id = id, typeId = "rusty_key", name = "Rusty Key", rarity = Rarity.Common, keyKind = keyKind)

  def playerWithItems(items: List[Item]): Player =
    val inv = items.foldLeft(Inventory.empty)((acc, item) => acc.addItem(item).getOrElse(fail("expected Right")))
    PlayerFixtures.startingPlayer(ClassId.Warrior).copy(inventory = inv)

  // --- Door ------------------------------------------------------------------

  test("Interact with Door navigates to target room"):
    val d            = door("r1", "r2")
    val state        = explorationAt(3, 3, entities = List(d))
    val TransitionResult(next, _, _) = resolver().interact(state, d.id)
    assertEquals(next.asInstanceOf[ExplorationState].dungeon.currentRoomId, "r2")

  test("Interact with unknown entity id returns error log"):
    val TransitionResult(next, log, _) = resolver().interact(explorationAt(3, 3), "ghost")
    assert(next.isInstanceOf[ExplorationState])
    assert(log.exists(_.contains("No entity found")))

  // --- Enemy -------------------------------------------------------------------

  test("Interact with Enemy transitions to CombatState"):
    val enemy        = Enemy("e1", x = 3, y = 3, typeId = "goblin", label = "Goblin")
    val state        = explorationAt(3, 3, entities = List(enemy))
    val TransitionResult(next, _, _) = resolver().interact(state, "e1")
    assert(next.isInstanceOf[CombatState])

  test("CombatState has correct enemy stats after engaging"):
    val enemy        = Enemy("e1", x = 3, y = 3, typeId = "goblin", label = "Goblin")
    val state        = explorationAt(3, 3, entities = List(enemy))
    val TransitionResult(next, _, _) = resolver().interact(state, "e1")
    val combat       = next.asInstanceOf[CombatState].combat
    assertEquals(combat.enemy.maxHp, goblinStats.maxHp)
    assertEquals(combat.enemy.label, "Goblin")

  test("CombatState enemy stats scale with difficulty"):
    val enemy        = Enemy("e1", x = 3, y = 3, typeId = "goblin", label = "Goblin")
    val state        = explorationAt(3, 3, entities = List(enemy)).copy(difficulty = Difficulty.Hard)
    val TransitionResult(next, _, _) = resolver().interact(state, "e1")
    val combat       = next.asInstanceOf[CombatState].combat
    assertEquals(combat.enemy.maxHp, math.round(goblinStats.maxHp * 1.25).toInt)

  test("Interact with unknown enemy typeId stays in Exploration with error"):
    val badEnemy        = Enemy("e2", x = 3, y = 3, typeId = "dragon", label = "Dragon")
    val state           = explorationAt(3, 3, entities = List(badEnemy))
    val TransitionResult(next, log, _)  = resolver().interact(state, "e2")
    assert(next.isInstanceOf[ExplorationState])
    assert(log.exists(_.contains("Unknown enemy type")))

  // --- Chest -------------------------------------------------------------------

  test("Interact with Chest removes it from room"):
    val chest        = Chest("c1", x = 3, y = 3)
    val state        = explorationAt(3, 3, entities = List(chest))
    val TransitionResult(next, _, _) = resolver().interact(state, "c1")
    assertEquals(next.asInstanceOf[ExplorationState].dungeon.currentRoom.entityById("c1"), None)

  test("Interact with Chest with empty itemDefs gives 'empty' log"):
    val chest       = Chest("c1", x = 3, y = 3)
    val state       = explorationAt(3, 3, entities = List(chest))
    val TransitionResult(_, log, _) = resolver().interact(state, "c1")
    assert(log.exists(_.toLowerCase.contains("empty")), s"Expected 'empty' in log: $log")

  test("Trapped chest spawns enemies instead of giving loot"):
    val chest           = Chest("c1", x = 3, y = 3, trapped = true)
    val state           = explorationAt(3, 3, entities = List(chest))
    val TransitionResult(next, log, _)  = resolver().interact(state, "c1")
    val nextExp         = next.asInstanceOf[ExplorationState]
    assertEquals(nextExp.dungeon.currentRoom.entityById("c1"), None)
    val spawned = nextExp.dungeon.currentRoom.entities.collect { case e: Enemy => e }
    assert(spawned.nonEmpty, "expected at least one enemy to spawn from the trap")
    assert(spawned.forall(_.typeId == "goblin"), s"expected only goblin typeIds: $spawned")
    assertEquals(nextExp.player.inventory.items, Nil)
    assert(log.exists(_.toLowerCase.contains("trap")), s"expected trap message in log: $log")

  test("Trapped chest enemies spawn on free tiles, not on the player"):
    val chest        = Chest("c1", x = 3, y = 3, trapped = true)
    val state        = explorationAt(3, 3, entities = List(chest))
    val TransitionResult(next, _, _) = resolver().interact(state, "c1")
    val nextExp      = next.asInstanceOf[ExplorationState]
    val spawned      = nextExp.dungeon.currentRoom.entities.collect { case e: Enemy => e }
    val room         = nextExp.dungeon.currentRoom
    assert(spawned.forall(e => room.isWalkable(e.x, e.y)), s"expected walkable spawn tiles: $spawned")
    assert(spawned.forall(e => (e.x, e.y) != (nextExp.playerX, nextExp.playerY)),
           s"expected no enemy on the player's tile: $spawned"
    )

  test("Non-trapped chest is unaffected by the trapped-chest path"):
    val chest        = Chest("c1", x = 3, y = 3, trapped = false)
    val state        = explorationAt(3, 3, entities = List(chest))
    val TransitionResult(next, _, _) = resolver().interact(state, "c1")
    val nextExp      = next.asInstanceOf[ExplorationState]
    assertEquals(nextExp.dungeon.currentRoom.entities.collect { case e: Enemy => e }, Nil)

  // --- LockedDoor --------------------------------------------------------------

  test("Interact with LockedDoor without a matching key is rejected and inventory is untouched"):
    val lockedDoor      = LockedDoor("ld1", x = 3, y = 3, direction = Direction.Down, targetRoomId = "r2")
    val state           = explorationAt(3, 3, entities = List(lockedDoor))
    val TransitionResult(next, log, _)  = resolver().interact(state, "ld1")
    val nextExp         = next.asInstanceOf[ExplorationState]
    assertEquals(nextExp.dungeon.currentRoomId, "r1")
    assertEquals(nextExp.player.inventory.items, Nil)
    assert(log.exists(_.toLowerCase.contains("locked")), s"expected locked message: $log")

  test("Interact with LockedDoor with a matching key unlocks it, consumes the key, and navigates"):
    val lockedDoor      = LockedDoor("ld1", x = 3, y = 3, direction = Direction.Down, targetRoomId = "r2")
    val key             = testKey()
    val player          = playerWithItems(List(key))
    val state           = ExplorationState(player, dungeonWith(entities = List(lockedDoor)), 3, 3)
    val TransitionResult(next, log, _)  = resolver().interact(state, "ld1")
    val nextExp         = next.asInstanceOf[ExplorationState]
    assertEquals(nextExp.dungeon.currentRoomId, "r2")
    assertEquals(nextExp.player.inventory.items, Nil)
    assert(log.exists(_.toLowerCase.contains("unlock")), s"expected unlock message: $log")
    val persistedDoor =
      nextExp.dungeon.rooms("r1").entityById("ld1").collect { case d: LockedDoor => d }
    assertEquals(persistedDoor.map(_.unlocked), Some(true))

  test("A key that can't unlock a Specific-tagged door is not consumed"):
    val lockedDoor      = LockedDoor("ld1", x = 3, y = 3, direction = Direction.Down, targetRoomId = "r2")
    val key             = testKey(keyKind = KeyKind.Specific("other_door"))
    val player          = playerWithItems(List(key))
    val state           = ExplorationState(player, dungeonWith(entities = List(lockedDoor)), 3, 3)
    val TransitionResult(next, log, _)  = resolver().interact(state, "ld1")
    val nextExp         = next.asInstanceOf[ExplorationState]
    assertEquals(nextExp.dungeon.currentRoomId, "r1")
    assertEquals(nextExp.player.inventory.items, List(key))
    assert(log.exists(_.toLowerCase.contains("locked")), s"expected locked message: $log")

  test("Interact with an already-unlocked LockedDoor navigates without touching the inventory"):
    val lockedDoor =
      LockedDoor("ld1", x = 3, y = 3, direction = Direction.Down, targetRoomId = "r2", unlocked = true)
    val key           = testKey()
    val player        = playerWithItems(List(key))
    val state         = ExplorationState(player, dungeonWith(entities = List(lockedDoor)), 3, 3)
    val TransitionResult(next, _, _)  = resolver().interact(state, "ld1")
    val nextExp       = next.asInstanceOf[ExplorationState]
    assertEquals(nextExp.dungeon.currentRoomId, "r2")
    assertEquals(nextExp.player.inventory.items, List(key))

  // --- Trapped door --------------------------------------------------------------

  test("Interact with a trapped door redirects to the entrance's resolved target"):
    val entranceDoor = Door("door_entrance", x = 4, y = 0, direction = Direction.Up, targetRoomId = "r2")
    val trapDoor = Door("door_trap",
                        x = 2,
                        y = 3,
                        direction = Direction.Down,
                        targetRoomId = "unused",
                        doorKind = DoorKind.Trapped
    )
    val state           = explorationAt(3, 3, entities = List(entranceDoor, trapDoor))
    val TransitionResult(next, log, _)  = resolver().interact(state, "door_trap")
    val nextExp         = next.asInstanceOf[ExplorationState]
    assertEquals(nextExp.dungeon.currentRoomId, "r2")
    assertEquals((nextExp.playerX, nextExp.playerY), (4, 4)) // Up-facing spawn point in an 8x6 room
    assert(log.exists(_.toLowerCase.contains("trap")), s"expected trap message: $log")

  test("Interact with a trapped door in a room with no entrance logs a fallback message"):
    val trapDoor = Door("door_trap",
                        x = 2,
                        y = 3,
                        direction = Direction.Down,
                        targetRoomId = "unused",
                        doorKind = DoorKind.Trapped
    )
    val state           = explorationAt(3, 3, entities = List(trapDoor))
    val TransitionResult(next, log, _)  = resolver().interact(state, "door_trap")
    assertEquals(next.asInstanceOf[ExplorationState].dungeon.currentRoomId, "r1")
    assert(log.exists(_.toLowerCase.contains("nowhere")), s"expected fallback message: $log")

  // --- Secret door --------------------------------------------------------------

  test("Interact with a still-hidden secret door returns 'not found'"):
    val secretDoor = Door("door_secret",
                          x = 5,
                          y = 3,
                          direction = Direction.Down,
                          targetRoomId = "r2",
                          doorKind = DoorKind.Secret,
                          revealed = false
    )
    val state           = explorationAt(3, 3, entities = List(secretDoor))
    val TransitionResult(next, log, _)  = resolver().interact(state, "door_secret")
    assertEquals(next.asInstanceOf[ExplorationState].dungeon.currentRoomId, "r1")
    assert(log.exists(_.contains("No entity found")), s"expected not-found message: $log")

  test("A revealed secret door behaves like a normal door"):
    val secretDoor = Door("door_secret",
                          x = 5,
                          y = 3,
                          direction = Direction.Down,
                          targetRoomId = "r2",
                          doorKind = DoorKind.Secret,
                          revealed = true
    )
    val state        = explorationAt(3, 3, entities = List(secretDoor))
    val TransitionResult(next, _, _) = resolver().interact(state, "door_secret")
    assertEquals(next.asInstanceOf[ExplorationState].dungeon.currentRoomId, "r2")

  test("revealSecretDoors reveals a secret door within Chebyshev distance 1"):
    val secretDoor = Door("door_secret",
                          x = 5,
                          y = 3,
                          direction = Direction.Down,
                          targetRoomId = "r2",
                          doorKind = DoorKind.Secret,
                          revealed = false
    )
    val room            = roomWithWallAt("r1", 5, 3, List(secretDoor))
    val (updated, log)  = resolver().revealSecretDoors(room, 4, 3)
    val revealedDoor    = updated.entityById("door_secret").collect { case d: Door => d }
    assertEquals(revealedDoor.map(_.revealed), Some(true))
    assertEquals(updated.tileAt(5, 3), Tile.Floor)
    assert(log.nonEmpty)

  test("revealSecretDoors leaves a door outside Chebyshev distance 1 untouched"):
    val secretDoor = Door("door_secret",
                          x = 5,
                          y = 3,
                          direction = Direction.Down,
                          targetRoomId = "r2",
                          doorKind = DoorKind.Secret,
                          revealed = false
    )
    val room            = roomWithWallAt("r1", 5, 3, List(secretDoor))
    val (updated, log)  = resolver().revealSecretDoors(room, 2, 3)
    val stillHidden     = updated.entityById("door_secret").collect { case d: Door => d }
    assertEquals(stillHidden.map(_.revealed), Some(false))
    assertEquals(updated.tileAt(5, 3), Tile.Wall)
    assertEquals(log, Nil)

  // --- Npc -----------------------------------------------------------------------

  /** Deterministic time source for the cooldown tests: starts at an arbitrary non-zero instant
    * (so "now - lastShownAt" arithmetic can't accidentally look like the very first interaction)
    * and only moves forward when a test explicitly calls `advance`. */
  class FakeClock(startAt: Long = 1_000_000L):
    private var current: Long = startAt
    def now(): Long             = current
    def advance(ms: Long): Unit = current += ms

  def npc(id: String = "sage"): Npc = Npc(id = id, x = 3, y = 3, name = "Sage")

  def dialogueDef(id: String = "sage",
                  dialogue: List[String] = List("Line one.", "Line two."),
                  fallbackDialogue: List[String] = List("Fallback one.", "Fallback two.")
  ): NpcDialogueDef =
    NpcDialogueDef(id = id, name = "Sage", dialogue = dialogue, fallbackDialogue = fallbackDialogue)

  val cooldown: Long = InteractionResolver.NpcInteractCooldownMillis + 100L

  test("First interact with an Npc shows the first line, advances the index, and logs nothing"):
    val clock                 = FakeClock()
    val state                 = explorationAt(3, 3, entities = List(npc()))
    val TransitionResult(next, log, dialogue) =
      resolver(npcDialogueDefs = Map("sage" -> dialogueDef()), clock = clock.now)
        .interact(state, "sage")
    assertEquals(dialogue.map(_.npcName), Some("Sage"))
    assertEquals(dialogue.map(_.line), Some("Line one."))
    assertEquals(log, Nil)
    val persisted =
      next.asInstanceOf[ExplorationState].dungeon.currentRoom.entityById("sage").collect { case n: Npc => n }
    assertEquals(persisted.map(_.dialogueIndex), Some(1))

  test("Re-interacting within the cooldown redisplays the same line without advancing"):
    val clock    = FakeClock()
    val resolve  = resolver(npcDialogueDefs = Map("sage" -> dialogueDef()), clock = clock.now)
    val state    = explorationAt(3, 3, entities = List(npc()))
    val TransitionResult(next1, _, d1) = resolve.interact(state, "sage")
    clock.advance(500L)
    val TransitionResult(next2, _, d2) = resolve.interact(next1.asInstanceOf[ExplorationState], "sage")
    assertEquals(d1.map(_.line), Some("Line one."))
    assertEquals(d2.map(_.line), Some("Line one."))
    val persisted =
      next2.asInstanceOf[ExplorationState].dungeon.currentRoom.entityById("sage").collect { case n: Npc => n }
    assertEquals(persisted.map(_.dialogueIndex), Some(1))

  test("Re-interacting after the cooldown elapses advances to the next line"):
    val clock   = FakeClock()
    val resolve = resolver(npcDialogueDefs = Map("sage" -> dialogueDef()), clock = clock.now)
    val state   = explorationAt(3, 3, entities = List(npc()))
    val TransitionResult(next1, _, _) = resolve.interact(state, "sage")
    clock.advance(cooldown)
    val TransitionResult(_, _, d2) = resolve.interact(next1.asInstanceOf[ExplorationState], "sage")
    assertEquals(d2.map(_.line), Some("Line two."))

  test("Exhausting the main dialogue falls back and rotates without repeating the same line twice in a row"):
    val clock   = FakeClock()
    val defs    = Map("sage" -> dialogueDef(dialogue = List("Only line.")))
    val resolve = resolver(npcDialogueDefs = defs, clock = clock.now)
    val initial = explorationAt(3, 3, entities = List(npc()))

    val TransitionResult(afterFirst, _, firstLine) = resolve.interact(initial, "sage")
    assertEquals(firstLine.map(_.line), Some("Only line."))

    val (_, shownFallbacks) = (1 to 4).foldLeft((afterFirst.asInstanceOf[ExplorationState], List.empty[String])):
      case ((state, acc), _) =>
        clock.advance(cooldown)
        val TransitionResult(next, _, d) = resolve.interact(state, "sage")
        (next.asInstanceOf[ExplorationState], acc :+ d.map(_.line).getOrElse(fail("expected a dialogue line")))

    assert(shownFallbacks.forall(l => List("Fallback one.", "Fallback two.").contains(l)),
           s"expected only fallback lines: $shownFallbacks"
    )
    shownFallbacks.sliding(2).foreach:
      case Seq(a, b) => assertNotEquals(a, b)
      case _         => ()

  test("Single-entry fallback pool always shows that one line"):
    val clock   = FakeClock()
    val defs    = Map(
      "sage" -> dialogueDef(dialogue = List("Only line."), fallbackDialogue = List("The one fallback."))
    )
    val resolve = resolver(npcDialogueDefs = defs, clock = clock.now)
    val initial = explorationAt(3, 3, entities = List(npc()))

    val TransitionResult(afterFirst, _, _) = resolve.interact(initial, "sage")

    val (_, shown) = (1 to 3).foldLeft((afterFirst.asInstanceOf[ExplorationState], List.empty[String])):
      case ((state, acc), _) =>
        clock.advance(cooldown)
        val TransitionResult(next, _, d) = resolve.interact(state, "sage")
        (next.asInstanceOf[ExplorationState], acc :+ d.map(_.line).getOrElse(fail("expected a dialogue line")))

    assertEquals(shown, List("The one fallback.", "The one fallback.", "The one fallback."))

  test("Empty fallback pool keeps re-showing the last main line"):
    val clock   = FakeClock()
    val defs    = Map("sage" -> dialogueDef(dialogue = List("First.", "Last."), fallbackDialogue = Nil))
    val resolve = resolver(npcDialogueDefs = defs, clock = clock.now)
    val initial = explorationAt(3, 3, entities = List(npc()))

    val TransitionResult(afterFirst, _, d1) = resolve.interact(initial, "sage")
    assertEquals(d1.map(_.line), Some("First."))

    clock.advance(cooldown)
    val TransitionResult(afterSecond, _, d2) = resolve.interact(afterFirst.asInstanceOf[ExplorationState], "sage")
    assertEquals(d2.map(_.line), Some("Last."))

    clock.advance(cooldown)
    val TransitionResult(afterThird, _, d3) = resolve.interact(afterSecond.asInstanceOf[ExplorationState], "sage")
    assertEquals(d3.map(_.line), Some("Last."))

    clock.advance(cooldown)
    val TransitionResult(_, _, d4) = resolve.interact(afterThird.asInstanceOf[ExplorationState], "sage")
    assertEquals(d4.map(_.line), Some("Last."))

  test("Interact with an Npc missing from the dialogue catalog degrades gracefully"):
    val state                 = explorationAt(3, 3, entities = List(npc()))
    val TransitionResult(next, log, dialogue) = resolver().interact(state, "sage")
    assert(next.isInstanceOf[ExplorationState])
    assertEquals(dialogue, None)
    assert(log.exists(_.contains("nothing to say")), s"expected fallback message: $log")
