package roguelite.game

import munit.FunSuite

/** Tests for [[AchievementChecker]]: pure logic, no DB, no GameState fixtures needed - every fact
  * needed is already embedded in the [[GameEvent]] or passed explicitly.
  */
class AchievementCheckerSuite extends FunSuite:

  private def defOf(id: String, condition: AchievementCondition): AchievementDef =
    AchievementDef(id, id, id, displayOrder = 0, condition)

  private val firstBlood    = defOf("first_blood", AchievementCondition.FirstKill)
  private val bossSlayer    = defOf("boss_slayer", AchievementCondition.DefeatBoss)
  private val untouchable   = defOf("untouchable", AchievementCondition.NoDamageVictory)
  private val level5        = defOf("level_5", AchievementCondition.ReachLevel(5))
  private val packrat       = defOf("packrat", AchievementCondition.FillInventory)
  private val keyMaster     = defOf("key_master", AchievementCondition.UnlockDoorWithKey)
  private val secretFinder  = defOf("secret_finder", AchievementCondition.RevealSecretDoor)
  private val veteran       = defOf("veteran", AchievementCondition.RunsCompleted(5))
  private val champion      = defOf("champion", AchievementCondition.RunsWon(5))
  private val winStreak     = defOf("win_streak", AchievementCondition.WinStreak(5))
  private val bigSpender    = defOf("big_spender", AchievementCondition.TotalShardsSpent(200))
  private val completionist = defOf("completionist", AchievementCondition.AllUpgradesUnlocked)

  private val allDefs: Map[String, AchievementDef] = Map(
    firstBlood.id    -> firstBlood,
    bossSlayer.id    -> bossSlayer,
    untouchable.id   -> untouchable,
    level5.id        -> level5,
    packrat.id       -> packrat,
    keyMaster.id     -> keyMaster,
    secretFinder.id  -> secretFinder,
    veteran.id       -> veteran,
    champion.id      -> champion,
    winStreak.id     -> winStreak,
    bigSpender.id    -> bigSpender,
    completionist.id -> completionist
  )

  // --- checkEvents: single-event conditions ---------------------------------

  test("EnemyDefeated unlocks first_blood when not already unlocked") {
    val (_, unlocked) = AchievementChecker.checkEvents(
      allDefs,
      Set.empty,
      AchievementStats.empty,
      List(GameEvent.EnemyDefeated(isBoss = false, tookNoDamage = false))
    )
    assertEquals(unlocked.map(_.id), List("first_blood"))
  }

  test("EnemyDefeated does not re-unlock first_blood once already unlocked") {
    val (_, unlocked) = AchievementChecker.checkEvents(
      allDefs,
      Set("first_blood"),
      AchievementStats.empty,
      List(GameEvent.EnemyDefeated(isBoss = false, tookNoDamage = false))
    )
    assertEquals(unlocked, Nil)
  }

  test("EnemyDefeated(isBoss = true) unlocks boss_slayer") {
    val (_, unlocked) = AchievementChecker.checkEvents(
      allDefs,
      Set("first_blood"),
      AchievementStats.empty,
      List(GameEvent.EnemyDefeated(isBoss = true, tookNoDamage = false))
    )
    assert(unlocked.map(_.id).contains("boss_slayer"))
  }

  test("EnemyDefeated(isBoss = false) does not unlock boss_slayer") {
    val (_, unlocked) = AchievementChecker.checkEvents(
      allDefs,
      Set("first_blood"),
      AchievementStats.empty,
      List(GameEvent.EnemyDefeated(isBoss = false, tookNoDamage = false))
    )
    assert(!unlocked.map(_.id).contains("boss_slayer"))
  }

  test("EnemyDefeated(tookNoDamage = true) unlocks untouchable") {
    val (_, unlocked) = AchievementChecker.checkEvents(
      allDefs,
      Set("first_blood"),
      AchievementStats.empty,
      List(GameEvent.EnemyDefeated(isBoss = false, tookNoDamage = true))
    )
    assert(unlocked.map(_.id).contains("untouchable"))
  }

  test("EnemyDefeated(tookNoDamage = false) does not unlock untouchable") {
    val (_, unlocked) = AchievementChecker.checkEvents(
      allDefs,
      Set("first_blood"),
      AchievementStats.empty,
      List(GameEvent.EnemyDefeated(isBoss = false, tookNoDamage = false))
    )
    assert(!unlocked.map(_.id).contains("untouchable"))
  }

  test("LeveledUp(5) unlocks level_5, LeveledUp(4) does not") {
    val (_, unlockedAt5) =
      AchievementChecker.checkEvents(allDefs, Set.empty, AchievementStats.empty, List(GameEvent.LeveledUp(5)))
    assert(unlockedAt5.map(_.id).contains("level_5"))

    val (_, unlockedAt4) =
      AchievementChecker.checkEvents(allDefs, Set.empty, AchievementStats.empty, List(GameEvent.LeveledUp(4)))
    assert(!unlockedAt4.map(_.id).contains("level_5"))
  }

  test("ItemPickedUp(inventoryFull = true) unlocks packrat, false does not") {
    val (_, full) = AchievementChecker.checkEvents(
      allDefs,
      Set.empty,
      AchievementStats.empty,
      List(GameEvent.ItemPickedUp(inventoryFull = true))
    )
    assert(full.map(_.id).contains("packrat"))

    val (_, notFull) = AchievementChecker.checkEvents(
      allDefs,
      Set.empty,
      AchievementStats.empty,
      List(GameEvent.ItemPickedUp(inventoryFull = false))
    )
    assert(!notFull.map(_.id).contains("packrat"))
  }

  test("DoorUnlockedWithKey unlocks key_master") {
    val (_, unlocked) = AchievementChecker.checkEvents(
      allDefs,
      Set.empty,
      AchievementStats.empty,
      List(GameEvent.DoorUnlockedWithKey)
    )
    assertEquals(unlocked.map(_.id), List("key_master"))
  }

  test("SecretDoorRevealed unlocks secret_finder") {
    val (_, unlocked) = AchievementChecker.checkEvents(
      allDefs,
      Set.empty,
      AchievementStats.empty,
      List(GameEvent.SecretDoorRevealed)
    )
    assertEquals(unlocked.map(_.id), List("secret_finder"))
  }

  // --- checkEvents: RunEnded counter bookkeeping ----------------------------

  test("RunEnded(false) increments runsCompleted and resets the win streak") {
    val startingStats = AchievementStats(runsCompleted = 4, runsWon = 3, currentWinStreak = 3)
    val (stats, _) = AchievementChecker.checkEvents(
      allDefs,
      Set.empty,
      startingStats,
      List(GameEvent.RunEnded(victory = false))
    )
    assertEquals(stats.runsCompleted, 5)
    assertEquals(stats.runsWon, 3)
    assertEquals(stats.currentWinStreak, 0)
  }

  test("RunEnded(true) increments runsCompleted, runsWon, and the win streak") {
    val startingStats = AchievementStats(runsCompleted = 4, runsWon = 3, currentWinStreak = 3)
    val (stats, _) = AchievementChecker.checkEvents(
      allDefs,
      Set.empty,
      startingStats,
      List(GameEvent.RunEnded(victory = true))
    )
    assertEquals(stats.runsCompleted, 5)
    assertEquals(stats.runsWon, 4)
    assertEquals(stats.currentWinStreak, 4)
  }

  test("a win that crosses all three run-count thresholds unlocks veteran, champion, and win_streak together") {
    val startingStats = AchievementStats(runsCompleted = 4, runsWon = 4, currentWinStreak = 4)
    val (_, unlocked) = AchievementChecker.checkEvents(
      allDefs,
      Set.empty,
      startingStats,
      List(GameEvent.RunEnded(victory = true))
    )
    assertEquals(unlocked.map(_.id).toSet, Set("veteran", "champion", "win_streak"))
  }

  test("a loss that breaks a 4-win streak, later followed by a 5th total win, does not unlock win_streak") {
    val (statsAfterLoss, unlockedAfterLoss) =
      AchievementChecker.checkEvents(
        allDefs,
        Set.empty,
        AchievementStats(runsCompleted = 4, runsWon = 4, currentWinStreak = 4),
        List(GameEvent.RunEnded(victory = false))
      )
    // runsCompleted also increments on a loss, so veteran (5 total runs, win or lose) legitimately
    // unlocks here - only win_streak/champion (win-gated) must NOT unlock from a loss.
    assertEquals(unlockedAfterLoss.map(_.id), List("veteran"))
    assertEquals(statsAfterLoss.currentWinStreak, 0)

    val (_, unlockedAfterNextWin) = AchievementChecker.checkEvents(
      allDefs,
      Set("veteran"), // already persisted after the loss, per the production GameSession flow
      statsAfterLoss,
      List(GameEvent.RunEnded(victory = true))
    )
    assert(!unlockedAfterNextWin.map(_.id).contains("win_streak"),
           s"win_streak should not unlock right after a broken streak: $unlockedAfterNextWin"
    )
  }

  test("multiple events in one list accumulate distinct achievements without duplicates") {
    val (_, unlocked) = AchievementChecker.checkEvents(
      allDefs,
      Set.empty,
      AchievementStats.empty,
      List(
        GameEvent.EnemyDefeated(isBoss = true, tookNoDamage = true),
        GameEvent.LeveledUp(5)
      )
    )
    assertEquals(unlocked.map(_.id).toSet, Set("first_blood", "boss_slayer", "untouchable", "level_5"))
    assertEquals(unlocked.map(_.id).distinct.length, unlocked.length)
  }

  // --- checkPurchase ---------------------------------------------------------

  test("checkPurchase accumulates spend across sequential calls and unlocks big_spender at the threshold") {
    val (statsAfterFirst, unlockedAfterFirst) =
      AchievementChecker.checkPurchase(allDefs, Set.empty, AchievementStats.empty, spent = 150, 1, 6)
    assertEquals(statsAfterFirst.totalShardsSpent, 150)
    assertEquals(unlockedAfterFirst, Nil)

    val (statsAfterSecond, unlockedAfterSecond) =
      AchievementChecker.checkPurchase(allDefs, Set.empty, statsAfterFirst, spent = 60, 2, 6)
    assertEquals(statsAfterSecond.totalShardsSpent, 210)
    assertEquals(unlockedAfterSecond.map(_.id), List("big_spender"))
  }

  test("checkPurchase unlocks completionist only when every upgrade is unlocked") {
    val (_, partiallyUnlocked) =
      AchievementChecker.checkPurchase(allDefs, Set.empty, AchievementStats.empty, spent = 10, 5, 6)
    assert(!partiallyUnlocked.map(_.id).contains("completionist"))

    val (_, fullyUnlocked) =
      AchievementChecker.checkPurchase(allDefs, Set.empty, AchievementStats.empty, spent = 10, 6, 6)
    assert(fullyUnlocked.map(_.id).contains("completionist"))
  }

  test("checkPurchase never unlocks completionist against an empty upgrade catalog") {
    val (_, unlocked) =
      AchievementChecker.checkPurchase(allDefs, Set.empty, AchievementStats.empty, spent = 0, 0, 0)
    assert(!unlocked.map(_.id).contains("completionist"))
  }
