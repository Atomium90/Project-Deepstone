package roguelite.game

import munit.CatsEffectSuite
import roguelite.engine.Difficulty

/** Tests for [[EnemyLoader]] and [[EnemyInstance.fromStats]]. The decode-path tests run against a
  * small fixture (via [[JsonResourceLoader.loadAllFromJson]]) that is isolated from and never
  * needs to change alongside `data/enemies.json`; the `fromStats` tests build [[EnemyStats]] by
  * hand for the same reason. Only the "real catalog" section at the bottom still touches the
  * production file, and only for size-independent invariants.
  */
class EnemyLoaderSuite extends CatsEffectSuite:

  private val fixture =
    """[
      |  {"typeId":"test_goblin","label":"Test Goblin","spriteId":"mob_x_idle","maxHp":30,"attack":8,"defense":2,"xpReward":15,"actions":[{"action":"ATTACK","weight":100}],"dropChance":30,"lootTable":[{"typeId":"health_potion","weight":1}]},
      |  {"typeId":"test_boss","label":"Test Boss","spriteId":"mob_y_idle","maxHp":200,"attack":25,"defense":10,"xpReward":100,"actions":[{"action":"ATTACK","weight":70},{"action":"DEFEND","weight":30}],"dropChance":100,"lootTable":[{"typeId":"health_potion","weight":1}]},
      |  {"typeId":"test_no_loot","label":"Test No Loot","spriteId":"mob_z_idle","maxHp":10,"attack":3,"defense":0,"xpReward":5,"actions":[{"action":"ATTACK","weight":100}]}
      |]""".stripMargin

  test("loadAllFromJson keys each entry by its typeId"):
    for stats <- EnemyLoader.loadAllFromJson(fixture)
    yield assertEquals(stats.keySet, Set("test_goblin", "test_boss", "test_no_loot"))

  test("fields round-trip through decoding"):
    for stats <- EnemyLoader.loadAllFromJson(fixture)
    yield
      val g = stats("test_goblin")
      assertEquals(g.maxHp, 30)
      assertEquals(g.attack, 8)
      assertEquals(g.defense, 2)
      assertEquals(g.xpReward, 15)
      assertEquals(g.dropChance, 30)
      assertEquals(g.lootTable, List(LootEntry("health_potion", 1)))

  test("multiple weighted actions decode correctly"):
    for stats <- EnemyLoader.loadAllFromJson(fixture)
    yield assertEquals(stats("test_boss").actions,
                        List(EnemyActionWeight("ATTACK", 70), EnemyActionWeight("DEFEND", 30))
    )

  test("omitted dropChance and lootTable default to 0 and Nil"):
    for stats <- EnemyLoader.loadAllFromJson(fixture)
    yield
      val noLoot = stats("test_no_loot")
      assertEquals(noLoot.dropChance, 0)
      assertEquals(noLoot.lootTable, Nil)

  test("a missing required field fails to parse"):
    val bad =
      """[{"typeId":"x","label":"X","spriteId":"s","attack":1,"defense":0,"xpReward":1,"actions":[{"action":"ATTACK","weight":1}]}]"""
    EnemyLoader.loadAllFromJson(bad).attempt.map(r => assert(r.isLeft, "expected a parse failure"))

  // ---------------------------------------------
  // EnemyInstance.fromStats (pure, built from hand-authored EnemyStats - no loading required)
  // ---------------------------------------------

  private val goblin = EnemyStats(
    typeId = "test_goblin",
    label = "Test Goblin",
    spriteId = "mob_x_idle",
    maxHp = 30,
    attack = 8,
    defense = 2,
    xpReward = 15,
    actions = List(EnemyActionWeight("ATTACK", 100)),
    dropChance = 30,
    lootTable = List(LootEntry("health_potion", 1))
  )

  test("fromStats creates an instance with full HP and the given entityId"):
    val instance = EnemyInstance.fromStats("entity_01", goblin)
    assertEquals(instance.hp, goblin.maxHp)
    assertEquals(instance.label, goblin.label)
    assertEquals(instance.entityId, "entity_01")

  test("fromStats defaults to Normal difficulty (no scaling)"):
    val instance = EnemyInstance.fromStats("entity_01", goblin)
    assertEquals(instance.maxHp, goblin.maxHp)
    assertEquals(instance.attack, goblin.attack)
    assertEquals(instance.defense, goblin.defense)
    assertEquals(instance.xpReward, goblin.xpReward)

  test("fromStats scales stats on Easy and Hard"):
    val easy = EnemyInstance.fromStats("e1", goblin, Difficulty.Easy)
    val hard = EnemyInstance.fromStats("e1", goblin, Difficulty.Hard)
    assertEquals(easy.maxHp, math.round(goblin.maxHp * 0.75).toInt)
    assertEquals(hard.maxHp, math.round(goblin.maxHp * 1.25).toInt)
    assertEquals(easy.hp, easy.maxHp)
    assert(easy.maxHp < goblin.maxHp)
    assert(hard.maxHp > goblin.maxHp)

  test("fromStats never scales maxHp/attack/xpReward below 1"):
    val fragile = EnemyStats(
      typeId = "sprite",
      label = "Sprite",
      spriteId = "mob_skeleton_idle",
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

  test("fromStats copies dropChance and lootTable"):
    val instance = EnemyInstance.fromStats("boss_01", goblin)
    assertEquals(instance.dropChance, goblin.dropChance)
    assertEquals(instance.lootTable, goblin.lootTable)

  // ---------------------------------------------
  // Real catalog: size-independent invariants only
  // ---------------------------------------------

  test("the real data/enemies.json resource loads successfully"):
    for stats <- EnemyLoader.loadAll()
    yield assert(stats.nonEmpty)

  test("every real enemy has positive HP, attack, and xpReward"):
    for stats <- EnemyLoader.loadAll()
    yield stats.values.foreach:
      s =>
        assert(s.maxHp > 0, s"${s.typeId} maxHp must be positive")
        assert(s.attack > 0, s"${s.typeId} attack must be positive")
        assert(s.defense >= 0, s"${s.typeId} defense must be non-negative")
        assert(s.xpReward > 0, s"${s.typeId} xpReward must be positive")

  test("every real enemy has at least one action, and every action weight is positive"):
    for stats <- EnemyLoader.loadAll()
    yield stats.values.foreach:
      s =>
        assert(s.actions.nonEmpty, s"${s.typeId} must have at least one action")
        s.actions.foreach(a => assert(a.weight > 0, s"${s.typeId} action ${a.action} has non-positive weight"))

  test("every real enemy's dropChance is in 0-100 range, and every loot entry weight is positive"):
    for stats <- EnemyLoader.loadAll()
    yield stats.values.foreach:
      s =>
        assert(s.dropChance >= 0 && s.dropChance <= 100, s"${s.typeId} dropChance ${s.dropChance} out of range")
        s.lootTable.foreach(e => assert(e.weight > 0, s"${s.typeId} loot entry ${e.typeId} has non-positive weight"))

  test("every real enemy with a guaranteed (100%) drop chance has a non-empty loot table"):
    for stats <- EnemyLoader.loadAll()
    yield stats.values.filter(_.dropChance == 100).foreach:
      s => assert(s.lootTable.nonEmpty, s"${s.typeId} has a 100% dropChance but no lootTable")
