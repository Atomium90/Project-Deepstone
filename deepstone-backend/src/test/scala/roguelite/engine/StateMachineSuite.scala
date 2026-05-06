package roguelite.engine

import munit.FunSuite
import roguelite.game.*

import scala.util.Random

class StateMachineSuite extends FunSuite:

  // ─────────────────────────────────────────────
  // Fixtures
  // ─────────────────────────────────────────────

  def makeTiles(w: Int = 8, h: Int = 6): Vector[Vector[Tile]] =
    Vector.tabulate(h, w): (row, col) =>
      if row == 0 || row == h - 1 || col == 0 || col == w - 1 then Tile.Wall else Tile.Floor

  def makeRoom(id: String, w: Int = 8, h: Int = 6, roomType: RoomType = RoomType.Combat, entities: List[Entity] = Nil): Room =
    Room(id, roomType, w, h, makeTiles(w, h), entities)

  def door(from: String, to: String): Door =
    Door(s"door_${from}_to_$to", x = 4, y = 5, direction = Direction.Down, targetRoomId = to)

  val goblinStats: EnemyStats = EnemyStats(
    typeId   = "goblin",
    label    = "Goblin",
    maxHp    = 20,
    attack   = 5,
    defense  = 0,
    xpReward = 15,
    actions  = List(EnemyActionWeight("ATTACK", 100)),
  )

  val enemyStatsMap: Map[String, EnemyStats] = Map("goblin" -> goblinStats)

  def simpleDungeon(entities: List[Entity] = Nil): Dungeon =
    val r1 = makeRoom("r1", entities = entities)
    val r2 = makeRoom("r2")
    Dungeon(Map("r1" -> r1, "r2" -> r2), "r1")

  def sm(dungeon: Dungeon = simpleDungeon()): StateMachine =
    StateMachine(dungeon, enemyStatsMap, CombatResolver(Random(0L)))

  def hubPlayer: Player =
    Player(ClassId.Warrior, hp = 100, maxHp = 100, resourceCurrent = 0, resourceMax = 100, level = 1, xp = 0, metaCurrency = 0)

  def explorationAt(x: Int, y: Int, entities: List[Entity] = Nil): ExplorationState =
    ExplorationState(Player.startingPlayer(ClassId.Warrior), simpleDungeon(entities), x, y)

  // ─────────────────────────────────────────────
  // Hub
  // ─────────────────────────────────────────────

  test("StartRun transitions to Exploration"):
    val (next, _) = sm().applyActionPure(HubState(hubPlayer), HubAction(HubActionType.StartRun, classId = Some(ClassId.Archer)))
    assert(next.isInstanceOf[ExplorationState])

  test("StartRun creates player with chosen class"):
    val (next, _) = sm().applyActionPure(HubState(hubPlayer), HubAction(HubActionType.StartRun, classId = Some(ClassId.Mage)))
    assertEquals(next.player.classId, ClassId.Mage)

  test("StartRun without classId stays in Hub"):
    val (next, _) = sm().applyActionPure(HubState(hubPlayer), HubAction(HubActionType.StartRun, classId = None))
    assert(next.isInstanceOf[HubState])

  test("Invalid action in Hub produces log"):
    val (next, log) = sm().applyActionPure(HubState(hubPlayer), Move(Direction.Up))
    assert(next.isInstanceOf[HubState])
    assert(log.nonEmpty)

  // ─────────────────────────────────────────────
  // Exploration — movement
  // ─────────────────────────────────────────────

  test("Move Up decreases playerY"):
    val (next, _) = sm().applyActionPure(explorationAt(3, 3), Move(Direction.Up))
    assertEquals(next.asInstanceOf[ExplorationState].playerY, 2)

  test("Move into wall is blocked"):
    val (next, _) = sm().applyActionPure(explorationAt(1, 1), Move(Direction.Up))
    assertEquals(next.asInstanceOf[ExplorationState].playerY, 1)

  // ─────────────────────────────────────────────
  // Exploration — interaction
  // ─────────────────────────────────────────────

  test("Interact with Door navigates to target room"):
    val d     = door("r1", "r2")
    val state = explorationAt(3, 3, entities = List(d))
    val (next, _) = sm(simpleDungeon(List(d))).applyActionPure(state, Interact(d.id))
    assertEquals(next.asInstanceOf[ExplorationState].dungeon.currentRoomId, "r2")

  test("Interact with Enemy transitions to CombatState"):
    val enemy = Enemy("e1", x = 3, y = 3, typeId = "goblin", label = "Goblin")
    val state = explorationAt(3, 3, entities = List(enemy))
    val (next, _) = sm(simpleDungeon(List(enemy))).applyActionPure(state, Interact("e1"))
    assert(next.isInstanceOf[CombatState], s"Expected CombatState, got ${next.getClass.getSimpleName}")

  test("CombatState has correct enemy stats after engaging"):
    val enemy = Enemy("e1", x = 3, y = 3, typeId = "goblin", label = "Goblin")
    val state = explorationAt(3, 3, entities = List(enemy))
    val (next, _) = sm(simpleDungeon(List(enemy))).applyActionPure(state, Interact("e1"))
    val combat = next.asInstanceOf[CombatState].combat
    assertEquals(combat.enemy.maxHp, goblinStats.maxHp)
    assertEquals(combat.enemy.label, "Goblin")

  test("Interact with unknown enemy typeId stays in Exploration with error"):
    val badEnemy = Enemy("e2", x = 3, y = 3, typeId = "dragon", label = "Dragon")
    val state    = explorationAt(3, 3, entities = List(badEnemy))
    val (next, log) = sm(simpleDungeon(List(badEnemy))).applyActionPure(state, Interact("e2"))
    assert(next.isInstanceOf[ExplorationState])
    assert(log.exists(_.contains("Unknown enemy type")))

  test("Interact with Chest removes it from room"):
    val chest = Chest("c1", x = 3, y = 3)
    val state = explorationAt(3, 3, entities = List(chest))
    val (next, _) = sm(simpleDungeon(List(chest))).applyActionPure(state, Interact("c1"))
    assertEquals(next.asInstanceOf[ExplorationState].dungeon.currentRoom.entityById("c1"), None)

  test("Interact with unknown entity id returns error log"):
    val (next, log) = sm().applyActionPure(explorationAt(3, 3), Interact("ghost"))
    assert(next.isInstanceOf[ExplorationState])
    assert(log.exists(_.contains("No entity found")))

  // ─────────────────────────────────────────────
  // Combat routing
  // ─────────────────────────────────────────────

  test("CombatAction in CombatState is routed to resolver"):
    val enemy    = Enemy("e1", 3, 3, typeId = "goblin", label = "Goblin")
    val instance = EnemyInstance.fromStats("e1", goblinStats)
    val state    = CombatState(hubPlayer, simpleDungeon(List(enemy)), 1, 1, Combat(instance), "e1")
    val (next, _) = sm().applyActionPure(state, CombatAction(CombatActionType.Attack))
    // Should either still be in combat, exploration (victory), or game over
    assert(
      next.isInstanceOf[CombatState] || next.isInstanceOf[ExplorationState] || next.isInstanceOf[GameOverState]
    )

  // ─────────────────────────────────────────────
  // StateUpdate projection
  // ─────────────────────────────────────────────

  test("CombatState.toStateUpdate includes CombatView"):
    val instance  = EnemyInstance.fromStats("e1", goblinStats)
    val state     = CombatState(hubPlayer, simpleDungeon(), 1, 1, Combat(instance), "e1")
    val update    = state.toStateUpdate()
    assert(update.combat.isDefined)
    assertEquals(update.combat.get.enemyLabel, "Goblin")

  test("GameOverState.toStateUpdate has GameOver phase"):
    assertEquals(GameOverState(hubPlayer).toStateUpdate().phase, GamePhase.GameOver)