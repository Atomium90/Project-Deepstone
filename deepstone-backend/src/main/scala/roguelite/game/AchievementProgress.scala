package roguelite.game

/** Lifetime counters/sets that back the count- and diversity-based achievement conditions
  * ([[AchievementCondition.RunsCompleted]], [[AchievementCondition.RunsWon]],
  * [[AchievementCondition.WinStreak]], [[AchievementCondition.TotalShardsSpent]],
  * [[AchievementCondition.ConsumablesUsed]], [[AchievementCondition.DistinctPotionTypesUsed]],
  * [[AchievementCondition.DistinctPerksWonWith]]).
  *
  * Persisted across several dedicated tables (`achievement_stats`, `consumable_stats`,
  * `potion_type_used`, `perk_won_with`), not as new columns on `meta` - see [[roguelite.db.Database]]
  * for why. `potionTypesUsed`/`perksWonWith` are append-only sets of ids, not counters - a new id is
  * recorded once and never removed, mirroring `unlocked` on [[AchievementProgress]] below.
  */
case class AchievementStats(
    runsCompleted: Int = 0,
    runsWon: Int = 0,
    currentWinStreak: Int = 0,
    totalShardsSpent: Int = 0,
    consumablesUsed: Int = 0,
    potionTypesUsed: Set[String] = Set.empty,
    perksWonWith: Set[String] = Set.empty
)

object AchievementStats:
  val empty: AchievementStats = AchievementStats()

/** Achievement meta-progression state that persists between runs and survives death - the
  * achievement-system counterpart of [[MetaProgression]].
  *
  * Loaded from SQLite at session start by [[roguelite.db.Database.loadUnlockedAchievements]] /
  * [[roguelite.db.Database.loadAchievementStats]] and kept in a `Ref[IO, AchievementProgress]`
  * inside [[roguelite.engine.GameSession]].
  *
  * @param unlocked
  *   Set of achievement ids permanently earned. Doubles as the "already happened at least once"
  *   flag for conditions like [[AchievementCondition.FirstKill]] - no separate boolean is needed
  *   since checking is inherently first-time-only once an id is in this set.
  * @param stats
  *   Lifetime counters used by count-based conditions.
  */
case class AchievementProgress(
    unlocked: Set[String],
    stats: AchievementStats
):
  def isUnlocked(achievementId: String): Boolean = unlocked.contains(achievementId)

object AchievementProgress:
  val empty: AchievementProgress = AchievementProgress(Set.empty, AchievementStats.empty)
