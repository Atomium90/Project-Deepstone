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
    spriteId: String,
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
  *   The id of the [[Enemy]] entity in the room, used to remove it from the room after a
  *   victorious combat.
  * @param dropChance
  *   Copied from [[EnemyStats]] so [[LootTable]] can read it without a second lookup. Forced to
  *   100 by [[fromStats]] when `isElite` is true.
  * @param isElite
  *   Copied from the room's [[Enemy.isElite]] at instantiation time (see [[fromStats]]) - read by
  *   [[CombatResolver.victory]] to force a guaranteed rarity floor on kill, and surfaced on
  *   [[roguelite.engine.CombatView.isElite]].
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
    lootTable: List[LootEntry] = Nil,
    isElite: Boolean = false
):
  def isAlive: Boolean = hp > 0

  /** Return a new instance with the given damage applied (HP floored at 0). */
  def takeDamage(amount: Int): EnemyInstance =
    copy(hp = (hp - amount).max(0))

object EnemyInstance:
  /** HP/attack multiplier for an Elite enemy, stacked multiplicatively on top of the difficulty
    * multiplier. Defense and xpReward are deliberately left unscaled - see [[fromStats]].
    */
  private val EliteStatMultiplier: Double = 1.75

  /** Create a fresh combat instance from static stats and the entity id, scaled by difficulty and
    * (if `isElite`) the Elite multiplier.
    *
    * maxHp/attack/xpReward are floored at 1 so a low base stat never scales down to 0 or negative
    * on Easy; defense is floored at 0 since a defenseless enemy is valid. Elite only scales
    * maxHp/attack (not defense/xpReward, deliberately - a harder fight for the same reward,
    * offset by the guaranteed loot floor instead) and forces `dropChance` to 100.
    */
  def fromStats(entityId: String,
                stats: EnemyStats,
                difficulty: Difficulty = Difficulty.Normal,
                isElite: Boolean = false
  ): EnemyInstance =
    val mult      = difficulty.statMultiplier
    val eliteMult = if isElite then EliteStatMultiplier else 1.0
    val scaledHp  = math.max(1, math.round(stats.maxHp * mult * eliteMult).toInt)
    EnemyInstance(
      entityId = entityId,
      typeId = stats.typeId,
      label = stats.label,
      hp = scaledHp,
      maxHp = scaledHp,
      attack = math.max(1, math.round(stats.attack * mult * eliteMult).toInt),
      defense = math.max(0, math.round(stats.defense * mult).toInt),
      xpReward = math.max(1, math.round(stats.xpReward * mult).toInt),
      actions = stats.actions,
      dropChance = if isElite then 100 else stats.dropChance,
      lootTable = stats.lootTable,
      isElite = isElite
    )
