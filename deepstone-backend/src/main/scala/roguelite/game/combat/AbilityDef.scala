package roguelite.game.combat

import roguelite.engine.{ ClassId, Player }
import roguelite.game.items.{ SetBonusEffect, SetDef }
import roguelite.game.progression.{ PerkDef, PerkEffect }

/** Effect produced when a class ability is activated.
  *
  * `EmpowerNextAttack` is deferred: it arms a [[PendingAbilityEffect]] that
  * [[CombatResolver.handleAttack]] consumes on the player's next Attack, so the turn it is cast
  * deals no damage (the enemy still answers). `AttackScaledDamage` is applied immediately instead.
  *
  * @param multiplierPercent
  *   Damage of the empowered Attack as a percentage of a plain one (300 = triple).
  * @param ignoreDefense
  *   The empowered Attack rolls against 0 enemy defense.
  * @param guaranteedCrit
  *   The empowered Attack always crits (the crit multiplier comes on top of `multiplierPercent`).
  * @param percent
  *   Immediate damage as a percentage of the player's attack stat, no jitter, no defense, no crit.
  */
enum AbilityEffect:
  case EmpowerNextAttack(multiplierPercent: Int, ignoreDefense: Boolean, guaranteedCrit: Boolean)
  case AttackScaledDamage(percent: Int)

/** Static definition of a class's combat ability, loaded from `data/abilities.json`.
  *
  * V1 gives every class exactly one ability, so [[CombatResolver]] resolves "use ability" with a
  * single lookup by [[classId]]. `resourceName` lives here (rather than on [[ClassDef]]) purely
  * because it is only ever needed alongside the ability's cost, in log messages like
  * "Not enough Rage"; revisit if a class ever gets more than one ability sharing the same pool.
  */
case class AbilityDef(
    classId: ClassId,
    id: String,
    name: String,
    cost: Int,
    resourceName: String,
    description: String,
    effect: AbilityEffect
)

object AbilityDef:

  /** `ability.cost` reduced by any active set AbilityCostReductionPercent bonus (Pyromancer 4pc)
    * plus any active AbilityCostReductionPercent perk, summed then applied once, floored at 0.
    *
    * Pure resolution shared by [[CombatResolver]] (the resource check/deduction) and the
    * `toStateUpdate` boundary (so the client sees the real, player-specific cost instead of the
    * static per-class catalog value) - same "resolve once at the view boundary" discipline already
    * used for `PlayerView.maxHp`/`ItemView.stats`.
    */
  def effectiveCost(player: Player,
                    ability: AbilityDef,
                    setDefs: Map[String, SetDef],
                    perkDefs: Map[String, PerkDef]
  ): Int =
    val setReduction = SetDef.activeBonuses(player.equippedSetIds, setDefs, player.classId).collect {
      case SetBonusEffect.AbilityCostReductionPercent(n) => n
    }.sum
    // A negative percent is a surcharge, from a risky perk.
    val perkReduction = PerkDef.activeEffects(player, perkDefs).collect {
      case PerkEffect.AbilityCostReductionPercent(n) => n
    }.sum
    math.round(ability.cost * (100 - setReduction - perkReduction).max(0) / 100.0).toInt
