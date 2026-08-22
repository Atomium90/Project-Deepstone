package roguelite.game

import roguelite.engine.{ Difficulty, Player }

/** Pure domain facts describing "achievement-worthy" things that happened during a state
  * transition. Emitted by the resolvers that already have the relevant context in scope
  * ([[CombatResolver]], [[InteractionResolver]]), threaded up through
  * [[roguelite.engine.StateMachine.applyActionPure]] via [[roguelite.engine.TransitionResult]], and
  * consumed by AchievementChecker in [[roguelite.engine.GameSession]] (added later).
  *
  * Deliberately achievement-agnostic: this layer only reports facts, it doesn't know which
  * achievement (if any) cares about them. Matches this codebase's preference for typed domain
  * modeling over implicit (prev, next) state diffing or stringly-typed inference.
  */
enum GameEvent:
  /** An enemy was defeated in combat (win). */
  case EnemyDefeated(isBoss: Boolean, tookNoDamage: Boolean, wasElite: Boolean)

  /** The player reached a new level. Emitted once per level gained: a single kill can emit
    * several if it crosses more than one XP threshold at once.
    */
  case LeveledUp(newLevel: Int)

  /** An item was successfully added to the inventory (chest or enemy loot drop). Build with
    * [[GameEvent.itemPickedUp]] rather than the constructor directly - every field here is derived
    * from the resulting player state, not just carried along.
    */
  case ItemPickedUp(inventoryFull: Boolean,
                    rarity: Rarity,
                    hasFourPieceSet: Boolean,
                    potionBeltFull: Boolean,
                    stackAtCapacity: Boolean
  )

  /** A locked door was opened by consuming a matching key. */
  case DoorUnlockedWithKey

  /** The player walked through a normal door, or a locked door that was already unlocked. */
  case DoorOpened

  /** A hidden secret door was revealed by proximity. */
  case SecretDoorRevealed

  /** A run ended, win or lose. */
  case RunEnded(victory: Boolean, difficulty: Difficulty, activePerkId: Option[String])

  /** Damage was dealt to a combatant. `targetIsPlayer` distinguishes the player taking a hit from
    * the player's attack landing on the enemy - `amount` is always positive. `crit` is true only
    * for a player Attack that rolled a critical hit; always false for enemy damage and for other
    * player actions (Ability's FlatDamage is crit-exempt, see CombatResolver.handleAttack).
    */
  case DamageDealt(targetIsPlayer: Boolean, amount: Int, crit: Boolean = false)

  /** The player was healed (HP only - resource restoration isn't reported here). Always the
    * player: no enemy-heal effect exists in this game's combat model.
    */
  case Healed(amount: Int)

  /** A potion was drunk (one charge consumed from a belt stack), regardless of its effect kind. */
  case ConsumableUsed(typeId: String)

object GameEvent:
  /** Builds an [[GameEvent.ItemPickedUp]] from the resulting player state and the item just
    * resolved - shared by every pickup call site ([[EquipmentResolver]], [[CombatResolver]],
    * [[InteractionResolver]]) so the derived fields aren't recomputed slightly differently in each
    * place.
    */
  def itemPickedUp(player: Player, item: Item, setDefs: Map[String, SetDef]): GameEvent =
    GameEvent.ItemPickedUp(
      inventoryFull = player.isFullyEquipped,
      rarity = item.rarity,
      hasFourPieceSet = SetDef.hasFourPieceSetActive(player.equippedSetIds, setDefs, player.classId),
      potionBeltFull = player.potionBelt.forall(_.isDefined),
      stackAtCapacity = player.potionBelt.exists(_.exists(s => s.count >= player.potionCapacity))
    )
