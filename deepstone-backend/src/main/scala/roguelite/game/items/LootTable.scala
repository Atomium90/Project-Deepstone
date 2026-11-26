package roguelite.game.items

import roguelite.engine.Difficulty
import roguelite.game.combat.EnemyInstance

import scala.util.Random

/** Weighted loot roll logic used by chests and enemy drops.
  *
  * All methods are deterministic given a seeded [[Random]], making them fully testable.
  */
object LootTable:

  /** How a chest or Shrine roll picks a kind of item first: relative weights per [[Item.kind]]. A
    * kind is picked by its weight, then an item of that kind by [[OwnClassFactor]] and the
    * difficulty's rarity weight. Two steps keep the share of each kind fixed whatever the number of
    * items of that kind the catalog holds, so adding items never dilutes armor or potions. A `key`
    * is left out on purpose while no room has a locked door for it to open: put it back together
    * with the first one.
    */
  private val KindWeights: List[(String, Int)] = List(
    "weapon"     -> 25,
    "armor"      -> 20,
    "accessory"  -> 30,
    "consumable" -> 25
  )

  /** A Shrine offers one item of each of these kinds (a potion is no relic). */
  private val ShrineKinds: List[String] = List("weapon", "armor", "accessory")

  /** The lowest rarity a Shrine candidate can be authored or rolled at. */
  private val ShrineRarityFloor: Rarity = Rarity.Uncommon

  /** Weight of an item that belongs to the player's class (its typeTag is one of the class's
    * affinity tags) against 1 for any other item: everything can drop, the player's own gear is just
    * more likely. Base weights are scaled by 10 so the difficulty's rarity multiplier still has room
    * to round.
    */
  private val OwnClassFactor: Int = 3
  private val BaseItemWeight: Int = 10

  /** Healing is the staple of the potion pool: a plain heal potion is this many times as likely as
    * any other potion once the consumable kind is picked, as it was when the chest pool was a
    * short list headed by it. Without it a chest would hand out the 4 other potions as often as the
    * one that keeps a run alive.
    */
  private val HealPotionFactor: Int = 3

  private def kindFactor(item: Item): Int = item match
    case Consumable(_, _, _, _, ConsumableEffect.HealFixed(_), _, _) => HealPotionFactor
    case _                                                           => 1

  private def typeTagOf(item: Item): Option[String] = item match
    case w: Weapon    => w.typeTag
    case a: Armor     => a.typeTag
    case a: Accessory => a.typeTag
    case _            => None

  /** Roll one item of `kind`, weighted toward the player's class. None when the catalog has no item
    * of that kind.
    */
  private def rollOfKind(kind: String,
                         itemDefs: Map[String, Item],
                         rng: Random,
                         difficulty: Difficulty,
                         rarityFloorOverride: Option[Rarity],
                         affinityTags: Set[String]
  ): Option[Item] =
    val pool = itemDefs.values.toList
      .filter(_.kind == kind)
      .sortBy(_.typeId)
      .map { item =>
        val own  = if Affinity.matches(typeTagOf(item), affinityTags) then OwnClassFactor else 1
        val base = BaseItemWeight * own * kindFactor(item)
        item -> weightFor(item, base, difficulty)
      }
    pickWeighted(pool, rng).map(item => rollRarityAndScale(item, rng, rarityFloorOverride).withNewId)

  /** Roll a chest drop: a kind by [[KindWeights]], then an item of that kind, so a chest can hold
    * any weapon, armor, accessory or potion of the game, the player's own class's ones more often.
    *
    * Returns None only if the catalog has no item of any rolled kind (an empty `itemDefs`).
    *
    * @param rarityFloorOverride
    *   Raises the tier roll's minimum eligible rarity to at least this (never lowers it below the
    *   item's own authored floor) - the Lucky Find perk's hook. Scaling still stays relative to
    *   the item's own authored floor, not the override, so an item already authored above Common
    *   still doesn't get its bonus double-counted.
    * @param affinityTags
    *   The player's class affinity tags (empty = no class weighting).
    */
  def rollChest(itemDefs: Map[String, Item],
                rng: Random,
                difficulty: Difficulty = Difficulty.Normal,
                rarityFloorOverride: Option[Rarity] = None,
                affinityTags: Set[String] = Set.empty
  ): Option[Item] =
    val kinds = KindWeights.filter((kind, _) => itemDefs.values.exists(_.kind == kind))
    pickWeighted(kinds, rng).flatMap(rollOfKind(_, itemDefs, rng, difficulty, rarityFloorOverride, affinityTags))

  /** Roll `count` reward candidates for a [[Shrine]] choice (see [[CombatResolver.victory]]'s
    * MiniBoss-kill spawn): one per kind of [[ShrineKinds]] in a random order (a weapon, an armor and
    * an accessory for the default 3), each at least [[ShrineRarityFloor]] and weighted toward the
    * player's class like a chest drop, each with its own instance id. With more candidates than
    * kinds the kinds repeat; a kind the catalog lacks is skipped, and a catalog with none of them
    * falls back to plain chest rolls. Returns fewer than `count` only for an empty catalog.
    */
  def rollShrineChoices(itemDefs: Map[String, Item],
                        rng: Random,
                        count: Int = 3,
                        difficulty: Difficulty = Difficulty.Normal,
                        affinityTags: Set[String] = Set.empty
  ): List[Item] =
    val floor = Some(ShrineRarityFloor)
    val kinds = rng.shuffle(ShrineKinds.filter(kind => itemDefs.values.exists(_.kind == kind)))
    if kinds.isEmpty then
      (1 to count).flatMap(_ => rollChest(itemDefs, rng, difficulty, floor, affinityTags)).toList
    else
      (0 until count)
        .flatMap(i => rollOfKind(kinds(i % kinds.size), itemDefs, rng, difficulty, floor, affinityTags))
        .toList

  /** Roll an enemy drop.
    *
    * First checks `enemy.dropChance` (0–100) against a uniform roll. If the roll succeeds, picks a
    * typeId from the enemy's own loot table using weighted random. Returns None if the chance roll
    * fails or the loot table is empty / unresolvable.
    *
    * @param rarityFloorOverride
    *   Same "only raises, never lowers, the item's own authored floor" semantics as
    *   [[rollChest]]'s - used by [[CombatResolver.victory]] to guarantee at least Rare on an Elite
    *   kill.
    */
  def rollEnemy(enemy: EnemyInstance,
                itemDefs: Map[String, Item],
                rng: Random,
                difficulty: Difficulty = Difficulty.Normal,
                rarityFloorOverride: Option[Rarity] = None
  ): Option[Item] =
    if enemy.dropChance <= 0 || rng.nextInt(100) >= enemy.dropChance then None
    else
      val pool = enemy.lootTable.flatMap(
        e => itemDefs.get(e.typeId).map(item => item -> weightFor(item, e.weight, difficulty))
      )
      pickWeighted(pool, rng).map(item => rollRarityAndScale(item, rng, rarityFloorOverride).withNewId)

  /** Apply the difficulty's rarity multiplier to a base weight, rounding to the nearest int. Below
    * 1.0 total the item is effectively removed from the pool.
    */
  private def weightFor(item: Item, baseWeight: Int, difficulty: Difficulty): Int =
    math.round(baseWeight * difficulty.rarityWeightMultiplier(item.rarity)).toInt

  /** Relative weights for the independent rarity-tier roll applied to a freshly-picked drop (see
    * [[rollRarityAndScale]]). Common bias is intentional: Rare/Epic should read as a notable find,
    * not the default outcome. A run opens a dozen or more chests, so these weights decide how much of
    * the gear power a run reaches comes from luck: at 60/25/12/3 every run found a Rare and most an
    * Epic, which made the class weapons carry a whole run. Distinct from [[weightFor]], which governs
    * which catalog entry is picked from a pool in the first place, not the tier a chosen entry then
    * drops at.
    */
  private val RarityRollWeights: Map[Rarity, Int] = Map(
    Rarity.Common   -> 70,
    Rarity.Uncommon -> 22,
    Rarity.Rare     -> 7,
    Rarity.Epic     -> 1
  )

  /** Roll a rarity tier for a freshly-picked drop and scale its numeric stat fields accordingly.
    *
    * The item's own `rarity` acts as a floor (see the [[Rarity]] docblock): the roll only
    * considers tiers at or above it, so a catalog-authored Uncommon item can never drop as Common
    * but can still roll Rare or Epic. Scaling is relative to the floor tier's own multiplier, not
    * absolute, so an item already authored above Common doesn't get its bonus double-counted.
    *
    * Weapon/Armor/Accessory scale by [[Rarity.statMultiplier]]. Consumable rolls too and scales
    * its flat-amount effect fields (heal/restore/damage) by the steeper [[Rarity.potionMultiplier]]
    * - a potion is a one-shot payoff, not a bonus that compounds over a whole run the way gear
    * does. Percent-based consumable effects (attack/crit buffs) stay on the gentler
    * `statMultiplier` curve instead, and their `turns` duration never scales at all - only the
    * magnitude gets bigger at higher rarity, not the duration. Key passes through unchanged,
    * keeping its authored rarity (a key has no numeric field to scale in the first place).
    */
  private def rollRarityAndScale(item: Item, rng: Random, floorOverride: Option[Rarity] = None): Item =
    val effectiveFloor = (authored: Rarity) =>
      floorOverride match {
        case Some(o) if o.ordinal > authored.ordinal => o
        case _                                       => authored
      }
    item match
      case w: Weapon =>
        val tier = rollTier(effectiveFloor(w.rarity), rng)
        w.copy(rarity = tier, attackBonus = scale(w.attackBonus, w.rarity, tier))

      case a: Armor =>
        val tier = rollTier(effectiveFloor(a.rarity), rng)
        a.copy(rarity = tier, defenseBonus = scale(a.defenseBonus, a.rarity, tier))

      case acc: Accessory =>
        val tier = rollTier(effectiveFloor(acc.rarity), rng)
        acc.copy(
          rarity = tier,
          hpBonus = acc.hpBonus.map(scale(_, acc.rarity, tier)),
          attackBonus = acc.attackBonus.map(scale(_, acc.rarity, tier)),
          defenseBonus = acc.defenseBonus.map(scale(_, acc.rarity, tier)),
          critChanceBonus = acc.critChanceBonus.map(scale(_, acc.rarity, tier))
        )

      case c: Consumable =>
        val tier = rollTier(effectiveFloor(c.rarity), rng)
        val scaledEffect = c.effect match
          case ConsumableEffect.HealFixed(amount) =>
            ConsumableEffect.HealFixed(scale(amount, c.rarity, tier, _.potionMultiplier))
          case ConsumableEffect.HealPercent(pct) =>
            ConsumableEffect.HealPercent(math.min(100, scale(pct, c.rarity, tier, _.potionMultiplier)))
          case ConsumableEffect.RestoreResource(amount) =>
            ConsumableEffect.RestoreResource(scale(amount, c.rarity, tier, _.potionMultiplier))
          case ConsumableEffect.FlatDamage(amount) =>
            ConsumableEffect.FlatDamage(scale(amount, c.rarity, tier, _.potionMultiplier))
          case ConsumableEffect.AttackBuff(percent, turns) =>
            ConsumableEffect.AttackBuff(scale(percent, c.rarity, tier), turns)
          case ConsumableEffect.CritBuff(percent, turns) =>
            ConsumableEffect.CritBuff(scale(percent, c.rarity, tier), turns)
        c.copy(rarity = tier, effect = scaledEffect)

      case other => other

  /** Roll a tier at or above `floor`, weighted by [[RarityRollWeights]]. Falls back to `floor`
    * itself in the unreachable case of every eligible tier having non-positive weight.
    */
  private def rollTier(floor: Rarity, rng: Random): Rarity =
    val eligible = Rarity.values.toList
      .filter(_.ordinal >= floor.ordinal)
      .map(tier => tier -> RarityRollWeights(tier))
    pickWeighted(eligible, rng).getOrElse(floor)

  /** Scale a single stat field from `floor` to `rolled`, relative to floor's own multiplier under
    * `multiplierOf` (defaults to [[Rarity.statMultiplier]] - gear's scaling curve; pass
    * `_.potionMultiplier` for a potion's flat-amount effect fields instead). Floored at 1 so
    * rounding never zeroes out a positive base stat.
    */
  private def scale(base: Int, floor: Rarity, rolled: Rarity, multiplierOf: Rarity => Double = _.statMultiplier): Int =
    math.max(1, math.round(base * (multiplierOf(rolled) / multiplierOf(floor))).toInt)

  /** Pick one element from a weighted list using a single uniform random draw.
    *
    * Uses an accumulator fold so no external library is needed. Returns None only if `pool` is
    * empty or all weights are zero.
    */
  private def pickWeighted[A](pool: List[(A, Int)], rng: Random): Option[A] =
    val total = pool.map(_._2).sum
    if total <= 0 then return None
    val roll = rng.nextInt(total)
    pool
      .foldLeft((0, Option.empty[A])):
        case ((cursor, found), (item, weight)) =>
          val next = cursor + weight
          val pick = if found.isEmpty && roll < next then Some(item) else found
          (next, pick)
      ._2
