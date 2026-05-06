package roguelite.engine

import cats.effect.IO
import munit.CatsEffectSuite
import roguelite.game.*

import scala.util.Random

class GameSessionSuite extends CatsEffectSuite:

  def makeTiles(w: Int = 8, h: Int = 6): Vector[Vector[Tile]] =
    Vector.tabulate(h, w): (row, col) =>
      if row == 0 || row == h - 1 || col == 0 || col == w - 1 then Tile.Wall else Tile.Floor

  val goblinStats: EnemyStats = EnemyStats(
    typeId = "goblin", label = "Goblin", maxHp = 20, attack = 5, defense = 0, xpReward = 15,
    actions = List(EnemyActionWeight("ATTACK", 100)),
  )

  def testDungeon: Dungeon =
    val tiles = makeTiles()
    val r1 = Room("r1", RoomType.Combat, 8, 6, tiles, Nil)
    val r2 = Room("r2", RoomType.Loot,   8, 6, tiles, Nil)
    Dungeon(Map("r1" -> r1, "r2" -> r2), "r1")

  def sm: StateMachine =
    StateMachine(testDungeon, Map("goblin" -> goblinStats), CombatResolver(Random(0L)))

  test("new session starts in Hub phase"):
    for
      session <- GameSession.create(sm)
      update  <- session.currentUpdate
    yield assertEquals(update.phase, GamePhase.Hub)

  test("StartRun transitions session to Exploration"):
    for
      session <- GameSession.create(sm)
      update  <- session.handle(HubAction(HubActionType.StartRun, classId = Some(ClassId.Warrior)))
    yield assertEquals(update.phase, GamePhase.Exploration)

  test("session state persists across multiple handle calls"):
    for
      session <- GameSession.create(sm)
      _       <- session.handle(HubAction(HubActionType.StartRun, classId = Some(ClassId.Archer)))
      update  <- session.handle(Move(Direction.Right))
    yield
      assertEquals(update.phase, GamePhase.Exploration)
      assertEquals(update.player.classId, ClassId.Archer)

  test("invalid action in wrong state returns log and does not crash"):
    for
      session <- GameSession.create(sm)
      update  <- session.handle(Move(Direction.Up))
    yield
      assertEquals(update.phase, GamePhase.Hub)
      assert(update.log.nonEmpty)

  test("handle is concurrency-safe"):
    for
      session <- GameSession.create(sm)
      _       <- session.handle(HubAction(HubActionType.StartRun, classId = Some(ClassId.Mage)))
      _       <- IO.both(
        session.handle(Move(Direction.Right)),
        session.handle(Move(Direction.Down)),
      )
      update  <- session.currentUpdate
    yield assertEquals(update.phase, GamePhase.Exploration)