package roguelite.game

/** The runtime state of an active combat encounter.
  *
  * Combat is turn-based and server-authoritative. The server resolves both the player's action and
  * the enemy's counter-action within a single transition, then sends one
  * [[roguelite.engine.StateUpdate]] back to the client.
  *
  * @param enemy
  *   The enemy currently being fought.
  * @param isPlayerTurn
  *   True when it is the player's turn to act. Set to false transiently during enemy resolution and
  *   back to true after. Always true in the StateUpdate sent to the client.
  * @param round
  *   Current round number, incremented after each full player + enemy cycle. Useful for logging and
  *   future time-based abilities.
  * @param playerIsDefending
  *   True if the player chose Defend this turn. Halves incoming enemy damage for one round, then
  *   resets to false.
  */
case class Combat(
    enemy: EnemyInstance,
    isPlayerTurn: Boolean = true,
    round: Int = 1,
    playerIsDefending: Boolean = false
)
