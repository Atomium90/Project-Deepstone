package roguelite.game

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
  /** Create a fresh combat instance from static stats and the entity id. */
  def fromStats(entityId: String, stats: EnemyStats): EnemyInstance =
    EnemyInstance(
      entityId = entityId,
      typeId = stats.typeId,
      label = stats.label,
      hp = stats.maxHp,
      maxHp = stats.maxHp,
      attack = stats.attack,
      defense = stats.defense,
      xpReward = stats.xpReward,
      actions = stats.actions,
      dropChance = stats.dropChance,
      lootTable = stats.lootTable
    )
