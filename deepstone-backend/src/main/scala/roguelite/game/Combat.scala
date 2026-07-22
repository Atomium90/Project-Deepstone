package roguelite.game

/** Effect that remains pending until the player's next Attack action.
  *
  * Set when the player uses a "charge" ability (Berserker Slash, Precise Shot). Consumed and
  * cleared by [[CombatResolver.handleAttack]] when the player next attacks. Mage's Arcane Blast is
  * immediate and therefore produces no pending effect.
  *
  * Kept in [[Combat]] (not [[roguelite.engine.Player]]) because it is strictly transient combat
  * state that evaporates at the end of a fight. It is not exposed in the client protocol for V1;
  * the combat log messages are sufficient feedback.
  */
enum PendingAbilityEffect:
  /** Warrior — Berserker Slash: raw damage (post-jitter) is doubled. */
  case DoubleNextAttack

  /** Archer — Precise Shot: attack rolls against 0 enemy defense instead of the actual value. */
  case IgnoreDefenseNextAttack

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
  * @param pendingAbility
  *   A "next attack" modifier set by an ability action. Consumed on the player's next Attack and
  *   then cleared. [[scala.None]] if no ability is pending.
  * @param tookDamage
  *   True once the player has taken any damage during this fight. Never reset mid-fight - only a
  *   fresh Combat (new encounter) starts it back at false. Backs the "untouchable" achievement,
  *   read from CombatResolver.victory at the moment the fight ends.
  */
case class Combat(
    enemy: EnemyInstance,
    isPlayerTurn: Boolean = true,
    round: Int = 1,
    playerIsDefending: Boolean = false,
    pendingAbility: Option[PendingAbilityEffect] = None,
    tookDamage: Boolean = false
)
