package roguelite.game

/** Pure resolver that checks which achievements just became newly satisfied, and updates the
  * lifetime counters ([[AchievementStats]]) that back the count-based conditions.
  *
  * Two entry points mirror the two ways an achievement can be triggered:
  *   - [[checkEvents]]: called from [[roguelite.engine.GameSession]] with the [[GameEvent]]s
  *     emitted by the pure resolvers during a normal action.
  *   - [[checkPurchase]]: called from [[roguelite.engine.GameSession]]'s BuyUpgrade handling,
  *     since BuyUpgrade never flows through the pure resolvers that emit [[GameEvent]]s.
  *
  * No DB access, no GameState — every fact needed is already embedded in the GameEvent or passed
  * explicitly, which keeps this fully unit-testable without any session/state fixtures.
  */
object AchievementChecker:

  /** Fold every event over the current stats, updating counters as it goes, and collect every
    * achievement whose condition becomes newly satisfied (not already unlocked, not already
    * collected earlier in this same fold).
    *
    * @return the updated stats plus the list of newly-unlocked achievement defs, in no particular
    *         order.
    */
  def checkEvents(defs: Map[String, AchievementDef],
                  unlocked: Set[String],
                  stats: AchievementStats,
                  events: List[GameEvent]
  ): (AchievementStats, List[AchievementDef]) =
    events.foldLeft((stats, List.empty[AchievementDef])):
      case ((currentStats, acc), event) =>
        val updatedStats  = applyEvent(currentStats, event)
        val alreadyQueued = acc.map(_.id).toSet
        val satisfied = defs.values.filter:
          d =>
            !unlocked.contains(d.id) && !alreadyQueued.contains(d.id) &&
              conditionMet(d.condition, event, updatedStats)
        (updatedStats, acc ++ satisfied)

  /** Check the two purchase-driven conditions ([[AchievementCondition.TotalShardsSpent]],
    * [[AchievementCondition.AllUpgradesUnlocked]]) after a successful upgrade purchase.
    *
    * @param spent                 Cost of the upgrade just purchased, added to the lifetime total.
    * @param unlockedUpgradeCount  Total upgrades unlocked so far, including this purchase.
    * @param totalUpgradeCount     Size of the full upgrade catalog.
    */
  def checkPurchase(defs: Map[String, AchievementDef],
                    unlocked: Set[String],
                    stats: AchievementStats,
                    spent: Int,
                    unlockedUpgradeCount: Int,
                    totalUpgradeCount: Int
  ): (AchievementStats, List[AchievementDef]) =
    val updatedStats = stats.copy(totalShardsSpent = stats.totalShardsSpent + spent)
    val satisfied = defs.values.filter:
      d =>
        !unlocked.contains(d.id) && (d.condition match {
          case AchievementCondition.TotalShardsSpent(amount) =>
            updatedStats.totalShardsSpent >= amount
          case AchievementCondition.AllUpgradesUnlocked =>
            totalUpgradeCount > 0 && unlockedUpgradeCount == totalUpgradeCount
          case _ => false
        })
    (updatedStats, satisfied.toList)

  private def applyEvent(stats: AchievementStats, event: GameEvent): AchievementStats = event match
    case GameEvent.RunEnded(victory) =>
      stats.copy(
        runsCompleted = stats.runsCompleted + 1,
        runsWon = if victory then stats.runsWon + 1 else stats.runsWon,
        currentWinStreak = if victory then stats.currentWinStreak + 1 else 0
      )
    case _ => stats

  private def conditionMet(condition: AchievementCondition,
                           event: GameEvent,
                           stats: AchievementStats
  ): Boolean =
    (condition, event) match
      case (AchievementCondition.FirstKill, GameEvent.EnemyDefeated(_, _))         => true
      case (AchievementCondition.DefeatBoss, GameEvent.EnemyDefeated(isBoss, _))   => isBoss
      case (AchievementCondition.NoDamageVictory, GameEvent.EnemyDefeated(_, tookNoDamage)) =>
        tookNoDamage
      case (AchievementCondition.ReachLevel(lvl), GameEvent.LeveledUp(newLevel)) => newLevel >= lvl
      case (AchievementCondition.FillInventory, GameEvent.ItemPickedUp(full))    => full
      case (AchievementCondition.UnlockDoorWithKey, GameEvent.DoorUnlockedWithKey) => true
      case (AchievementCondition.RevealSecretDoor, GameEvent.SecretDoorRevealed)   => true
      case (AchievementCondition.RunsCompleted(n), GameEvent.RunEnded(_)) => stats.runsCompleted >= n
      case (AchievementCondition.RunsWon(n), GameEvent.RunEnded(victory)) =>
        victory && stats.runsWon >= n
      case (AchievementCondition.WinStreak(n), GameEvent.RunEnded(_)) => stats.currentWinStreak >= n
      case _                                                          => false
