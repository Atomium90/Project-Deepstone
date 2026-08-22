package roguelite.game

import munit.CatsEffectSuite
import roguelite.engine.ClassId

/** Tests for [[UpgradeLoader]]. The decode-path tests run against a small fixture (via
  * [[JsonResourceLoader.loadAllFromJson]]) that is isolated from and never needs to change
  * alongside `data/upgrades.json` - only the "real catalog" section at the bottom still touches
  * the production file, and only for size-independent checks.
  */
class UpgradeLoaderSuite extends CatsEffectSuite:

  private val fixture =
    """[
      |  {"id":"test_hp","label":"L","description":"d","cost":10,"displayOrder":1,"icon":"i.png","category":"stat","effect":{"type":"MaxHpBoost","amount":20}},
      |  {"id":"test_slot","label":"L","description":"d","cost":20,"displayOrder":2,"icon":"i.png","category":"stat","effect":{"type":"ExtraPotionSlot"}},
      |  {"id":"test_cap","label":"L","description":"d","cost":30,"displayOrder":3,"icon":"i.png","category":"stat","effect":{"type":"ExtraPotionCapacity"}},
      |  {"id":"test_item","label":"L","description":"d","cost":40,"displayOrder":4,"icon":"i.png","category":"meta","effect":{"type":"StartingItem","typeId":"health_potion"}},
      |  {"id":"test_unlock","label":"L","description":"d","cost":50,"displayOrder":5,"icon":"i.png","category":"meta","effect":{"type":"UnlockClass","classId":"mage"}},
      |  {"id":"test_atk","label":"L","description":"d","cost":60,"displayOrder":6,"icon":"i.png","category":"stat","effect":{"type":"FlatAttackBoost","amount":3}},
      |  {"id":"test_rarity","label":"L","description":"d","cost":70,"displayOrder":7,"icon":"i.png","category":"stat","effect":{"type":"GuaranteedChestRarity","rarity":"rare"}},
      |  {"id":"test_kit","label":"L","description":"d","cost":80,"displayOrder":8,"icon":"i.png","category":"meta","effect":{"type":"UnlockStartingKit","classId":"warrior"}}
      |]""".stripMargin

  test("loadAllFromJson keys each entry by its id"):
    for defs <- UpgradeLoader.loadAllFromJson(fixture)
    yield assertEquals(defs.size, 8)

  test("every effect variant decodes to its own case class"):
    for defs <- UpgradeLoader.loadAllFromJson(fixture)
    yield
      assertEquals(defs("test_hp").effect, UpgradeEffect.MaxHpBoost(20))
      assertEquals(defs("test_slot").effect, UpgradeEffect.ExtraPotionSlot)
      assertEquals(defs("test_cap").effect, UpgradeEffect.ExtraPotionCapacity)
      assertEquals(defs("test_item").effect, UpgradeEffect.StartingItem("health_potion"))
      assertEquals(defs("test_unlock").effect, UpgradeEffect.UnlockClass(ClassId.Mage))
      assertEquals(defs("test_atk").effect, UpgradeEffect.FlatAttackBoost(3))
      assertEquals(defs("test_rarity").effect, UpgradeEffect.GuaranteedChestRarity(Rarity.Rare))
      assertEquals(defs("test_kit").effect, UpgradeEffect.UnlockStartingKit(ClassId.Warrior))

  test("category decodes Stat and Meta"):
    for defs <- UpgradeLoader.loadAllFromJson(fixture)
    yield
      assertEquals(defs("test_hp").category, UpgradeCategory.Stat)
      assertEquals(defs("test_item").category, UpgradeCategory.Meta)

  test("MaxHpBoost missing its 'amount' field fails to parse"):
    val bad =
      """[{"id":"x","label":"L","description":"d","cost":1,"displayOrder":1,"icon":"i.png","category":"stat","effect":{"type":"MaxHpBoost"}}]"""
    UpgradeLoader.loadAllFromJson(bad).attempt.map(r => assert(r.isLeft, "expected a parse failure"))

  test("an unknown category fails to parse"):
    val bad =
      """[{"id":"x","label":"L","description":"d","cost":1,"displayOrder":1,"icon":"i.png","category":"bogus","effect":{"type":"ExtraPotionSlot"}}]"""
    UpgradeLoader.loadAllFromJson(bad).attempt.map(r => assert(r.isLeft, "expected a parse failure"))

  test("an unknown effect type fails to parse"):
    val bad =
      """[{"id":"x","label":"L","description":"d","cost":1,"displayOrder":1,"icon":"i.png","category":"stat","effect":{"type":"NotReal"}}]"""
    UpgradeLoader.loadAllFromJson(bad).attempt.map(r => assert(r.isLeft, "expected a parse failure"))

  test("an unknown classId in UnlockClass fails to parse"):
    val bad =
      """[{"id":"x","label":"L","description":"d","cost":1,"displayOrder":1,"icon":"i.png","category":"meta","effect":{"type":"UnlockClass","classId":"paladin"}}]"""
    UpgradeLoader.loadAllFromJson(bad).attempt.map(r => assert(r.isLeft, "expected a parse failure"))

  // ---------------------------------------------
  // Real catalog: size-independent checks only
  // ---------------------------------------------

  test("the real data/upgrades.json resource loads successfully"):
    for defs <- UpgradeLoader.loadAll()
    yield assert(defs.nonEmpty)

  test("every upgrade has a positive cost"):
    UpgradeLoader
      .loadAll()
      .map:
        defs =>
          defs.values.foreach:
            u => assert(u.cost > 0, s"${u.id} has non-positive cost ${u.cost}")

  test("every upgrade has a non-empty icon"):
    UpgradeLoader
      .loadAll()
      .map:
        defs =>
          defs.values.foreach:
            u => assert(u.icon.nonEmpty, s"${u.id} has no icon")

  test("displayOrder values are unique"):
    UpgradeLoader
      .loadAll()
      .map:
        defs =>
          val orders = defs.values.map(_.displayOrder).toList
          assertEquals(orders.distinct.length, orders.length, "duplicate displayOrder found")
