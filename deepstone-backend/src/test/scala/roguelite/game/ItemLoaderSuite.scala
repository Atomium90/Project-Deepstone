package roguelite.game

import munit.CatsEffectSuite

/** Tests for [[ItemLoader]]. The decode-path tests run against a small fixture (via
  * [[JsonResourceLoader.loadAllFromJson]]) covering every [[Item]] kind, every
  * [[ConsumableEffect]] variant, and every [[KeyKind]] variant - isolated from and never needing
  * to change alongside `data/items.json`. Only the "real catalog" section near the bottom still
  * touches the production file, and only for size-independent invariants and authoring
  * conventions (iconId matching typeId). The pure `Item`/`Rarity` logic tests at the very bottom
  * don't touch any catalog, loaded or fixture, and are unchanged.
  */
class ItemLoaderSuite extends CatsEffectSuite:

  private val fixture =
    """[
      |  {"typeId":"test_sword","kind":"weapon","name":"Test Sword","rarity":"common","attackBonus":5,"typeTag":"heavy","setId":"test_set","description":"d","iconId":"test_sword"},
      |  {"typeId":"test_plate","kind":"armor","name":"Test Plate","rarity":"uncommon","defenseBonus":4,"typeTag":"heavy"},
      |  {"typeId":"test_trinket","kind":"accessory","name":"Test Trinket","rarity":"rare","hpBonus":10,"attackBonus":2,"defenseBonus":3,"critChanceBonus":4,"typeTag":"magic"},
      |  {"typeId":"test_heal_fixed","kind":"consumable","name":"Test Heal Fixed","rarity":"common","effect":{"type":"HealFixed","amount":20}},
      |  {"typeId":"test_heal_percent","kind":"consumable","name":"Test Heal Percent","rarity":"common","effect":{"type":"HealPercent","percent":25}},
      |  {"typeId":"test_restore","kind":"consumable","name":"Test Restore","rarity":"common","effect":{"type":"RestoreResource","amount":15}},
      |  {"typeId":"test_atk_buff","kind":"consumable","name":"Test Attack Buff","rarity":"common","effect":{"type":"AttackBuff","percent":20,"turns":3}},
      |  {"typeId":"test_flat_dmg","kind":"consumable","name":"Test Flat Damage","rarity":"common","effect":{"type":"FlatDamage","amount":30}},
      |  {"typeId":"test_crit_buff","kind":"consumable","name":"Test Crit Buff","rarity":"common","effect":{"type":"CritBuff","percent":15,"turns":3}},
      |  {"typeId":"test_key_generic","kind":"key","name":"Test Key Generic","rarity":"common","keyKind":"generic"},
      |  {"typeId":"test_key_universal","kind":"key","name":"Test Key Universal","rarity":"common","keyKind":"universal"},
      |  {"typeId":"test_key_specific","kind":"key","name":"Test Key Specific","rarity":"common","keyKind":"specific","doorId":"vault_01"},
      |  {"typeId":"test_key_typed","kind":"key","name":"Test Key Typed","rarity":"common","keyKind":"typed","doorTag":"fire"}
      |]""".stripMargin

  test("loadAllFromJson keys each entry by its typeId, all as empty-id prototypes"):
    for items <- ItemLoader.loadAllFromJson(fixture)
    yield
      assertEquals(items.size, 13)
      items.values.foreach(item => assertEquals(item.id, "", s"${item.typeId} prototype should have empty id"))

  test("weapon decodes attackBonus, typeTag, setId, and iconId"):
    for items <- ItemLoader.loadAllFromJson(fixture)
    yield
      val w = items("test_sword").asInstanceOf[Weapon]
      assertEquals(w.attackBonus, 5)
      assertEquals(w.typeTag, Some("heavy"))
      assertEquals(w.setId, Some("test_set"))
      assertEquals(w.iconId, Some("test_sword"))

  test("armor decodes defenseBonus and typeTag"):
    for items <- ItemLoader.loadAllFromJson(fixture)
    yield
      val a = items("test_plate").asInstanceOf[Armor]
      assertEquals(a.defenseBonus, 4)
      assertEquals(a.typeTag, Some("heavy"))

  test("accessory decodes every optional bonus field"):
    for items <- ItemLoader.loadAllFromJson(fixture)
    yield
      val a = items("test_trinket").asInstanceOf[Accessory]
      assertEquals(a.hpBonus, Some(10))
      assertEquals(a.attackBonus, Some(2))
      assertEquals(a.defenseBonus, Some(3))
      assertEquals(a.critChanceBonus, Some(4))

  test("every ConsumableEffect variant decodes to its own case class"):
    for items <- ItemLoader.loadAllFromJson(fixture)
    yield
      def effectOf(typeId: String) = items(typeId).asInstanceOf[Consumable].effect
      assertEquals(effectOf("test_heal_fixed"), ConsumableEffect.HealFixed(20))
      assertEquals(effectOf("test_heal_percent"), ConsumableEffect.HealPercent(25))
      assertEquals(effectOf("test_restore"), ConsumableEffect.RestoreResource(15))
      assertEquals(effectOf("test_atk_buff"), ConsumableEffect.AttackBuff(20, 3))
      assertEquals(effectOf("test_flat_dmg"), ConsumableEffect.FlatDamage(30))
      assertEquals(effectOf("test_crit_buff"), ConsumableEffect.CritBuff(15, 3))

  test("every KeyKind variant decodes to its own case class"):
    for items <- ItemLoader.loadAllFromJson(fixture)
    yield
      def keyKindOf(typeId: String) = items(typeId).asInstanceOf[Key].keyKind
      assertEquals(keyKindOf("test_key_generic"), KeyKind.Generic)
      assertEquals(keyKindOf("test_key_universal"), KeyKind.Universal)
      assertEquals(keyKindOf("test_key_specific"), KeyKind.Specific("vault_01"))
      assertEquals(keyKindOf("test_key_typed"), KeyKind.Typed("fire"))

  test("withNewId creates a distinct non-empty instance id"):
    for items <- ItemLoader.loadAllFromJson(fixture)
    yield
      val proto    = items("test_sword")
      val instance = proto.withNewId
      assert(instance.id.nonEmpty, "Instance id must not be empty")
      assertNotEquals(proto.id, instance.id)

  test("two withNewId calls produce distinct ids"):
    for items <- ItemLoader.loadAllFromJson(fixture)
    yield
      val proto = items("test_heal_fixed")
      assertNotEquals(proto.withNewId.id, proto.withNewId.id)

  test("a weapon missing its 'attackBonus' field fails to parse"):
    val bad = """[{"typeId":"x","kind":"weapon","name":"X","rarity":"common"}]"""
    ItemLoader.loadAllFromJson(bad).attempt.map(r => assert(r.isLeft, "expected a parse failure"))

  test("an armor missing its 'defenseBonus' field fails to parse"):
    val bad = """[{"typeId":"x","kind":"armor","name":"X","rarity":"common"}]"""
    ItemLoader.loadAllFromJson(bad).attempt.map(r => assert(r.isLeft, "expected a parse failure"))

  test("a consumable missing its 'effect' field fails to parse"):
    val bad = """[{"typeId":"x","kind":"consumable","name":"X","rarity":"common"}]"""
    ItemLoader.loadAllFromJson(bad).attempt.map(r => assert(r.isLeft, "expected a parse failure"))

  test("an unknown consumable effect type fails to parse"):
    val bad = """[{"typeId":"x","kind":"consumable","name":"X","rarity":"common","effect":{"type":"NotReal"}}]"""
    ItemLoader.loadAllFromJson(bad).attempt.map(r => assert(r.isLeft, "expected a parse failure"))

  test("a specific key missing its 'doorId' field fails to parse"):
    val bad = """[{"typeId":"x","kind":"key","name":"X","rarity":"common","keyKind":"specific"}]"""
    ItemLoader.loadAllFromJson(bad).attempt.map(r => assert(r.isLeft, "expected a parse failure"))

  test("a typed key missing its 'doorTag' field fails to parse"):
    val bad = """[{"typeId":"x","kind":"key","name":"X","rarity":"common","keyKind":"typed"}]"""
    ItemLoader.loadAllFromJson(bad).attempt.map(r => assert(r.isLeft, "expected a parse failure"))

  test("an unknown keyKind fails to parse"):
    val bad = """[{"typeId":"x","kind":"key","name":"X","rarity":"common","keyKind":"bogus"}]"""
    ItemLoader.loadAllFromJson(bad).attempt.map(r => assert(r.isLeft, "expected a parse failure"))

  test("an unknown item kind fails to parse"):
    val bad = """[{"typeId":"x","kind":"bogus","name":"X","rarity":"common"}]"""
    ItemLoader.loadAllFromJson(bad).attempt.map(r => assert(r.isLeft, "expected a parse failure"))

  test("an unknown rarity fails to parse"):
    val bad = """[{"typeId":"x","kind":"weapon","name":"X","rarity":"mythic","attackBonus":1}]"""
    ItemLoader.loadAllFromJson(bad).attempt.map(r => assert(r.isLeft, "expected a parse failure"))

  // ---------------------------------------------
  // Real catalog: size-independent invariants and authoring conventions
  // ---------------------------------------------

  test("the real data/items.json resource loads successfully"):
    for items <- ItemLoader.loadAll()
    yield assert(items.nonEmpty)

  test("all real items have non-empty typeId and name"):
    for items <- ItemLoader.loadAll()
    yield items.values.foreach:
      item =>
        assert(item.typeId.nonEmpty, "typeId must not be empty")
        assert(item.name.nonEmpty, "name must not be empty")

  test("real weapons have positive attackBonus, real armors have positive defenseBonus"):
    for items <- ItemLoader.loadAll()
    yield
      items.values.collect { case w: Weapon => w }.foreach(w => assert(w.attackBonus > 0, s"${w.typeId} attackBonus must be positive"))
      items.values.collect { case a: Armor => a }.foreach(a => assert(a.defenseBonus > 0, s"${a.typeId} defenseBonus must be positive"))

  test("real accessories have at least one positive bonus"):
    for items <- ItemLoader.loadAll()
    yield items.values
      .collect { case a: Accessory => a }
      .foreach:
        a =>
          val bonuses = List(a.hpBonus, a.attackBonus, a.defenseBonus, a.critChanceBonus).flatten
          assert(bonuses.nonEmpty, s"${a.typeId} must have at least one bonus")
          assert(bonuses.forall(_ > 0), s"${a.typeId} every present bonus must be positive")

  test("every real consumable's effect values are positive"):
    for items <- ItemLoader.loadAll()
    yield items.values
      .collect { case c: Consumable => c }
      .foreach:
        c =>
          c.effect match
            case ConsumableEffect.HealFixed(amount) =>
              assert(amount > 0, s"${c.typeId} heal amount must be positive")
            case ConsumableEffect.HealPercent(pct) =>
              assert(pct > 0 && pct <= 100, s"${c.typeId} heal percent must be 1-100")
            case ConsumableEffect.RestoreResource(amount) =>
              assert(amount > 0, s"${c.typeId} restore amount must be positive")
            case ConsumableEffect.AttackBuff(percent, turns) =>
              assert(percent > 0, s"${c.typeId} attack buff percent must be positive")
              assert(turns > 0, s"${c.typeId} attack buff turns must be positive")
            case ConsumableEffect.FlatDamage(amount) =>
              assert(amount > 0, s"${c.typeId} flat damage amount must be positive")
            case ConsumableEffect.CritBuff(percent, turns) =>
              assert(percent > 0, s"${c.typeId} crit buff percent must be positive")
              assert(turns > 0, s"${c.typeId} crit buff turns must be positive")

  test("every real weapon/armor/accessory/consumable has an iconId matching its own typeId"):
    for items <- ItemLoader.loadAll()
    yield items.values.foreach:
      case item @ (_: Weapon | _: Armor | _: Accessory | _: Consumable) =>
        assertEquals(item.iconId, Some(item.typeId), s"${item.typeId} should have a matching iconId")
      case _ => () // keys have no sourced art in this pass

  test("real keys have no iconId in the current catalog"):
    for items <- ItemLoader.loadAll()
    yield items.values.foreach:
      case item: Key =>
        assertEquals(item.iconId, None, s"${item.typeId} unexpectedly has an iconId")
      case _ => ()

  // ---------------------------------------------
  // Accessory.statLine (pure, no loading required - covers the new optional bonus fields)
  // ---------------------------------------------

  test("Accessory.statLine composes only the bonuses that are present"):
    val hpOnly    = Accessory("", "t", "HP Only", Rarity.Common, hpBonus = Some(8))
    val atkOnly   = Accessory("", "t", "ATK Only", Rarity.Common, attackBonus = Some(2), typeTag = Some("heavy"))
    val defOnly   = Accessory("", "t", "Def Only", Rarity.Common, defenseBonus = Some(5))
    val critOnly  = Accessory("", "t", "Crit Only", Rarity.Common, critChanceBonus = Some(6))
    assertEquals(hpOnly.statLine, "+8 MAX HP")
    assertEquals(atkOnly.statLine, "+2 ATK [heavy]")
    assertEquals(defOnly.statLine, "+5 DEF")
    assertEquals(critOnly.statLine, "+6% CRIT")

  test("Accessory.statLine combines multiple present bonuses"):
    val combo = Accessory("", "t", "Combo", Rarity.Common, hpBonus = Some(5), attackBonus = Some(3))
    assertEquals(combo.statLine, "+5 MAX HP, +3 ATK")

  // ---------------------------------------------
  // Item.effectiveStatLine (pure) - the affinity-doubled value shown to a specific player
  // ---------------------------------------------

  test("effectiveStatLine doubles a Weapon's attackBonus when its typeTag matches affinityTags"):
    val bow = Weapon("", "t", "Bow", Rarity.Common, attackBonus = 5, typeTag = Some("ranged"))
    assertEquals(bow.effectiveStatLine(Set("ranged")), "+10 ATK [ranged]")

  test("effectiveStatLine leaves a Weapon's attackBonus unscaled when its typeTag doesn't match"):
    val bow = Weapon("", "t", "Bow", Rarity.Common, attackBonus = 5, typeTag = Some("ranged"))
    assertEquals(bow.effectiveStatLine(Set("heavy")), "+5 ATK [ranged]")
    assertEquals(bow.effectiveStatLine(Set.empty), bow.statLine)

  test("effectiveStatLine leaves an untagged Weapon unscaled regardless of affinityTags"):
    val sword = Weapon("", "t", "Sword", Rarity.Common, attackBonus = 5)
    assertEquals(sword.effectiveStatLine(Set("heavy")), sword.statLine)

  test("effectiveStatLine doubles an Armor's defenseBonus when its typeTag matches affinityTags"):
    val plate = Armor("", "t", "Plate", Rarity.Common, defenseBonus = 4, typeTag = Some("heavy"))
    assertEquals(plate.effectiveStatLine(Set("heavy")), "+8 DEF [heavy]")
    assertEquals(plate.effectiveStatLine(Set("ranged")), "+4 DEF [heavy]")

  test("effectiveStatLine doubles an Accessory's attack/defense/crit bonuses but never hpBonus"):
    val trinket = Accessory("",
                            "t",
                            "Trinket",
                            Rarity.Common,
                            hpBonus = Some(10),
                            typeTag = Some("magic"),
                            attackBonus = Some(2),
                            defenseBonus = Some(3),
                            critChanceBonus = Some(4)
    )
    assertEquals(trinket.effectiveStatLine(Set("magic")), "+10 MAX HP, +4 ATK, +6 DEF, +8% CRIT [magic]")
    assertEquals(trinket.effectiveStatLine(Set("heavy")), trinket.statLine)

  // ---------------------------------------------
  // Rarity (pure)
  // ---------------------------------------------

  test("Rarity.statMultiplier increases strictly with tier"):
    val multipliers = List(Rarity.Common, Rarity.Uncommon, Rarity.Rare, Rarity.Epic).map(_.statMultiplier)
    assertEquals(multipliers, multipliers.sorted)
    assertEquals(multipliers.distinct.size, 4, "every tier must have a distinct multiplier")

  test("Rarity.label is a distinct lowercase string per tier"):
    val labels = List(Rarity.Common, Rarity.Uncommon, Rarity.Rare, Rarity.Epic).map(_.label)
    assertEquals(labels, List("common", "uncommon", "rare", "epic"))
