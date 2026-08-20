package roguelite.game

import munit.CatsEffectSuite

/** Tests for [[PerkLoader]]: JSON parsing and effect decoding for every perk kind. */
class PerkLoaderSuite extends CatsEffectSuite:

  test("PerkLoader loads all expected perk ids") {
    PerkLoader
      .loadAll()
      .map:
        defs => assertEquals(defs.keySet, Set("well_stocked"))
  }

  test("well_stocked decodes to ExtraStartingItem(health_potion)") {
    PerkLoader
      .loadAll()
      .map:
        defs => assertEquals(defs("well_stocked").effect, PerkEffect.ExtraStartingItem("health_potion"))
  }

  test("every perk has a non-empty icon") {
    PerkLoader
      .loadAll()
      .map:
        defs =>
          defs.values.foreach:
            p => assert(p.icon.nonEmpty, s"${p.id} has no icon")
  }
