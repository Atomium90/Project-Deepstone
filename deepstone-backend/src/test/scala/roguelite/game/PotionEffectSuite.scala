package roguelite.game

import munit.FunSuite
import roguelite.engine.{ ClassId, CombatAction, CombatActionType, CombatState, Player }

import scala.util.Random

/** Tests for potion-sourced timed buffs ([[TimedBuff]]/[[TimedBuffEffect]]) and the new
  * [[ConsumableEffect]] cases (`AttackBuff`, `FlatDamage`) that produce them.
  */
class PotionEffectSuite extends FunSuite:

  // -----------------------------------------------------------------------
  // Fixtures
  // -----------------------------------------------------------------------

  private def makePlayer(hp: Int = 200, maxHp: Int = 200): Player =
    Player(classId = ClassId.Warrior,
           hp = hp,
           maxHp = maxHp,
           resourceCurrent = 0,
           resourceMax = 100,
           level = 1,
           xp = 0,
           metaCurrency = 0
    )

  private val defendingEnemy = EnemyInstance(
    entityId = "dummy",
    typeId = "goblin",
    label = "Goblin",
    hp = 500,
    maxHp = 500,
    attack = 1,
    defense = 0,
    xpReward = 0,
    actions = List(EnemyActionWeight("DEFEND", 100)),
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

  private def stateWithBuffs(player: Player, buffs: List[TimedBuff]): CombatState =
    CombatState(player, makeDungeon, 0, 0, Combat(enemy = defendingEnemy, activeBuffs = buffs), "dummy")

  // -----------------------------------------------------------------------
  // Buff decrement / expiry
  // -----------------------------------------------------------------------

  test("a buff with 1 turn remaining expires after one round and logs it") {
    val buff  = TimedBuff(TimedBuffEffect.AttackBonusPercent(20), turnsRemaining = 1)
    val state = stateWithBuffs(makePlayer(), List(buff))
    val (next, log, _) = CombatResolver(Random(1)).resolve(state, CombatAction(CombatActionType.Defend))

    val nextCombat = next match { case cs: CombatState => cs.combat; case _ => fail("expected CombatState") }
    assertEquals(nextCombat.activeBuffs, Nil)
    assert(log.exists(_.contains("fades")), s"expected an expiry log line: $log")
  }

  test("a buff with more than 1 turn remaining decrements but survives") {
    val buff  = TimedBuff(TimedBuffEffect.AttackBonusPercent(20), turnsRemaining = 3)
    val state = stateWithBuffs(makePlayer(), List(buff))
    val (next, log, _) = CombatResolver(Random(1)).resolve(state, CombatAction(CombatActionType.Defend))

    val nextCombat = next match { case cs: CombatState => cs.combat; case _ => fail("expected CombatState") }
    assertEquals(nextCombat.activeBuffs, List(TimedBuff(TimedBuffEffect.AttackBonusPercent(20), turnsRemaining = 2)))
    assert(!log.exists(_.contains("fades")), s"a surviving buff shouldn't log an expiry: $log")
  }

  test("multiple buffs decrement independently, one can expire while another survives") {
    val shortBuff = TimedBuff(TimedBuffEffect.AttackBonusPercent(10), turnsRemaining = 1)
    val longBuff  = TimedBuff(TimedBuffEffect.AttackBonusPercent(20), turnsRemaining = 2)
    val state     = stateWithBuffs(makePlayer(), List(shortBuff, longBuff))
    val (next, _, _) = CombatResolver(Random(1)).resolve(state, CombatAction(CombatActionType.Defend))

    val nextCombat = next match { case cs: CombatState => cs.combat; case _ => fail("expected CombatState") }
    assertEquals(nextCombat.activeBuffs, List(TimedBuff(TimedBuffEffect.AttackBonusPercent(20), turnsRemaining = 1)))
  }
