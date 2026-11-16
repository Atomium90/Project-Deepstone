package roguelite.game

import munit.FunSuite
import roguelite.engine.Difficulty

/** Tests for [[DifficultyUnlocks]]. The catalog mirrors the shipped rules (Normal after a win on
  * Easy, Hard after 4 wins on Normal and 300 Shards spent, Hardcore only when the story opens it)
  * but is a fixture of its own, so a change to `data/difficulty.json` never breaks these.
  */
class DifficultyUnlocksSuite extends FunSuite:

  private val catalog: Map[Difficulty, DifficultyTierDef] = Map(
    Difficulty.Easy -> DifficultyTierDef(Difficulty.Easy, None, List("a")),
    Difficulty.Normal -> DifficultyTierDef(Difficulty.Normal, Some(TierRequirement(Difficulty.Easy, 1, 0)), List("a", "b")),
    Difficulty.Hard -> DifficultyTierDef(Difficulty.Hard, Some(TierRequirement(Difficulty.Normal, 4, 300)), List("a", "b", "b")),
    Difficulty.Hardcore -> DifficultyTierDef(Difficulty.Hardcore, None, List("a", "b", "b"), storyLocked = true)
  )

  private def unlocked(stats: AchievementStats): Set[Difficulty] = DifficultyUnlocks.unlockedTiers(catalog, stats)

  private val everything =
    AchievementStats(runsWon = 50, totalShardsSpent = 5000, winsByDifficulty = Map(Difficulty.Normal -> 50, Difficulty.Hard -> 20))

  test("a tier only the story opens stays locked however much has been won and spent"):
    assert(!DifficultyUnlocks.isUnlocked(catalog, Difficulty.Hardcore, everything))
    assert(!DifficultyUnlocks.unlockedTiers(catalog, everything).contains(Difficulty.Hardcore))

  test("a tier only the story opens is unlocked once the story has opened it, and the others do not change"):
    val opened = Set(Difficulty.Hardcore)
    assert(DifficultyUnlocks.isUnlocked(catalog, Difficulty.Hardcore, AchievementStats.empty, opened))
    assertEquals(DifficultyUnlocks.unlockedTiers(catalog, AchievementStats.empty, opened), Set(Difficulty.Easy, Difficulty.Hardcore))

  test("the story opening a tier does not open the tiers that victories open"):
    assert(!DifficultyUnlocks.isUnlocked(catalog, Difficulty.Normal, AchievementStats.empty, Set(Difficulty.Hardcore)))

  test("the hub shows every tier but a story tier the story has not opened yet"):
    assert(DifficultyUnlocks.isShown(catalog, Difficulty.Hard, Set.empty))
    assert(!DifficultyUnlocks.isShown(catalog, Difficulty.Hardcore, Set.empty))
    assert(DifficultyUnlocks.isShown(catalog, Difficulty.Hardcore, Set(Difficulty.Hardcore)))

  test("a tier the catalog does not list is shown"):
    assert(DifficultyUnlocks.isShown(catalog - Difficulty.Hardcore, Difficulty.Hardcore, Set.empty))

  test("a new save has only the lowest tier"):
    assertEquals(unlocked(AchievementStats.empty), Set(Difficulty.Easy))

  test("a win on Easy opens Normal, not Hard"):
    val stats = AchievementStats(runsWon = 1, winsByDifficulty = Map(Difficulty.Easy -> 1))
    assertEquals(unlocked(stats), Set(Difficulty.Easy, Difficulty.Normal))

  test("a win on a tier other than the one required does not count"):
    val stats = AchievementStats(runsWon = 1, winsByDifficulty = Map(Difficulty.Hard -> 1))
    assert(!DifficultyUnlocks.isUnlocked(catalog, Difficulty.Normal, stats))

  test("Hard needs every win on Normal"):
    val stats = AchievementStats(runsWon = 3, totalShardsSpent = 500, winsByDifficulty = Map(Difficulty.Normal -> 3))
    assert(!DifficultyUnlocks.isUnlocked(catalog, Difficulty.Hard, stats))

  test("Hard needs every Shard of the requirement spent"):
    val stats = AchievementStats(runsWon = 4, totalShardsSpent = 299, winsByDifficulty = Map(Difficulty.Normal -> 4))
    assert(!DifficultyUnlocks.isUnlocked(catalog, Difficulty.Hard, stats))

  test("Hard opens once both the wins and the Shards are there"):
    val stats = AchievementStats(
      runsWon = 5,
      totalShardsSpent = 300,
      winsByDifficulty = Map(Difficulty.Easy -> 1, Difficulty.Normal -> 4)
    )
    assertEquals(unlocked(stats), Set(Difficulty.Easy, Difficulty.Normal, Difficulty.Hard))

  test("a tier needs a win on its own required tier, not on a higher one"):
    val stats = AchievementStats(runsWon = 4, totalShardsSpent = 300, winsByDifficulty = Map(Difficulty.Hard -> 4))
    assert(!DifficultyUnlocks.isUnlocked(catalog, Difficulty.Hard, stats))

  test("victories from before wins were counted per tier count for the lowest tier"):
    val stats = AchievementStats(runsWon = 6)
    assertEquals(DifficultyUnlocks.winsOn(Difficulty.Easy, stats), 6)
    assertEquals(DifficultyUnlocks.winsOn(Difficulty.Normal, stats), 0)
    assertEquals(unlocked(stats), Set(Difficulty.Easy, Difficulty.Normal))

  test("only the victories no tier accounts for are credited to the lowest tier"):
    val stats = AchievementStats(runsWon = 5, winsByDifficulty = Map(Difficulty.Easy -> 1, Difficulty.Normal -> 3))
    assertEquals(DifficultyUnlocks.winsOn(Difficulty.Easy, stats), 2)
    assertEquals(DifficultyUnlocks.winsOn(Difficulty.Normal, stats), 3)

  test("a tier counter above the total never credits a negative number of wins"):
    val stats = AchievementStats(runsWon = 1, winsByDifficulty = Map(Difficulty.Normal -> 3))
    assertEquals(DifficultyUnlocks.winsOn(Difficulty.Easy, stats), 0)

  test("progress reports what is done against what is required"):
    val stats    = AchievementStats(runsWon = 2, totalShardsSpent = 120, winsByDifficulty = Map(Difficulty.Normal -> 2))
    val progress = DifficultyUnlocks.progress(catalog, Difficulty.Hard, stats).get
    assertEquals(progress, DifficultyUnlocks.Progress(TierRequirement(Difficulty.Normal, 4, 300), 2, 120))
    assert(!progress.met)

  test("a tier that needs nothing has no progress to report"):
    assertEquals(DifficultyUnlocks.progress(catalog, Difficulty.Easy, AchievementStats.empty), None)

  test("a tier missing from the catalog is available"):
    assertEquals(DifficultyUnlocks.unlockedTiers(Map.empty, AchievementStats.empty), Difficulty.values.toSet)
