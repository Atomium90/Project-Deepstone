package roguelite.engine

import cats.effect.IO
import cats.effect.kernel.Ref

/** Represents one active player connection.
  *
  * Each WebSocket connection gets its own GameSession. The session owns a `Ref[IO, GameState]` — an
  * atomically-updatable reference that keeps the authoritative game state for that player.
  *
  * Usage: call `handle` for every incoming player action; it transitions the state and returns the
  * `StateUpdate` to send back to the client.
  */
class GameSession private (stateRef: Ref[IO, GameState], stateMachine: StateMachine):
  /** Process a player action, update the internal state, and return the new state snapshot to be
    * serialized and sent to the client.
    */
  def handle(action: PlayerAction): IO[StateUpdate] =
    stateRef.modify:
      currentState =>
        val (nextState, log) = stateMachine.applyActionPure(currentState, action)
        (nextState, nextState.toStateUpdate(log))

  /** Return the current state snapshot without changing anything. Useful for sending the initial
    * state right after connection.
    */
  def currentUpdate: IO[StateUpdate] =
    stateRef.get.map(_.toStateUpdate())

object GameSession:
  def create(stateMachine: StateMachine): IO[GameSession] =
    val initialPlayer = Player(classId = ClassId.Warrior, // placeholder until class selection is wired
                               hp = 100,
                               maxHp = 100,
                               resourceCurrent = 0,
                               resourceMax = 100,
                               level = 1,
                               xp = 0,
                               metaCurrency = 0
    )
    for ref <- Ref.of[IO, GameState](HubState(initialPlayer))
    yield GameSession(ref, stateMachine)
