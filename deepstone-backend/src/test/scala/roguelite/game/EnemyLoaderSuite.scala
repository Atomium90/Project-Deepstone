package roguelite.game

import munit.CatsEffectSuite

class EnemyLoaderSuite extends CatsEffectSuite:

  test("loadAll returns a non-empty map"):
    for stats <- EnemyLoader.loadAll()
    yield assert(stats.nonEmpty)

  test("loadAll contains all expected enemy types"):
    for stats <- EnemyLoader.loadAll()
    yield
      val expected = List("goblin", "orc", "skeleton", "cave_troll", "stone_golem", "giant_spider")
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

  // ---------------------------------------------
  // Loot table fields (new in item system)
  // ---------------------------------------------

  test("boss enemies have 100% drop chance"):
    for stats <- EnemyLoader.loadAll()
    yield
      assertEquals(stats("stone_golem").dropChance, 100, "Stone Golem should have 100% drop")
      assertEquals(stats("giant_spider").dropChance, 100, "Giant Spider should have 100% drop")

  test("boss enemies have non-empty loot tables"):
    for stats <- EnemyLoader.loadAll()
    yield
      assert(stats("stone_golem").lootTable.nonEmpty, "Stone Golem must have a loot table")
      assert(stats("giant_spider").lootTable.nonEmpty, "Giant Spider must have a loot table")

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
