package roguelite.game

import munit.CatsEffectSuite

class ItemLoaderSuite extends CatsEffectSuite:

  test("loadAll returns a non-empty map"):
    for items <- ItemLoader.loadAll()
    yield assert(items.nonEmpty)

  test("loadAll contains all expected item types"):
    for items <- ItemLoader.loadAll()
    yield
      val expected = List(
        "steel_sword",
        "wooden_emerald_bow",
        "steel_plate",
        "leather_cape",
        "ring_of_strength",
        "skull_talisman",
        "health_potion",
        "greater_potion",
        "ether",
        "rusty_key"
      )
      expected.foreach:
        typeId => assert(items.contains(typeId), s"Missing item type: $typeId")

  test("prototype items have empty id"):
    for items <- ItemLoader.loadAll()
    yield items.values.foreach:
      item => assertEquals(item.id, "", s"${item.typeId} prototype should have empty id")

  test("all items have non-empty typeId and name"):
    for items <- ItemLoader.loadAll()
    yield items.values.foreach:
      item =>
        assert(item.typeId.nonEmpty, "typeId must not be empty")
        assert(item.name.nonEmpty, "name must not be empty")

  test("weapons have positive attackBonus"):
    for items <- ItemLoader.loadAll()
    yield items.values
      .collect {
        case w: Weapon => w
      }
      .foreach:
        w => assert(w.attackBonus > 0, s"${w.typeId} attackBonus must be positive")

  test("armors have positive defenseBonus"):
    for items <- ItemLoader.loadAll()
    yield items.values
      .collect {
        case a: Armor => a
      }
      .foreach:
        a => assert(a.defenseBonus > 0, s"${a.typeId} defenseBonus must be positive")

  test("accessories have at least one positive bonus"):
    for items <- ItemLoader.loadAll()
    yield items.values
      .collect {
        case a: Accessory => a
      }
      .foreach:
        a =>
          val bonuses = List(a.hpBonus, a.attackBonus, a.defenseBonus, a.critChanceBonus).flatten
          assert(bonuses.nonEmpty, s"${a.typeId} must have at least one bonus")
          assert(bonuses.forall(_ > 0), s"${a.typeId} every present bonus must be positive")

  test("consumables have a valid effect"):
    for items <- ItemLoader.loadAll()
    yield items.values
      .collect {
        case c: Consumable => c
      }
      .foreach:
        c => assert(c.effect != null, s"${c.typeId} must have an effect")

  test("HealFixed consumables have positive amount"):
    for items <- ItemLoader.loadAll()
    yield items.values
      .collect {
        case c: Consumable => c
      }
      .foreach:
        c =>
          c.effect match
            case ConsumableEffect.HealFixed(amount) =>
              assert(amount > 0, s"${c.typeId} heal amount must be positive")
            case ConsumableEffect.HealPercent(pct) =>
              assert(pct > 0 && pct <= 100, s"${c.typeId} heal percent must be 1-100")
            case ConsumableEffect.RestoreResource(amount) =>
              assert(amount > 0, s"${c.typeId} restore amount must be positive")

  test("uncommon items have higher stat bonuses than their common counterparts"):
    // Armor has no generic (non-set) common tier in the current catalog (all 6 armor rows are
    // set pieces, all uncommon - a known, accepted content gap), so only weapons are comparable
    // here.
    for items <- ItemLoader.loadAll()
    yield
      val practiceSword = items("practice_sword").asInstanceOf[Weapon]
      val steelSword     = items("steel_sword").asInstanceOf[Weapon]
      assert(steelSword.attackBonus > practiceSword.attackBonus,
             "Steel Sword (uncommon) should have higher attack than Practice Sword (common)"
      )

  test("withNewId creates a distinct non-empty instance id"):
    for items <- ItemLoader.loadAll()
    yield
      val proto    = items("steel_sword")
      val instance = proto.withNewId
      assert(instance.id.nonEmpty, "Instance id must not be empty")
      assertNotEquals(proto.id, instance.id)

  test("two withNewId calls produce distinct ids"):
    for items <- ItemLoader.loadAll()
    yield
      val proto = items("health_potion")
      assertNotEquals(proto.withNewId.id, proto.withNewId.id)

  test("loadAll contains a Generic key"):
    for items <- ItemLoader.loadAll()
    yield
      items.get("rusty_key") match
        case Some(k: Key) => assertEquals(k.keyKind, KeyKind.Generic)
        case other        => fail(s"expected rusty_key to be a Key, got: $other")

  test("every weapon/armor/accessory has an iconId matching its own typeId"):
    for items <- ItemLoader.loadAll()
    yield items.values.foreach:
      case item @ (_: Weapon | _: Armor | _: Accessory) =>
        assertEquals(item.iconId, Some(item.typeId), s"${item.typeId} should have a matching iconId")
      case _ => () // consumables/keys have no sourced art in this pass

  test("consumables and keys have no iconId in the current catalog"):
    for items <- ItemLoader.loadAll()
    yield items.values.foreach:
      case item @ (_: Consumable | _: Key) =>
        assertEquals(item.iconId, None, s"${item.typeId} unexpectedly has an iconId")
      case _ => ()

  // ---------------------------------------------
  // Accessory.statLine (pure, no loading required - covers the new optional bonus fields)
  // ---------------------------------------------

  test("Accessory.statLine composes only the bonuses that are present"):
    val hpOnly  = Accessory("", "t", "HP Only", Rarity.Common, hpBonus = Some(8))
    val atkOnly = Accessory("", "t", "ATK Only", Rarity.Common, attackBonus = Some(2), typeTag = Some("heavy"))
    val defOnly = Accessory("", "t", "Def Only", Rarity.Common, defenseBonus = Some(5))
    val critOnly = Accessory("", "t", "Crit Only", Rarity.Common, critChanceBonus = Some(6))
    assertEquals(hpOnly.statLine, "+8 MAX HP")
    assertEquals(atkOnly.statLine, "+2 ATK [heavy]")
    assertEquals(defOnly.statLine, "+5 DEF")
    assertEquals(critOnly.statLine, "+6% CRIT")

  test("Accessory.statLine combines multiple present bonuses"):
    val combo = Accessory("", "t", "Combo", Rarity.Common, hpBonus = Some(5), attackBonus = Some(3))
    assertEquals(combo.statLine, "+5 MAX HP, +3 ATK")
