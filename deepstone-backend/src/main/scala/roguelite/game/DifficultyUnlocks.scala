package roguelite.game

import roguelite.engine.Difficulty

/** Which difficulty tiers a save has unlocked, from the tier catalog (see [[DifficultyTierDef]])
  * and the lifetime stats. Pure: the server derives the answer from the stats every time it needs
  * it, so nothing is stored that could drift from them.
  */
object DifficultyUnlocks:

  /** How far a save is from one tier's requirement. */
  case class Progress(requirement: TierRequirement, winsDone: Int, shardsSpentDone: Int):
    def met: Boolean = winsDone >= requirement.wins && shardsSpentDone >= requirement.shardsSpent

  /** Victories on `difficulty`. A victory recorded before wins were counted per tier belongs to no
    * tier (see [[AchievementStats.winsByDifficulty]]) and counts for the lowest one, so a save that
    * had already won keeps the tiers that first win opens.
    */
  def winsOn(difficulty: Difficulty, stats: AchievementStats): Int =
    val recorded = stats.winsByDifficulty.getOrElse(difficulty, 0)
    if difficulty == Difficulty.values.head then recorded + untrackedWins(stats) else recorded

  private def untrackedWins(stats: AchievementStats): Int =
    (stats.runsWon - stats.winsByDifficulty.values.sum).max(0)

  /** The save's progress toward `difficulty`, or `None` for a tier that needs nothing (or that the
    * catalog does not list).
    */
  def progress(catalog: Map[Difficulty, DifficultyTierDef],
               difficulty: Difficulty,
               stats: AchievementStats
  ): Option[Progress] =
    catalog.get(difficulty).flatMap(_.requirement).map {
      requirement => Progress(requirement, winsOn(requirement.winsAt, stats), stats.totalShardsSpent)
    }

  /** Whether `difficulty` can be started. A tier with no requirement, or absent from the catalog,
    * is always available. A tier only the story opens (see [[DifficultyTierDef.storyLocked]]) is
    * available once the story has opened it, whatever the stats say.
    *
    * @param storyOpened
    *   The tiers the story has opened so far (see [[StoryProgress.openedTiers]]).
    */
  def isUnlocked(catalog: Map[Difficulty, DifficultyTierDef],
                 difficulty: Difficulty,
                 stats: AchievementStats,
                 storyOpened: Set[Difficulty] = Set.empty
  ): Boolean =
    if catalog.get(difficulty).exists(_.storyLocked) then storyOpened.contains(difficulty)
    else progress(catalog, difficulty, stats).forall(_.met)

  def unlockedTiers(catalog: Map[Difficulty, DifficultyTierDef],
                    stats: AchievementStats,
                    storyOpened: Set[Difficulty] = Set.empty
  ): Set[Difficulty] =
    Difficulty.values.filter(isUnlocked(catalog, _, stats, storyOpened)).toSet

  /** Whether the hub shows `difficulty`: every tier, except one only the story opens that it has not
    * opened yet, so that nothing in the hub gives the story away.
    */
  def isShown(catalog: Map[Difficulty, DifficultyTierDef], difficulty: Difficulty, storyOpened: Set[Difficulty]): Boolean =
    !catalog.get(difficulty).exists(_.storyLocked) || storyOpened.contains(difficulty)
