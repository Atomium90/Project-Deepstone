package roguelite.game

import munit.FunSuite

class InventorySuite extends FunSuite:

  // --- Sample items --------------------------------------------------------
  private val sword   = Weapon   ("w1", "iron_sword",    "Iron Sword",    Rarity.Common,   attackBonus  = 3)
  private val sword2  = Weapon   ("w2", "steel_sword",   "Steel Sword",   Rarity.Uncommon, attackBonus  = 7)
  private val armor   = Armor    ("a1", "leather_armor", "Leather Armor", Rarity.Common,   defenseBonus = 2)
  private val armor2  = Armor    ("a2", "chain_mail",    "Chain Mail",    Rarity.Uncommon, defenseBonus = 5)
  private val ring    = Accessory("r1", "iron_ring",     "Iron Ring",     Rarity.Common,   hpBonus      = 10)
  private val potion  = Consumable("p1", "health_potion", "Health Potion", Rarity.Common,
    ConsumableEffect.HealFixed(30))
  private val ether   = Consumable("e1", "ether", "Ether", Rarity.Uncommon,
    ConsumableEffect.RestoreResource(20))

  // --- Empty inventory -----------------------------------------------------

  test("empty inventory has MaxSlots slots all empty"):
    val inv = Inventory.empty
    assertEquals(inv.slots.length, Inventory.MaxSlots)
    assert(inv.slots.forall(_.isEmpty))
    assertEquals(inv.size, 0)
    assert(!inv.isFull)

  // --- addItem -------------------------------------------------------------

  test("addItem fills the first available slot"):
    val inv = Inventory.empty.addItem(sword).getOrElse(fail("expected Right"))
    assertEquals(inv.slots(0), Some(sword))
    assertEquals(inv.size, 1)

  test("addItem fills slots sequentially"):
    val result = for
      i1 <- Inventory.empty.addItem(sword)
      i2 <- i1.addItem(armor)
      i3 <- i2.addItem(potion)
    yield i3
    val inv = result.getOrElse(fail("expected Right"))
    assertEquals(inv.slots(0), Some(sword))
    assertEquals(inv.slots(1), Some(armor))
    assertEquals(inv.slots(2), Some(potion))

  test("addItem to full inventory returns Left"):
    val items = List(sword, armor, ring, potion, ether, sword2)
    val full  = items.foldLeft(Inventory.empty): (acc, item) =>
      acc.addItem(item).getOrElse(acc)
    assert(full.isFull)
    assertEquals(full.size, 6)
    assert(full.addItem(sword).isLeft)

  // --- removeAt ------------------------------------------------------------

  test("removeAt returns the item and clears the slot"):
    val inv = Inventory.empty.addItem(sword).getOrElse(fail("expected Right"))
    val (removed, newInv): (Option[Item], Inventory) = inv.removeAt(0)
    assertEquals(removed, Some(sword))
    assertEquals(newInv.slots(0), None)
    assertEquals(newInv.size, 0)

  test("removeAt on an empty slot returns None and leaves inventory unchanged"):
    val (removed, unchanged): (Option[Item], Inventory) = Inventory.empty.removeAt(0)
    assertEquals(removed, None)
    assertEquals(unchanged.size, 0)

  test("removeAt out of bounds returns None and leaves inventory unchanged"):
    val inv = Inventory.empty.addItem(sword).getOrElse(fail("expected Right"))
    val (removed, same): (Option[Item], Inventory) = inv.removeAt(99)
    assertEquals(removed, None)
    assertEquals(same.size, 1)

  // --- findById ------------------------------------------------------------

  test("findById returns correct slot index and item"):
    val inv = (for
      i1 <- Inventory.empty.addItem(sword)
      i2 <- i1.addItem(armor)
    yield i2).getOrElse(fail("expected Right"))
    assertEquals(inv.findById("a1"), Some((1, armor)))

  test("findById returns None for unknown id"):
    assertEquals(Inventory.empty.findById("nope"), None)

  // --- Passive stat bonuses -------------------------------------------------

  test("totalAttackBonus sums all weapon bonuses"):
    val inv = (for
      i1 <- Inventory.empty.addItem(sword)
      i2 <- i1.addItem(sword2)
      i3 <- i2.addItem(armor) // armor should not contribute
    yield i3).getOrElse(fail("expected Right"))
    assertEquals(inv.totalAttackBonus, 10) // 3 + 7

  test("totalAttackBonus is 0 with no weapons"):
    val inv = Inventory.empty.addItem(armor).getOrElse(fail("expected Right"))
    assertEquals(inv.totalAttackBonus, 0)

  test("totalDefenseBonus sums all armor bonuses"):
    val inv = (for
      i1 <- Inventory.empty.addItem(armor)
      i2 <- i1.addItem(armor2)
      i3 <- i2.addItem(sword) // weapon should not contribute
    yield i3).getOrElse(fail("expected Right"))
    assertEquals(inv.totalDefenseBonus, 7) // 2 + 5

  test("totalDefenseBonus is 0 with no armor"):
    assertEquals(Inventory.empty.totalDefenseBonus, 0)

  // --- consumables / items -------------------------------------------------

  test("consumables returns only Consumable items with their slot index"):
    val inv = (for
      i1 <- Inventory.empty.addItem(sword)
      i2 <- i1.addItem(potion)
      i3 <- i2.addItem(ether)
    yield i3).getOrElse(fail("expected Right"))
    val cs = inv.consumables
    assertEquals(cs.length, 2)
    assertEquals(cs(0), (1, potion))
    assertEquals(cs(1), (2, ether))

  test("items returns all present items in slot order"):
    val inv = (for
      i1 <- Inventory.empty.addItem(sword)
      i2 <- i1.addItem(potion)
    yield i2).getOrElse(fail("expected Right"))
    assertEquals(inv.items, List(sword, potion))