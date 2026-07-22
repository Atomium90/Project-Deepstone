package roguelite.game

/** Pure domain facts describing "achievement-worthy" things that happened during a state
  * transition. Emitted by the resolvers that already have the relevant context in scope
  * ([[CombatResolver]], [[InteractionResolver]]), threaded up through
  * [[roguelite.engine.StateMachine.applyActionPure]] via [[roguelite.engine.TransitionResult]], and
  * consumed by AchievementChecker in [[roguelite.engine.GameSession]] (added later).
  *
  * Deliberately achievement-agnostic — this layer only reports facts, it doesn't know which
  * achievement (if any) cares about them. Matches this codebase's preference for typed domain
  * modeling over implicit (prev, next) state diffing or stringly-typed inference.
  */
enum GameEvent:
  /** An enemy was defeated in combat (win). */
  case EnemyDefeated(isBoss: Boolean, tookNoDamage: Boolean)

  /** The player reached a new level. Emitted once per level gained — a single kill can emit
    * several if it crosses more than one XP threshold at once.
    */
  case LeveledUp(newLevel: Int)

  /** An item was successfully added to the inventory (chest or enemy loot drop). */
  case ItemPickedUp(inventoryFull: Boolean)

  /** A locked door was opened by consuming a matching key. */
  case DoorUnlockedWithKey

  /** A hidden secret door was revealed by proximity. */
  case SecretDoorRevealed

  /** A run ended, win or lose. */
  case RunEnded(victory: Boolean)
