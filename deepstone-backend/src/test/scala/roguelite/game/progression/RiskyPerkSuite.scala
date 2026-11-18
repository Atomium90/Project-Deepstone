package roguelite.game.progression

import munit.FunSuite
import roguelite.engine.{ ClassId, CombatAction, CombatActionType, CombatState, Difficulty, ExplorationState, GameState, Player }
import roguelite.game.combat.{ AbilityDef, AbilityEffect, Combat, CombatResolver, EnemyActionWeight, EnemyInstance, EnemyStats }
import roguelite.game.dungeon.{ Dungeon, Enemy, Room, RoomType, Tile }
import roguelite.game.items.{ Consumable, ConsumableEffect, PotionStack, Rarity }

import scala.util.Random

/** The malus perks, the Shards they pay and the hooks they act through: [[PerkDef]]'s helpers, the
  * enemy stat percent, the heal and cost maluses and the Shards of a kill. The offer and the start of
  * a run are tested where they live ([[roguelite.engine.StateMachineSuite]], [[roguelite.engine.GameSessionSuite]]).
  */
class RiskyPerkSuite extends FunSuite:

  // -----------------------------------------------------------------------
  // Fixtures
  // -----------------------------------------------------------------------

  private val heavyHand = PerkDef("heavy_hand", "Heavy Hand", "d", "*", PerkEffect.FlatDamageBonus(1))

  private val hunted = PerkDef("hunted", "Hunted", "d", "*", PerkEffect.EnemyStatPercent(25),
                               risky = true, shardBonusPercent = 60, victoryShardBonus = 30
  )

  private val bitter = PerkDef("bitter", "Bitter", "d", "*", PerkEffect.PotionHealBonusPercent(-40),
                               risky = true, shardBonusPercent = 40, victoryShardBonus = 20
  )

  private val strained = PerkDef("strained", "Strained", "d", "*", PerkEffect.AbilityCostReductionPercent(-40),
                                 risky = true, shardBonusPercent = 40
  )

  private val bargain = PerkDef("bargain", "Bargain", "d", "*", PerkEffect.EnemyStatPercent(15),
                                extraEffects = List(PerkEffect.MaxHpPercent(-15)),
                                risky = true, shardBonusPercent = 90, victoryShardBonus = 50
  )

  private val catalog: Map[String, PerkDef] =
    List(heavyHand, hunted, bitter, strained, bargain).map(p => p.id -> p).toMap

  private def player(normal: Option[String] = None, risky: Option[String] = None): Player =
    Player(classId = ClassId.Warrior, hp = 200, maxHp = 200, resourceCurrent = 100, resourceMax = 100,
           level = 1, xp = 0, metaCurrency = 0, activePerkId = normal, activeRiskyPerkId = risky
    )

  // -----------------------------------------------------------------------
  // PerkDef helpers
  // -----------------------------------------------------------------------

  test("a perk with several effects lists the main one first"):
    assertEquals(bargain.effects, List(PerkEffect.EnemyStatPercent(15), PerkEffect.MaxHpPercent(-15)))
    assertEquals(heavyHand.effects, List(PerkEffect.FlatDamageBonus(1)))

  test("the active effects are the ordinary perk's, then the risky one's"):
    val both = player(Some("heavy_hand"), Some("hunted"))
    assertEquals(PerkDef.activeEffects(both, catalog), List(PerkEffect.FlatDamageBonus(1), PerkEffect.EnemyStatPercent(25)))
    assertEquals(PerkDef.activeEffects(player(risky = Some("bargain")), catalog), bargain.effects)

  test("no perk, or an id the catalog does not have, gives no effect"):
    assertEquals(PerkDef.activeEffects(player(), catalog), Nil)
    assertEquals(PerkDef.activeEffects(player(Some("gone"), Some("also_gone")), catalog), Nil)

  test("the Shards a perk adds are summed over the perks taken"):
    val both = player(Some("heavy_hand"), Some("hunted"))
    assertEquals(PerkDef.shardBonusPercent(both, catalog), 60)
    assertEquals(PerkDef.victoryShardBonus(both, catalog), 30)
    assertEquals(PerkDef.shardBonusPercent(player(), catalog), 0)

  test("a win pays nothing on a run that takes no blessing, nor once the altar is shut"):
    val p = player(risky = Some("hunted"))
    assertEquals(PerkDef.winBonus(p, catalog, blessings = true, altarShut = false), 30)
    assertEquals(PerkDef.winBonus(p, catalog, blessings = false, altarShut = false), 0)
    assertEquals(PerkDef.winBonus(p, catalog, blessings = true, altarShut = true), 0)

  test("shardsWithBonus rounds up and adds at least one, and leaves a run with no bonus alone"):
    assertEquals(PerkDef.shardsWithBonus(1, 60), 2) // 1.6 would round to 2 anyway, and it is at least +1
    assertEquals(PerkDef.shardsWithBonus(1, 10), 2) // 1.1 rounds up to 2
    assertEquals(PerkDef.shardsWithBonus(3, 50), 5) // 4.5 rounds up
    assertEquals(PerkDef.shardsWithBonus(4, 25), 5) // exactly 5
    assertEquals(PerkDef.shardsWithBonus(10, 40), 14)
    assertEquals(PerkDef.shardsWithBonus(5, 0), 5)
    assertEquals(PerkDef.shardsWithBonus(5, -20), 5)
    assertEquals(PerkDef.shardsWithBonus(0, 50), 0)

  // -----------------------------------------------------------------------
  // Enemies that are stronger
  // -----------------------------------------------------------------------

  private val goblin = EnemyStats(typeId = "goblin", label = "Goblin", spriteId = "g", maxHp = 20, attack = 8, defense = 2,
                                  xpReward = 15, actions = List(EnemyActionWeight("ATTACK", 100)),
                                  dropChance = 25, lootTable = Nil
  )

  test("a risky perk raises an enemy's HP and attack and nothing else"):
    val base   = EnemyInstance.fromStats("e", goblin, Difficulty.Normal, isElite = false)
    val tougher = EnemyInstance.fromStats("e", goblin, Difficulty.Normal, isElite = false, extraPercent = 25)
    assertEquals(tougher.maxHp, 25)
    assertEquals(tougher.hp, 25)
    assertEquals(tougher.attack, 10)
    assertEquals(tougher.defense, base.defense)
    assertEquals(tougher.xpReward, base.xpReward)

  test("no extra percent leaves an enemy as it was"):
    assertEquals(
      EnemyInstance.fromStats("e", goblin, Difficulty.Hard, isElite = true, extraPercent = 0),
      EnemyInstance.fromStats("e", goblin, Difficulty.Hard, isElite = true)
    )

  test("the extra percent stacks on the difficulty and the Elite multipliers, and an enemy keeps 1 HP at least"):
    val stacked = EnemyInstance.fromStats("e", goblin, Difficulty.Hard, isElite = true, extraPercent = 25)
    assertEquals(stacked.maxHp, math.round(20 * 1.25 * 1.75 * 1.25).toInt)
    val gutted = EnemyInstance.fromStats("e", goblin, Difficulty.Normal, extraPercent = -1000)
    assertEquals(gutted.maxHp, 1)
    assertEquals(gutted.attack, 1)

  // -----------------------------------------------------------------------
  // Potions that heal less, abilities that cost more
  // -----------------------------------------------------------------------

  private val tankEnemy = EnemyInstance(entityId = "dummy", typeId = "goblin", label = "Goblin", hp = 500, maxHp = 500,
                                        attack = 40, defense = 0, xpReward = 0,
                                        actions = List(EnemyActionWeight("DEFEND", 100)), dropChance = 0, lootTable = Nil
  )

  private def dungeon: Dungeon =
    val room = Room("r", RoomType.Combat, "dungeon", 3, 3, Vector.fill(3)(Vector.fill(3)(Tile.Floor)),
                    List(Enemy("dummy", 1, 1, "goblin", "Goblin"))
    )
    Dungeon.fromRooms(List(room)).getOrElse(throw IllegalStateException("test dungeon"))

  private def fight(p: Player, enemy: EnemyInstance = tankEnemy): CombatState =
    CombatState(p, dungeon, 0, 0, Combat(enemy = enemy), "dummy")

  private def usePotion(p: Player, potion: Consumable): GameState =
    val stocked = p.copy(potionBelt = p.potionBelt.updated(0, Some(PotionStack(potion, 1))))
    CombatResolver(Random(1), perkDefs = catalog)
      .resolve(fight(stocked), CombatAction(CombatActionType.Item, itemId = Some(potion.id)))
      ._1

  test("a risky perk thins a potion's heal by its percent"):
    val potion = Consumable("p1", "health_potion", "Health Potion", Rarity.Common, ConsumableEffect.HealFixed(30))
    val hurt   = player().copy(hp = 50)
    assertEquals(usePotion(hurt, potion).asInstanceOf[CombatState].player.hp, 80)
    assertEquals(usePotion(hurt.copy(activeRiskyPerkId = Some("bitter")), potion).asInstanceOf[CombatState].player.hp, 68) // 30 - 40% = 18

  test("a potion heals at least 1 HP whatever the malus"):
    val potion = Consumable("p1", "weak_potion", "Weak Potion", Rarity.Common, ConsumableEffect.HealFixed(1))
    val hurt   = player(risky = Some("bitter")).copy(hp = 50)
    assertEquals(usePotion(hurt, potion).asInstanceOf[CombatState].player.hp, 51)

  test("a heal bonus and a heal malus cancel out in their sum, not one after the other"):
    val blessing = PerkDef("blessing", "B", "d", "*", PerkEffect.PotionHealBonusPercent(50))
    val resolver = CombatResolver(Random(1), perkDefs = catalog + (blessing.id -> blessing))
    val potion   = Consumable("p1", "health_potion", "Health Potion", Rarity.Common, ConsumableEffect.HealFixed(40))
    val hurt     = player(Some("blessing"), Some("bitter")).copy(hp = 50)
    val stocked  = hurt.copy(potionBelt = hurt.potionBelt.updated(0, Some(PotionStack(potion, 1))))
    val (next, _, _) = resolver.resolve(fight(stocked), CombatAction(CombatActionType.Item, itemId = Some("p1")))
    assertEquals(next.asInstanceOf[CombatState].player.hp, 94) // 50 + 40 * (100 + 50 - 40) / 100 = 50 + 44

  private val ability = AbilityDef(ClassId.Warrior, id = "test_ability", name = "Test Strike", cost = 40,
                                   resourceName = "Rage", description = "d", effect = AbilityEffect.AttackScaledDamage(1)
  )

  test("a risky perk makes abilities cost more, and the resource is deducted at the new cost"):
    val resolver = CombatResolver(Random(1), abilityDefs = Map(ClassId.Warrior -> ability), perkDefs = catalog)
    val (next, _, _) = resolver.resolve(fight(player(risky = Some("strained"))), CombatAction(CombatActionType.Ability))
    assertEquals(next.asInstanceOf[CombatState].player.resourceCurrent, 44) // 100 - 56

  test("an ability the player can no longer afford at the new cost is refused"):
    val resolver = CombatResolver(Random(1), abilityDefs = Map(ClassId.Warrior -> ability), perkDefs = catalog)
    val short = player(risky = Some("strained")).copy(resourceCurrent = 50) // 50 would pay 40, not 56
    val (next, log, _) = resolver.resolve(fight(short), CombatAction(CombatActionType.Ability))
    assertEquals(next.asInstanceOf[CombatState].player.resourceCurrent, 50)
    assert(log.exists(_.key == "log.combat.notEnoughResource"), s"expected a refusal: $log")

  test("AbilityDef.effectiveCost applies the surcharge for the stats and the view too"):
    assertEquals(AbilityDef.effectiveCost(player(risky = Some("strained")), ability, Map.empty, catalog), 56)
    assertEquals(AbilityDef.effectiveCost(player(), ability, Map.empty, catalog), 40)

  // -----------------------------------------------------------------------
  // Shards of a kill
  // -----------------------------------------------------------------------

  private def killShards(p: Player, xpReward: Int, difficulty: Difficulty = Difficulty.Normal, altarShut: Boolean = false): Int =
    val weak = tankEnemy.copy(hp = 1, xpReward = xpReward)
    val state = CombatState(p, dungeon, 0, 0, Combat(enemy = weak), "dummy", difficulty = difficulty, altarShut = altarShut)
    val (next, _, _) = CombatResolver(Random(1), perkDefs = catalog).resolve(state, CombatAction(CombatActionType.Attack))
    assert(next.isInstanceOf[ExplorationState], s"expected the fight to be won, got $next")
    next.player.metaCurrency - p.metaCurrency

  test("a kill earns one Shard per 5 XP, and a risky perk adds its percent, rounded up"):
    assertEquals(killShards(player(), 50), 10)
    assertEquals(killShards(player(risky = Some("hunted")), 50), 16) // 10 + 60%
    assertEquals(killShards(player(risky = Some("bitter")), 50), 14) // 10 + 40%

  test("a bonus is felt even on the smallest kill"):
    assertEquals(killShards(player(), 3), 1)
    assertEquals(killShards(player(risky = Some("hunted")), 3), 2) // 1 + 60% is 1.6, at least +1

  test("a risky perk earns no Shard more on a run that takes no blessing, nor once the altar is shut"):
    val p = player(risky = Some("hunted"))
    assertEquals(killShards(p, 50, Difficulty.Hardcore), 0)
    assertEquals(killShards(p, 50, altarShut = true), 0)

  test("a heal bonus perk does not change the Shards: only a perk that pays Shards does"):
    assertEquals(killShards(player(Some("heavy_hand")), 50), 10)
