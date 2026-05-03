package roguelite.engine

import cats.effect.IO
import munit.CatsEffectSuite
import roguelite.game.*

class GameSessionSuite extends CatsEffectSuite:

  // Minimal two-room dungeon — enough for all session tests
  def testDungeon: Dungeon =
    val tiles = Vector.tabulate(6, 8):
      (row, col) => if row == 0 || row == 5 || col == 0 || col == 7 then Tile.Wall else Tile.Floor
    val r1 = Room("r1", RoomType.Combat, width = 8, height = 6, tiles = tiles, entities = Nil)
    val r2 = Room("r2", RoomType.Loot, width = 8, height = 6, tiles = tiles, entities = Nil)
    Dungeon(rooms = Map("r1" -> r1, "r2" -> r2), currentRoomId = "r1")

  val sm = StateMachine(testDungeon)

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

  test("invalid action in wrong state returns log message and does not crash"):
    for
      session <- GameSession.create(sm)
      // Move while still in Hub → should be handled gracefully
      update <- session.handle(Move(Direction.Up))
    yield
      assertEquals(update.phase, GamePhase.Hub)
      assert(update.log.nonEmpty, "Expected a log message for invalid action")

  test("handle is concurrency-safe: two concurrent calls produce consistent state"):
    for
      session <- GameSession.create(sm)
      _       <- session.handle(HubAction(HubActionType.StartRun, classId = Some(ClassId.Mage)))
      // Fire two moves concurrently — state should still be valid after both
      _ <- IO.both(
        session.handle(Move(Direction.Right)),
        session.handle(Move(Direction.Down))
      )
      update <- session.currentUpdate
    yield assertEquals(update.phase, GamePhase.Exploration)
