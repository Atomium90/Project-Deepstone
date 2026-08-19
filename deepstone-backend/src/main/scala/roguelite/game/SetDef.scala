package roguelite.game

import roguelite.engine.ClassId

/** A single tier's bonus within an equipment set (see [[SetDef]]). */
enum SetBonusEffect:
  /** Increase maxHp by a percentage of its current value. */
  case MaxHpPercent(percent: Int)

  /** Flat defense added on top of weapon/armor/accessory bonuses. */
  case FlatDefense(amount: Int)

  /** Flat attack added on top of weapon/armor/accessory bonuses. */
  case FlatAttack(amount: Int)

  /** Flat crit chance percentage points added on top of accessory bonuses. */
  case CritChancePercent(percent: Int)

  /** Final damage multiplier applied after defense mitigation, alongside the crit multiplier. */
  case AttackDamagePercent(percent: Int)

  /** The next Attack action taken in a fresh combat always crits, regardless of critChance. */
  case FirstAttackAlwaysCrit

  /** Ability resource cost reduced by a percentage. */
  case AbilityCostReductionPercent(percent: Int)

  /** Heal a percentage of the killing blow's damage as HP when an enemy is defeated. */
  case HealOnKillPercent(percent: Int)

/** One tier's payload within a set: the mechanical effect plus the exact flavor text from
  * sets.csv, shown as-is in tooltips/badges rather than derived from the effect at render time.
  */
case class SetBonus(effect: SetBonusEffect, label: String)

/** Static definition of an equipment set's 2-piece and 4-piece bonuses, loaded from
  * `data/sets.json`.
  *
  * A set is 4 pieces (1 weapon + 1 armor + 2 accessories, matching the fixed equipment slot
  * model) sharing the same `setId` on their [[Item]] entries in items.json. The 2-piece and
  * 4-piece bonuses stack: a fully-equipped set grants both, not just the 4-piece one.
  */
case class SetDef(
    id: String,
    name: String,
    classId: ClassId,
    twoPiece: SetBonus,
    fourPiece: SetBonus
)
