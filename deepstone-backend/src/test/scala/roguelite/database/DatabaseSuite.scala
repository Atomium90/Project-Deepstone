package roguelite.db

import munit.CatsEffectSuite
import roguelite.game.{ AchievementStats, MetaProgression, UpgradeDef, UpgradeEffect }

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

  // -----------------------------------------------------------------------
  // MetaProgression domain logic (pure — no DB needed)
  // -----------------------------------------------------------------------

  /** Minimal upgrade catalog mirroring upgrades.json — avoids file I/O in unit tests. */
  private val testUpgradeDefs: Map[String, UpgradeDef] = Map(
    "hp_boost_1" -> UpgradeDef("hp_boost_1",
                               "Iron Constitution I",
                               "+20 max HP for the next run",
                               cost = 30,
                               displayOrder = 0,
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
