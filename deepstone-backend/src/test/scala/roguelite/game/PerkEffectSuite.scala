package roguelite.game

import munit.FunSuite
import roguelite.engine.{ ClassId, CombatAction, CombatActionType, CombatState, Player }

import scala.util.Random

/** Tests for run-perk effect resolution in [[CombatResolver]]: [[CombatResolver.activePerkEffect]]
  * and each perk kind actually showing up in combat math, mirroring [[SetBonusSuite]]'s style for
  * the equivalent set-bonus tests.
  */
class PerkEffectSuite extends FunSuite:

  // -----------------------------------------------------------------------
  // Fixtures
  // -----------------------------------------------------------------------

  private def makePlayer(activePerkId: Option[String] = None): Player =
    Player(classId = ClassId.Warrior,
           hp = 200,
           maxHp = 200,
           resourceCurrent = 0,
           resourceMax = 100,
           level = 1,
           xp = 0,
           metaCurrency = 0,
           activePerkId = activePerkId
    )

  private val tankEnemy = EnemyInstance(
    entityId = "dummy",
    typeId = "goblin",
    label = "Goblin",
    hp = 500,
    maxHp = 500,
    attack = 40,
    defense = 0,
    xpReward = 0,
    actions = List(EnemyActionWeight("ATTACK", 100)),
    dropChance = 0,
    lootTable = Nil
  )

  private def makeDungeon: Dungeon =
    val room = Room("r",
                    RoomType.Combat,
                    3,
                    3,
                    Vector.fill(3)(Vector.fill(3)(Tile.Floor)),
                    List(Enemy("dummy", 1, 1, "goblin", "Goblin"))
    )
    Dungeon.fromRooms(List(room)).getOrElse(throw IllegalStateException("test dungeon"))

  private def makeCombatState(player: Player): CombatState =
    CombatState(player, makeDungeon, 0, 0, Combat(enemy = tankEnemy), "dummy")

  private def extractEnemyHp(state: roguelite.engine.GameState): Int = state match
    case cs: CombatState => cs.combat.enemy.hp
    case _                => fail("expected CombatState (enemy should survive with 500 HP and 0 defense)")

  // -----------------------------------------------------------------------
  // activePerkEffect
  // -----------------------------------------------------------------------

  private val heavyHand = PerkDef("heavy_hand", "Heavy Hand", "+1 flat damage per hit this run",
                                  icon = "*", effect = PerkEffect.FlatDamageBonus(1)
  )

  test("activePerkEffect is None when no perk is active") {
    val resolver = CombatResolver(Random(1), perkDefs = Map(heavyHand.id -> heavyHand))
    assertEquals(resolver.activePerkEffect(makePlayer()), None)
  }

  test("activePerkEffect is None when the active perk id isn't in the loaded catalog") {
    val resolver = CombatResolver(Random(1), perkDefs = Map.empty)
    assertEquals(resolver.activePerkEffect(makePlayer(activePerkId = Some("heavy_hand"))), None)
  }

  test("activePerkEffect resolves the active perk's effect from the catalog") {
    val resolver = CombatResolver(Random(1), perkDefs = Map(heavyHand.id -> heavyHand))
    assertEquals(resolver.activePerkEffect(makePlayer(activePerkId = Some("heavy_hand"))),
                Some(PerkEffect.FlatDamageBonus(1))
    )
  }

  // -----------------------------------------------------------------------
  // FlatDamageBonus: shows up exactly in combat math
  // -----------------------------------------------------------------------

  test("FlatDamageBonus perk adds exactly to the player's attack") {
    val perkDefs = Map(heavyHand.id -> heavyHand)
    val base     = makePlayer()
    val withPerk = makePlayer(activePerkId = Some("heavy_hand"))

    val (nextBase, _, _)     = CombatResolver(Random(5), perkDefs = perkDefs)
      .resolve(makeCombatState(base), CombatAction(CombatActionType.Attack))
    val (nextWithPerk, _, _) = CombatResolver(Random(5), perkDefs = perkDefs)
      .resolve(makeCombatState(withPerk), CombatAction(CombatActionType.Attack))

    val dmgBase     = 500 - extractEnemyHp(nextBase)
    val dmgWithPerk = 500 - extractEnemyHp(nextWithPerk)
    assertEquals(dmgWithPerk - dmgBase, 1, "expected the +1 FlatDamageBonus perk to add exactly 1 damage")
  }

  // -----------------------------------------------------------------------
  // PotionHealBonusPercent: shows up exactly in potion healing
  // -----------------------------------------------------------------------

  private val herbalistBlessing = PerkDef("herbalist_blessing", "Herbalist's Blessing",
                                          "Potions heal 50% more this run", icon = "*",
                                          effect = PerkEffect.PotionHealBonusPercent(50)
  )

  // DEFEND-only so the enemy's counter-attack never lands, keeping the HP delta attributable to
  // the potion alone.
  private val passiveEnemy = tankEnemy.copy(actions = List(EnemyActionWeight("DEFEND", 100)))

  private def equipPotion(player: Player, potion: Consumable): Player =
    player.copy(potionBelt = player.potionBelt.updated(0, Some(potion)))

  private def stateWithPotion(player: Player, potion: Consumable): CombatState =
    CombatState(equipPotion(player, potion), makeDungeon, 0, 0, Combat(enemy = passiveEnemy), "dummy")

  test("PotionHealBonusPercent perk increases a HealFixed potion's heal amount") {
    val perkDefs = Map(herbalistBlessing.id -> herbalistBlessing)
    val potion   = Consumable("p1", "health_potion", "Health Potion", Rarity.Common, ConsumableEffect.HealFixed(30))

    val (nextBase, _, _)     = CombatResolver(Random(1), perkDefs = perkDefs)
      .resolve(stateWithPotion(makePlayer().copy(hp = 50), potion), CombatAction(CombatActionType.Item, itemId = Some("p1")))
    val (nextWithPerk, _, _) = CombatResolver(Random(1), perkDefs = perkDefs)
      .resolve(stateWithPotion(makePlayer(activePerkId = Some("herbalist_blessing")).copy(hp = 50), potion),
               CombatAction(CombatActionType.Item, itemId = Some("p1"))
      )

    assertEquals(nextBase.player.hp, 80, "50 + 30 (base heal, no perk)")
    assertEquals(nextWithPerk.player.hp, 95, "50 + 45 (30 boosted by +50%)")
  }

  test("PotionHealBonusPercent perk increases a HealPercent potion's heal amount") {
    val perkDefs = Map(herbalistBlessing.id -> herbalistBlessing)
    // maxHp = 200 (see makePlayer), so HealPercent(20) heals 40 at baseline.
    val potion = Consumable("p1", "pct_potion", "Pct Potion", Rarity.Common, ConsumableEffect.HealPercent(20))

    val (nextBase, _, _)     = CombatResolver(Random(1), perkDefs = perkDefs)
      .resolve(stateWithPotion(makePlayer().copy(hp = 50), potion), CombatAction(CombatActionType.Item, itemId = Some("p1")))
    val (nextWithPerk, _, _) = CombatResolver(Random(1), perkDefs = perkDefs)
      .resolve(stateWithPotion(makePlayer(activePerkId = Some("herbalist_blessing")).copy(hp = 50), potion),
               CombatAction(CombatActionType.Item, itemId = Some("p1"))
      )

    assertEquals(nextBase.player.hp, 90, "50 + 40 (base heal, no perk)")
    assertEquals(nextWithPerk.player.hp, 110, "50 + 60 (40 boosted by +50%)")
  }

  // -----------------------------------------------------------------------
  // AbilityCostReductionPercent: reduces the resource cost actually deducted
  // -----------------------------------------------------------------------

  private val efficientCasting = PerkDef("efficient_casting", "Efficient Casting",
                                         "Abilities cost 20% less this run", icon = "*",
                                         effect = PerkEffect.AbilityCostReductionPercent(20)
  )

  private val testAbility = AbilityDef(ClassId.Warrior,
                                       id = "test_ability",
                                       name = "Test Strike",
                                       cost = 40,
                                       resourceName = "Rage",
                                       description = "test",
                                       effect = AbilityEffect.FlatDamage(1)
  )

  // DEFEND-only so a Warrior's +10 Rage-on-hit gain never confounds the resourceCurrent assertion.
  private val passiveAbilityEnemy = tankEnemy.copy(actions = List(EnemyActionWeight("DEFEND", 100)))

  private def stateForAbility(player: Player): CombatState =
    CombatState(player, makeDungeon, 0, 0, Combat(enemy = passiveAbilityEnemy), "dummy")

  test("AbilityCostReductionPercent perk reduces the resource cost actually deducted") {
    val perkDefs    = Map(efficientCasting.id -> efficientCasting)
    val abilityDefs = Map(ClassId.Warrior -> testAbility)

    val base     = makePlayer().copy(resourceCurrent = 40, resourceMax = 100)
    val withPerk = makePlayer(activePerkId = Some("efficient_casting")).copy(resourceCurrent = 40, resourceMax = 100)

    val (nextBase, _, _)     = CombatResolver(Random(1), abilityDefs = abilityDefs, perkDefs = perkDefs)
      .resolve(stateForAbility(base), CombatAction(CombatActionType.Ability))
    val (nextWithPerk, _, _) = CombatResolver(Random(1), abilityDefs = abilityDefs, perkDefs = perkDefs)
      .resolve(stateForAbility(withPerk), CombatAction(CombatActionType.Ability))

    assertEquals(nextBase.player.resourceCurrent, 0, "full cost (40) deducted without the perk")
    assertEquals(nextWithPerk.player.resourceCurrent, 8, "80% of 40 = 32 deducted, 8 left with the -20% perk")
  }

  test("AbilityCostReductionPercent perk stacks additively with an active set discount") {
    val costSet = SetDef(
      id = "cost_set",
      name = "Cost Set",
      classId = ClassId.Warrior,
      twoPiece = SetBonus(SetBonusEffect.FlatAttack(0), "no-op"),
      fourPiece = SetBonus(SetBonusEffect.AbilityCostReductionPercent(30), "-30% ability cost")
    )
    val setDefs     = Map(costSet.id -> costSet)
    val perkDefs    = Map(efficientCasting.id -> efficientCasting)
    val abilityDefs = Map(ClassId.Warrior -> testAbility)

    val player = makePlayer(activePerkId = Some("efficient_casting"))
      .copy(resourceCurrent = 40, resourceMax = 100,
            equippedWeapon = Some(Weapon(id = Item.newId(), typeId = "dw", name = "Dummy Weapon",
                                         rarity = Rarity.Common, attackBonus = 0, setId = Some(costSet.id)
                                  )
            ),
            equippedArmor = Some(Armor(id = Item.newId(), typeId = "da", name = "Dummy Armor",
                                       rarity = Rarity.Common, defenseBonus = 0, setId = Some(costSet.id)
                                 )
            ),
            equippedAccessories = Vector(
              Some(Accessory(id = Item.newId(), typeId = "dac0", name = "Dummy Accessory 0",
                             rarity = Rarity.Common, setId = Some(costSet.id)
                   )
              ),
              Some(Accessory(id = Item.newId(), typeId = "dac1", name = "Dummy Accessory 1",
                             rarity = Rarity.Common, setId = Some(costSet.id)
                   )
              )
            )
      )

    val (next, _, _) = CombatResolver(Random(1), abilityDefs = abilityDefs, setDefs = setDefs, perkDefs = perkDefs)
      .resolve(stateForAbility(player), CombatAction(CombatActionType.Ability))

    assertEquals(next.player.resourceCurrent, 20,
                 "30% (set) + 20% (perk) = 50% off 40 = 20 deducted, 20 left"
    )
  }
