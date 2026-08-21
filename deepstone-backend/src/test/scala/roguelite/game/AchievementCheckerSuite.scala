package roguelite.game

import munit.FunSuite
import roguelite.engine.Difficulty

/** Tests for [[AchievementChecker]]: pure logic, no DB, no GameState fixtures needed - every fact
  * needed is already embedded in the [[GameEvent]] or passed explicitly.
  */
class AchievementCheckerSuite extends FunSuite:

  private def defOf(id: String, condition: AchievementCondition): AchievementDef =
    AchievementDef(id, id, id, displayOrder = 0, condition)

  /** Builds an ItemPickedUp event with every field defaulted to its "nothing notable happened"
    * value, so each test only names the one field its condition actually cares about. */
  private def itemPickedUp(inventoryFull: Boolean = false,
                           rarity: Rarity = Rarity.Common,
                           hasFourPieceSet: Boolean = false,
                           potionBeltFull: Boolean = false,
                           stackAtCapacity: Boolean = false
  ): GameEvent =
    GameEvent.ItemPickedUp(inventoryFull, rarity, hasFourPieceSet, potionBeltFull, stackAtCapacity)

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
  private val epicFind      = defOf("epic_find", AchievementCondition.LootRarity(Rarity.Epic))
  private val setComplete   = defOf("set_complete", AchievementCondition.FourPieceSetActive)
  private val fullBelt      = defOf("full_belt", AchievementCondition.FillPotionBelt)
  private val stockpiler    = defOf("stockpiler", AchievementCondition.FillPotionStack)
  private val hardModeVictory =
    defOf("hard_mode_victory", AchievementCondition.WinOnDifficulty(Difficulty.Hard))
  private val potionMaster = defOf("potion_master", AchievementCondition.ConsumablesUsed(10))
  private val potionConnoisseur =
    defOf("potion_connoisseur", AchievementCondition.DistinctPotionTypesUsed(5))
  private val jackOfAllTrades =
    defOf("jack_of_all_trades", AchievementCondition.DistinctPerksWonWith(5))

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
    completionist.id -> completionist,
    epicFind.id      -> epicFind,
    setComplete.id   -> setComplete,
    fullBelt.id      -> fullBelt,
    stockpiler.id    -> stockpiler,
    hardModeVictory.id -> hardModeVictory,
    potionMaster.id -> potionMaster,
    potionConnoisseur.id -> potionConnoisseur,
    jackOfAllTrades.id -> jackOfAllTrades
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
      List(itemPickedUp(inventoryFull = true))
    )
    assert(full.map(_.id).contains("packrat"))

    val (_, notFull) = AchievementChecker.checkEvents(
      allDefs,
      Set.empty,
      AchievementStats.empty,
      List(itemPickedUp(inventoryFull = false))
    )
    assert(!notFull.map(_.id).contains("packrat"))
  }

  test("ItemPickedUp(rarity = Epic) unlocks epic_find, a lower rarity does not") {
    val (_, epic) = AchievementChecker.checkEvents(
      allDefs,
      Set.empty,
      AchievementStats.empty,
      List(itemPickedUp(rarity = Rarity.Epic))
    )
    assert(epic.map(_.id).contains("epic_find"))

    val (_, rare) = AchievementChecker.checkEvents(
      allDefs,
      Set.empty,
      AchievementStats.empty,
      List(itemPickedUp(rarity = Rarity.Rare))
    )
    assert(!rare.map(_.id).contains("epic_find"))
  }

  test("ItemPickedUp(hasFourPieceSet = true) unlocks set_complete, false does not") {
    val (_, active) = AchievementChecker.checkEvents(
      allDefs,
      Set.empty,
      AchievementStats.empty,
      List(itemPickedUp(hasFourPieceSet = true))
    )
    assert(active.map(_.id).contains("set_complete"))

    val (_, inactive) = AchievementChecker.checkEvents(
      allDefs,
      Set.empty,
      AchievementStats.empty,
      List(itemPickedUp(hasFourPieceSet = false))
    )
    assert(!inactive.map(_.id).contains("set_complete"))
  }

  test("ItemPickedUp(potionBeltFull = true) unlocks full_belt, false does not") {
    val (_, full) = AchievementChecker.checkEvents(
      allDefs,
      Set.empty,
      AchievementStats.empty,
      List(itemPickedUp(potionBeltFull = true))
    )
    assert(full.map(_.id).contains("full_belt"))

    val (_, notFull) = AchievementChecker.checkEvents(
      allDefs,
      Set.empty,
      AchievementStats.empty,
      List(itemPickedUp(potionBeltFull = false))
    )
    assert(!notFull.map(_.id).contains("full_belt"))
  }

  test("ItemPickedUp(stackAtCapacity = true) unlocks stockpiler, false does not") {
    val (_, atCapacity) = AchievementChecker.checkEvents(
      allDefs,
      Set.empty,
      AchievementStats.empty,
      List(itemPickedUp(stackAtCapacity = true))
    )
    assert(atCapacity.map(_.id).contains("stockpiler"))

    val (_, belowCapacity) = AchievementChecker.checkEvents(
      allDefs,
      Set.empty,
      AchievementStats.empty,
      List(itemPickedUp(stackAtCapacity = false))
    )
    assert(!belowCapacity.map(_.id).contains("stockpiler"))
  }

  test("winning on Hard unlocks hard_mode_victory; winning on Normal, or losing on Hard, does not") {
    val (_, wonHard) = AchievementChecker.checkEvents(
      allDefs,
      Set.empty,
      AchievementStats.empty,
      List(GameEvent.RunEnded(victory = true, difficulty = Difficulty.Hard, activePerkId = None))
    )
    assert(wonHard.map(_.id).contains("hard_mode_victory"))

    val (_, wonNormal) = AchievementChecker.checkEvents(
      allDefs,
      Set.empty,
      AchievementStats.empty,
      List(GameEvent.RunEnded(victory = true, difficulty = Difficulty.Normal, activePerkId = None))
    )
    assert(!wonNormal.map(_.id).contains("hard_mode_victory"))

    val (_, lostHard) = AchievementChecker.checkEvents(
      allDefs,
      Set.empty,
      AchievementStats.empty,
      List(GameEvent.RunEnded(victory = false, difficulty = Difficulty.Hard, activePerkId = None))
    )
    assert(!lostHard.map(_.id).contains("hard_mode_victory"))
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
      List(GameEvent.RunEnded(victory = false, difficulty = Difficulty.Normal, activePerkId = None))
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
      List(GameEvent.RunEnded(victory = true, difficulty = Difficulty.Normal, activePerkId = None))
    )
    assertEquals(stats.runsCompleted, 5)
    assertEquals(stats.runsWon, 4)
    assertEquals(stats.currentWinStreak, 4)
  }

  test("ConsumableUsed increments consumablesUsed and adds the typeId to potionTypesUsed") {
    val (stats, _) = AchievementChecker.checkEvents(
      allDefs,
      Set.empty,
      AchievementStats.empty,
      List(GameEvent.ConsumableUsed("health_potion"), GameEvent.ConsumableUsed("health_potion"),
           GameEvent.ConsumableUsed("second_wind")
      )
    )
    assertEquals(stats.consumablesUsed, 3)
    assertEquals(stats.potionTypesUsed, Set("health_potion", "second_wind"))
  }

  test("the 10th ConsumableUsed unlocks potion_master, the 9th does not") {
    val (_, notYet) = AchievementChecker.checkEvents(
      allDefs,
      Set.empty,
      AchievementStats(consumablesUsed = 8),
      List(GameEvent.ConsumableUsed("health_potion")) // 8 -> 9, below threshold
    )
    assert(!notYet.map(_.id).contains("potion_master"))

    val (_, unlocked) = AchievementChecker.checkEvents(
      allDefs,
      Set.empty,
      AchievementStats(consumablesUsed = 9),
      List(GameEvent.ConsumableUsed("health_potion")) // 9 -> 10, crosses the threshold
    )
    assert(unlocked.map(_.id).contains("potion_master"))
  }

  test("the 5th distinct potion type unlocks potion_connoisseur, a repeat of an existing type does not") {
    val fourDistinct = AchievementStats(potionTypesUsed =
      Set("health_potion", "second_wind", "battle_brew", "volatile_flask")
    )
    val (_, fifthNewType) = AchievementChecker.checkEvents(
      allDefs,
      Set.empty,
      fourDistinct,
      List(GameEvent.ConsumableUsed("focus_tonic")) // a genuinely new 5th type
    )
    assert(fifthNewType.map(_.id).contains("potion_connoisseur"))

    val (_, repeatOfExisting) = AchievementChecker.checkEvents(
      allDefs,
      Set.empty,
      fourDistinct,
      List(GameEvent.ConsumableUsed("health_potion")) // already in the set, still only 4 distinct
    )
    assert(!repeatOfExisting.map(_.id).contains("potion_connoisseur"))
  }

  test("RunEnded(victory = true) with an active perk adds it to perksWonWith; a loss does not") {
    val (afterWin, _) = AchievementChecker.checkEvents(
      allDefs,
      Set.empty,
      AchievementStats.empty,
      List(GameEvent.RunEnded(victory = true, difficulty = Difficulty.Normal, activePerkId = Some("heavy_hand")))
    )
    assertEquals(afterWin.perksWonWith, Set("heavy_hand"))

    val (afterLoss, _) = AchievementChecker.checkEvents(
      allDefs,
      Set.empty,
      AchievementStats.empty,
      List(GameEvent.RunEnded(victory = false, difficulty = Difficulty.Normal, activePerkId = Some("heavy_hand")))
    )
    assertEquals(afterLoss.perksWonWith, Set.empty[String])
  }

  test("winning with the 5th distinct perk unlocks jack_of_all_trades, a repeat perk does not") {
    val fourDistinct =
      AchievementStats(perksWonWith = Set("heavy_hand", "herbalist_blessing", "efficient_casting", "lucky_find"))
    val (_, fifthNewPerk) = AchievementChecker.checkEvents(
      allDefs,
      Set.empty,
      fourDistinct,
      List(GameEvent.RunEnded(victory = true, difficulty = Difficulty.Normal, activePerkId = Some("well_stocked")))
    )
    assert(fifthNewPerk.map(_.id).contains("jack_of_all_trades"))

    val (_, repeatOfExisting) = AchievementChecker.checkEvents(
      allDefs,
      Set.empty,
      fourDistinct,
      List(GameEvent.RunEnded(victory = true, difficulty = Difficulty.Normal, activePerkId = Some("heavy_hand")))
    )
    assert(!repeatOfExisting.map(_.id).contains("jack_of_all_trades"))
  }

  test("a win that crosses all three run-count thresholds unlocks veteran, champion, and win_streak together") {
    val startingStats = AchievementStats(runsCompleted = 4, runsWon = 4, currentWinStreak = 4)
    val (_, unlocked) = AchievementChecker.checkEvents(
      allDefs,
      Set.empty,
      startingStats,
      List(GameEvent.RunEnded(victory = true, difficulty = Difficulty.Normal, activePerkId = None))
    )
    assertEquals(unlocked.map(_.id).toSet, Set("veteran", "champion", "win_streak"))
  }

  test("a loss that breaks a 4-win streak, later followed by a 5th total win, does not unlock win_streak") {
    val (statsAfterLoss, unlockedAfterLoss) =
      AchievementChecker.checkEvents(
        allDefs,
        Set.empty,
        AchievementStats(runsCompleted = 4, runsWon = 4, currentWinStreak = 4),
        List(GameEvent.RunEnded(victory = false, difficulty = Difficulty.Normal, activePerkId = None))
      )
    // runsCompleted also increments on a loss, so veteran (5 total runs, win or lose) legitimately
    // unlocks here - only win_streak/champion (win-gated) must NOT unlock from a loss.
    assertEquals(unlockedAfterLoss.map(_.id), List("veteran"))
    assertEquals(statsAfterLoss.currentWinStreak, 0)

    val (_, unlockedAfterNextWin) = AchievementChecker.checkEvents(
      allDefs,
      Set("veteran"), // already persisted after the loss, per the production GameSession flow
      statsAfterLoss,
      List(GameEvent.RunEnded(victory = true, difficulty = Difficulty.Normal, activePerkId = None))
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
