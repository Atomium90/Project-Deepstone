package roguelite.game

import roguelite.engine.ClassId

/** Effect produced when a class ability is activated.
  *
  * `DoubleNextAttack` and `IgnoreDefenseNextAttack` are deferred — they arm a
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
  * "Not enough Rage" — revisit if a class ever gets more than one ability sharing the same pool.
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
