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
    "iron_ring"       -> Accessory("", "iron_ring", "Iron Ring", Rarity.Common, 10),
    "rusty_key"       -> Key("", "rusty_key", "Rusty Key", Rarity.Common, KeyKind.Generic),
    "steel_sword"     -> Weapon("", "steel_sword", "Steel Sword", Rarity.Uncommon, 7),
    "chain_mail"      -> Armor("", "chain_mail", "Chain Mail", Rarity.Uncommon, 5),
    "amulet_of_vigor" -> Accessory("", "amulet_of_vigor", "Amulet of Vigor", Rarity.Uncommon, 20)
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
