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
            "extra_slot",
            "extra_potion_capacity",
            "weapon_mastery",
            "rarity_insight",
            "warrior_kit",
            "archer_kit",
            "mage_kit"
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

  test("extra_slot decodes to ExtraPotionSlot") {
    UpgradeLoader
      .loadAll()
      .map:
        defs => assertEquals(defs("extra_slot").effect, UpgradeEffect.ExtraPotionSlot)
  }

  test("extra_potion_capacity decodes to ExtraPotionCapacity") {
    UpgradeLoader
      .loadAll()
      .map:
        defs => assertEquals(defs("extra_potion_capacity").effect, UpgradeEffect.ExtraPotionCapacity)
  }

  test("weapon_mastery decodes to FlatAttackBoost") {
    UpgradeLoader
      .loadAll()
      .map:
        defs => assertEquals(defs("weapon_mastery").effect, UpgradeEffect.FlatAttackBoost(1))
  }

  test("rarity_insight decodes to GuaranteedChestRarity") {
    UpgradeLoader
      .loadAll()
      .map:
        defs =>
          assertEquals(defs("rarity_insight").effect, UpgradeEffect.GuaranteedChestRarity(Rarity.Uncommon))
  }

  test("archer_unlock and mage_unlock decode to UnlockClass") {
    UpgradeLoader
      .loadAll()
      .map:
        defs =>
          assertEquals(defs("archer_unlock").effect, UpgradeEffect.UnlockClass(ClassId.Archer))
          assertEquals(defs("mage_unlock").effect, UpgradeEffect.UnlockClass(ClassId.Mage))
  }

  test("warrior_kit, archer_kit, and mage_kit decode to UnlockStartingKit") {
    UpgradeLoader
      .loadAll()
      .map:
        defs =>
          assertEquals(defs("warrior_kit").effect, UpgradeEffect.UnlockStartingKit(ClassId.Warrior))
          assertEquals(defs("archer_kit").effect, UpgradeEffect.UnlockStartingKit(ClassId.Archer))
          assertEquals(defs("mage_kit").effect, UpgradeEffect.UnlockStartingKit(ClassId.Mage))
  }

  test("every upgrade has a positive cost") {
    UpgradeLoader
      .loadAll()
      .map:
        defs =>
          defs.values.foreach:
            u => assert(u.cost > 0, s"${u.id} has non-positive cost ${u.cost}")
  }

  test("categories match the STATS/META split") {
    UpgradeLoader
      .loadAll()
      .map:
        defs =>
          val statIds = Set("hp_boost_1",
                            "hp_boost_2",
                            "extra_slot",
                            "extra_potion_capacity",
                            "weapon_mastery",
                            "rarity_insight"
          )
          val metaIds =
            Set("potion_start", "archer_unlock", "mage_unlock", "warrior_kit", "archer_kit", "mage_kit")
          statIds.foreach(id => assertEquals(defs(id).category, UpgradeCategory.Stat, id))
          metaIds.foreach(id => assertEquals(defs(id).category, UpgradeCategory.Meta, id))
  }

  test("every upgrade has a non-empty icon") {
    UpgradeLoader
      .loadAll()
      .map:
        defs =>
          defs.values.foreach:
            u => assert(u.icon.nonEmpty, s"${u.id} has no icon")
  }
