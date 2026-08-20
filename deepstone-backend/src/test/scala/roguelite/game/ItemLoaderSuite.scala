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
        "second_wind",
        "battle_brew",
        "volatile_flask",
        "focus_tonic",
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

  test("every consumable's effect values are positive"):
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
            case ConsumableEffect.AttackBuff(percent, turns) =>
              assert(percent > 0, s"${c.typeId} attack buff percent must be positive")
              assert(turns > 0, s"${c.typeId} attack buff turns must be positive")
            case ConsumableEffect.FlatDamage(amount) =>
              assert(amount > 0, s"${c.typeId} flat damage amount must be positive")
            case ConsumableEffect.CritBuff(percent, turns) =>
              assert(percent > 0, s"${c.typeId} crit buff percent must be positive")
              assert(turns > 0, s"${c.typeId} crit buff turns must be positive")

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

  test("every weapon/armor/accessory/consumable has an iconId matching its own typeId"):
    for items <- ItemLoader.loadAll()
    yield items.values.foreach:
      case item @ (_: Weapon | _: Armor | _: Accessory | _: Consumable) =>
        assertEquals(item.iconId, Some(item.typeId), s"${item.typeId} should have a matching iconId")
      case _ => () // keys have no sourced art in this pass

  test("keys have no iconId in the current catalog"):
    for items <- ItemLoader.loadAll()
    yield items.values.foreach:
      case item: Key =>
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
