package roguelite.game

import munit.CatsEffectSuite

/** Tests for [[PerkLoader]]. The decode-path tests run against a small fixture (via
  * [[JsonResourceLoader.loadAllFromJson]]) that is isolated from and never needs to change
  * alongside `data/perks.json` - only the "real catalog" section at the bottom still touches the
  * production file, and only for a size-independent smoke check.
  */
class PerkLoaderSuite extends CatsEffectSuite:

  private val fixture =
    """[
      |  {"id":"test_extra_item","label":"L","description":"d","icon":"icon.png","effect":{"type":"ExtraStartingItem","typeId":"health_potion"}},
      |  {"id":"test_flat_dmg","label":"L","description":"d","icon":"icon.png","effect":{"type":"FlatDamageBonus","amount":2}},
      |  {"id":"test_heal_bonus","label":"L","description":"d","icon":"icon.png","effect":{"type":"PotionHealBonusPercent","amount":25}},
      |  {"id":"test_cost_reduction","label":"L","description":"d","icon":"icon.png","effect":{"type":"AbilityCostReductionPercent","amount":10}},
      |  {"id":"test_lucky_chest","label":"L","description":"d","icon":"icon.png","effect":{"type":"GuaranteedRarityFirstChest","minRarity":"epic"}}
      |]""".stripMargin

  test("loadAllFromJson keys each entry by its id"):
    for defs <- PerkLoader.loadAllFromJson(fixture)
    yield assertEquals(defs.keySet,
                        Set("test_extra_item", "test_flat_dmg", "test_heal_bonus", "test_cost_reduction", "test_lucky_chest")
    )

  test("every effect variant decodes to its own case class"):
    for defs <- PerkLoader.loadAllFromJson(fixture)
    yield
      assertEquals(defs("test_extra_item").effect, PerkEffect.ExtraStartingItem("health_potion"))
      assertEquals(defs("test_flat_dmg").effect, PerkEffect.FlatDamageBonus(2))
      assertEquals(defs("test_heal_bonus").effect, PerkEffect.PotionHealBonusPercent(25))
      assertEquals(defs("test_cost_reduction").effect, PerkEffect.AbilityCostReductionPercent(10))
      assertEquals(defs("test_lucky_chest").effect, PerkEffect.GuaranteedRarityFirstChest(Rarity.Epic))

  test("ExtraStartingItem missing its 'typeId' field fails to parse"):
    val bad = """[{"id":"x","label":"L","description":"d","icon":"i.png","effect":{"type":"ExtraStartingItem"}}]"""
    PerkLoader.loadAllFromJson(bad).attempt.map(r => assert(r.isLeft, "expected a parse failure"))

  test("an unknown effect type fails to parse"):
    val bad = """[{"id":"x","label":"L","description":"d","icon":"i.png","effect":{"type":"NotReal"}}]"""
    PerkLoader.loadAllFromJson(bad).attempt.map(r => assert(r.isLeft, "expected a parse failure"))

  test("malformed top-level JSON fails to parse"):
    PerkLoader.loadAllFromJson("not valid json").attempt.map(r => assert(r.isLeft, "expected a parse failure"))

  test("GuaranteedRarityFirstChest's minRarity resolves every rarity tier"):
    val fixture =
      """[
        |  {"id":"c","label":"L","description":"d","icon":"i.png","effect":{"type":"GuaranteedRarityFirstChest","minRarity":"common"}},
        |  {"id":"u","label":"L","description":"d","icon":"i.png","effect":{"type":"GuaranteedRarityFirstChest","minRarity":"uncommon"}}
        |]""".stripMargin
    for defs <- PerkLoader.loadAllFromJson(fixture)
    yield
      assertEquals(defs("c").effect, PerkEffect.GuaranteedRarityFirstChest(Rarity.Common))
      assertEquals(defs("u").effect, PerkEffect.GuaranteedRarityFirstChest(Rarity.Uncommon))

  test("an unknown minRarity fails to parse"):
    val bad =
      """[{"id":"x","label":"L","description":"d","icon":"i.png","effect":{"type":"GuaranteedRarityFirstChest","minRarity":"mythic"}}]"""
    PerkLoader.loadAllFromJson(bad).attempt.map(r => assert(r.isLeft, "expected a parse failure"))

  // ---------------------------------------------
  // Real catalog: size-independent smoke check only
  // ---------------------------------------------

  test("the real data/perks.json resource loads successfully"):
    for defs <- PerkLoader.loadAll()
    yield assert(defs.nonEmpty)
