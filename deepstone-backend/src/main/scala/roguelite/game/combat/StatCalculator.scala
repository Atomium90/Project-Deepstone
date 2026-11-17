package roguelite.game.combat

import roguelite.engine.Player
import roguelite.game.items.{ Accessory, Affinity, SetBonusEffect, SetDef }
import roguelite.game.progression.{ PerkDef, PerkEffect }

/** Where a part of a stat comes from, for the breakdown the player can read. */
enum StatSource:
  case Level, MaxHp, Bonus, Weapon, Armor, Accessories, Set, Perk

/** One part of a stat. `affinity` is true when the item bonus in `value` is doubled by the class
  * affinity (see [[Affinity]]).
  */
case class StatTerm(source: StatSource, value: Int, affinity: Boolean = false)

/**
  * The numbers a player's attack, defense and crit chance are made of, worked out in one place so the
  * combat math ([[CombatResolver]]) and what the client shows cannot drift apart.
  *
  * Every stat is the sum of its terms, which are also what the client lists on hover. Weapon, armor
  * and accessory bonuses are doubled by the class affinity (each accessory on its own tag), and a set
  * bonus only counts for the class the set is made for (see [[SetDef.activeBonuses]]).
  *
  * @param setDefs
  *   Loaded equipment set catalog, keyed by setId.
  * @param perkDefs
  *   Loaded run-perk catalog, keyed by id, resolves [[Player.activePerkId]].
  */
final class StatCalculator(setDefs: Map[String, SetDef], perkDefs: Map[String, PerkDef]):

  /** Every set bonus effect currently active for `player` (see [[SetDef.activeBonuses]]). */
  def activeSetBonuses(player: Player): List[SetBonusEffect] =
    SetDef.activeBonuses(player.equippedSetIds, setDefs, player.classId)

  def sumSetBonus(player: Player, extract: PartialFunction[SetBonusEffect, Int]): Int =
    activeSetBonuses(player).collect(extract).sum

  /** The effects of the run perks the player took, resolved against the catalog (none if no perk is
    * active or an id is not in it).
    */
  def activePerkEffects(player: Player): List[PerkEffect] = PerkDef.activeEffects(player, perkDefs)

  /** Attack: level scaling + max-HP factor + permanent bonus + affinity-aware weapon and accessory
    * bonuses + any active set FlatAttack bonus + any active FlatDamageBonus perk. Example: Hunter's
    * Bow (+5 ATK, "ranged") held by an Archer (affinity "ranged") gives +10.
    */
  def attackTerms(player: Player): List[StatTerm] =
    val weapon = player.equippedWeapon.map: w =>
      StatTerm(StatSource.Weapon,
               w.attackBonus * Affinity.multiplier(w.typeTag, player.affinityTags),
               Affinity.matches(w.typeTag, player.affinityTags) && w.attackBonus != 0
      )
    val perk = activePerkEffects(player).collect { case PerkEffect.FlatDamageBonus(n) => n }.sum
    List(
      Some(StatTerm(StatSource.Level, player.level * 5)),
      Some(StatTerm(StatSource.MaxHp, player.maxHp / 10)),
      Some(StatTerm(StatSource.Bonus, player.bonusAttack)),
      weapon,
      Some(accessoryTerm(player, _.attackBonus)),
      Some(StatTerm(StatSource.Set, sumSetBonus(player, { case SetBonusEffect.FlatAttack(n) => n }))),
      Some(StatTerm(StatSource.Perk, perk))
    ).flatten

  /** Defense: level scaling + permanent bonus + affinity-aware armor and accessory bonuses + any
    * active set FlatDefense bonus.
    */
  def defenseTerms(player: Player): List[StatTerm] =
    val armor = player.equippedArmor.map: a =>
      StatTerm(StatSource.Armor,
               a.defenseBonus * Affinity.multiplier(a.typeTag, player.affinityTags),
               Affinity.matches(a.typeTag, player.affinityTags) && a.defenseBonus != 0
      )
    List(
      Some(StatTerm(StatSource.Level, player.level * 2)),
      Some(StatTerm(StatSource.Bonus, player.bonusDefense)),
      armor,
      Some(accessoryTerm(player, _.defenseBonus)),
      Some(StatTerm(StatSource.Set, sumSetBonus(player, { case SetBonusEffect.FlatDefense(n) => n })))
    ).flatten

  /** Percent chance (0-100 and more) of a critical hit on the player's next Attack: both accessories'
    * critChanceBonus (same affinity rule) plus any active set CritChancePercent bonus. Weapon and armor
    * never add to it. Not capped: 100 or more is a guaranteed crit, which is what [[CombatResolver]]
    * rolls against.
    */
  def critTerms(player: Player): List[StatTerm] =
    List(
      accessoryTerm(player, _.critChanceBonus),
      StatTerm(StatSource.Set, sumSetBonus(player, { case SetBonusEffect.CritChancePercent(n) => n }))
    )

  def attack(player: Player): Int     = total(attackTerms(player))
  def defense(player: Player): Int    = total(defenseTerms(player))
  def critChance(player: Player): Int = total(critTerms(player))

  /** The final percentage a set adds to every hit (Fire Mage 2pc), on top of the base damage. Applied
    * together with any attack potion buff, before the crit multiplier.
    */
  def setDamagePercent(player: Player): Int =
    sumSetBonus(player, { case SetBonusEffect.AttackDamagePercent(n) => n })

  /** Silent Archer 4pc: the first attack of every fight is a critical hit. */
  def firstAttackAlwaysCrits(player: Player): Boolean =
    activeSetBonuses(player).contains(SetBonusEffect.FirstAttackAlwaysCrit)

  private def total(terms: List[StatTerm]): Int = terms.map(_.value).sum

  /** One bonus field summed across both accessory slots. The affinity doubling is judged on each
    * accessory's own type tag, not on the accessory kind as a whole.
    */
  private def accessoryTerm(player: Player, extract: Accessory => Option[Int]): StatTerm =
    val parts = player.equippedAccessories.flatten.map: acc =>
      val base = extract(acc).getOrElse(0)
      (base * Affinity.multiplier(acc.typeTag, player.affinityTags),
       base != 0 && Affinity.matches(acc.typeTag, player.affinityTags)
      )
    StatTerm(StatSource.Accessories, parts.map(_._1).sum, parts.exists(_._2))

object StatCalculator:

  /** Damage multiplier applied on a critical hit. */
  val CritMultiplier = 1.5
