package roguelite.game

/** A safety net for a player who is stuck: the starting-kit upgrades are what lets a class start
  * with a weapon and armor, and a player who keeps dying without ever saving enough Stone Shards
  * for one never gets out of the bare-handed start. After enough finished runs, the missing
  * Shards are given.
  *
  * Pure rule, no IO: [[roguelite.engine.GameSession]] decides when to ask (every return to the hub
  * after a run) and applies the grant. The rule stops on its own once a kit is bought, or once the
  * player can afford one, so it holds no state of its own.
  */
object ShardTopUp:

  /** How many runs the player must have finished, won or lost, before being helped. */
  val RunsBeforeHelp = 3

  /** @param amount
    *   The Shards to add, exactly what is missing for `upgrade`.
    * @param upgrade
    *   The starting-kit upgrade the grant is meant for, the cheapest one.
    */
  case class Grant(amount: Int, upgrade: UpgradeDef)

  /** The grant due to this player, if any: at least [[RunsBeforeHelp]] runs finished, no
    * starting-kit upgrade owned, and not enough Shards for the cheapest one.
    */
  def grantFor(meta: MetaProgression,
               stats: AchievementStats,
               upgradeDefs: Map[String, UpgradeDef]
  ): Option[Grant] =
    val kits = upgradeDefs.values.filter(isKit).toList
    if stats.runsCompleted < RunsBeforeHelp || kits.exists(k => meta.isUnlocked(k.id)) then None
    else
      kits.sortBy(k => (k.cost, k.displayOrder)).headOption.flatMap:
        cheapest =>
          if meta.currency >= cheapest.cost then None
          else Some(Grant(cheapest.cost - meta.currency, cheapest))

  private def isKit(upgrade: UpgradeDef): Boolean = upgrade.effect match
    case UpgradeEffect.UnlockStartingKit(_) => true
    case _                                  => false
