package roguelite.game

import munit.CatsEffectSuite

class EnemyLoaderSuite extends CatsEffectSuite:

  test("loadAll returns a non-empty map"):
    for stats <- EnemyLoader.loadAll()
      yield assert(stats.nonEmpty)

  test("loadAll contains all expected enemy types"):
    for stats <- EnemyLoader.loadAll()
      yield
        val expectedTypes = List("goblin", "orc", "skeleton", "cave_troll", "stone_golem", "giant_spider")
        expectedTypes.foreach: typeId =>
          assert(stats.contains(typeId), s"Missing enemy type: $typeId")

  test("enemy stats have positive HP, attack, defense, and xpReward"):
    for stats <- EnemyLoader.loadAll()
      yield stats.values.foreach: s =>
        assert(s.maxHp     > 0, s"${s.typeId} maxHp must be positive")
        assert(s.attack    > 0, s"${s.typeId} attack must be positive")
        assert(s.defense   >= 0, s"${s.typeId} defense must be non-negative")
        assert(s.xpReward  > 0, s"${s.typeId} xpReward must be positive")

  test("all enemies have at least one action"):
    for stats <- EnemyLoader.loadAll()
      yield stats.values.foreach: s =>
        assert(s.actions.nonEmpty, s"${s.typeId} must have at least one action")

  test("all action weights are positive"):
    for stats <- EnemyLoader.loadAll()
      yield stats.values.foreach: s =>
        s.actions.foreach: a =>
          assert(a.weight > 0, s"${s.typeId} action ${a.action} has non-positive weight")

  test("boss enemies have more HP than regular enemies"):
    for stats <- EnemyLoader.loadAll()
      yield
        val goblinHp = stats("goblin").maxHp
        val golemHp  = stats("stone_golem").maxHp
        assert(golemHp > goblinHp, s"Boss should have more HP than goblin ($golemHp vs $goblinHp)")

  test("EnemyInstance.fromStats creates instance with full HP"):
    for stats <- EnemyLoader.loadAll()
      yield
        val goblinStats    = stats("goblin")
        val instance       = EnemyInstance.fromStats("entity_01", goblinStats)
        assertEquals(instance.hp, goblinStats.maxHp)
        assertEquals(instance.label, goblinStats.label)
        assertEquals(instance.entityId, "entity_01")