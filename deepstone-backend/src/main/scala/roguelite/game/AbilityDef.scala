package roguelite.game

import roguelite.engine.{ ClassId, Player }

/** Effect produced when a class ability is activated.
  *
  * `DoubleNextAttack` and `IgnoreDefenseNextAttack` are deferred: they arm a
  * [[PendingAbilityEffect]] that [[CombatResolver.handleAttack]] consumes on the player's next
  * Attack. `FlatDamage` is applied immediately instead.
  */
enum AbilityEffect:
  case DoubleNextAttack
  case IgnoreDefenseNextAttack
  case FlatDamage(amount: Int)

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
    * used for `PlayerView.maxHp`/`ItemView.statLine`.
    */
  def effectiveCost(player: Player,
                    ability: AbilityDef,
                    setDefs: Map[String, SetDef],
                    perkDefs: Map[String, PerkDef]
  ): Int =
    val setReduction = SetDef.activeBonuses(player.equippedSetIds, setDefs, player.classId).collect {
      case SetBonusEffect.AbilityCostReductionPercent(n) => n
    }.sum
    val perkReduction = player.activePerkId.flatMap(perkDefs.get).map(_.effect) match {
      case Some(PerkEffect.AbilityCostReductionPercent(n)) => n
      case _                                               => 0
    }
    math.round(ability.cost * (100 - setReduction - perkReduction).max(0) / 100.0).toInt
