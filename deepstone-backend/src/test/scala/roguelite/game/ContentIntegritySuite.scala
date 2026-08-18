package roguelite.game

import munit.CatsEffectSuite
import roguelite.engine.Difficulty

import scala.util.Random

/** Cross-validates the loaded content files against each other - the individual loaders only
  * check their own file is well-formed, none of them check that a typeId one file references
  * (an enemy's lootTable, a class's startingKit) actually exists in items.json. A typo here
  * doesn't crash anything (LootTable/EquipmentResolver treat a missing typeId as "skip it"), it
  * just silently makes something undroppable or un-obtainable - exactly the class of mistake a
  * large loot-pool rewrite is prone to.
  */
class ContentIntegritySuite extends CatsEffectSuite:

  test("every enemy lootTable typeId exists in items.json"):
    for
      items <- ItemLoader.loadAll()
      enemies <- EnemyLoader.loadAll()
    yield enemies.values.foreach:
      enemy =>
        enemy.lootTable.foreach:
          entry =>
            assert(items.contains(entry.typeId),
                   s"${enemy.typeId}'s lootTable references unknown typeId '${entry.typeId}'"
            )

  test("every class startingKit typeId exists in items.json"):
    for
      items   <- ItemLoader.loadAll()
      classes <- ClassLoader.loadAll()
    yield classes.values.foreach:
      cd =>
        cd.startingKit.foreach:
          typeId =>
            assert(items.contains(typeId),
                   s"${cd.classId} startingKit references unknown typeId '$typeId'"
            )

  test("rollChest never returns None against the real item catalog"):
    for items <- ItemLoader.loadAll()
    yield
      val rng = Random(0)
      for _ <- 1 to 200 do
        assert(LootTable.rollChest(items, rng, Difficulty.Normal).isDefined,
               "rollChest returned None - a ChestPool typeId is likely missing from items.json"
        )

  test("every enemy with a positive dropChance can produce a drop against the real item catalog"):
    for
      items   <- ItemLoader.loadAll()
      enemies <- EnemyLoader.loadAll()
    yield enemies.values.filter(_.lootTable.nonEmpty).foreach:
      stats =>
        val instance = EnemyInstance.fromStats("e1", stats, Difficulty.Normal)
        val forcedDrop = instance.copy(dropChance = 100)
        val rng = Random(0)
        val gotADrop = (1 to 50).exists(_ => LootTable.rollEnemy(forcedDrop, items, rng, Difficulty.Normal).isDefined)
        assert(gotADrop, s"${stats.typeId}'s lootTable never produced a drop - likely all typeIds are missing from items.json")
