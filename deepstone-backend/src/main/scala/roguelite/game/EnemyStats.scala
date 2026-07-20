package roguelite.game

import roguelite.engine.Difficulty

// ---------------------------------------------
// Static enemy data (loaded from enemies.json)
// ---------------------------------------------

/** One possible action an enemy can take on its turn. */
case class EnemyActionWeight(action: String, weight: Int)

/** One entry in an enemy's loot table: an item typeId and its relative drop weight. */
case class LootEntry(typeId: String, weight: Int)

/** Immutable stats for an enemy type, shared across all instances of that type.
  *
  * Loaded once at startup and never mutated. All fields reflect the base values before any
  * difficulty scaling is applied.
  */
case class EnemyStats(
    typeId: String,
    label: String,
    maxHp: Int,
    attack: Int,
    defense: Int,
    xpReward: Int,
    actions: List[EnemyActionWeight],
    dropChance: Int = 0,
    lootTable: List[LootEntry] = Nil
)

// ---------------------------------------------
// Runtime enemy instance (mutable during combat)
// ---------------------------------------------

/** A live enemy in an active combat, with its own current HP.
  *
  * Separated from [[EnemyStats]] so that stats remain a pure reference table while combat state
  * stays self-contained in [[Combat]].
  *
  * @param entityId
  *   The id of the [[Enemy]] entity in the room — used to remove it from the room after a
  *   victorious combat.
  * @param dropChance
  *   Copied from [[EnemyStats]] so [[LootTable]] can read it without a second lookup.
  */
case class EnemyInstance(
    entityId: String,
    typeId: String,
    label: String,
    hp: Int,
    maxHp: Int,
    attack: Int,
    defense: Int,
    xpReward: Int,
    actions: List[EnemyActionWeight],
    dropChance: Int = 0,
    lootTable: List[LootEntry] = Nil
):
  def isAlive: Boolean = hp > 0

  /** Return a new instance with the given damage applied (HP floored at 0). */
  def takeDamage(amount: Int): EnemyInstance =
    copy(hp = (hp - amount).max(0))

object EnemyInstance:
  /** Create a fresh combat instance from static stats and the entity id, scaled by difficulty.
    *
    * maxHp/attack/xpReward are floored at 1 so a low base stat never scales down to 0 or negative
    * on Easy; defense is floored at 0 since a defenseless enemy is valid.
    */
  def fromStats(entityId: String,
                stats: EnemyStats,
                difficulty: Difficulty = Difficulty.Normal
  ): EnemyInstance =
    val mult      = difficulty.statMultiplier
    val scaledHp  = math.max(1, math.round(stats.maxHp * mult).toInt)
    EnemyInstance(
      entityId = entityId,
      typeId = stats.typeId,
      label = stats.label,
      hp = scaledHp,
      maxHp = scaledHp,
      attack = math.max(1, math.round(stats.attack * mult).toInt),
      defense = math.max(0, math.round(stats.defense * mult).toInt),
      xpReward = math.max(1, math.round(stats.xpReward * mult).toInt),
      actions = stats.actions,
      dropChance = stats.dropChance,
      lootTable = stats.lootTable
    )
