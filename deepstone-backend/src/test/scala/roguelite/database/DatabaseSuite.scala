package roguelite.db

import munit.CatsEffectSuite
import roguelite.engine.Difficulty
import roguelite.game.{
  AchievementStats,
  CardRecord,
  MetaProgression,
  RunOutcome,
  StoryProgress,
  UpgradeCategory,
  UpgradeDef,
  UpgradeEffect,
  VowRecord,
  VowState
}

/** Tests for [[Database]]: schema initialisation, meta loading, currency persistence,
  * and upgrade purchase atomicity.
  *
  * Each test runs against a fresh in-memory SQLite database via [[Database.inMemory()]].
  * The ResourceFixture guarantees the database is initialised before the test body runs
  * and the HikariCP pool is released cleanly after.
  */
class DatabaseSuite extends CatsEffectSuite:

  // One independent in-memory DB per test
  val db = ResourceFixture(Database.inMemory())

  // -----------------------------------------------------------------------
  // Initialisation
  // -----------------------------------------------------------------------

  db.test("fresh database has zero currency") {
    db =>
      db.loadMeta()
        .map:
          meta => assertEquals(meta.currency, 0)
  }

  db.test("fresh database has no unlocked upgrades") {
    db =>
      db.loadMeta()
        .map:
          meta => assertEquals(meta.unlockedUpgrades, Set.empty[String])
  }

  db.test("initialize is idempotent — safe to call multiple times") {
    db =>
      // initialize() is already called by inMemory(); calling it again should not fail or duplicate rows
      db.initialize() *> db.initialize() *> db
        .loadMeta()
        .map:
          meta => assertEquals(meta.currency, 0)
  }

  // -----------------------------------------------------------------------
  // Currency persistence
  // -----------------------------------------------------------------------

  db.test("saveCurrency persists the new balance") {
    db =>
      for
        _    <- db.saveCurrency(150)
        meta <- db.loadMeta()
      yield assertEquals(meta.currency, 150)
  }

  db.test("saveCurrency overwrites the previous balance") {
    db =>
      for
        _    <- db.saveCurrency(100)
        _    <- db.saveCurrency(75)
        meta <- db.loadMeta()
      yield assertEquals(meta.currency, 75)
  }

  db.test("saveCurrency accepts zero") {
    db =>
      for
        _    <- db.saveCurrency(200)
        _    <- db.saveCurrency(0)
        meta <- db.loadMeta()
      yield assertEquals(meta.currency, 0)
  }

  // -----------------------------------------------------------------------
  // Upgrade purchases
  // -----------------------------------------------------------------------

  db.test("purchaseUpgrade records the upgrade and updates currency atomically") {
    db =>
      for
        _    <- db.saveCurrency(100)
        _    <- db.purchaseUpgrade("hp_boost_1", newCurrency = 70) // cost 30
        meta <- db.loadMeta()
      yield
        assertEquals(meta.currency, 70)
        assert(meta.isUnlocked("hp_boost_1"), "hp_boost_1 should be unlocked")
  }

  db.test("purchaseUpgrade does not affect other upgrades") {
    db =>
      for
        _    <- db.saveCurrency(200)
        _    <- db.purchaseUpgrade("hp_boost_1", newCurrency = 170)
        meta <- db.loadMeta()
      yield
        assert(!meta.isUnlocked("hp_boost_2"), "hp_boost_2 should NOT be unlocked")
        assert(!meta.isUnlocked("potion_start"), "potion_start should NOT be unlocked")
        assert(!meta.isUnlocked("archer_unlock"), "archer_unlock should NOT be unlocked")
  }

  db.test("purchaseUpgrade is idempotent — INSERT OR IGNORE prevents duplicates") {
    db =>
      for
        _ <- db.saveCurrency(200)
        _ <- db.purchaseUpgrade("hp_boost_1", newCurrency = 170)
        _ <- db.purchaseUpgrade("hp_boost_1",
                                newCurrency = 100
        ) // second purchase overrides currency
        meta <- db.loadMeta()
      yield
        // Upgrade is still unlocked (only once in the set)
        assert(meta.isUnlocked("hp_boost_1"))
        assertEquals(meta.unlockedUpgrades.count(_ == "hp_boost_1"), 1)
  }

  db.test("multiple upgrades can be purchased independently") {
    db =>
      for
        _    <- db.saveCurrency(500)
        _    <- db.purchaseUpgrade("hp_boost_1", newCurrency = 470)
        _    <- db.purchaseUpgrade("archer_unlock", newCurrency = 420)
        _    <- db.purchaseUpgrade("mage_unlock", newCurrency = 340)
        meta <- db.loadMeta()
      yield
        assertEquals(meta.currency, 340)
        assertEquals(meta.unlockedUpgrades, Set("hp_boost_1", "archer_unlock", "mage_unlock"))
  }

  // -----------------------------------------------------------------------
  // Achievement unlocks and lifetime stats
  // -----------------------------------------------------------------------

  db.test("fresh database has no unlocked achievements") {
    db =>
      db.loadUnlockedAchievements()
        .map:
          unlocked => assertEquals(unlocked, Set.empty[String])
  }

  db.test("fresh database has zero-valued achievement stats") {
    db =>
      db.loadAchievementStats()
        .map:
          stats => assertEquals(stats, AchievementStats.empty)
  }

  db.test("unlockAchievement records the achievement") {
    db =>
      for
        _        <- db.unlockAchievement("first_blood")
        unlocked <- db.loadUnlockedAchievements()
      yield assert(unlocked.contains("first_blood"), "first_blood should be unlocked")
  }

  db.test("unlockAchievement is idempotent — INSERT OR IGNORE prevents duplicates") {
    db =>
      for
        _        <- db.unlockAchievement("first_blood")
        _        <- db.unlockAchievement("first_blood")
        unlocked <- db.loadUnlockedAchievements()
      yield assertEquals(unlocked.count(_ == "first_blood"), 1)
  }

  db.test("multiple achievements unlock independently") {
    db =>
      for
        _        <- db.unlockAchievement("first_blood")
        _        <- db.unlockAchievement("boss_slayer")
        unlocked <- db.loadUnlockedAchievements()
      yield assertEquals(unlocked, Set("first_blood", "boss_slayer"))
  }

  db.test("saveAchievementStats persists and round-trips every counter") {
    db =>
      val stats = AchievementStats(runsCompleted = 3, runsWon = 2, currentWinStreak = 2, totalShardsSpent = 150)
      for
        _      <- db.saveAchievementStats(stats)
        loaded <- db.loadAchievementStats()
      yield assertEquals(loaded, stats)
  }

  db.test("saveAchievementStats overwrites the previous values") {
    db =>
      for
        _      <- db.saveAchievementStats(AchievementStats(runsCompleted = 1, runsWon = 1, currentWinStreak = 1, totalShardsSpent = 50))
        _      <- db.saveAchievementStats(AchievementStats(runsCompleted = 5, runsWon = 0, currentWinStreak = 0, totalShardsSpent = 300))
        loaded <- db.loadAchievementStats()
      yield assertEquals(loaded, AchievementStats(runsCompleted = 5, runsWon = 0, currentWinStreak = 0, totalShardsSpent = 300))
  }

  db.test("saveAchievementStats round-trips consumablesUsed, a separate table from the other counters") {
    db =>
      for
        _      <- db.saveAchievementStats(AchievementStats(consumablesUsed = 7))
        loaded <- db.loadAchievementStats()
      yield assertEquals(loaded.consumablesUsed, 7)
  }

  db.test("saveAchievementStats round-trips the wins of every difficulty") {
    db =>
      val stats = AchievementStats(runsWon = 3, winsByDifficulty = Map(Difficulty.Easy -> 2, Difficulty.Hard -> 1))
      for
        _      <- db.saveAchievementStats(stats)
        loaded <- db.loadAchievementStats()
      yield assertEquals(loaded.winsByDifficulty, stats.winsByDifficulty)
  }

  db.test("saveAchievementStats overwrites the wins of a difficulty and keeps the others") {
    db =>
      for
        _ <- db.saveAchievementStats(
          AchievementStats(winsByDifficulty = Map(Difficulty.Easy -> 1, Difficulty.Normal -> 4))
        )
        _      <- db.saveAchievementStats(AchievementStats(winsByDifficulty = Map(Difficulty.Normal -> 5)))
        loaded <- db.loadAchievementStats()
      yield assertEquals(loaded.winsByDifficulty, Map(Difficulty.Easy -> 1, Difficulty.Normal -> 5))
  }

  db.test("initialize can run again without losing the wins of a difficulty") {
    db =>
      for
        _      <- db.saveAchievementStats(AchievementStats(winsByDifficulty = Map(Difficulty.Normal -> 2)))
        _      <- db.initialize()
        loaded <- db.loadAchievementStats()
      yield assertEquals(loaded.winsByDifficulty, Map(Difficulty.Normal -> 2))
  }

  db.test("recordPotionTypeUsed adds the typeId, idempotently") {
    db =>
      for
        _      <- db.recordPotionTypeUsed("health_potion")
        _      <- db.recordPotionTypeUsed("health_potion")
        _      <- db.recordPotionTypeUsed("second_wind")
        loaded <- db.loadAchievementStats()
      yield assertEquals(loaded.potionTypesUsed, Set("health_potion", "second_wind"))
  }

  db.test("recordPerkWonWith adds the perk id, idempotently") {
    db =>
      for
        _      <- db.recordPerkWonWith("heavy_hand")
        _      <- db.recordPerkWonWith("heavy_hand")
        _      <- db.recordPerkWonWith("lucky_find")
        loaded <- db.loadAchievementStats()
      yield assertEquals(loaded.perksWonWith, Set("heavy_hand", "lucky_find"))
  }

  // -----------------------------------------------------------------------
  // Story progress
  // -----------------------------------------------------------------------

  db.test("a fresh database has no story progress") {
    db =>
      db.loadStoryProgress().map(progress => assertEquals(progress, StoryProgress.empty))
  }

  db.test("saveStoryState round-trips the chapter and the outcome of the last run") {
    db =>
      for
        _      <- db.saveStoryState(3, Some(RunOutcome.Lost))
        loaded <- db.loadStoryProgress()
      yield
        assertEquals(loaded.chapter, 3)
        assertEquals(loaded.lastRun, Some(RunOutcome.Lost))
  }

  db.test("saveStoryState overwrites the previous values and can clear the last run") {
    db =>
      for
        _      <- db.saveStoryState(2, Some(RunOutcome.Won))
        _      <- db.saveStoryState(4, None)
        loaded <- db.loadStoryProgress()
      yield
        assertEquals(loaded.chapter, 4)
        assertEquals(loaded.lastRun, None)
  }

  db.test("recordSceneSeen adds the scene id, idempotently") {
    db =>
      for
        _      <- db.recordSceneSeen("npc.1.s1")
        _      <- db.recordSceneSeen("npc.1.s1")
        _      <- db.recordSceneSeen("npc.1.s2")
        loaded <- db.loadStoryProgress()
      yield assertEquals(loaded.scenesSeen, Set("npc.1.s1", "npc.1.s2"))
  }

  db.test("saveVow round-trips an NPC's vow, awaiting its report or not") {
    db =>
      for
        _      <- db.saveVow("npc.1", VowRecord(VowState.Failed, reported = false))
        _      <- db.saveVow("npc.2", VowRecord(VowState.Declined))
        loaded <- db.loadStoryProgress()
      yield assertEquals(loaded.vows,
                         Map("npc.1" -> VowRecord(VowState.Failed, reported = false),
                             "npc.2" -> VowRecord(VowState.Declined)
                         )
      )
  }

  db.test("saveVow replaces the vow an NPC had and leaves the others alone") {
    db =>
      for
        _      <- db.saveVow("npc.1", VowRecord(VowState.Active))
        _      <- db.saveVow("npc.2", VowRecord(VowState.Kept))
        _      <- db.saveVow("npc.1", VowRecord(VowState.Kept, reported = false))
        loaded <- db.loadStoryProgress()
      yield assertEquals(loaded.vows,
                         Map("npc.1" -> VowRecord(VowState.Kept, reported = false), "npc.2" -> VowRecord(VowState.Kept))
      )
  }

  db.test("saveDeathStreak round-trips the number of deaths in a row and can be reset") {
    db =>
      for
        _     <- db.saveDeathStreak(4)
        first <- db.loadStoryProgress()
        _     <- db.saveDeathStreak(0)
        again <- db.loadStoryProgress()
      yield assertEquals((first.deathStreak, again.deathStreak), (4, 0))
  }

  db.test("Hardcore is locked on a fresh save, and once the story opens it stays opened") {
    db =>
      for
        fresh  <- db.loadStoryProgress()
        _      <- db.saveHardcoreUnlocked()
        _      <- db.saveHardcoreUnlocked()
        _      <- db.initialize()
        loaded <- db.loadStoryProgress()
      yield
        assertEquals(fresh.hardcoreUnlocked, false)
        assertEquals(fresh.openedTiers, Set.empty[Difficulty])
        assertEquals(loaded.hardcoreUnlocked, true)
        assertEquals(loaded.openedTiers, Set(Difficulty.Hardcore))
  }

  db.test("recordRelicRead remembers each Devoted whose relic was read, idempotently") {
    db =>
      for
        fresh  <- db.loadStoryProgress()
        _      <- db.recordRelicRead(4)
        _      <- db.recordRelicRead(4)
        _      <- db.recordRelicRead(1)
        loaded <- db.loadStoryProgress()
      yield
        assertEquals(fresh.relicsRead, Set.empty[Int])
        assertEquals(loaded.relicsRead, Set(1, 4))
  }

  db.test("recordCardEarned keeps the cards in the order they were earned, idempotently") {
    db =>
      for
        _      <- db.recordCardEarned("ch1")
        _      <- db.recordCardEarned("prologue")
        _      <- db.recordCardEarned("ch1")
        loaded <- db.loadStoryProgress()
      yield assertEquals(loaded.cards, List(CardRecord("ch1"), CardRecord("prologue")))
  }

  db.test("markCardSeen marks only that card, and a card seen stays seen when earned again") {
    db =>
      for
        _      <- db.recordCardEarned("prologue")
        _      <- db.recordCardEarned("ch1")
        _      <- db.markCardSeen("prologue")
        _      <- db.recordCardEarned("prologue")
        loaded <- db.loadStoryProgress()
      yield assertEquals(loaded.cards, List(CardRecord("prologue", seen = true), CardRecord("ch1")))
  }

  db.test("markCardSeen does not create a card that was never earned") {
    db =>
      for
        _      <- db.markCardSeen("ch3")
        loaded <- db.loadStoryProgress()
      yield assertEquals(loaded.cards, Nil)
  }

  db.test("initialize can run again without losing the story progress") {
    db =>
      for
        _      <- db.saveStoryState(2, Some(RunOutcome.Won))
        _      <- db.recordSceneSeen("npc.1.s1")
        _      <- db.saveVow("npc.1", VowRecord(VowState.Active))
        _      <- db.recordCardEarned("ch1")
        _      <- db.saveDeathStreak(2)
        _      <- db.recordRelicRead(3)
        _      <- db.initialize()
        loaded <- db.loadStoryProgress()
      yield
        assertEquals(loaded.chapter, 2)
        assertEquals(loaded.scenesSeen, Set("npc.1.s1"))
        assertEquals(loaded.vows, Map("npc.1" -> VowRecord(VowState.Active)))
        assertEquals(loaded.cards, List(CardRecord("ch1")))
        assertEquals(loaded.deathStreak, 2)
        assertEquals(loaded.relicsRead, Set(3))
  }

  // -----------------------------------------------------------------------
  // MetaProgression domain logic (pure, no DB needed)
  // -----------------------------------------------------------------------

  /** Minimal upgrade catalog mirroring upgrades.json, avoids file I/O in unit tests. */
  private val testUpgradeDefs: Map[String, UpgradeDef] = Map(
    "hp_boost_1" -> UpgradeDef("hp_boost_1",
                               "Iron Constitution I",
                               "+20 max HP for the next run",
                               cost = 30,
                               displayOrder = 0,
                               icon = "❤",
                               category = UpgradeCategory.Stat,
                               effect = UpgradeEffect.MaxHpBoost(20)
    )
  )

  test("MetaProgression.purchase deducts cost and records upgrade") {
    val meta = MetaProgression(currency = 80, unlockedUpgrades = Set.empty)
    meta.purchase("hp_boost_1", testUpgradeDefs) match
      case Right(newMeta) =>
        assertEquals(newMeta.currency, 50) // 80 - 30
        assert(newMeta.isUnlocked("hp_boost_1"))
      case Left(err) => fail(s"unexpected error: $err")
  }

  test("MetaProgression.purchase fails when already unlocked") {
    val meta = MetaProgression(currency = 200, unlockedUpgrades = Set("hp_boost_1"))
    meta.purchase("hp_boost_1", testUpgradeDefs) match
      case Left(msg) => assert(msg.contains("already purchased"), s"unexpected message: $msg")
      case Right(_)  => fail("expected Left for already-unlocked upgrade")
  }

  test("MetaProgression.purchase fails when currency is insufficient") {
    val meta = MetaProgression(currency = 10, unlockedUpgrades = Set.empty)
    meta.purchase("hp_boost_1", testUpgradeDefs) match // costs 30
      case Left(msg) => assert(msg.contains("Not enough"), s"unexpected message: $msg")
      case Right(_)  => fail("expected Left for insufficient currency")
  }

  test("MetaProgression.purchase fails for unknown upgradeId") {
    val meta = MetaProgression(currency = 9999, unlockedUpgrades = Set.empty)
    meta.purchase("nonexistent_upgrade", testUpgradeDefs) match
      case Left(msg) => assert(msg.contains("Unknown"), s"unexpected message: $msg")
      case Right(_)  => fail("expected Left for unknown upgrade")
  }
