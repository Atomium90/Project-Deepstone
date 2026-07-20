package roguelite.game

import roguelite.engine.Difficulty

import scala.util.Random

/** Weighted loot roll logic used by chests and enemy drops.
  *
  * All methods are deterministic given a seeded [[Random]], making them fully testable.
  */
object LootTable:

  /** Items that can drop from any chest (typeId → relative weight). Weights are relative — only
    * their ratio matters.
    */
  private val ChestPool: List[(String, Int)] = List(
    "health_potion"   -> 30,
    "iron_sword"      -> 17,
    "hunters_bow"     -> 17,
    "leather_armor"   -> 15,
    "iron_ring"       -> 11,
    "steel_sword"     -> 4,
    "chain_mail"      -> 4,
    "amulet_of_vigor" -> 2
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
    pickWeighted(pool, rng).map(_.withNewId)

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
      pickWeighted(pool, rng).map(_.withNewId)

  /** Apply the difficulty's rarity multiplier to a base weight, rounding to the nearest int. Below
    * 1.0 total the item is effectively removed from the pool.
    */
  private def weightFor(item: Item, baseWeight: Int, difficulty: Difficulty): Int =
    math.round(baseWeight * difficulty.rarityWeightMultiplier(item.rarity)).toInt

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
