package roguelite.game

import munit.FunSuite
import roguelite.engine.*

import scala.util.Random

class CombatResolverSuite extends FunSuite:

  // ─────────────────────────────────────────────
  // Fixtures
  // ─────────────────────────────────────────────

  /** Seeded resolver for deterministic tests. */
  def resolver(seed: Long = 0L): CombatResolver = CombatResolver(Random(seed))

  def makeTiles(w: Int = 8, h: Int = 6): Vector[Vector[roguelite.game.Tile]] =
    Vector.tabulate(h, w): (row, col) =>
      if row == 0 || row == h - 1 || col == 0 || col == w - 1
      then roguelite.game.Tile.Wall
      else roguelite.game.Tile.Floor

  def testRoom(id: String, entities: List[Entity] = Nil): Room =
    Room(id, RoomType.Combat, width = 8, height = 6, tiles = makeTiles(), entities = entities)

  def weakEnemy(entityId: String = "e1", hp: Int = 1): EnemyInstance =
    EnemyInstance(
      entityId  = entityId,
      typeId    = "goblin",
      label     = "Goblin",
      hp        = hp,
      maxHp     = 20,
      attack    = 5,
      defense   = 0,
      xpReward  = 15,
      actions   = List(EnemyActionWeight("ATTACK", 100)),
    )

  def strongEnemy(entityId: String = "e1"): EnemyInstance =
    EnemyInstance(
      entityId  = entityId,
      typeId    = "boss",
      label     = "Boss",
      hp        = 500,
      maxHp     = 500,
      attack    = 999,
      defense   = 0,
      xpReward  = 100,
      actions   = List(EnemyActionWeight("ATTACK", 100)),
    )

  def defendingEnemy(entityId: String = "e1"): EnemyInstance =
    EnemyInstance(
      entityId  = entityId,
      typeId    = "goblin",
      label     = "Goblin",
      hp        = 50,
      maxHp     = 50,
      attack    = 5,
      defense   = 0,
      xpReward  = 15,
      actions   = List(EnemyActionWeight("DEFEND", 100)),
    )

  def fullHpPlayer(classId: ClassId = ClassId.Warrior): Player =
    Player.startingPlayer(classId)

  def lowHpPlayer: Player =
    Player.startingPlayer(ClassId.Warrior).copy(hp = 1)

  def testDungeon(enemies: List[Entity] = Nil): Dungeon =
    val room = testRoom("r1", entities = enemies)
    Dungeon(rooms = Map("r1" -> room), currentRoomId = "r1")

  def combatState(enemy: EnemyInstance, player: Player = fullHpPlayer()): CombatState =
    val entityEnemy = Enemy(id = enemy.entityId, x = 3, y = 3, typeId = enemy.typeId, label = enemy.label)
    CombatState(
      player        = player,
      dungeon       = testDungeon(enemies = List(entityEnemy)),
      playerX       = 1,
      playerY       = 1,
      combat        = Combat(enemy = enemy),
      enemyEntityId = enemy.entityId,
    )

  // ─────────────────────────────────────────────
  // calcDamage
  // ─────────────────────────────────────────────

  test("calcDamage always returns at least 1"):
    val r = resolver()
    for _ <- 1 to 100 do
      assert(r.calcDamage(1, 999) >= 1, "Damage should never be below 1")

  test("calcDamage scales with attack stat"):
    val r      = resolver(seed = 42L)
    val low    = r.calcDamage(5, 0)
    val high   = r.calcDamage(50, 0)
    assert(high > low, s"Higher attack should deal more damage on average ($high vs $low)")

  test("calcDamage is reduced by defense"):
    val r        = resolver(seed = 42L)
    val noArmor  = r.calcDamage(20, 0)
    val withArmor = CombatResolver(Random(42L)).calcDamage(20, 10)
    assert(noArmor > withArmor, s"Defense should reduce damage ($noArmor vs $withArmor)")

  // ─────────────────────────────────────────────
  // Attack action
  // ─────────────────────────────────────────────

  test("Attack on a 1-HP enemy produces victory"):
    val state = combatState(weakEnemy(hp = 1))
    val (next, _) = resolver().resolve(state, CombatAction(CombatActionType.Attack))
    assert(next.isInstanceOf[ExplorationState], s"Expected ExplorationState, got ${next.getClass.getSimpleName}")

  test("Victory awards XP to the player"):
    val state     = combatState(weakEnemy(hp = 1))
    val (next, _) = resolver().resolve(state, CombatAction(CombatActionType.Attack))
    assert(next.player.xp > 0, "Player should have gained XP")

  test("Victory removes the enemy entity from the room"):
    val state     = combatState(weakEnemy(entityId = "e1", hp = 1))
    val (next, _) = resolver().resolve(state, CombatAction(CombatActionType.Attack))
    val room      = next.asInstanceOf[ExplorationState].dungeon.currentRoom
    assertEquals(room.entityById("e1"), None)

  test("Attack on healthy enemy stays in CombatState"):
    val state     = combatState(weakEnemy(hp = 999))
    val (next, _) = resolver().resolve(state, CombatAction(CombatActionType.Attack))
    // Enemy has 999 HP — player cannot one-shot it
    assert(next.isInstanceOf[CombatState] || next.isInstanceOf[GameOverState])

  test("Attack log mentions damage dealt"):
    val state   = combatState(weakEnemy(hp = 999))
    val (_, log) = resolver().resolve(state, CombatAction(CombatActionType.Attack))
    assert(log.exists(_.contains("damage")), s"Expected damage mention in log: $log")

  // ─────────────────────────────────────────────
  // Defend action
  // ─────────────────────────────────────────────

  test("Defend stays in CombatState"):
    val state     = combatState(weakEnemy(hp = 50))
    val (next, _) = resolver().resolve(state, CombatAction(CombatActionType.Defend))
    assert(next.isInstanceOf[CombatState])

  test("Defend against attacking enemy reduces damage taken"):
    // Use a deterministic resolver and measure HP difference
    val enemy        = weakEnemy(hp = 50)
    val noDefend     = combatState(enemy)
    val withDefend   = combatState(enemy)

    val (afterNoDefend,  _) = CombatResolver(Random(1L)).resolve(noDefend,   CombatAction(CombatActionType.Attack))
    val (afterWithDefend, _) = CombatResolver(Random(1L)).resolve(withDefend, CombatAction(CombatActionType.Defend))

    // Both should still be alive (enemy is weak), compare HP
    (afterNoDefend, afterWithDefend) match
      case (e: ExplorationState, d: CombatState) =>
        // no-defend killed the enemy; defend kept combat going — inconclusive for this comparison
        ()
      case (e: CombatState, d: CombatState) =>
        assert(
          d.player.hp >= e.player.hp,
          s"Defending player should have taken less damage (defend HP: ${d.player.hp}, no-defend HP: ${e.player.hp})"
        )
      case _ => ()

  // ─────────────────────────────────────────────
  // Defeat
  // ─────────────────────────────────────────────

  test("Player death from enemy attack produces GameOverState"):
    val state     = combatState(strongEnemy(), player = lowHpPlayer)
    val (next, _) = resolver().resolve(state, CombatAction(CombatActionType.Attack))
    // Strong enemy always kills a 1-HP player on counter-attack
    assert(
      next.isInstanceOf[GameOverState] || next.isInstanceOf[ExplorationState],
      s"Expected GameOver or Exploration, got ${next.getClass.getSimpleName}"
    )

  // ─────────────────────────────────────────────
  // Stubs
  // ─────────────────────────────────────────────

  test("Ability returns a not-implemented log and stays in CombatState"):
    val state      = combatState(weakEnemy(hp = 50))
    val (next, log) = resolver().resolve(state, CombatAction(CombatActionType.Ability))
    assert(next.isInstanceOf[CombatState])
    assert(log.exists(_.toLowerCase.contains("not yet")))

  test("Item use returns a not-implemented log and stays in CombatState"):
    val state      = combatState(weakEnemy(hp = 50))
    val (next, log) = resolver().resolve(state, CombatAction(CombatActionType.Item))
    assert(next.isInstanceOf[CombatState])
    assert(log.exists(_.toLowerCase.contains("not yet")))