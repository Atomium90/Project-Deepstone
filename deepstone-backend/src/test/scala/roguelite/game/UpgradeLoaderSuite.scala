package roguelite.game

import munit.CatsEffectSuite
import roguelite.engine.ClassId

/** Tests for [[UpgradeLoader]]: JSON parsing and effect decoding for every upgrade kind. */
class UpgradeLoaderSuite extends CatsEffectSuite:

  test("UpgradeLoader loads all expected upgrade ids") {
    UpgradeLoader
      .loadAll()
      .map:
        defs =>
          val expectedIds = Set(
            "hp_boost_1",
            "hp_boost_2",
            "potion_start",
            "archer_unlock",
            "mage_unlock",
            "extra_slot"
          )
          assertEquals(defs.keySet, expectedIds)
  }

  test("displayOrder values are unique") {
    UpgradeLoader
      .loadAll()
      .map:
        defs =>
          val orders = defs.values.map(_.displayOrder).toList
          assertEquals(orders.distinct.length, orders.length, "duplicate displayOrder found")
  }

  test("hp_boost_1 and hp_boost_2 decode to MaxHpBoost with the right amounts") {
    UpgradeLoader
      .loadAll()
      .map:
        defs =>
          assertEquals(defs("hp_boost_1").effect, UpgradeEffect.MaxHpBoost(20))
          assertEquals(defs("hp_boost_2").effect, UpgradeEffect.MaxHpBoost(40))
  }

  test("potion_start decodes to StartingItem(health_potion)") {
    UpgradeLoader
      .loadAll()
      .map:
        defs => assertEquals(defs("potion_start").effect, UpgradeEffect.StartingItem("health_potion"))
  }

  test("extra_slot decodes to ExtraInventorySlot") {
    UpgradeLoader
      .loadAll()
      .map:
        defs => assertEquals(defs("extra_slot").effect, UpgradeEffect.ExtraInventorySlot)
  }

  test("archer_unlock and mage_unlock decode to UnlockClass") {
    UpgradeLoader
      .loadAll()
      .map:
        defs =>
          assertEquals(defs("archer_unlock").effect, UpgradeEffect.UnlockClass(ClassId.Archer))
          assertEquals(defs("mage_unlock").effect, UpgradeEffect.UnlockClass(ClassId.Mage))
  }

  test("every upgrade has a positive cost") {
    UpgradeLoader
      .loadAll()
      .map:
        defs =>
          defs.values.foreach:
            u => assert(u.cost > 0, s"${u.id} has non-positive cost ${u.cost}")
  }
