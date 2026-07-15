package roguelite.game

import munit.CatsEffectSuite
import roguelite.engine.ClassId

/** Tests for [[ClassLoader]]: JSON parsing, field validation, and affinity tag correctness. */
class ClassLoaderSuite extends CatsEffectSuite:

  test("ClassLoader loads all three classes") {
    ClassLoader
      .loadAll()
      .map:
        defs =>
          assertEquals(defs.size, 3)
          assert(defs.contains(ClassId.Warrior), "missing Warrior")
          assert(defs.contains(ClassId.Archer), "missing Archer")
          assert(defs.contains(ClassId.Mage), "missing Mage")
  }

  test("Warrior has correct base stats") {
    ClassLoader
      .loadAll()
      .map:
        defs =>
          val w = defs(ClassId.Warrior)
          assertEquals(w.hp, 120)
          assertEquals(w.resourceMax, 100)
          assertEquals(w.resourceStart, 0)
  }

  test("Archer has correct base stats") {
    ClassLoader
      .loadAll()
      .map:
        defs =>
          val a = defs(ClassId.Archer)
          assertEquals(a.hp, 90)
          assertEquals(a.resourceMax, 50)
          assertEquals(a.resourceStart, 50)
  }

  test("Mage has correct base stats") {
    ClassLoader
      .loadAll()
      .map:
        defs =>
          val m = defs(ClassId.Mage)
          assertEquals(m.hp, 70)
          assertEquals(m.resourceMax, 80)
          assertEquals(m.resourceStart, 80)
  }

  test("Warrior affinity is heavy") {
    ClassLoader
      .loadAll()
      .map:
        defs => assertEquals(defs(ClassId.Warrior).affinityTags, Set("heavy"))
  }

  test("Archer affinity is ranged") {
    ClassLoader
      .loadAll()
      .map:
        defs => assertEquals(defs(ClassId.Archer).affinityTags, Set("ranged"))
  }

  test("Mage affinity is magic") {
    ClassLoader
      .loadAll()
      .map:
        defs => assertEquals(defs(ClassId.Mage).affinityTags, Set("magic"))
  }

  test("every class has a non-empty starting kit") {
    ClassLoader
      .loadAll()
      .map:
        defs =>
          defs.values.foreach:
            cd => assert(cd.startingKit.nonEmpty, s"${cd.classId} has an empty startingKit")
  }

  test("starting kit typeIds are valid (exist in items.json)") {
    for
      classDefs <- ClassLoader.loadAll()
      itemDefs  <- ItemLoader.loadAll()
    yield classDefs.values.foreach:
      cd =>
        cd.startingKit.foreach:
          typeId =>
            assert(
              itemDefs.contains(typeId),
              s"${cd.classId} startingKit references unknown typeId '$typeId'"
            )
  }
