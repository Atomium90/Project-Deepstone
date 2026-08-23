package roguelite.game

import munit.FunSuite
import roguelite.engine.Difficulty

import scala.util.Random

class LootTableSuite extends FunSuite:

  private val itemDefs: Map[String, Item] = Map(
    "health_potion" -> Consumable("",
                                  "health_potion",
                                  "Health Potion",
                                  Rarity.Common,
                                  ConsumableEffect.HealFixed(30)
    ),
    "iron_sword"      -> Weapon("", "iron_sword", "Iron Sword", Rarity.Common, 3),
    "hunters_bow"     -> Weapon("", "hunters_bow", "Hunter's Bow", Rarity.Common, 4),
    "leather_armor"   -> Armor("", "leather_armor", "Leather Armor", Rarity.Common, 2),
    "iron_ring"       -> Accessory("", "iron_ring", "Iron Ring", Rarity.Common, Some(10)),
    "rusty_key"       -> Key("", "rusty_key", "Rusty Key", Rarity.Common, KeyKind.Generic),
    "steel_sword"     -> Weapon("", "steel_sword", "Steel Sword", Rarity.Uncommon, 7),
    "chain_mail"      -> Armor("", "chain_mail", "Chain Mail", Rarity.Uncommon, 5),
    "amulet_of_vigor" -> Accessory("", "amulet_of_vigor", "Amulet of Vigor", Rarity.Uncommon, Some(20))
  )

  // --- Chest rolls ---------------------------------------------------------

  test("rollChest returns Some with a valid item def map"):
    assert(LootTable.rollChest(itemDefs, Random(42)).isDefined)

  test("rollChest assigns a non-empty instance id"):
    val item = LootTable.rollChest(itemDefs, Random(42)).getOrElse(fail("expected Some"))
    assert(item.id.nonEmpty)

  test("rollChest returns None when itemDefs is empty"):
    assertEquals(LootTable.rollChest(Map.empty[String, Item], Random(42)), None)

  test("rollChest creates distinct instance ids on repeated calls"):
    val rng = Random(42)
    val id1 = LootTable.rollChest(itemDefs, rng).map(_.id)
    val id2 = LootTable.rollChest(itemDefs, rng).map(_.id)
    assertNotEquals(id1, id2)

  test("rollChest is deterministic: same seed yields same typeId"):
    val t1 = LootTable.rollChest(itemDefs, Random(7)).map(_.typeId)
    val t2 = LootTable.rollChest(itemDefs, Random(7)).map(_.typeId)
    assertEquals(t1, t2)

  // Seed 3 is a seed found (once) to land rusty_key on the very first draw - a direct example
  // instead of hoping one of 500 attempts hits it.
  test("rollChest can produce a key"):
    val item = LootTable.rollChest(itemDefs, Random(3)).getOrElse(fail("expected Some"))
    assertEquals(item.typeId, "rusty_key")

  // --- Enemy drops ---------------------------------------------------------

  // dropChance <= 0 short-circuits before rollEnemy ever calls rng (see LootTable.rollEnemy), so
  // this holds for every possible roll, not just probabilistically - one seeded call proves it.
  test("rollEnemy with dropChance 0 never drops"):
    val enemy = makeEnemy(dropChance = 0)
    assertEquals(LootTable.rollEnemy(enemy, itemDefs, Random(1)), None)

  // rng.nextInt(100) always yields 0-99, so `>= 100` (the only way rollEnemy bails on the chance
  // roll) can never be true - structurally guaranteed for every seed, not a matter of luck.
  test("rollEnemy with dropChance 100 always drops"):
    val enemy = makeEnemy(dropChance = 100)
    assert(LootTable.rollEnemy(enemy, itemDefs, Random(1)).isDefined)

  test("rollEnemy with empty loot table returns None even at 100% chance"):
    val enemy = makeEnemy(dropChance = 100, lootTable = Nil)
    assertEquals(LootTable.rollEnemy(enemy, itemDefs, Random(1)), None)

  test("rollEnemy with unknown typeId returns None even at 100% chance"):
    val enemy = makeEnemy(dropChance = 100, lootTable = List(LootEntry("does_not_exist", 100)))
    assertEquals(LootTable.rollEnemy(enemy, Map.empty[String, Item], Random(1)), None)

  test("rollEnemy assigns a non-empty instance id"):
    val enemy = makeEnemy(dropChance = 100)
    val item  = LootTable.rollEnemy(enemy, itemDefs, Random(1)).getOrElse(fail("expected Some"))
    assert(item.id.nonEmpty)

  test("rollEnemy returns item whose typeId is in the loot table"):
    val enemy = makeEnemy(dropChance = 100, lootTable = List(LootEntry("iron_sword", 100)))
    val item  = LootTable.rollEnemy(enemy, itemDefs, Random(1)).getOrElse(fail("expected Some"))
    assertEquals(item.typeId, "iron_sword")

  // --- Difficulty-aware rarity weighting ------------------------------------

  test("Easy and Normal difficulty do not change loot odds"):
    val enemy = makeEnemy(dropChance = 100,
                          lootTable = List(LootEntry("iron_sword", 50), LootEntry("steel_sword", 50))
    )
    assertEquals(
      LootTable.rollEnemy(enemy, itemDefs, Random(9), Difficulty.Easy).map(_.typeId),
      LootTable.rollEnemy(enemy, itemDefs, Random(9), Difficulty.Normal).map(_.typeId)
    )

  // Genuinely distributional (comparing two empirical rates against each other) - no single seed
  // can stand in for "the aggregate skews higher," so this stays trial-based.
  test("Hard difficulty increases the relative odds of Uncommon loot"):
    val enemy = makeEnemy(dropChance = 100,
                          lootTable = List(LootEntry("iron_sword", 50), LootEntry("steel_sword", 50))
    )

    def uncommonRate(difficulty: Difficulty): Double =
      val rng    = Random(123)
      val trials = 2000
      val hits = (1 to trials).count:
        _ => LootTable.rollEnemy(enemy, itemDefs, rng, difficulty).exists(_.typeId == "steel_sword")
      hits.toDouble / trials

    val normalRate = uncommonRate(Difficulty.Normal)
    val hardRate    = uncommonRate(Difficulty.Hard)
    assert(hardRate > normalRate, s"expected Hard ($hardRate) > Normal ($normalRate)")

  // --- Rarity roll-and-scale -------------------------------------------------

  // rollTier only ever picks among tiers >= floor (see LootTable.rollTier's `eligible` filter), so
  // no rng value can pick a tier below the floor - one seeded call proves it as well as 300 would.
  test("rolled drops never fall below the item's authored rarity floor"):
    val enemy = makeEnemy(dropChance = 100, lootTable = List(LootEntry("steel_sword", 100)))
    val item  = LootTable.rollEnemy(enemy, itemDefs, Random(11)).getOrElse(fail("expected Some"))
    assert(item.rarity.ordinal >= Rarity.Uncommon.ordinal, s"steel_sword (floor Uncommon) rolled ${item.rarity}")

  // Seed 5 is a seed found (once) to roll steel_sword above its Uncommon floor on the first draw.
  test("rolled drops can land above the item's authored floor"):
    val enemy = makeEnemy(dropChance = 100, lootTable = List(LootEntry("steel_sword", 100)))
    val item  = LootTable.rollEnemy(enemy, itemDefs, Random(5)).getOrElse(fail("expected Some"))
    assertNotEquals(item.rarity, Rarity.Uncommon, "expected this seed to roll steel_sword above its Uncommon floor")

  // Seed 5 rolls iron_sword (Common floor) up to Uncommon on the first draw.
  test("a weapon's attackBonus scales relative to its floor's multiplier when rolled higher"):
    val enemy = makeEnemy(dropChance = 100, lootTable = List(LootEntry("iron_sword", 100)))
    val scaledUp = LootTable.rollEnemy(enemy, itemDefs, Random(5)) match
      case Some(w: Weapon) if w.rarity != Rarity.Common => w
      case other                                        => fail(s"expected an above-Common Weapon, got $other")

    val expected =
      math.max(1, math.round(3 * (scaledUp.rarity.statMultiplier / Rarity.Common.statMultiplier)).toInt)
    assertEquals(scaledUp.attackBonus, expected)

  // Key falls through rollRarityAndScale's `case other => other` branch - rng is never even
  // consulted for a Key, so this holds for any seed, not just this one.
  test("keys keep their authored rarity, never rolled"):
    val enemy = makeEnemy(dropChance = 100, lootTable = List(LootEntry("rusty_key", 100)))
    val item  = LootTable.rollEnemy(enemy, itemDefs, Random(5)).getOrElse(fail("expected Some"))
    assertEquals(item.rarity, Rarity.Common)

  // Seed 5 rolls health_potion (Common floor) above Common on the first draw.
  test("consumables can roll above their authored floor"):
    val enemy = makeEnemy(dropChance = 100, lootTable = List(LootEntry("health_potion", 100)))
    val item  = LootTable.rollEnemy(enemy, itemDefs, Random(5)).getOrElse(fail("expected Some"))
    assertNotEquals(item.rarity, Rarity.Common, "expected this seed to roll health_potion above its Common floor")

  // --- rarityFloorOverride (Lucky Find perk hook) ---------------------------

  // effectiveFloor raises the floor rollTier filters against before any rng draw happens, so an
  // override can never be bypassed by an unlucky roll - one seeded call proves it as well as 300
  // would.
  test("rarityFloorOverride raises the minimum eligible tier for rollChest"):
    val soleItem: Map[String, Item] = Map("health_potion" -> itemDefs("health_potion")) // Common floor
    val item = LootTable.rollChest(soleItem, Random(11), rarityFloorOverride = Some(Rarity.Rare))
      .getOrElse(fail("expected Some"))
    assert(item.rarity.ordinal >= Rarity.Rare.ordinal, s"expected at least Rare with the override, got ${item.rarity}")

  test("rarityFloorOverride never pulls a roll below the item's own authored floor"):
    val soleItem: Map[String, Item] = Map("steel_sword" -> itemDefs("steel_sword")) // Uncommon floor
    // An override of Common (lower than steel_sword's own Uncommon floor) must not pull it down.
    val item = LootTable.rollChest(soleItem, Random(11), rarityFloorOverride = Some(Rarity.Common))
      .getOrElse(fail("expected Some"))
    assert(item.rarity.ordinal >= Rarity.Uncommon.ordinal,
           s"expected at least Uncommon (its own floor), got ${item.rarity}"
    )

  test("rarityFloorOverride raises the minimum eligible tier for rollEnemy (Elite kill hook)"):
    val soleItem: Map[String, Item] = Map("iron_sword" -> itemDefs("iron_sword")) // Common floor
    val enemy = makeEnemy(dropChance = 100, lootTable = List(LootEntry("iron_sword", 100)))
    val item = LootTable.rollEnemy(enemy, soleItem, Random(11), rarityFloorOverride = Some(Rarity.Rare))
      .getOrElse(fail("expected Some"))
    assert(item.rarity.ordinal >= Rarity.Rare.ordinal, s"expected at least Rare with the override, got ${item.rarity}")

  test("rarityFloorOverride on rollEnemy never pulls a roll below the item's own authored floor"):
    val soleItem: Map[String, Item] = Map("steel_sword" -> itemDefs("steel_sword")) // Uncommon floor
    val enemy = makeEnemy(dropChance = 100, lootTable = List(LootEntry("steel_sword", 100)))
    // An override of Common (lower than steel_sword's own Uncommon floor) must not pull it down.
    val item = LootTable.rollEnemy(enemy, soleItem, Random(11), rarityFloorOverride = Some(Rarity.Common))
      .getOrElse(fail("expected Some"))
    assert(item.rarity.ordinal >= Rarity.Uncommon.ordinal,
           s"expected at least Uncommon (its own floor), got ${item.rarity}"
    )

  // Seed 1 rolls steel_sword (Uncommon floor) all the way up to Epic under a Rare override, on the
  // first draw - exercises the strongest case (scaling relative to the authored floor, not the
  // override) directly instead of searching 500 attempts for an Epic result.
  test("scaling under an override still uses the item's own authored floor as the baseline"):
    val soleItem: Map[String, Item] = Map("steel_sword" -> itemDefs("steel_sword")) // Uncommon floor, +7 ATK
    val epicRoll = LootTable.rollChest(soleItem, Random(1), rarityFloorOverride = Some(Rarity.Rare)) match
      case Some(w: Weapon) if w.rarity == Rarity.Epic => w
      case other                                      => fail(s"expected an Epic Weapon, got $other")
    val expected =
      math.max(1, math.round(7 * (Rarity.Epic.statMultiplier / Rarity.Uncommon.statMultiplier)).toInt)
    assertEquals(epicRoll.attackBonus, expected)

  // Seed 5 rolls health_potion (Common floor) above Common on the first draw.
  test("a HealFixed potion's amount scales by potionMultiplier relative to its floor when rolled higher"):
    val enemy = makeEnemy(dropChance = 100, lootTable = List(LootEntry("health_potion", 100)))
    val scaledUp = LootTable.rollEnemy(enemy, itemDefs, Random(5)) match
      case Some(c: Consumable) if c.rarity != Rarity.Common => c
      case other                                            => fail(s"expected an above-Common Consumable, got $other")

    val expected =
      math.max(1, math.round(30 * (scaledUp.rarity.potionMultiplier / Rarity.Common.potionMultiplier)).toInt)
    scaledUp.effect match
      case ConsumableEffect.HealFixed(amount) => assertEquals(amount, expected)
      case other                              => fail(s"expected HealFixed, got $other")

  // Seed 5 rolls battle_brew (Common floor) above Common on the first draw.
  test("an AttackBuff potion's percent scales by statMultiplier (not potionMultiplier), turns never scales"):
    val buffPotion = Consumable("",
                                "battle_brew",
                                "Battle Brew",
                                Rarity.Common,
                                ConsumableEffect.AttackBuff(percent = 20, turns = 3)
    )
    val defs  = Map("battle_brew" -> buffPotion)
    val enemy = makeEnemy(dropChance = 100, lootTable = List(LootEntry("battle_brew", 100)))
    val scaledUp = LootTable.rollEnemy(enemy, defs, Random(5)) match
      case Some(c: Consumable) if c.rarity != Rarity.Common => c
      case other                                            => fail(s"expected an above-Common Consumable, got $other")

    val expectedPercent =
      math.max(1, math.round(20 * (scaledUp.rarity.statMultiplier / Rarity.Common.statMultiplier)).toInt)
    scaledUp.effect match
      case ConsumableEffect.AttackBuff(percent, turns) =>
        assertEquals(percent, expectedPercent)
        assertEquals(turns, 3, "duration must never scale with rarity")
      case other => fail(s"expected AttackBuff, got $other")

  // Genuinely distributional (comparing aggregate counts across tiers) - no single seed can stand
  // in for "Epic is rarer than Common over many rolls," so this stays trial-based.
  test("Epic rolls are rarer than Common rolls over many trials"):
    val enemy = makeEnemy(dropChance = 100, lootTable = List(LootEntry("iron_sword", 100)))
    val rng     = Random(21)
    val results = (1 to 3000).flatMap(_ => LootTable.rollEnemy(enemy, itemDefs, rng)).map(_.rarity)
    val commonCount = results.count(_ == Rarity.Common)
    val epicCount   = results.count(_ == Rarity.Epic)
    assert(commonCount > epicCount, s"expected Common ($commonCount) > Epic ($epicCount) over 3000 trials")

  // --- Helper --------------------------------------------------------------

  private def makeEnemy(
      dropChance: Int,
      lootTable: List[LootEntry] = List(LootEntry("health_potion", 100))
  ): EnemyInstance =
    EnemyInstance(
      entityId = "e1",
      typeId = "goblin",
      label = "Goblin",
      hp = 20,
      maxHp = 20,
      attack = 8,
      defense = 2,
      xpReward = 15,
      actions = Nil,
      dropChance = dropChance,
      lootTable = lootTable
    )
