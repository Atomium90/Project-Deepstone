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

object SetDef:
  /** Active 2pc/4pc bonus effects across every set represented in `equippedSetIds` (one entry per
    * equipped weapon/armor/accessory that carries a setId - duplicates are expected and are how
    * piece counts are derived), restricted to sets whose `classId` matches `playerClassId`. 2pc
    * and 4pc bonuses stack: a 4-piece set contributes both, not just the 4pc one.
    *
    * Sets are class-specific by design (`sets.csv`'s classId column, "un set par classe"):
    * nothing in [[roguelite.game.EquipmentResolver]] stops a player from equipping another
    * class's gear, but doing so should never grant that set's bonus - otherwise a class could
    * stack a rival class's numeric bonuses (e.g. flat attack, crit chance) on top of its own kit,
    * which sets are meant to make distinct per class in the first place. Unknown setIds absent
    * from `setDefs` are ignored, same as an off-class match.
    *
    * Free-standing (not on [[roguelite.engine.Player]]) so both [[CombatResolver]] and
    * [[roguelite.engine.Player.reconcileSetHpBonus]] share one counting/threshold implementation
    * without `game` needing to depend on `engine.Player`.
    */
  def activeBonuses(equippedSetIds: List[String],
                    setDefs: Map[String, SetDef],
                    playerClassId: ClassId
  ): List[SetBonusEffect] =
    equippedSetIds
      .groupBy(identity)
      .view
      .mapValues(_.size)
      .toList
      .flatMap:
        case (setId, count) =>
          setDefs.get(setId).filter(_.classId == playerClassId).toList.flatMap:
            setDef =>
              (if count >= 2 then List(setDef.twoPiece.effect) else Nil) ++
                (if count >= 4 then List(setDef.fourPiece.effect) else Nil)
