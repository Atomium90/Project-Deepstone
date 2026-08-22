package roguelite.game

import munit.CatsEffectSuite
import roguelite.engine.Difficulty

/** Tests for [[AchievementLoader]]. The decode-path tests run against a small fixture (via
  * [[JsonResourceLoader.loadAllFromJson]]) covering each shape of [[AchievementCondition]] the DTO
  * supports (no-arg, level/amount/count/rarity/difficulty-parameterized) rather than every real
  * condition - it's isolated from and never needs to change alongside `data/achievements.json`.
  * Only the "real catalog" section at the bottom still touches the production file.
  */
class AchievementLoaderSuite extends CatsEffectSuite:

  private val fixture =
    """[
      |  {"id":"test_noarg","label":"L","description":"d","displayOrder":1,"condition":{"type":"FirstKill"}},
      |  {"id":"test_level","label":"L","description":"d","displayOrder":2,"condition":{"type":"ReachLevel","level":5}},
      |  {"id":"test_amount","label":"L","description":"d","displayOrder":3,"condition":{"type":"TotalShardsSpent","amount":100}},
      |  {"id":"test_count","label":"L","description":"d","displayOrder":4,"condition":{"type":"RunsWon","count":3}},
      |  {"id":"test_rarity","label":"L","description":"d","displayOrder":5,"condition":{"type":"LootRarity","rarity":"epic"}},
      |  {"id":"test_difficulty","label":"L","description":"d","displayOrder":6,"condition":{"type":"WinOnDifficulty","difficulty":"hard"}}
      |]""".stripMargin

  test("loadAllFromJson keys each entry by its id"):
    for defs <- AchievementLoader.loadAllFromJson(fixture)
    yield assertEquals(defs.size, 6)

  test("every condition shape decodes to its own case class"):
    for defs <- AchievementLoader.loadAllFromJson(fixture)
    yield
      assertEquals(defs("test_noarg").condition, AchievementCondition.FirstKill)
      assertEquals(defs("test_level").condition, AchievementCondition.ReachLevel(5))
      assertEquals(defs("test_amount").condition, AchievementCondition.TotalShardsSpent(100))
      assertEquals(defs("test_count").condition, AchievementCondition.RunsWon(3))
      assertEquals(defs("test_rarity").condition, AchievementCondition.LootRarity(Rarity.Epic))
      assertEquals(defs("test_difficulty").condition, AchievementCondition.WinOnDifficulty(Difficulty.Hard))

  test("ReachLevel missing its 'level' field fails to parse"):
    val bad = """[{"id":"x","label":"L","description":"d","displayOrder":1,"condition":{"type":"ReachLevel"}}]"""
    AchievementLoader.loadAllFromJson(bad).attempt.map(r => assert(r.isLeft, "expected a parse failure"))

  test("an unknown condition type fails to parse"):
    val bad = """[{"id":"x","label":"L","description":"d","displayOrder":1,"condition":{"type":"NotReal"}}]"""
    AchievementLoader.loadAllFromJson(bad).attempt.map(r => assert(r.isLeft, "expected a parse failure"))

  test("an unknown rarity fails to parse"):
    val bad =
      """[{"id":"x","label":"L","description":"d","displayOrder":1,"condition":{"type":"LootRarity","rarity":"mythic"}}]"""
    AchievementLoader.loadAllFromJson(bad).attempt.map(r => assert(r.isLeft, "expected a parse failure"))

  test("an unknown difficulty fails to parse"):
    val bad =
      """[{"id":"x","label":"L","description":"d","displayOrder":1,"condition":{"type":"WinOnDifficulty","difficulty":"nightmare"}}]"""
    AchievementLoader.loadAllFromJson(bad).attempt.map(r => assert(r.isLeft, "expected a parse failure"))

  // ---------------------------------------------
  // Real catalog: size-independent checks only
  // ---------------------------------------------

  test("the real data/achievements.json resource loads successfully"):
    for defs <- AchievementLoader.loadAll()
    yield assert(defs.nonEmpty)

  test("displayOrder values are unique"):
    AchievementLoader
      .loadAll()
      .map:
        defs =>
          val orders = defs.values.map(_.displayOrder).toList
          assertEquals(orders.distinct.length, orders.length, "duplicate displayOrder found")
