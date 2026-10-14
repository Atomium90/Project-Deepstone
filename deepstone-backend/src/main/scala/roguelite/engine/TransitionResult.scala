package roguelite.engine

import roguelite.game.GameEvent

/** Result of one pure state transition ([[StateMachine.applyActionPure]] or
  * [[roguelite.game.InteractionResolver.interact]]).
  *
  * Introduced instead of growing a positional tuple further: a positional element would make every
  * call site's access unreadable, and every match arm producing a result already needs updating
  * regardless.
  *
  * @param state    The new game state after the action.
  * @param log      Narrative log lines shown in the client's log panel.
  * @param events   Pure domain facts ([[GameEvent]]) describing achievement-worthy things that
  *                 happened, consumed by AchievementChecker in [[GameSession]].
  */
case class TransitionResult(
    state: GameState,
    log: List[String],
    events: List[GameEvent] = Nil
)
