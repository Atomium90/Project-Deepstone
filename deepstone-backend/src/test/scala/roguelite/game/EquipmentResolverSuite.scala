package roguelite.game

import munit.FunSuite
import roguelite.engine.{ ClassId, Player, PlayerFixtures }

/** Tests for [[EquipmentResolver.resolvePickup]]: auto-equip into an empty slot, discard on a
  * collision. Every item kind x empty/occupied slot combination is exercised directly here,
  * since the tests that check weapon/armor/potion bonuses elsewhere in this codebase construct
  * an already-equipped Player via `.copy` and never actually call `resolvePickup` itself.
  */
class EquipmentResolverSuite extends FunSuite:

  private def player: Player = PlayerFixtures.startingPlayer(ClassId.Warrior)

  private val sword  = Weapon("w1", "iron_sword", "Iron Sword", Rarity.Common, attackBonus = 3)
  private val sword2 = Weapon("w2", "steel_sword", "Steel Sword", Rarity.Uncommon, attackBonus = 7)
  private val armor  = Armor("a1", "leather_armor", "Leather Armor", Rarity.Common, defenseBonus = 2)
  private val armor2 = Armor("a2", "chain_mail", "Chain Mail", Rarity.Uncommon, defenseBonus = 6)
  private val ring   = Accessory("r1", "iron_ring", "Iron Ring", Rarity.Common, hpBonus = 10)
  private val ring2  = Accessory("r2", "gold_ring", "Gold Ring", Rarity.Uncommon, hpBonus = 15)
  private val potion =
    Consumable("p1", "health_potion", "Health Potion", Rarity.Common, ConsumableEffect.HealFixed(30))
  private val ether =
    Consumable("p2", "ether", "Ether", Rarity.Uncommon, ConsumableEffect.RestoreResource(20))
  private val key = Key("k1", "rusty_key", "Rusty Key", Rarity.Common, KeyKind.Generic)

  // --- Weapon ------------------------------------------------------------------

  test("Weapon into an empty weapon slot equips it"):
    EquipmentResolver.resolvePickup(player, sword) match
      case PickupOutcome.Equipped(p) => assertEquals(p.equippedWeapon, Some(sword))
      case other                     => fail(s"expected Equipped, got $other")

  test("Weapon into an occupied weapon slot is discarded"):
    val equipped = player.copy(equippedWeapon = Some(sword))
    EquipmentResolver.resolvePickup(equipped, sword2) match
      case PickupOutcome.Discarded => ()
      case other                   => fail(s"expected Discarded, got $other")

  // --- Armor -------------------------------------------------------------------

  test("Armor into an empty armor slot equips it"):
    EquipmentResolver.resolvePickup(player, armor) match
      case PickupOutcome.Equipped(p) => assertEquals(p.equippedArmor, Some(armor))
      case other                     => fail(s"expected Equipped, got $other")

  test("Armor into an occupied armor slot is discarded"):
    val equipped = player.copy(equippedArmor = Some(armor))
    EquipmentResolver.resolvePickup(equipped, armor2) match
      case PickupOutcome.Discarded => ()
      case other                   => fail(s"expected Discarded, got $other")

  // --- Accessory -----------------------------------------------------------------

  test("Accessory into an empty accessory slot equips it and bumps maxHp"):
    val baseMaxHp = player.maxHp
    EquipmentResolver.resolvePickup(player, ring) match
      case PickupOutcome.Equipped(p) =>
        assertEquals(p.equippedAccessories(0), Some(ring))
        assertEquals(p.maxHp, baseMaxHp + ring.hpBonus)
      case other => fail(s"expected Equipped, got $other")

  test("Accessory fills the first empty accessory slot"):
    val oneEquipped = player.copy(equippedAccessories = Vector(Some(ring), None))
    EquipmentResolver.resolvePickup(oneEquipped, ring2) match
      case PickupOutcome.Equipped(p) =>
        assertEquals(p.equippedAccessories, Vector(Some(ring), Some(ring2)))
      case other => fail(s"expected Equipped, got $other")

  test("Accessory pickup is discarded once both accessory slots are full"):
    val bothFull = player.copy(equippedAccessories = Vector(Some(ring), Some(ring2)))
    EquipmentResolver.resolvePickup(bothFull, ring) match
      case PickupOutcome.Discarded => ()
      case other                   => fail(s"expected Discarded, got $other")

  // --- Consumable (potion belt) -------------------------------------------------

  test("Consumable into an empty potion belt slot equips it"):
    EquipmentResolver.resolvePickup(player, potion) match
      case PickupOutcome.Equipped(p) => assertEquals(p.potionBelt(0), Some(potion))
      case other                     => fail(s"expected Equipped, got $other")

  test("Consumable fills the first empty potion belt slot"):
    val oneSlotUsed = player.copy(potionBelt = Vector(Some(potion), None))
    EquipmentResolver.resolvePickup(oneSlotUsed, ether) match
      case PickupOutcome.Equipped(p) => assertEquals(p.potionBelt, Vector(Some(potion), Some(ether)))
      case other                     => fail(s"expected Equipped, got $other")

  test("Consumable pickup is discarded once the potion belt is full"):
    val beltFull = player.copy(potionBelt = Vector(Some(potion), Some(ether)))
    EquipmentResolver.resolvePickup(beltFull, potion) match
      case PickupOutcome.Discarded => ()
      case other                   => fail(s"expected Discarded, got $other")

  // --- Key ---------------------------------------------------------------------

  test("Key pickup increments the matching KeyKind counter, never a choice"):
    EquipmentResolver.resolvePickup(player, key) match
      case PickupOutcome.KeyCollected(p) => assertEquals(p.keyCounts.getOrElse(KeyKind.Generic, 0), 1)
      case other                         => fail(s"expected KeyCollected, got $other")

  test("A second key of the same kind increments the counter again"):
    val withOne = player.copy(keyCounts = Map(KeyKind.Generic -> 1))
    EquipmentResolver.resolvePickup(withOne, key) match
      case PickupOutcome.KeyCollected(p) => assertEquals(p.keyCounts(KeyKind.Generic), 2)
      case other                         => fail(s"expected KeyCollected, got $other")

  test("Different key kinds are counted independently"):
    val specificKey = Key("k2", "special_key", "Special Key", Rarity.Common, KeyKind.Specific("door_1"))
    val withGeneric  = player.copy(keyCounts = Map(KeyKind.Generic -> 1))
    EquipmentResolver.resolvePickup(withGeneric, specificKey) match
      case PickupOutcome.KeyCollected(p) =>
        assertEquals(p.keyCounts(KeyKind.Generic), 1)
        assertEquals(p.keyCounts(KeyKind.Specific("door_1")), 1)
      case other => fail(s"expected KeyCollected, got $other")
