package roguelite.game.progression

import munit.CatsEffectSuite

import roguelite.game.items.Rarity

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
  // Risky perks: a malus for more Shards
  // ---------------------------------------------

  private val riskyFixture =
    """[
      |  {"id":"risky_enemies","label":"L","description":"d","icon":"i.png","risky":true,"shardBonusPercent":60,"victoryShardBonus":30,"effect":{"type":"EnemyStatPercent","amount":25}},
      |  {"id":"risky_hp","label":"L","description":"d","icon":"i.png","risky":true,"shardBonusPercent":50,"effect":{"type":"MaxHpPercent","amount":-30}},
      |  {"id":"risky_potion","label":"L","description":"d","icon":"i.png","risky":true,"shardBonusPercent":40,"effect":{"type":"PotionHealBonusPercent","amount":-40}},
      |  {"id":"risky_pact","label":"L","description":"d","icon":"i.png","risky":true,"shardBonusPercent":90,"victoryShardBonus":50,"effect":{"type":"EnemyStatPercent","amount":15},"extraEffects":[{"type":"MaxHpPercent","amount":-15}]},
      |  {"id":"plain","label":"L","description":"d","icon":"i.png","effect":{"type":"FlatDamageBonus","amount":1}}
      |]""".stripMargin

  test("the effects of a risky perk decode, a malus as a negative amount"):
    for defs <- PerkLoader.loadAllFromJson(riskyFixture)
    yield
      assertEquals(defs("risky_enemies").effect, PerkEffect.EnemyStatPercent(25))
      assertEquals(defs("risky_hp").effect, PerkEffect.MaxHpPercent(-30))
      assertEquals(defs("risky_potion").effect, PerkEffect.PotionHealBonusPercent(-40))

  test("a risky perk carries its flag and the Shards it pays"):
    for defs <- PerkLoader.loadAllFromJson(riskyFixture)
    yield
      assertEquals(defs("risky_enemies").risky, true)
      assertEquals(defs("risky_enemies").shardBonusPercent, 60)
      assertEquals(defs("risky_enemies").victoryShardBonus, 30)
      assertEquals(defs("risky_hp").victoryShardBonus, 0)

  test("a perk with no risky fields is an ordinary perk that pays nothing more"):
    for defs <- PerkLoader.loadAllFromJson(riskyFixture)
    yield
      assertEquals(defs("plain").risky, false)
      assertEquals(defs("plain").shardBonusPercent, 0)
      assertEquals(defs("plain").victoryShardBonus, 0)
      assertEquals(defs("plain").extraEffects, Nil)

  test("a perk with several effects lists them all, the main one first"):
    for defs <- PerkLoader.loadAllFromJson(riskyFixture)
    yield assertEquals(defs("risky_pact").effects, List(PerkEffect.EnemyStatPercent(15), PerkEffect.MaxHpPercent(-15)))

  test("EnemyStatPercent and MaxHpPercent missing their 'amount' fail to parse"):
    val enemies = """[{"id":"x","label":"L","description":"d","icon":"i.png","effect":{"type":"EnemyStatPercent"}}]"""
    val hp      = """[{"id":"x","label":"L","description":"d","icon":"i.png","effect":{"type":"MaxHpPercent"}}]"""
    for
      a <- PerkLoader.loadAllFromJson(enemies).attempt
      b <- PerkLoader.loadAllFromJson(hp).attempt
    yield
      assert(a.isLeft, "expected a parse failure")
      assert(b.isLeft, "expected a parse failure")

  test("an unknown effect in extraEffects fails to parse"):
    val bad =
      """[{"id":"x","label":"L","description":"d","icon":"i.png","effect":{"type":"MaxHpPercent","amount":-5},"extraEffects":[{"type":"NotReal"}]}]"""
    PerkLoader.loadAllFromJson(bad).attempt.map(r => assert(r.isLeft, "expected a parse failure"))

  // ---------------------------------------------
  // Real catalog: size-independent smoke check only
  // ---------------------------------------------

  test("the real data/perks.json resource loads successfully"):
    for defs <- PerkLoader.loadAll()
    yield assert(defs.nonEmpty)

  test("every risky perk of the real catalog takes a malus and pays Shards for it, and no ordinary perk pays any"):
    def isMalus(effect: PerkEffect): Boolean = effect match
      case PerkEffect.EnemyStatPercent(n)            => n > 0
      case PerkEffect.MaxHpPercent(n)                => n < 0
      case PerkEffect.PotionHealBonusPercent(n)      => n < 0
      case PerkEffect.AbilityCostReductionPercent(n) => n < 0
      case _                                         => false
    for defs <- PerkLoader.loadAll()
    yield
      val (risky, ordinary) = defs.values.toList.partition(_.risky)
      assert(risky.nonEmpty, "expected at least one risky perk")
      for p <- risky do
        assert(p.effects.exists(isMalus), s"${p.id} is risky but takes no malus")
        assert(p.shardBonusPercent > 0, s"${p.id} is risky but pays no Shards per kill")
      for p <- ordinary do
        assertEquals(p.shardBonusPercent, 0, s"${p.id} is not risky but pays Shards")
        assertEquals(p.victoryShardBonus, 0, s"${p.id} is not risky but pays a win bonus")
        assert(!p.effects.exists(isMalus), s"${p.id} is not risky but takes a malus")
