package roguelite.engine

import roguelite.game.GameEvent

/** Result of one pure state transition ([[StateMachine.applyActionPure]] or
  * [[roguelite.game.InteractionResolver.interact]]).
  *
  * Introduced instead of growing the previous `(GameState, List[String], Option[DialogueView])`
  * tuple further — a positional element would make every call site's access unreadable, and every
  * match arm producing a result already needs updating regardless.
  *
  * @param state    The new game state after the action.
  * @param log      Narrative log lines shown in the client's log panel.
  * @param dialogue NPC dialogue line produced by an Interact on an [[roguelite.game.Npc]]. Transient
  *                 — only present on the single result the interaction produced.
  * @param events   Pure domain facts ([[GameEvent]]) describing achievement-worthy things that
  *                 happened, consumed by AchievementChecker in [[GameSession]].
  */
case class TransitionResult(
    state: GameState,
    log: List[String],
    dialogue: Option[DialogueView] = None,
    events: List[GameEvent] = Nil
)
