package roguelite.engine

import munit.FunSuite

class StateMachineSuite extends FunSuite:

  val sm = StateMachine()

  def hubPlayer: Player =
    Player(ClassId.Warrior, hp = 100, maxHp = 100, resourceCurrent = 0, resourceMax = 100, level = 1, xp = 0, metaCurrency = 0)

  def explorationState(classId: ClassId = ClassId.Warrior): ExplorationState =
    ExplorationState(Player.startingPlayer(classId))

  // -- Hub transitions -------------------------------------------------------

  test("StartRun from Hub transitions to Exploration"):
    val action         = HubAction(HubActionType.StartRun, classId = Some(ClassId.Archer))
    val (next, log)    = sm.applyActionPure(HubState(hubPlayer), action)
    assert(next.isInstanceOf[ExplorationState], s"Expected ExplorationState, got ${next.getClass.getSimpleName}")

  test("StartRun creates a player with the chosen class"):
    val action      = HubAction(HubActionType.StartRun, classId = Some(ClassId.Mage))
    val (next, _)   = sm.applyActionPure(HubState(hubPlayer), action)
    assertEquals(next.player.classId, ClassId.Mage)

  test("StartRun without classId stays in Hub (invalid action)"):
    val action    = HubAction(HubActionType.StartRun, classId = None)
    val (next, _) = sm.applyActionPure(HubState(hubPlayer), action)
    // The Hub → StartRun path requires a classId, so the state machine should not transition
    assert(next.isInstanceOf[HubState], s"Expected HubState, got ${next.getClass.getSimpleName}")

  test("Move action in Hub is ignored and stays in Hub"):
    val (next, log) = sm.applyActionPure(HubState(hubPlayer), Move(Direction.Up))
    assert(next.isInstanceOf[HubState])
    assert(log.nonEmpty, "Expected a log message for invalid action")

  // -- Exploration transitions -----------------------------------------------

  test("Move Up decreases playerY by 1"):
    val initial       = ExplorationState(Player.startingPlayer(ClassId.Warrior), playerX = 3, playerY = 3)
    val (next, _)     = sm.applyActionPure(initial, Move(Direction.Up))
    val exp           = next.asInstanceOf[ExplorationState]
    assertEquals(exp.playerY, 2)
    assertEquals(exp.playerX, 3)

  test("Move Down increases playerY by 1"):
    val initial   = ExplorationState(Player.startingPlayer(ClassId.Warrior), playerX = 3, playerY = 3)
    val (next, _) = sm.applyActionPure(initial, Move(Direction.Down))
    assertEquals(next.asInstanceOf[ExplorationState].playerY, 4)

  test("Move Left decreases playerX by 1"):
    val initial   = ExplorationState(Player.startingPlayer(ClassId.Warrior), playerX = 3, playerY = 3)
    val (next, _) = sm.applyActionPure(initial, Move(Direction.Left))
    assertEquals(next.asInstanceOf[ExplorationState].playerX, 2)

  test("Move Right increases playerX by 1"):
    val initial   = ExplorationState(Player.startingPlayer(ClassId.Warrior), playerX = 3, playerY = 3)
    val (next, _) = sm.applyActionPure(initial, Move(Direction.Right))
    assertEquals(next.asInstanceOf[ExplorationState].playerX, 4)

  test("Player cannot move into walls (clamped to grid bounds)"):
    val atTopEdge   = ExplorationState(Player.startingPlayer(ClassId.Warrior), playerX = 1, playerY = 1)
    val (next, _)   = sm.applyActionPure(atTopEdge, Move(Direction.Up))
    val exp         = next.asInstanceOf[ExplorationState]
    assertEquals(exp.playerY, 1, "Y should be clamped at 1 (inner tile boundary)")

  test("HubAction in Exploration is ignored"):
    val state     = explorationState()
    val (next, log) = sm.applyActionPure(state, HubAction(HubActionType.StartRun, classId = Some(ClassId.Warrior)))
    assert(next.isInstanceOf[ExplorationState])
    assert(log.nonEmpty)

  // -- Player starting stats -------------------------------------------------

  test("Warrior starts with more HP than Mage"):
    val warrior = Player.startingPlayer(ClassId.Warrior)
    val mage    = Player.startingPlayer(ClassId.Mage)
    assert(warrior.maxHp > mage.maxHp, s"Warrior HP ${warrior.maxHp} should be > Mage HP ${mage.maxHp}")

  test("All classes start at level 1 with 0 XP"):
    ClassId.values.foreach: classId =>
      val p = Player.startingPlayer(classId)
      assertEquals(p.level, 1)
      assertEquals(p.xp, 0)

  // -- StateUpdate projection ------------------------------------------------

  test("HubState.toStateUpdate has Hub phase"):
    val update = HubState(hubPlayer).toStateUpdate()
    assertEquals(update.phase, GamePhase.Hub)

  test("ExplorationState.toStateUpdate has Exploration phase and a room"):
    val update = explorationState().toStateUpdate()
    assertEquals(update.phase, GamePhase.Exploration)
    assert(update.room.isDefined, "Room should be defined during exploration")

  test("GameOverState.toStateUpdate has GameOver phase"):
    val update = GameOverState(hubPlayer).toStateUpdate()
    assertEquals(update.phase, GamePhase.GameOver)