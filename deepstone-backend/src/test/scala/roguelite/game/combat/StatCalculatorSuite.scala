package roguelite.game.combat

import munit.FunSuite
import roguelite.engine.{ ClassId, Player, PlayerFixtures }
import roguelite.game.items.{ Accessory, Affinity, Armor, Rarity, SetBonus, SetBonusEffect, SetDef, Weapon }
import roguelite.game.progression.{ PerkDef, PerkEffect }

/** The numbers a stat is made of, term by term. CombatResolverSuite, AffinitySuite, SetBonusSuite and
  * PerkEffectSuite already check them through damage dealt, this checks the calculator directly, with
  * exact values and the breakdown the client lists.
  */
class StatCalculatorSuite extends FunSuite:

  private val warrior: Player = PlayerFixtures.startingPlayer(ClassId.Warrior) // 120 HP, "heavy" affinity

  private def weapon(bonus: Int, tag: Option[String] = None, setId: Option[String] = None) =
    Weapon("w", "sword", "Sword", Rarity.Common, bonus, tag, setId)

  private def armor(bonus: Int, tag: Option[String] = None, setId: Option[String] = None) =
    Armor("a", "plate", "Plate", Rarity.Common, bonus, tag, setId)

  private def accessory(
      attack: Option[Int] = None,
      defense: Option[Int] = None,
      crit: Option[Int] = None,
      tag: Option[String] = None,
      setId: Option[String] = None
  ) = Accessory("c", "ring", "Ring", Rarity.Common, None, tag, attack, defense, crit, setId)

  private def twoPieceSet(effect: SetBonusEffect, fourPiece: SetBonusEffect = SetBonusEffect.FlatDefense(0)) =
    SetDef("heavy_set", "Heavy Set", ClassId.Warrior, SetBonus(effect, "two"), SetBonus(fourPiece, "four"))

  private def calc(sets: Map[String, SetDef] = Map.empty, perks: Map[String, PerkDef] = Map.empty) =
    StatCalculator(sets, perks)

  private def terms(list: List[StatTerm]): Map[StatSource, StatTerm] = list.map(t => t.source -> t).toMap

  test("a bare player's attack is level x 5 plus a tenth of max HP"):
    val c = calc()
    assertEquals(c.attack(warrior), 5 + 12)
    assertEquals(terms(c.attackTerms(warrior))(StatSource.Level).value, 5)
    assertEquals(terms(c.attackTerms(warrior))(StatSource.MaxHp).value, 12)

  test("a bare player's defense is level x 2"):
    assertEquals(calc().defense(warrior), 2)

  test("a bare player has no crit chance"):
    assertEquals(calc().critChance(warrior), 0)

  test("level-up bonuses count in attack and in defense"):
    val p = warrior.copy(level = 4, bonusAttack = 3, bonusDefense = 2)
    assertEquals(calc().attack(p), 20 + 12 + 3)
    assertEquals(calc().defense(p), 8 + 2)

  test("a weapon off the class affinity counts once"):
    val p = warrior.copy(equippedWeapon = Some(weapon(6, Some("magic"))))
    val t = terms(calc().attackTerms(p))(StatSource.Weapon)
    assertEquals(t.value, 6)
    assertEquals(t.affinity, false)

  test("a weapon on the class affinity counts twice and says so"):
    val p = warrior.copy(equippedWeapon = Some(weapon(6, Some("heavy"))))
    val t = terms(calc().attackTerms(p))(StatSource.Weapon)
    assertEquals(t.value, 12)
    assertEquals(t.affinity, true)
    assertEquals(calc().attack(p), 5 + 12 + 12)

  test("a weapon with no tag counts once, and a bonus of 0 is not flagged as doubled"):
    val untagged = warrior.copy(equippedWeapon = Some(weapon(4)))
    assertEquals(terms(calc().attackTerms(untagged))(StatSource.Weapon).value, 4)
    val zero = warrior.copy(equippedWeapon = Some(weapon(0, Some("heavy"))))
    assertEquals(terms(calc().attackTerms(zero))(StatSource.Weapon).affinity, false)

  test("no weapon means no weapon term at all"):
    assert(!terms(calc().attackTerms(warrior)).contains(StatSource.Weapon))

  test("armor follows the same affinity rule"):
    val matching = warrior.copy(equippedArmor = Some(armor(5, Some("heavy"))))
    assertEquals(terms(calc().defenseTerms(matching))(StatSource.Armor), StatTerm(StatSource.Armor, 10, affinity = true))
    val other = warrior.copy(equippedArmor = Some(armor(5, Some("light"))))
    assertEquals(terms(calc().defenseTerms(other))(StatSource.Armor), StatTerm(StatSource.Armor, 5))

  test("each accessory is judged on its own tag"):
    val p = warrior.copy(equippedAccessories =
      Vector(Some(accessory(attack = Some(3), tag = Some("heavy"))), Some(accessory(attack = Some(2), tag = Some("magic"))))
    )
    val t = terms(calc().attackTerms(p))(StatSource.Accessories)
    assertEquals(t.value, 3 * 2 + 2)
    assertEquals(t.affinity, true)

  test("accessories that match nothing are not flagged"):
    val p = warrior.copy(equippedAccessories = Vector(Some(accessory(defense = Some(4), tag = Some("magic"))), None))
    assertEquals(terms(calc().defenseTerms(p))(StatSource.Accessories), StatTerm(StatSource.Accessories, 4))

  test("crit chance comes from the accessories alone, with the affinity doubling"):
    val p = warrior.copy(
      equippedWeapon = Some(weapon(9, Some("heavy"))),
      equippedAccessories = Vector(Some(accessory(crit = Some(4), tag = Some("heavy"))), Some(accessory(crit = Some(3))))
    )
    assertEquals(calc().critChance(p), 4 * 2 + 3)

  test("crit chance is not capped here, 100 or more is a guaranteed crit"):
    val p = warrior.copy(equippedAccessories = Vector(Some(accessory(crit = Some(70), tag = Some("heavy"))), None))
    assertEquals(calc().critChance(p), 140)

  test("an active set adds its flat attack, flat defense and crit bonuses"):
    val sets = Map("heavy_set" -> twoPieceSet(SetBonusEffect.FlatAttack(3), SetBonusEffect.CritChancePercent(5)))
    val p = warrior.copy(
      equippedWeapon = Some(weapon(1, setId = Some("heavy_set"))),
      equippedArmor = Some(armor(1, setId = Some("heavy_set")))
    )
    // two pieces: the 2pc bonus only
    assertEquals(terms(calc(sets).attackTerms(p))(StatSource.Set).value, 3)
    assertEquals(calc(sets).critChance(p), 0)
    // four pieces: both
    val full = p.copy(equippedAccessories =
      Vector(Some(accessory(setId = Some("heavy_set"))), Some(accessory(setId = Some("heavy_set"))))
    )
    assertEquals(calc(sets).critChance(full), 5)

  test("a set bonus counts for the class the set is made for only"):
    val sets = Map("heavy_set" -> twoPieceSet(SetBonusEffect.FlatDefense(4)))
    val gear = (warrior: Player) =>
      warrior.copy(
        equippedWeapon = Some(weapon(1, setId = Some("heavy_set"))),
        equippedArmor = Some(armor(1, setId = Some("heavy_set")))
      )
    assertEquals(terms(calc(sets).defenseTerms(gear(warrior)))(StatSource.Set).value, 4)
    val archer = PlayerFixtures.startingPlayer(ClassId.Archer)
    assertEquals(terms(calc(sets).defenseTerms(gear(archer)))(StatSource.Set).value, 0)

  test("the flat damage perk adds to attack"):
    val perks = Map("heavy_hand" -> PerkDef("heavy_hand", "Heavy Hand", "+2", "icon", PerkEffect.FlatDamageBonus(2)))
    val p     = warrior.copy(activePerkId = Some("heavy_hand"))
    assertEquals(terms(calc(perks = perks).attackTerms(p))(StatSource.Perk).value, 2)
    assertEquals(calc(perks = perks).attack(p), calc().attack(warrior) + 2)

  test("a perk id missing from the catalog adds nothing"):
    assertEquals(calc().attack(warrior.copy(activePerkId = Some("ghost"))), calc().attack(warrior))

  test("a stat is always the sum of its terms"):
    val p = warrior.copy(
      level = 3,
      bonusAttack = 1,
      equippedWeapon = Some(weapon(5, Some("heavy"))),
      equippedAccessories = Vector(Some(accessory(attack = Some(2))), None)
    )
    val c = calc()
    assertEquals(c.attack(p), c.attackTerms(p).map(_.value).sum)
    assertEquals(c.defense(p), c.defenseTerms(p).map(_.value).sum)
    assertEquals(c.critChance(p), c.critTerms(p).map(_.value).sum)

  test("the set damage percent and the first-attack crit read the active set bonuses"):
    val sets = Map("heavy_set" -> twoPieceSet(SetBonusEffect.AttackDamagePercent(10), SetBonusEffect.FirstAttackAlwaysCrit))
    val two = warrior.copy(
      equippedWeapon = Some(weapon(1, setId = Some("heavy_set"))),
      equippedArmor = Some(armor(1, setId = Some("heavy_set")))
    )
    assertEquals(calc(sets).setDamagePercent(two), 10)
    assertEquals(calc(sets).firstAttackAlwaysCrits(two), false)
    val four = two.copy(equippedAccessories =
      Vector(Some(accessory(setId = Some("heavy_set"))), Some(accessory(setId = Some("heavy_set"))))
    )
    assertEquals(calc(sets).firstAttackAlwaysCrits(four), true)
    assertEquals(calc().setDamagePercent(warrior), 0)

  test("the affinity rule doubles on a match only"):
    assertEquals(Affinity.multiplier(Some("heavy"), Set("heavy")), 2)
    assertEquals(Affinity.multiplier(Some("magic"), Set("heavy")), 1)
    assertEquals(Affinity.multiplier(None, Set("heavy")), 1)
    assertEquals(Affinity.matches(Some("heavy"), Set.empty), false)

  test("the crit multiplier is shared with the combat math"):
    assertEquals(StatCalculator.CritMultiplier, 1.5)
