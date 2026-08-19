package roguelite.game

import munit.FunSuite
import roguelite.engine.{ ClassId, ExplorationState, Player, PlayerFixtures }

/** Tests for [[EquipmentResolver]]: `resolvePickup` (auto-equip into an empty slot, offer a
  * choice on a collision) and `resolveChoice` (resolve that choice). Every item kind x
  * empty/occupied slot combination is exercised directly here, since the tests that check
  * weapon/armor/potion bonuses elsewhere in this codebase construct an already-equipped Player
  * via `.copy` and never actually call `resolvePickup` itself.
  */
class EquipmentResolverSuite extends FunSuite:

  private def player: Player = PlayerFixtures.startingPlayer(ClassId.Warrior)

  private def testDungeon: Dungeon =
    Dungeon(rooms = Map("r1" -> Room("r1", RoomType.Combat, 4, 4, Vector.fill(4)(Vector.fill(4)(Tile.Floor)), Nil)),
            currentRoomId = "r1"
    )

  private def explorationState(p: Player = player,
                               pending: Option[PendingEquipChoice] = None
  ): ExplorationState =
    ExplorationState(p, testDungeon, playerX = 1, playerY = 1, pendingEquipChoice = pending)

  private val sword  = Weapon("w1", "iron_sword", "Iron Sword", Rarity.Common, attackBonus = 3)
  private val sword2 = Weapon("w2", "steel_sword", "Steel Sword", Rarity.Uncommon, attackBonus = 7)
  private val armor  = Armor("a1", "leather_armor", "Leather Armor", Rarity.Common, defenseBonus = 2)
  private val armor2 = Armor("a2", "chain_mail", "Chain Mail", Rarity.Uncommon, defenseBonus = 6)
  private val ring   = Accessory("r1", "iron_ring", "Iron Ring", Rarity.Common, hpBonus = Some(10))
  private val ring2  = Accessory("r2", "gold_ring", "Gold Ring", Rarity.Uncommon, hpBonus = Some(15))
  private val ring3  = Accessory("r3", "ruby_ring", "Ruby Ring", Rarity.Uncommon, hpBonus = Some(20))
  private val potion =
    Consumable("p1", "health_potion", "Health Potion", Rarity.Common, ConsumableEffect.HealFixed(30))
  private val ether =
    Consumable("p2", "ether", "Ether", Rarity.Uncommon, ConsumableEffect.RestoreResource(20))
  private val key = Key("k1", "rusty_key", "Rusty Key", Rarity.Common, KeyKind.Generic)

  // --- resolvePickup: Weapon -----------------------------------------------------

  test("Weapon into an empty weapon slot equips it"):
    EquipmentResolver.resolvePickup(player, sword) match
      case PickupOutcome.Equipped(p) => assertEquals(p.equippedWeapon, Some(sword))
      case other                     => fail(s"expected Equipped, got $other")

  test("Weapon into an occupied weapon slot offers a choice against the current weapon"):
    val equipped = player.copy(equippedWeapon = Some(sword))
    EquipmentResolver.resolvePickup(equipped, sword2) match
      case PickupOutcome.ChoicePending(pending) =>
        assertEquals(pending.newItem, sword2)
        assertEquals(pending.currentItems, Map(EquipSlot.WeaponSlot -> sword))
      case other => fail(s"expected ChoicePending, got $other")

  // --- resolvePickup: Armor -------------------------------------------------------

  test("Armor into an empty armor slot equips it"):
    EquipmentResolver.resolvePickup(player, armor) match
      case PickupOutcome.Equipped(p) => assertEquals(p.equippedArmor, Some(armor))
      case other                     => fail(s"expected Equipped, got $other")

  test("Armor into an occupied armor slot offers a choice against the current armor"):
    val equipped = player.copy(equippedArmor = Some(armor))
    EquipmentResolver.resolvePickup(equipped, armor2) match
      case PickupOutcome.ChoicePending(pending) =>
        assertEquals(pending.newItem, armor2)
        assertEquals(pending.currentItems, Map(EquipSlot.ArmorSlot -> armor))
      case other => fail(s"expected ChoicePending, got $other")

  // --- resolvePickup: Accessory -----------------------------------------------------

  test("Accessory into an empty accessory slot equips it and bumps maxHp"):
    val baseMaxHp = player.maxHp
    EquipmentResolver.resolvePickup(player, ring) match
      case PickupOutcome.Equipped(p) =>
        assertEquals(p.equippedAccessories(0), Some(ring))
        assertEquals(p.maxHp, baseMaxHp + ring.hpBonus.getOrElse(0))
      case other => fail(s"expected Equipped, got $other")

  test("Accessory fills the first empty accessory slot"):
    val oneEquipped = player.copy(equippedAccessories = Vector(Some(ring), None))
    EquipmentResolver.resolvePickup(oneEquipped, ring2) match
      case PickupOutcome.Equipped(p) =>
        assertEquals(p.equippedAccessories, Vector(Some(ring), Some(ring2)))
      case other => fail(s"expected Equipped, got $other")

  test("Accessory pickup offers both slots as choices once both are full"):
    val bothFull = player.copy(equippedAccessories = Vector(Some(ring), Some(ring2)))
    EquipmentResolver.resolvePickup(bothFull, ring3) match
      case PickupOutcome.ChoicePending(pending) =>
        assertEquals(pending.newItem, ring3)
        assertEquals(pending.currentItems,
                     Map(EquipSlot.AccessorySlot(0) -> ring, EquipSlot.AccessorySlot(1) -> ring2)
        )
      case other => fail(s"expected ChoicePending, got $other")

  // --- resolvePickup: Consumable (potion belt) ---------------------------------------

  test("Consumable into an empty potion belt slot equips it"):
    EquipmentResolver.resolvePickup(player, potion) match
      case PickupOutcome.Equipped(p) => assertEquals(p.potionBelt(0), Some(potion))
      case other                     => fail(s"expected Equipped, got $other")

  test("Consumable fills the first empty potion belt slot"):
    val oneSlotUsed = player.copy(potionBelt = Vector(Some(potion), None))
    EquipmentResolver.resolvePickup(oneSlotUsed, ether) match
      case PickupOutcome.Equipped(p) => assertEquals(p.potionBelt, Vector(Some(potion), Some(ether)))
      case other                     => fail(s"expected Equipped, got $other")

  test("Consumable pickup offers both belt slots as choices once the belt is full"):
    val beltFull = player.copy(potionBelt = Vector(Some(potion), Some(ether)))
    EquipmentResolver.resolvePickup(beltFull, potion) match
      case PickupOutcome.ChoicePending(pending) =>
        assertEquals(pending.newItem, potion)
        assertEquals(pending.currentItems,
                     Map(EquipSlot.PotionSlot(0) -> potion, EquipSlot.PotionSlot(1) -> ether)
        )
      case other => fail(s"expected ChoicePending, got $other")

  // --- resolvePickup: Key ---------------------------------------------------------

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

  // --- resolveChoice ---------------------------------------------------------------

  test("resolveChoice with no pending choice logs an error and leaves state unchanged"):
    val exp = explorationState()
    val (next, log, events) = EquipmentResolver.resolveChoice(exp, Some(EquipSlot.WeaponSlot))
    assertEquals(next, exp)
    assert(log.exists(_.toLowerCase.contains("no equip choice")), s"expected a no-pending-choice message: $log")
    assertEquals(events, Nil)

  test("resolveChoice(None) discards the new item and clears the pending choice"):
    val pending = PendingEquipChoice(sword2, Map(EquipSlot.WeaponSlot -> sword))
    val exp     = explorationState(player.copy(equippedWeapon = Some(sword)), Some(pending))
    val (next, log, events) = EquipmentResolver.resolveChoice(exp, None)
    val nextExp = next.asInstanceOf[ExplorationState]
    assertEquals(nextExp.pendingEquipChoice, None)
    assertEquals(nextExp.player.equippedWeapon, Some(sword))
    assert(log.exists(_.contains(sword2.name)), s"expected the discarded item's name in the log: $log")
    assertEquals(events, Nil)

  test("resolveChoice targeting the offered weapon slot equips the new weapon"):
    val pending = PendingEquipChoice(sword2, Map(EquipSlot.WeaponSlot -> sword))
    val exp     = explorationState(player.copy(equippedWeapon = Some(sword)), Some(pending))
    val (next, log, events) = EquipmentResolver.resolveChoice(exp, Some(EquipSlot.WeaponSlot))
    val nextExp = next.asInstanceOf[ExplorationState]
    assertEquals(nextExp.pendingEquipChoice, None)
    assertEquals(nextExp.player.equippedWeapon, Some(sword2))
    assert(log.exists(_.contains(sword2.name)))
    assert(events.contains(GameEvent.ItemPickedUp(inventoryFull = nextExp.player.isFullyEquipped)))

  test("resolveChoice targeting one of two accessory slots replaces only that accessory and adjusts maxHp"):
    val basePlayer = player.copy(equippedAccessories = Vector(Some(ring), Some(ring2)))
    val baseMaxHp  = basePlayer.maxHp
    val pending = PendingEquipChoice(ring3,
                                     Map(EquipSlot.AccessorySlot(0) -> ring, EquipSlot.AccessorySlot(1) -> ring2)
    )
    val exp = explorationState(basePlayer, Some(pending))
    val (next, _, _) = EquipmentResolver.resolveChoice(exp, Some(EquipSlot.AccessorySlot(1)))
    val nextExp       = next.asInstanceOf[ExplorationState]
    assertEquals(nextExp.player.equippedAccessories, Vector(Some(ring), Some(ring3)))
    assertEquals(nextExp.player.maxHp, baseMaxHp - ring2.hpBonus.getOrElse(0) + ring3.hpBonus.getOrElse(0))

  test("resolveChoice targeting the offered armor slot equips the new armor"):
    val pending = PendingEquipChoice(armor2, Map(EquipSlot.ArmorSlot -> armor))
    val exp     = explorationState(player.copy(equippedArmor = Some(armor)), Some(pending))
    val (next, _, _) = EquipmentResolver.resolveChoice(exp, Some(EquipSlot.ArmorSlot))
    assertEquals(next.asInstanceOf[ExplorationState].player.equippedArmor, Some(armor2))

  test("resolveChoice targeting a potion belt slot replaces that potion"):
    val basePlayer = player.copy(potionBelt = Vector(Some(potion), Some(ether)))
    val pending =
      PendingEquipChoice(potion, Map(EquipSlot.PotionSlot(0) -> potion, EquipSlot.PotionSlot(1) -> ether))
    val exp = explorationState(basePlayer, Some(pending))
    val (next, _, _) = EquipmentResolver.resolveChoice(exp, Some(EquipSlot.PotionSlot(1)))
    assertEquals(next.asInstanceOf[ExplorationState].player.potionBelt, Vector(Some(potion), Some(potion)))

  test("resolveChoice targeting a slot that wasn't offered is rejected, pending choice stays set"):
    val pending = PendingEquipChoice(sword2, Map(EquipSlot.WeaponSlot -> sword))
    val exp     = explorationState(player.copy(equippedWeapon = Some(sword)), Some(pending))
    val (next, log, events) = EquipmentResolver.resolveChoice(exp, Some(EquipSlot.ArmorSlot))
    val nextExp = next.asInstanceOf[ExplorationState]
    assertEquals(nextExp.pendingEquipChoice, Some(pending))
    assert(log.exists(_.toLowerCase.contains("invalid")), s"expected an invalid-slot message: $log")
    assertEquals(events, Nil)

  // --- Set HP bonus reconciliation --------------------------------------------------

  private val hpSet = SetDef(
    id = "hp_set",
    name = "HP Set",
    classId = ClassId.Warrior,
    twoPiece = SetBonus(SetBonusEffect.MaxHpPercent(10), "+10% max HP"),
    fourPiece = SetBonus(SetBonusEffect.FlatDefense(5), "+5 flat DEF")
  )
  private val setDefs = Map(hpSet.id -> hpSet)

  private def hpWeapon: Weapon =
    Weapon(Item.newId(), "hw", "HP Weapon", Rarity.Common, attackBonus = 0, setId = Some(hpSet.id))
  private def hpArmor: Armor =
    Armor(Item.newId(), "ha", "HP Armor", Rarity.Common, defenseBonus = 0, setId = Some(hpSet.id))
  private def hpAccessory(n: Int): Accessory =
    Accessory(Item.newId(), s"hac$n", s"HP Accessory $n", Rarity.Common, setId = Some(hpSet.id))

  test("equipping the 2nd piece of a set applies its 2pc MaxHpPercent bonus"):
    val withWeapon = player.copy(equippedWeapon = Some(hpWeapon)) // 1 piece: below the 2pc threshold
    val baseMaxHp  = withWeapon.maxHp
    EquipmentResolver.resolvePickup(withWeapon, hpArmor, setDefs) match
      case PickupOutcome.Equipped(p) =>
        val expectedBonus = baseMaxHp * 10 / 100
        assertEquals(p.maxHp, baseMaxHp + expectedBonus)
        assertEquals(p.activeSetHpBonusFlat, expectedBonus)
      case other => fail(s"expected Equipped, got $other")

  test("equipping a full set as the wrong class applies no HP bonus"):
    val archer = PlayerFixtures.startingPlayer(ClassId.Archer).copy(equippedWeapon = Some(hpWeapon))
    val baseMaxHp = archer.maxHp
    EquipmentResolver.resolvePickup(archer, hpArmor, setDefs) match
      case PickupOutcome.Equipped(p) =>
        assertEquals(p.maxHp, baseMaxHp, "an Archer wearing a Warrior set should get no MaxHpPercent bonus")
        assertEquals(p.activeSetHpBonusFlat, 0)
      case other => fail(s"expected Equipped, got $other")

  test("crossing to 4 pieces doesn't change the HP bonus when the 4pc effect isn't MaxHpPercent"):
    val threePieces = player
      .copy(equippedWeapon = Some(hpWeapon),
            equippedArmor = Some(hpArmor),
            equippedAccessories = Vector(Some(hpAccessory(0)), None)
      )
      .reconcileSetHpBonus(setDefs) // pre-apply the 2pc bonus so the fixture is self-consistent
    val baseMaxHp = threePieces.maxHp
    val baseBonus = threePieces.activeSetHpBonusFlat
    assert(baseBonus > 0, "sanity: 2pc bonus should already be active at 3 pieces")

    EquipmentResolver.resolvePickup(threePieces, hpAccessory(1), setDefs) match
      case PickupOutcome.Equipped(p) =>
        assertEquals(p.maxHp, baseMaxHp)
        assertEquals(p.activeSetHpBonusFlat, baseBonus)
      case other => fail(s"expected Equipped, got $other")

  test("swapping a set piece out via resolveChoice removes the HP bonus once below 2 pieces"):
    val basePlayer = player
      .copy(equippedWeapon = Some(hpWeapon), equippedArmor = Some(hpArmor))
      .reconcileSetHpBonus(setDefs)
    assert(basePlayer.activeSetHpBonusFlat > 0, "sanity: 2pc bonus should be active before the swap")

    val plainArmor = Armor(Item.newId(), "plain_armor", "Plain Armor", Rarity.Common, defenseBonus = 1)
    val pending     = PendingEquipChoice(plainArmor, Map(EquipSlot.ArmorSlot -> hpArmor))
    val exp         = explorationState(basePlayer, Some(pending))

    val (next, _, _) = EquipmentResolver.resolveChoice(exp, Some(EquipSlot.ArmorSlot), setDefs)
    val nextPlayer    = next.asInstanceOf[ExplorationState].player
    assertEquals(nextPlayer.activeSetHpBonusFlat, 0)
    assertEquals(nextPlayer.maxHp, basePlayer.maxHp - basePlayer.activeSetHpBonusFlat)
