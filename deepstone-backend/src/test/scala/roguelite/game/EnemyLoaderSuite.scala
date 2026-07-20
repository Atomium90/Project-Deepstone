package roguelite.game

import munit.CatsEffectSuite
import roguelite.engine.Difficulty

class EnemyLoaderSuite extends CatsEffectSuite:

  test("loadAll returns a non-empty map"):
    for stats <- EnemyLoader.loadAll()
    yield assert(stats.nonEmpty)

  test("loadAll contains all expected enemy types"):
    for stats <- EnemyLoader.loadAll()
    yield
      val expected = List(
        "goblin", "orc", "skeleton", "cave_troll", "bandit", "dire_wolf", "cultist",
        "stone_golem", "giant_spider", "troll_king", "wraith", "lich", "ogre_warlord"
      )
      expected.foreach:
        typeId => assert(stats.contains(typeId), s"Missing enemy type: $typeId")

  test("enemy stats have positive HP, attack, and xpReward"):
    for stats <- EnemyLoader.loadAll()
    yield stats.values.foreach:
      s =>
        assert(s.maxHp > 0, s"${s.typeId} maxHp must be positive")
        assert(s.attack > 0, s"${s.typeId} attack must be positive")
        assert(s.defense >= 0, s"${s.typeId} defense must be non-negative")
        assert(s.xpReward > 0, s"${s.typeId} xpReward must be positive")

  test("all enemies have at least one action"):
    for stats <- EnemyLoader.loadAll()
    yield stats.values.foreach:
      s => assert(s.actions.nonEmpty, s"${s.typeId} must have at least one action")

  test("all action weights are positive"):
    for stats <- EnemyLoader.loadAll()
    yield stats.values.foreach:
      s =>
        s.actions.foreach:
          a => assert(a.weight > 0, s"${s.typeId} action ${a.action} has non-positive weight")

  test("boss enemies have more HP than regular enemies"):
    for stats <- EnemyLoader.loadAll()
    yield assert(stats("stone_golem").maxHp > stats("goblin").maxHp)

  test("EnemyInstance.fromStats creates instance with full HP and correct entityId"):
    for stats <- EnemyLoader.loadAll()
    yield
      val goblin   = stats("goblin")
      val instance = EnemyInstance.fromStats("entity_01", goblin)
      assertEquals(instance.hp, goblin.maxHp)
      assertEquals(instance.label, goblin.label)
      assertEquals(instance.entityId, "entity_01")

  test("EnemyInstance.fromStats defaults to Normal difficulty (no scaling)"):
    for stats <- EnemyLoader.loadAll()
    yield
      val goblin   = stats("goblin")
      val instance = EnemyInstance.fromStats("entity_01", goblin)
      assertEquals(instance.maxHp, goblin.maxHp)
      assertEquals(instance.attack, goblin.attack)
      assertEquals(instance.defense, goblin.defense)
      assertEquals(instance.xpReward, goblin.xpReward)

  test("EnemyInstance.fromStats scales stats on Easy and Hard"):
    for stats <- EnemyLoader.loadAll()
    yield
      val goblin = stats("goblin")
      val easy   = EnemyInstance.fromStats("e1", goblin, Difficulty.Easy)
      val hard   = EnemyInstance.fromStats("e1", goblin, Difficulty.Hard)
      assertEquals(easy.maxHp, math.round(goblin.maxHp * 0.75).toInt)
      assertEquals(hard.maxHp, math.round(goblin.maxHp * 1.25).toInt)
      assertEquals(easy.hp, easy.maxHp)
      assert(easy.maxHp < goblin.maxHp)
      assert(hard.maxHp > goblin.maxHp)

  test("EnemyInstance.fromStats never scales maxHp/attack/xpReward below 1"):
    val fragile = EnemyStats(
      typeId = "sprite",
      label = "Sprite",
      maxHp = 1,
      attack = 1,
      defense = 0,
      xpReward = 1,
      actions = List(EnemyActionWeight("ATTACK", 100))
    )
    val easy = EnemyInstance.fromStats("e1", fragile, Difficulty.Easy)
    assertEquals(easy.maxHp, 1)
    assertEquals(easy.attack, 1)
    assertEquals(easy.xpReward, 1)
    assertEquals(easy.defense, 0)

  // ---------------------------------------------
  // Loot table fields (new in item system)
  // ---------------------------------------------

  test("boss enemies have 100% drop chance"):
    for stats <- EnemyLoader.loadAll()
    yield
      val bosses = List("stone_golem", "giant_spider", "troll_king", "wraith", "lich", "ogre_warlord")
      bosses.foreach:
        typeId => assertEquals(stats(typeId).dropChance, 100, s"$typeId should have 100% drop")

  test("boss enemies have non-empty loot tables"):
    for stats <- EnemyLoader.loadAll()
    yield
      val bosses = List("stone_golem", "giant_spider", "troll_king", "wraith", "lich", "ogre_warlord")
      bosses.foreach:
        typeId => assert(stats(typeId).lootTable.nonEmpty, s"$typeId must have a loot table")

  test("regular enemies have dropChance in 0-100 range"):
    for stats <- EnemyLoader.loadAll()
    yield stats.values.foreach:
      s =>
        assert(s.dropChance >= 0 && s.dropChance <= 100,
               s"${s.typeId} dropChance ${s.dropChance} out of range"
        )

  test("all loot table entry weights are positive"):
    for stats <- EnemyLoader.loadAll()
    yield stats.values.foreach:
      s =>
        s.lootTable.foreach:
          e => assert(e.weight > 0, s"${s.typeId} loot entry ${e.typeId} has non-positive weight")

  test("EnemyInstance.fromStats copies dropChance and lootTable"):
    for stats <- EnemyLoader.loadAll()
    yield
      val golem    = stats("stone_golem")
      val instance = EnemyInstance.fromStats("boss_01", golem)
      assertEquals(instance.dropChance, golem.dropChance)
      assertEquals(instance.lootTable, golem.lootTable)
