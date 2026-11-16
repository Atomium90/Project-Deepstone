package roguelite.game

import munit.FunSuite
import roguelite.engine.ClassId

class ShardTopUpSuite extends FunSuite:

  private def upgrade(id: String, cost: Int, displayOrder: Int, effect: UpgradeEffect): UpgradeDef =
    UpgradeDef(id, id, "test", cost, displayOrder, icon = "*", category = UpgradeCategory.Meta, effect = effect)

  private val warriorKit = upgrade("warrior_kit", 25, 9, UpgradeEffect.UnlockStartingKit(ClassId.Warrior))
  private val archerKit  = upgrade("archer_kit", 25, 10, UpgradeEffect.UnlockStartingKit(ClassId.Archer))
  private val mageKit    = upgrade("mage_kit", 40, 11, UpgradeEffect.UnlockStartingKit(ClassId.Mage))
  // Cheaper than any kit, and not one: it must never be the target of a grant.
  private val hpBoost = upgrade("hp_boost_1", 5, 0, UpgradeEffect.MaxHpBoost(20))

  private def catalogOf(upgrades: UpgradeDef*): Map[String, UpgradeDef] = upgrades.map(u => u.id -> u).toMap

  private val catalog = catalogOf(hpBoost, warriorKit, archerKit, mageKit)

  private val stuck = AchievementStats(runsCompleted = ShardTopUp.RunsBeforeHelp)

  private def meta(currency: Int, owned: Set[String] = Set.empty): MetaProgression =
    MetaProgression(currency, owned)

  test("gives exactly the missing Shards for the cheapest kit once enough runs are finished") {
    assertEquals(ShardTopUp.grantFor(meta(10), stuck, catalog), Some(ShardTopUp.Grant(15, warriorKit)))
  }

  test("gives the whole price to a player with no Shard at all") {
    assertEquals(ShardTopUp.grantFor(meta(0), stuck, catalog).map(_.amount), Some(25))
  }

  test("waits until the required number of runs are finished") {
    val early = AchievementStats(runsCompleted = ShardTopUp.RunsBeforeHelp - 1)
    assertEquals(ShardTopUp.grantFor(meta(0), early, catalog), None)
  }

  test("keeps helping after more runs than the minimum") {
    val later = AchievementStats(runsCompleted = ShardTopUp.RunsBeforeHelp + 7)
    assertEquals(ShardTopUp.grantFor(meta(0), later, catalog).map(_.amount), Some(25))
  }

  test("gives nothing to a player who can already afford the cheapest kit") {
    assertEquals(ShardTopUp.grantFor(meta(25), stuck, catalog), None)
    assertEquals(ShardTopUp.grantFor(meta(100), stuck, catalog), None)
  }

  test("gives nothing once any kit is owned, even another class's") {
    assertEquals(ShardTopUp.grantFor(meta(0, owned = Set("archer_kit")), stuck, catalog), None)
  }

  test("an upgrade that is not a kit never counts as one") {
    assertEquals(ShardTopUp.grantFor(meta(0, owned = Set("hp_boost_1")), stuck, catalog).map(_.upgrade), Some(warriorKit))
  }

  test("ties on price go to the lowest display order, whatever the map order") {
    val grant = ShardTopUp.grantFor(meta(0), stuck, catalogOf(mageKit, archerKit, warriorKit))
    assertEquals(grant.map(_.upgrade), Some(warriorKit))
  }

  test("the cheapest kit wins over a dearer one with a lower display order") {
    val cheapLate = upgrade("cheap_kit", 10, 99, UpgradeEffect.UnlockStartingKit(ClassId.Mage))
    assertEquals(ShardTopUp.grantFor(meta(0), stuck, catalogOf(warriorKit, cheapLate)).map(_.upgrade), Some(cheapLate))
  }

  test("gives nothing when the catalog has no kit upgrade") {
    assertEquals(ShardTopUp.grantFor(meta(0), stuck, catalogOf(hpBoost)), None)
  }
