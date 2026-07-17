package roguelite.game

import munit.CatsEffectSuite
import roguelite.engine.ClassId

/** Tests for [[AbilityLoader]]: JSON parsing and per-class ability data. */
class AbilityLoaderSuite extends CatsEffectSuite:

  test("AbilityLoader loads one ability per class") {
    AbilityLoader
      .loadAll()
      .map:
        defs =>
          assertEquals(defs.size, 3)
          assert(defs.contains(ClassId.Warrior), "missing Warrior")
          assert(defs.contains(ClassId.Archer), "missing Archer")
          assert(defs.contains(ClassId.Mage), "missing Mage")
  }

  test("Warrior ability is Berserker Slash: 40 Rage, DoubleNextAttack") {
    AbilityLoader
      .loadAll()
      .map:
        defs =>
          val a = defs(ClassId.Warrior)
          assertEquals(a.name, "Berserker Slash")
          assertEquals(a.cost, 40)
          assertEquals(a.resourceName, "Rage")
          assertEquals(a.effect, AbilityEffect.DoubleNextAttack)
  }

  test("Archer ability is Precise Shot: 30 Focus, IgnoreDefenseNextAttack") {
    AbilityLoader
      .loadAll()
      .map:
        defs =>
          val a = defs(ClassId.Archer)
          assertEquals(a.name, "Precise Shot")
          assertEquals(a.cost, 30)
          assertEquals(a.resourceName, "Focus")
          assertEquals(a.effect, AbilityEffect.IgnoreDefenseNextAttack)
  }

  test("Mage ability is Arcane Blast: 30 Mana, 45 flat damage") {
    AbilityLoader
      .loadAll()
      .map:
        defs =>
          val a = defs(ClassId.Mage)
          assertEquals(a.name, "Arcane Blast")
          assertEquals(a.cost, 30)
          assertEquals(a.resourceName, "Mana")
          assertEquals(a.effect, AbilityEffect.FlatDamage(45))
  }

  test("every ability has a non-empty id and description") {
    AbilityLoader
      .loadAll()
      .map:
        defs =>
          defs.values.foreach:
            a =>
              assert(a.id.nonEmpty, s"${a.classId} ability has an empty id")
              assert(a.description.nonEmpty, s"${a.classId} ability has an empty description")
  }
