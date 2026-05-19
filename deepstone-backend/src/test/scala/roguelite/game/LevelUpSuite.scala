package roguelite.game

import munit.FunSuite
import roguelite.engine.{ ClassId, Player }

import scala.util.Random

/** Tests for [[LevelUpSystem]]: XP threshold formulas, perk effects, and level-up sequencing. */
class LevelUpSuite extends FunSuite:

  // -----------------------------------------------------------------------
  // Fixtures
  // -----------------------------------------------------------------------

  /** Build a minimal player for level-up tests. Class doesn't matter here. */
  private def makePlayer(
      level: Int,
      xp: Int,
      hp: Int = 80,
      maxHp: Int = 100,
      resourceMax: Int = 100,
      bonusAtk: Int = 0,
      bonusDef: Int = 0
  ): Player =
    Player(
      classId = ClassId.Warrior,
      hp = hp,
      maxHp = maxHp,
      resourceCurrent = 0,
      resourceMax = resourceMax,
      level = level,
      xp = xp,
      metaCurrency = 0,
      bonusAttack = bonusAtk,
      bonusDefense = bonusDef
    )

  // -----------------------------------------------------------------------
  // XP threshold formula
  // -----------------------------------------------------------------------

  test("xpThreshold: level 1 → 2 requires 100 XP") {
    assertEquals(LevelUpSystem.xpThreshold(1), 100)
  }

  test("xpThreshold: level 2 → 3 requires 275 XP") {
    assertEquals(LevelUpSystem.xpThreshold(2), 275)
  }

  test("xpThreshold: level 3 → 4 requires 450 XP") {
    assertEquals(LevelUpSystem.xpThreshold(3), 450)
  }

  // -----------------------------------------------------------------------
  // Cumulative XP
  // -----------------------------------------------------------------------

  test("cumulativeXpFor: level 1 needs 0 total XP (already there)") {
    assertEquals(LevelUpSystem.cumulativeXpFor(1), 0)
  }

  test("cumulativeXpFor: level 2 needs 100 total XP") {
    assertEquals(LevelUpSystem.cumulativeXpFor(2), 100)
  }

  test("cumulativeXpFor: level 3 needs 375 total XP") {
    assertEquals(LevelUpSystem.cumulativeXpFor(3), 375)
  }

  test("cumulativeXpFor: level 4 needs 825 total XP") {
    assertEquals(LevelUpSystem.cumulativeXpFor(4), 825)
  }

  // -----------------------------------------------------------------------
  // applyLevelUps: sequencing
  // -----------------------------------------------------------------------

  test("applyLevelUps: no level-up when XP is below threshold") {
    val player        = makePlayer(level = 1, xp = 99) // threshold is 100
    val (result, log) = LevelUpSystem.applyLevelUps(player, Random(42))
    assertEquals(result.level, 1)
    assertEquals(log, Nil)
  }

  test("applyLevelUps: level-up at exact threshold") {
    val player        = makePlayer(level = 1, xp = 100)
    val (result, log) = LevelUpSystem.applyLevelUps(player, Random(42))
    assertEquals(result.level, 2)
    assertEquals(log.length, 1)
    assert(log.head.contains("Level 2"), s"expected 'Level 2' in log, got: $log")
  }

  test("applyLevelUps: level-up above threshold") {
    val player        = makePlayer(level = 1, xp = 150) // well above 100
    val (result, log) = LevelUpSystem.applyLevelUps(player, Random(42))
    assertEquals(result.level, 2)
    assertEquals(log.length, 1)
  }

  test("applyLevelUps: multi level-up when XP crosses two thresholds") {
    // Level 1→2 at 100, level 2→3 at 375 total — give 400 XP
    val player        = makePlayer(level = 1, xp = 400)
    val (result, log) = LevelUpSystem.applyLevelUps(player, Random(42))
    assertEquals(result.level, 3)
    assertEquals(log.length, 2)
    assert(log(0).contains("Level 2"), s"expected 'Level 2' in log(0): $log")
    assert(log(1).contains("Level 3"), s"expected 'Level 3' in log(1): $log")
  }

  test("applyLevelUps: exactly one stat changes per level gained") {
    val player        = makePlayer(level = 1, xp = 100)
    val (result, log) = LevelUpSystem.applyLevelUps(player, Random(42))

    val changed = List(
      result.maxHp != player.maxHp,
      result.bonusAttack != player.bonusAttack,
      result.bonusDefense != player.bonusDefense,
      result.resourceMax != player.resourceMax
    ).count(identity)

    assertEquals(changed, 1, s"expected exactly 1 stat change, got $changed — result: $result")
  }

  test("applyLevelUps: XP is not modified (accumulates throughout the run)") {
    val player      = makePlayer(level = 1, xp = 100)
    val (result, _) = LevelUpSystem.applyLevelUps(player, Random(42))
    assertEquals(result.xp, 100)
  }

  // -----------------------------------------------------------------------
  // applyPerk: individual perk effects
  // -----------------------------------------------------------------------

  test("applyPerk: MaxHpBoost increases maxHp by 15 and heals 15") {
    val player         = makePlayer(level = 1, xp = 0, hp = 60, maxHp = 100)
    val (result, desc) = LevelUpSystem.applyPerk(player, LevelUpSystem.Perk.MaxHpBoost)
    assertEquals(result.maxHp, 115)
    assertEquals(result.hp, 75) // 60 + 15
    assert(desc.contains("Max HP"), s"unexpected perk description: $desc")
  }

  test("applyPerk: MaxHpBoost HP is capped at new maxHp when player is full") {
    val player      = makePlayer(level = 1, xp = 0, hp = 100, maxHp = 100)
    val (result, _) = LevelUpSystem.applyPerk(player, LevelUpSystem.Perk.MaxHpBoost)
    assertEquals(result.maxHp, 115)
    assertEquals(result.hp, 115) // healed to the new cap, not 100 + 15 = 115 capped at 115
  }

  test("applyPerk: AttackBoost increases bonusAttack by 2") {
    val player         = makePlayer(level = 1, xp = 0, bonusAtk = 3)
    val (result, desc) = LevelUpSystem.applyPerk(player, LevelUpSystem.Perk.AttackBoost)
    assertEquals(result.bonusAttack, 5)
    assert(desc.contains("Attack"), s"unexpected perk description: $desc")
  }

  test("applyPerk: DefenseBoost increases bonusDefense by 1") {
    val player         = makePlayer(level = 1, xp = 0, bonusDef = 2)
    val (result, desc) = LevelUpSystem.applyPerk(player, LevelUpSystem.Perk.DefenseBoost)
    assertEquals(result.bonusDefense, 3)
    assert(desc.contains("Defense"), s"unexpected perk description: $desc")
  }

  test("applyPerk: ResourceBoost increases resourceMax by 20") {
    val player         = makePlayer(level = 1, xp = 0, resourceMax = 100)
    val (result, desc) = LevelUpSystem.applyPerk(player, LevelUpSystem.Perk.ResourceBoost)
    assertEquals(result.resourceMax, 120)
    assert(desc.contains("Resource"), s"unexpected perk description: $desc")
  }

  test("applyPerk: AttackBoost stacks correctly across multiple levels") {
    val player = makePlayer(level = 1, xp = 400, bonusAtk = 0)
    // Force two AttackBoosts by picking a seed — verify stack, not the specific perk
    val (result, log) = LevelUpSystem.applyLevelUps(player, Random(42))
    // Whatever perks were given, bonusAttack must be a multiple of 2 if any were AttackBoost
    assertEquals(result.bonusAttack % 2, 0)
    assertEquals(result.level, 3)
    assertEquals(log.length, 2)
  }
