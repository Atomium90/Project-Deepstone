package roguelite.game

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

  /** Validate and apply a purchase against the loaded upgrade catalog (see [[UpgradeLoader]]). */
  def purchase(upgradeId: String,
              upgradeDefs: Map[String, UpgradeDef]
  ): Either[String, MetaProgression] =
    upgradeDefs.get(upgradeId) match {
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
