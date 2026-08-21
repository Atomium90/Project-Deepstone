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

  test("rollChest can produce a key"):
    val rng  = Random(0)
    val hits = (1 to 500).exists(_ => LootTable.rollChest(itemDefs, rng).exists(_.typeId == "rusty_key"))
    assert(hits, "expected rusty_key to be a reachable rollChest outcome")

  // --- Enemy drops ---------------------------------------------------------

  test("rollEnemy with dropChance 0 never drops"):
    val enemy = makeEnemy(dropChance = 0)
    (1 to 50).foreach:
      _ => assertEquals(LootTable.rollEnemy(enemy, itemDefs, Random()), None)

  test("rollEnemy with dropChance 100 always drops"):
    val enemy = makeEnemy(dropChance = 100)
    (1 to 20).foreach:
      _ => assert(LootTable.rollEnemy(enemy, itemDefs, Random()).isDefined)

  test("rollEnemy with empty loot table returns None even at 100% chance"):
    val enemy = makeEnemy(dropChance = 100, lootTable = Nil)
    assertEquals(LootTable.rollEnemy(enemy, itemDefs, Random()), None)

  test("rollEnemy with unknown typeId returns None even at 100% chance"):
    val enemy = makeEnemy(dropChance = 100, lootTable = List(LootEntry("does_not_exist", 100)))
    assertEquals(LootTable.rollEnemy(enemy, Map.empty[String, Item], Random()), None)

  test("rollEnemy assigns a non-empty instance id"):
    val enemy = makeEnemy(dropChance = 100)
    val item  = LootTable.rollEnemy(enemy, itemDefs, Random(1)).getOrElse(fail("expected Some"))
    assert(item.id.nonEmpty)

  test("rollEnemy returns item whose typeId is in the loot table"):
    val enemy = makeEnemy(dropChance = 100, lootTable = List(LootEntry("iron_sword", 100)))
    val item  = LootTable.rollEnemy(enemy, itemDefs, Random()).getOrElse(fail("expected Some"))
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

  test("rolled drops never fall below the item's authored rarity floor"):
    val enemy = makeEnemy(dropChance = 100, lootTable = List(LootEntry("steel_sword", 100)))
    val rng   = Random(11)
    (1 to 300).foreach:
      _ =>
        val item = LootTable.rollEnemy(enemy, itemDefs, rng).getOrElse(fail("expected Some"))
        assert(item.rarity.ordinal >= Rarity.Uncommon.ordinal,
               s"steel_sword (floor Uncommon) rolled ${item.rarity}"
        )

  test("rolled drops can land above the item's authored floor over many trials"):
    val enemy = makeEnemy(dropChance = 100, lootTable = List(LootEntry("steel_sword", 100)))
    val rng   = Random(3)
    val rolledAboveFloor = (1 to 500).exists:
      _ => LootTable.rollEnemy(enemy, itemDefs, rng).exists(_.rarity != Rarity.Uncommon)
    assert(rolledAboveFloor, "expected at least one steel_sword drop above its Uncommon floor in 500 trials")

  test("a weapon's attackBonus scales relative to its floor's multiplier when rolled higher"):
    val enemy = makeEnemy(dropChance = 100, lootTable = List(LootEntry("iron_sword", 100)))
    val rng   = Random(3)
    val scaledUp = (1 to 500)
      .flatMap(_ => LootTable.rollEnemy(enemy, itemDefs, rng))
      .collectFirst { case w: Weapon if w.rarity != Rarity.Common => w }
      .getOrElse(fail("expected at least one iron_sword drop above Common in 500 trials"))

    val expected =
      math.max(1, math.round(3 * (scaledUp.rarity.statMultiplier / Rarity.Common.statMultiplier)).toInt)
    assertEquals(scaledUp.attackBonus, expected)

  test("keys keep their authored rarity, never rolled"):
    val enemy = makeEnemy(dropChance = 100, lootTable = List(LootEntry("rusty_key", 100)))
    val rng   = Random(5)
    (1 to 300).foreach:
      _ =>
        val item = LootTable.rollEnemy(enemy, itemDefs, rng).getOrElse(fail("expected Some"))
        assertEquals(item.rarity, Rarity.Common)

  test("consumables can roll above their authored floor over many trials"):
    val enemy = makeEnemy(dropChance = 100, lootTable = List(LootEntry("health_potion", 100)))
    val rng   = Random(3)
    val rolledAboveFloor = (1 to 500).exists:
      _ => LootTable.rollEnemy(enemy, itemDefs, rng).exists(_.rarity != Rarity.Common)
    assert(rolledAboveFloor, "expected at least one health_potion drop above its Common floor in 500 trials")

  // --- rarityFloorOverride (Lucky Find perk hook) ---------------------------

  test("rarityFloorOverride raises the minimum eligible tier for rollChest"):
    val soleItem: Map[String, Item] = Map("health_potion" -> itemDefs("health_potion")) // Common floor
    val rng = Random(11)
    (1 to 300).foreach:
      _ =>
        val item = LootTable.rollChest(soleItem, rng, rarityFloorOverride = Some(Rarity.Rare))
          .getOrElse(fail("expected Some"))
        assert(item.rarity.ordinal >= Rarity.Rare.ordinal, s"expected at least Rare with the override, got ${item.rarity}")

  test("rarityFloorOverride never pulls a roll below the item's own authored floor"):
    val soleItem: Map[String, Item] = Map("steel_sword" -> itemDefs("steel_sword")) // Uncommon floor
    val rng = Random(11)
    (1 to 300).foreach:
      _ =>
        // An override of Common (lower than steel_sword's own Uncommon floor) must not pull it down.
        val item = LootTable.rollChest(soleItem, rng, rarityFloorOverride = Some(Rarity.Common))
          .getOrElse(fail("expected Some"))
        assert(item.rarity.ordinal >= Rarity.Uncommon.ordinal,
               s"expected at least Uncommon (its own floor), got ${item.rarity}"
        )

  test("scaling under an override still uses the item's own authored floor as the baseline"):
    val soleItem: Map[String, Item] = Map("steel_sword" -> itemDefs("steel_sword")) // Uncommon floor, +7 ATK
    val rng = Random(3)
    val epicRoll = (1 to 500)
      .flatMap(_ => LootTable.rollChest(soleItem, rng, rarityFloorOverride = Some(Rarity.Rare)))
      .collectFirst { case w: Weapon if w.rarity == Rarity.Epic => w }
      .getOrElse(fail("expected at least one Epic roll in 500 trials"))
    val expected =
      math.max(1, math.round(7 * (Rarity.Epic.statMultiplier / Rarity.Uncommon.statMultiplier)).toInt)
    assertEquals(epicRoll.attackBonus, expected)

  test("a HealFixed potion's amount scales by potionMultiplier relative to its floor when rolled higher"):
    val enemy = makeEnemy(dropChance = 100, lootTable = List(LootEntry("health_potion", 100)))
    val rng   = Random(3)
    val scaledUp = (1 to 500)
      .flatMap(_ => LootTable.rollEnemy(enemy, itemDefs, rng))
      .collectFirst { case c: Consumable if c.rarity != Rarity.Common => c }
      .getOrElse(fail("expected at least one health_potion drop above Common in 500 trials"))

    val expected =
      math.max(1, math.round(30 * (scaledUp.rarity.potionMultiplier / Rarity.Common.potionMultiplier)).toInt)
    scaledUp.effect match
      case ConsumableEffect.HealFixed(amount) => assertEquals(amount, expected)
      case other                              => fail(s"expected HealFixed, got $other")

  test("an AttackBuff potion's percent scales by statMultiplier (not potionMultiplier), turns never scales"):
    val buffPotion = Consumable("",
                                "battle_brew",
                                "Battle Brew",
                                Rarity.Common,
                                ConsumableEffect.AttackBuff(percent = 20, turns = 3)
    )
    val defs  = Map("battle_brew" -> buffPotion)
    val enemy = makeEnemy(dropChance = 100, lootTable = List(LootEntry("battle_brew", 100)))
    val rng   = Random(3)
    val scaledUp = (1 to 500)
      .flatMap(_ => LootTable.rollEnemy(enemy, defs, rng))
      .collectFirst { case c: Consumable if c.rarity != Rarity.Common => c }
      .getOrElse(fail("expected at least one battle_brew drop above Common in 500 trials"))

    val expectedPercent =
      math.max(1, math.round(20 * (scaledUp.rarity.statMultiplier / Rarity.Common.statMultiplier)).toInt)
    scaledUp.effect match
      case ConsumableEffect.AttackBuff(percent, turns) =>
        assertEquals(percent, expectedPercent)
        assertEquals(turns, 3, "duration must never scale with rarity")
      case other => fail(s"expected AttackBuff, got $other")

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
