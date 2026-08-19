package roguelite.game

import munit.CatsEffectSuite
import roguelite.engine.ClassId

/** Tests for [[SetLoader]]: JSON parsing and the 6 authored set definitions. */
class SetLoaderSuite extends CatsEffectSuite:

  test("SetLoader loads all 6 sets") {
    SetLoader
      .loadAll()
      .map:
        defs =>
          assertEquals(
            defs.keySet,
            Set("light_soldier",
                "enraged_berserker",
                "silent_archer",
                "iron_bolter",
                "pyromancer",
                "necromancer"
            )
          )
  }

  test("every set is assigned to a real class") {
    SetLoader
      .loadAll()
      .map:
        defs =>
          defs.values.foreach:
            s => assert(Set(ClassId.Warrior, ClassId.Archer, ClassId.Mage).contains(s.classId))
  }

  test("light_soldier: +5% max HP at 2pc, +2 flat DEF at 4pc") {
    SetLoader
      .loadAll()
      .map:
        defs =>
          val s = defs("light_soldier")
          assertEquals(s.classId, ClassId.Warrior)
          assertEquals(s.twoPiece.effect, SetBonusEffect.MaxHpPercent(5))
          assertEquals(s.fourPiece.effect, SetBonusEffect.FlatDefense(2))
  }

  test("enraged_berserker: +5% crit at 2pc, +10% attack damage at 4pc") {
    SetLoader
      .loadAll()
      .map:
        defs =>
          val s = defs("enraged_berserker")
          assertEquals(s.twoPiece.effect, SetBonusEffect.CritChancePercent(5))
          assertEquals(s.fourPiece.effect, SetBonusEffect.AttackDamagePercent(10))
  }

  test("silent_archer: +5% crit at 2pc, first attack always crits at 4pc") {
    SetLoader
      .loadAll()
      .map:
        defs =>
          val s = defs("silent_archer")
          assertEquals(s.classId, ClassId.Archer)
          assertEquals(s.twoPiece.effect, SetBonusEffect.CritChancePercent(5))
          assertEquals(s.fourPiece.effect, SetBonusEffect.FirstAttackAlwaysCrit)
  }

  test("iron_bolter: +3 flat ATK at 2pc, +8% max HP at 4pc") {
    SetLoader
      .loadAll()
      .map:
        defs =>
          val s = defs("iron_bolter")
          assertEquals(s.twoPiece.effect, SetBonusEffect.FlatAttack(3))
          assertEquals(s.fourPiece.effect, SetBonusEffect.MaxHpPercent(8))
  }

  test("pyromancer: +10% attack damage at 2pc, -15% ability cost at 4pc") {
    SetLoader
      .loadAll()
      .map:
        defs =>
          val s = defs("pyromancer")
          assertEquals(s.classId, ClassId.Mage)
          assertEquals(s.twoPiece.effect, SetBonusEffect.AttackDamagePercent(10))
          assertEquals(s.fourPiece.effect, SetBonusEffect.AbilityCostReductionPercent(15))
  }

  test("necromancer: +10% max HP at 2pc, heal 10% of kill damage at 4pc") {
    SetLoader
      .loadAll()
      .map:
        defs =>
          val s = defs("necromancer")
          assertEquals(s.twoPiece.effect, SetBonusEffect.MaxHpPercent(10))
          assertEquals(s.fourPiece.effect, SetBonusEffect.HealOnKillPercent(10))
  }

  test("every set bonus has a non-empty label") {
    SetLoader
      .loadAll()
      .map:
        defs =>
          defs.values.foreach:
            s =>
              assert(s.twoPiece.label.nonEmpty, s"${s.id} 2pc label is empty")
              assert(s.fourPiece.label.nonEmpty, s"${s.id} 4pc label is empty")
  }
