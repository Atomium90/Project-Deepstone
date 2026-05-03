package roguelite.engine

import munit.FunSuite
import roguelite.game.*

class StateMachineSuite extends FunSuite:

  def makeTiles(w: Int, h: Int): Vector[Vector[Tile]] =
    Vector.tabulate(h, w): (row, col) =>
      if row == 0 || row == h - 1 || col == 0 || col == w - 1 then Tile.Wall else Tile.Floor

  def makeRoom(id: String, w: Int = 8, h: Int = 6, roomType: RoomType = RoomType.Combat, entities: List[Entity] = Nil): Room =
    Room(id = id, roomType = roomType, width = w, height = h, tiles = makeTiles(w, h), entities = entities)

  def door(fromRoom: String, toRoom: String): Door =
    Door(id = s"door_${fromRoom}_to_$toRoom", x = 4, y = 5, direction = Direction.Down, targetRoomId = toRoom)

  def simpleDungeon(extraEntities: List[Entity] = Nil): Dungeon =
    val r1 = makeRoom("r1", entities = extraEntities)
    val r2 = makeRoom("r2")
    Dungeon(rooms = Map("r1" -> r1, "r2" -> r2), currentRoomId = "r1")

  def hubPlayer: Player =
    Player(ClassId.Warrior, hp = 100, maxHp = 100, resourceCurrent = 0, resourceMax = 100, level = 1, xp = 0, metaCurrency = 0)

  def sm(dungeon: Dungeon = simpleDungeon()): StateMachine = StateMachine(dungeon)

  def explorationAt(x: Int, y: Int, entities: List[Entity] = Nil): ExplorationState =
    ExplorationState(Player.startingPlayer(ClassId.Warrior), simpleDungeon(entities), playerX = x, playerY = y)

  test("StartRun from Hub transitions to Exploration"):
    val (next, _) = sm().applyActionPure(HubState(hubPlayer), HubAction(HubActionType.StartRun, classId = Some(ClassId.Archer)))
    assert(next.isInstanceOf[ExplorationState])

  test("StartRun creates a player with the chosen class"):
    val (next, _) = sm().applyActionPure(HubState(hubPlayer), HubAction(HubActionType.StartRun, classId = Some(ClassId.Mage)))
    assertEquals(next.player.classId, ClassId.Mage)

  test("StartRun without classId stays in Hub"):
    val (next, _) = sm().applyActionPure(HubState(hubPlayer), HubAction(HubActionType.StartRun, classId = None))
    assert(next.isInstanceOf[HubState])

  test("Move Up decreases playerY by 1"):
    val (next, _) = sm().applyActionPure(explorationAt(3, 3), Move(Direction.Up))
    assertEquals(next.asInstanceOf[ExplorationState].playerY, 2)

  test("Player cannot move into a wall tile"):
    val (next, _) = sm().applyActionPure(explorationAt(1, 1), Move(Direction.Up))
    assertEquals(next.asInstanceOf[ExplorationState].playerY, 1)

  test("Interact with a Door navigates to the target room"):
    val d         = door("r1", "r2")
    val state     = explorationAt(3, 3, entities = List(d))
    val (next, _) = sm(simpleDungeon(List(d))).applyActionPure(state, Interact(d.id))
    assertEquals(next.asInstanceOf[ExplorationState].dungeon.currentRoomId, "r2")

  test("Interact with an Enemy transitions to CombatState"):
    val enemy     = Enemy("e1", x = 3, y = 3, label = "Goblin")
    val state     = explorationAt(3, 3, entities = List(enemy))
    val (next, _) = sm(simpleDungeon(List(enemy))).applyActionPure(state, Interact("e1"))
    assert(next.isInstanceOf[CombatState])

  test("Interact with a Chest removes it from the room"):
    val chest     = Chest("c1", x = 3, y = 3)
    val state     = explorationAt(3, 3, entities = List(chest))
    val (next, _) = sm(simpleDungeon(List(chest))).applyActionPure(state, Interact("c1"))
    assertEquals(next.asInstanceOf[ExplorationState].dungeon.currentRoom.entityById("c1"), None)

  test("Interact with unknown id produces error log"):
    val (next, log) = sm().applyActionPure(explorationAt(3, 3), Interact("ghost"))
    assert(next.isInstanceOf[ExplorationState])
    assert(log.exists(_.contains("No entity found")))

  test("GameOverState.toStateUpdate has GameOver phase"):
    assertEquals(GameOverState(hubPlayer).toStateUpdate().phase, GamePhase.GameOver)