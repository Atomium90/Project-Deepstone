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
