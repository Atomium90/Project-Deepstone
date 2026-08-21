package roguelite.game

import munit.CatsEffectSuite
import roguelite.engine.Difficulty

/** Tests for [[AchievementLoader]]: JSON parsing and condition decoding for every achievement kind. */
class AchievementLoaderSuite extends CatsEffectSuite:

  test("AchievementLoader loads all expected achievement ids") {
    AchievementLoader
      .loadAll()
      .map:
        defs =>
          val expectedIds = Set(
            "first_blood",
            "boss_slayer",
            "level_5",
            "untouchable",
            "packrat",
            "big_spender",
            "key_master",
            "secret_finder",
            "veteran",
            "champion",
            "win_streak",
            "completionist",
            "epic_find",
            "set_complete",
            "full_belt",
            "stockpiler",
            "hard_mode_victory"
          )
          assertEquals(defs.keySet, expectedIds)
  }

  test("displayOrder values are unique") {
    AchievementLoader
      .loadAll()
      .map:
        defs =>
          val orders = defs.values.map(_.displayOrder).toList
          assertEquals(orders.distinct.length, orders.length, "duplicate displayOrder found")
  }

  test("no-arg conditions decode correctly") {
    AchievementLoader
      .loadAll()
      .map:
        defs =>
          assertEquals(defs("first_blood").condition, AchievementCondition.FirstKill)
          assertEquals(defs("boss_slayer").condition, AchievementCondition.DefeatBoss)
          assertEquals(defs("untouchable").condition, AchievementCondition.NoDamageVictory)
          assertEquals(defs("packrat").condition, AchievementCondition.FillInventory)
          assertEquals(defs("key_master").condition, AchievementCondition.UnlockDoorWithKey)
          assertEquals(defs("secret_finder").condition, AchievementCondition.RevealSecretDoor)
          assertEquals(defs("completionist").condition, AchievementCondition.AllUpgradesUnlocked)
          assertEquals(defs("set_complete").condition, AchievementCondition.FourPieceSetActive)
          assertEquals(defs("full_belt").condition, AchievementCondition.FillPotionBelt)
          assertEquals(defs("stockpiler").condition, AchievementCondition.FillPotionStack)
          assertEquals(defs("hard_mode_victory").condition,
                       AchievementCondition.WinOnDifficulty(Difficulty.Hard)
          )
  }

  test("parameterized conditions decode with the right values") {
    AchievementLoader
      .loadAll()
      .map:
        defs =>
          assertEquals(defs("level_5").condition, AchievementCondition.ReachLevel(5))
          assertEquals(defs("big_spender").condition, AchievementCondition.TotalShardsSpent(200))
          assertEquals(defs("veteran").condition, AchievementCondition.RunsCompleted(5))
          assertEquals(defs("champion").condition, AchievementCondition.RunsWon(5))
          assertEquals(defs("win_streak").condition, AchievementCondition.WinStreak(5))
          assertEquals(defs("epic_find").condition, AchievementCondition.LootRarity(Rarity.Epic))
  }
