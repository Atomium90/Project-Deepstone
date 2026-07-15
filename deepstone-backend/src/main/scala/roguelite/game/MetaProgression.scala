package roguelite.game

/** Static definition of one hub upgrade available for purchase. */
case class UpgradeDef(
    id: String,
    label: String,
    description: String,
    cost: Int
)

object UpgradeDef:
  /** All upgrades available in the hub, in display order. */
  val all: List[UpgradeDef] = List(
    UpgradeDef("hp_boost_1", "Iron Constitution I", "+20 max HP for the next run", cost = 30),
    UpgradeDef("hp_boost_2", "Iron Constitution II", "+40 max HP for the next run", cost = 75),
    UpgradeDef("potion_start",
               "Emergency Supplies",
               "Start each run with a Health Potion",
               cost = 40
    ),
    UpgradeDef("archer_unlock", "Ranger's Path", "Unlock the Archer class", cost = 50),
    UpgradeDef("mage_unlock", "Arcane Studies", "Unlock the Mage class", cost = 80),
    UpgradeDef("extra_slot", "Packrat", "Expand your inventory to 7 item slots", cost = 60)
  )

  val byId: Map[String, UpgradeDef] = all
    .map(
      u => u.id -> u
    )
    .toMap

/** Meta-progression state that persists between runs and survives death.
  *
  * Loaded from SQLite at session start by [[roguelite.db.Database.loadMeta]] and kept in a `Ref[IO,
  * MetaProgression]` inside [[roguelite.engine.GameSession]].
  *
  * All mutation methods return a new `MetaProgression`; the caller is responsible for persisting
  * changes via [[roguelite.db.Database]].
  *
  * @param currency
  *   Current balance of Stone Shards (meta-currency earned from combat).
  * @param unlockedUpgrades
  *   Set of upgrade ids the player has permanently purchased.
  */
case class MetaProgression(
    currency: Int,
    unlockedUpgrades: Set[String]
):
  def isUnlocked(upgradeId: String): Boolean =
    unlockedUpgrades.contains(upgradeId)

  def purchase(upgradeId: String): Either[String, MetaProgression] =
    UpgradeDef.byId.get(upgradeId) match {
      case None => Left(s"Unknown upgrade: '$upgradeId'.")
      case Some(u) if unlockedUpgrades.contains(upgradeId) =>
        Left(s"${u.label} is already purchased.")
      case Some(u) if currency < u.cost =>
        Left(s"Not enough Shards — ${u.label} costs ${u.cost} (you have $currency).")
      case Some(u) =>
        Right(copy(currency = currency - u.cost, unlockedUpgrades = unlockedUpgrades + upgradeId))
    }

object MetaProgression:
  val empty: MetaProgression = MetaProgression(currency = 0, unlockedUpgrades = Set.empty)
