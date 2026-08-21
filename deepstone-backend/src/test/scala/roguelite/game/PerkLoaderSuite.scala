package roguelite.game

import munit.CatsEffectSuite

/** Tests for [[PerkLoader]]: JSON parsing and effect decoding for every perk kind. */
class PerkLoaderSuite extends CatsEffectSuite:

  test("PerkLoader loads all expected perk ids") {
    PerkLoader
      .loadAll()
      .map:
        defs => assertEquals(defs.keySet, Set("well_stocked", "heavy_hand", "herbalist_blessing"))
  }

  test("well_stocked decodes to ExtraStartingItem(health_potion)") {
    PerkLoader
      .loadAll()
      .map:
        defs => assertEquals(defs("well_stocked").effect, PerkEffect.ExtraStartingItem("health_potion"))
  }

  test("heavy_hand decodes to FlatDamageBonus(1)") {
    PerkLoader
      .loadAll()
      .map:
        defs => assertEquals(defs("heavy_hand").effect, PerkEffect.FlatDamageBonus(1))
  }

  test("herbalist_blessing decodes to PotionHealBonusPercent(50)") {
    PerkLoader
      .loadAll()
      .map:
        defs => assertEquals(defs("herbalist_blessing").effect, PerkEffect.PotionHealBonusPercent(50))
  }

  test("every perk has a non-empty icon") {
    PerkLoader
      .loadAll()
      .map:
        defs =>
          defs.values.foreach:
            p => assert(p.icon.nonEmpty, s"${p.id} has no icon")
  }
