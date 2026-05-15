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
        "iron_sword",
        "steel_sword",
        "hunters_bow",
        "leather_armor",
        "chain_mail",
        "iron_ring",
        "amulet_of_vigor",
        "health_potion",
        "greater_potion",
        "ether"
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

  test("accessories have positive hpBonus"):
    for items <- ItemLoader.loadAll()
    yield items.values
      .collect {
        case a: Accessory => a
      }
      .foreach:
        a => assert(a.hpBonus > 0, s"${a.typeId} hpBonus must be positive")

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
    for items <- ItemLoader.loadAll()
    yield
      val ironSword  = items("iron_sword").asInstanceOf[Weapon]
      val steelSword = items("steel_sword").asInstanceOf[Weapon]
      assert(steelSword.attackBonus > ironSword.attackBonus,
             "Steel Sword (uncommon) should have higher attack than Iron Sword (common)"
      )

      val leather = items("leather_armor").asInstanceOf[Armor]
      val chain   = items("chain_mail").asInstanceOf[Armor]
      assert(chain.defenseBonus > leather.defenseBonus,
             "Chain Mail (uncommon) should have higher defense than Leather Armor (common)"
      )

  test("withNewId creates a distinct non-empty instance id"):
    for items <- ItemLoader.loadAll()
    yield
      val proto    = items("iron_sword")
      val instance = proto.withNewId
      assert(instance.id.nonEmpty, "Instance id must not be empty")
      assertNotEquals(proto.id, instance.id)

  test("two withNewId calls produce distinct ids"):
    for items <- ItemLoader.loadAll()
    yield
      val proto = items("health_potion")
      assertNotEquals(proto.withNewId.id, proto.withNewId.id)
