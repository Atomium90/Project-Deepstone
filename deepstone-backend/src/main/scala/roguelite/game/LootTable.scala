package roguelite.game

import roguelite.engine.Difficulty

import scala.util.Random

/** Weighted loot roll logic used by chests and enemy drops.
  *
  * All methods are deterministic given a seeded [[Random]], making them fully testable.
  */
object LootTable:

  /** Items that can drop from any chest (typeId → relative weight). Weights are relative: only
    * their ratio matters.
    */
  private val ChestPool: List[(String, Int)] = List(
    "health_potion"          -> 25,
    "practice_sword"         -> 10,
    "gold_dagger"            -> 10,
    "leather_bound_grimoire" -> 10,
    "soulstone"              -> 10,
    "rusty_key"              -> 12,
    "steel_sword"            -> 5,
    "wooden_emerald_bow"     -> 5,
    "pyromancer_staff"       -> 5,
    "luck_clover"            -> 8
  )

  /** Roll a chest drop. Chests always yield exactly one item from [[ChestPool]].
    *
    * Returns None only if none of the pool typeIds exist in `itemDefs` (should not happen at
    * runtime with a valid items.json).
    */
  def rollChest(itemDefs: Map[String, Item],
                rng: Random,
                difficulty: Difficulty = Difficulty.Normal
  ): Option[Item] =
    val pool = ChestPool.flatMap {
      case (typeId, w) => itemDefs.get(typeId).map(item => item -> weightFor(item, w, difficulty))
    }
    pickWeighted(pool, rng).map(item => rollRarityAndScale(item, rng).withNewId)

  /** Roll an enemy drop.
    *
    * First checks `enemy.dropChance` (0–100) against a uniform roll. If the roll succeeds, picks a
    * typeId from the enemy's own loot table using weighted random. Returns None if the chance roll
    * fails or the loot table is empty / unresolvable.
    */
  def rollEnemy(enemy: EnemyInstance,
                itemDefs: Map[String, Item],
                rng: Random,
                difficulty: Difficulty = Difficulty.Normal
  ): Option[Item] =
    if enemy.dropChance <= 0 || rng.nextInt(100) >= enemy.dropChance then None
    else
      val pool = enemy.lootTable.flatMap(
        e => itemDefs.get(e.typeId).map(item => item -> weightFor(item, e.weight, difficulty))
      )
      pickWeighted(pool, rng).map(item => rollRarityAndScale(item, rng).withNewId)

  /** Apply the difficulty's rarity multiplier to a base weight, rounding to the nearest int. Below
    * 1.0 total the item is effectively removed from the pool.
    */
  private def weightFor(item: Item, baseWeight: Int, difficulty: Difficulty): Int =
    math.round(baseWeight * difficulty.rarityWeightMultiplier(item.rarity)).toInt

  /** Relative weights for the independent rarity-tier roll applied to a freshly-picked drop (see
    * [[rollRarityAndScale]]). Common bias is intentional: Rare/Epic should read as a notable find,
    * not the default outcome. Distinct from [[weightFor]], which governs which catalog entry is
    * picked from a pool in the first place, not the tier a chosen entry then drops at.
    */
  private val RarityRollWeights: Map[Rarity, Int] = Map(
    Rarity.Common   -> 60,
    Rarity.Uncommon -> 25,
    Rarity.Rare     -> 12,
    Rarity.Epic     -> 3
  )

  /** Roll a rarity tier for a freshly-picked drop and scale its numeric stat fields accordingly.
    *
    * The item's own `rarity` acts as a floor (see the [[Rarity]] docblock): the roll only
    * considers tiers at or above it, so a catalog-authored Uncommon item can never drop as Common
    * but can still roll Rare or Epic. Scaling is relative to the floor tier's own multiplier, not
    * absolute, so an item already authored above Common doesn't get its bonus double-counted.
    *
    * Only Weapon/Armor/Accessory carry numeric stat fields worth scaling; Consumable/Key pass
    * through unchanged, keeping their authored rarity (rolling a "Rare" health potion that heals
    * the same amount would be meaningless).
    */
  private def rollRarityAndScale(item: Item, rng: Random): Item = item match
    case w: Weapon =>
      val tier = rollTier(w.rarity, rng)
      w.copy(rarity = tier, attackBonus = scale(w.attackBonus, w.rarity, tier))

    case a: Armor =>
      val tier = rollTier(a.rarity, rng)
      a.copy(rarity = tier, defenseBonus = scale(a.defenseBonus, a.rarity, tier))

    case acc: Accessory =>
      val tier = rollTier(acc.rarity, rng)
      acc.copy(
        rarity = tier,
        hpBonus = acc.hpBonus.map(scale(_, acc.rarity, tier)),
        attackBonus = acc.attackBonus.map(scale(_, acc.rarity, tier)),
        defenseBonus = acc.defenseBonus.map(scale(_, acc.rarity, tier)),
        critChanceBonus = acc.critChanceBonus.map(scale(_, acc.rarity, tier))
      )

    case other => other

  /** Roll a tier at or above `floor`, weighted by [[RarityRollWeights]]. Falls back to `floor`
    * itself in the unreachable case of every eligible tier having non-positive weight.
    */
  private def rollTier(floor: Rarity, rng: Random): Rarity =
    val eligible = Rarity.values.toList
      .filter(_.ordinal >= floor.ordinal)
      .map(tier => tier -> RarityRollWeights(tier))
    pickWeighted(eligible, rng).getOrElse(floor)

  /** Scale a single stat field from `floor` to `rolled`, relative to floor's own multiplier.
    * Floored at 1 so rounding never zeroes out a positive base stat.
    */
  private def scale(base: Int, floor: Rarity, rolled: Rarity): Int =
    math.max(1, math.round(base * (rolled.statMultiplier / floor.statMultiplier)).toInt)

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
