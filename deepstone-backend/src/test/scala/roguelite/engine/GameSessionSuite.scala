package roguelite.engine

import cats.effect.IO
import munit.CatsEffectSuite

class GameSessionSuite extends CatsEffectSuite:

  val sm = StateMachine()

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
      update  <- session.handle(Move(Direction.Up))
    yield
      assertEquals(update.phase, GamePhase.Hub)
      assert(update.log.nonEmpty, "Expected a log message for invalid action")

  test("handle is concurrency-safe: two concurrent calls produce consistent state"):
    for
      session <- GameSession.create(sm)
      _       <- session.handle(HubAction(HubActionType.StartRun, classId = Some(ClassId.Mage)))
      // Fire two moves concurrently — state should still be valid after both
      _       <- IO.both(
        session.handle(Move(Direction.Right)),
        session.handle(Move(Direction.Down)),
      )
      update  <- session.currentUpdate
    yield assertEquals(update.phase, GamePhase.Exploration)