package roguelite.game.progression

import roguelite.game.core.LogLine

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
  ): Either[LogLine, MetaProgression] =
    upgradeDefs.get(upgradeId) match {
      case None => Left(LogLine.of("log.shop.unknownUpgrade", "id" -> upgradeId))
      case Some(u) if unlockedUpgrades.contains(upgradeId) =>
        Left(LogLine.of("log.shop.alreadyOwned", "upgrade" -> u.id))
      case Some(u) if currency < u.cost =>
        Left(LogLine.of("log.shop.cantAfford", "upgrade" -> u.id, "cost" -> u.cost, "have" -> currency))
      case Some(u) =>
        Right(copy(currency = currency - u.cost, unlockedUpgrades = unlockedUpgrades + upgradeId))
    }

object MetaProgression:
  val empty: MetaProgression = MetaProgression(currency = 0, unlockedUpgrades = Set.empty)
