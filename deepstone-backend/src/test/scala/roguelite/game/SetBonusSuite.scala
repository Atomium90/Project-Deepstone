package roguelite.game

import munit.FunSuite
import roguelite.engine.{ ClassId, CombatAction, CombatActionType, CombatState, Player }

import scala.util.Random

/** Tests for equipment set bonus resolution in [[CombatResolver]]: piece counting, the 2pc/4pc
  * threshold, and the "stat simple" bonus kinds (FlatAttack, FlatDefense, CritChancePercent,
  * AttackDamagePercent) actually showing up in combat math.
  */
class SetBonusSuite extends FunSuite:

  // -----------------------------------------------------------------------
  // Fixtures
  // -----------------------------------------------------------------------

  private def makePlayer(classId: ClassId = ClassId.Warrior,
                         affinityTags: Set[String] = Set.empty,
                         hp: Int = 200,
                         maxHp: Int = 200,
                         level: Int = 1
  ): Player =
    Player(classId = classId,
           hp = hp,
           maxHp = maxHp,
           resourceCurrent = 0,
           resourceMax = 100,
           level = level,
           xp = 0,
           metaCurrency = 0,
           affinityTags = affinityTags
    )

  /** A weapon/armor/accessory pair of dummy items sharing `setId`, all with zero own stats so
    * only the set bonus itself is observable once equipped.
    */
  private def dummyWeapon(setId: String): Weapon =
    Weapon(id = Item.newId(), typeId = "dw", name = "Dummy Weapon", rarity = Rarity.Common,
           attackBonus = 0, setId = Some(setId)
    )
  private def dummyArmor(setId: String): Armor =
    Armor(id = Item.newId(), typeId = "da", name = "Dummy Armor", rarity = Rarity.Common,
          defenseBonus = 0, setId = Some(setId)
    )
  private def dummyAccessory(setId: String, n: Int): Accessory =
    Accessory(id = Item.newId(), typeId = s"dac$n", name = s"Dummy Accessory $n", rarity = Rarity.Common,
              setId = Some(setId)
    )

  /** Equip `count` (0-4) pieces of `setId` onto `player`: weapon, armor, then accessory 0, then
    * accessory 1, in that order. Every piece has zero own stats (see [[dummyWeapon]] etc.).
    */
  private def withSetPieces(player: Player, setId: String, count: Int): Player =
    val p1 = if count >= 1 then player.copy(equippedWeapon = Some(dummyWeapon(setId))) else player
    val p2 = if count >= 2 then p1.copy(equippedArmor = Some(dummyArmor(setId))) else p1
    val p3 =
      if count >= 3 then
        p2.copy(equippedAccessories = p2.equippedAccessories.updated(0, Some(dummyAccessory(setId, 0))))
      else p2
    if count >= 4 then
      p3.copy(equippedAccessories = p3.equippedAccessories.updated(1, Some(dummyAccessory(setId, 1))))
    else p3

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

  // -----------------------------------------------------------------------
  // activeSetBonuses: piece counting and thresholds (pure, package-private)
  // -----------------------------------------------------------------------

  private val twoPieceOnly = SetDef(
    id = "two_piece_set",
    name = "Two Piece Set",
    classId = ClassId.Warrior,
    twoPiece = SetBonus(SetBonusEffect.FlatAttack(3), "+3 flat ATK"),
    fourPiece = SetBonus(SetBonusEffect.FlatDefense(4), "+4 flat DEF")
  )

  test("activeSetBonuses is empty with 0 or 1 pieces equipped") {
    val resolver = CombatResolver(Random(1), setDefs = Map("two_piece_set" -> twoPieceOnly))
    assertEquals(resolver.activeSetBonuses(withSetPieces(makePlayer(), "two_piece_set", 0)), Nil)
    assertEquals(resolver.activeSetBonuses(withSetPieces(makePlayer(), "two_piece_set", 1)), Nil)
  }

  test("activeSetBonuses includes only the 2pc effect at 2 or 3 pieces") {
    val resolver = CombatResolver(Random(1), setDefs = Map("two_piece_set" -> twoPieceOnly))
    val expected = List(SetBonusEffect.FlatAttack(3))
    assertEquals(resolver.activeSetBonuses(withSetPieces(makePlayer(), "two_piece_set", 2)), expected)
    assertEquals(resolver.activeSetBonuses(withSetPieces(makePlayer(), "two_piece_set", 3)), expected)
  }

  test("activeSetBonuses includes both the 2pc and 4pc effect at 4 pieces") {
    val resolver = CombatResolver(Random(1), setDefs = Map("two_piece_set" -> twoPieceOnly))
    val active   = resolver.activeSetBonuses(withSetPieces(makePlayer(), "two_piece_set", 4))
    assertEquals(active, List(SetBonusEffect.FlatAttack(3), SetBonusEffect.FlatDefense(4)))
  }

  test("activeSetBonuses ignores an equipped setId absent from the loaded catalog") {
    val resolver = CombatResolver(Random(1), setDefs = Map.empty)
    assertEquals(resolver.activeSetBonuses(withSetPieces(makePlayer(), "unknown_set", 4)), Nil)
  }

  test("activeSetBonuses is empty when the set's classId doesn't match the player's class") {
    val resolver = CombatResolver(Random(1), setDefs = Map("two_piece_set" -> twoPieceOnly))
    val archer   = withSetPieces(makePlayer(classId = ClassId.Archer), "two_piece_set", 4)
    assertEquals(resolver.activeSetBonuses(archer), Nil,
                 "an Archer wearing a full Warrior set should get none of its bonuses"
    )
  }

  // -----------------------------------------------------------------------
  // hasFourPieceSetActive: same class-gating as activeBonuses, boolean shape
  // -----------------------------------------------------------------------

  test("hasFourPieceSetActive is true at 4 pieces, false below that") {
    val setDefs = Map("two_piece_set" -> twoPieceOnly)
    val player  = makePlayer()
    assertEquals(
      SetDef.hasFourPieceSetActive(withSetPieces(player, "two_piece_set", 4).equippedSetIds, setDefs, ClassId.Warrior),
      true
    )
    assertEquals(
      SetDef.hasFourPieceSetActive(withSetPieces(player, "two_piece_set", 3).equippedSetIds, setDefs, ClassId.Warrior),
      false
    )
  }

  test("hasFourPieceSetActive is false when the set's classId doesn't match the player's class") {
    val setDefs = Map("two_piece_set" -> twoPieceOnly)
    val archer  = withSetPieces(makePlayer(classId = ClassId.Archer), "two_piece_set", 4)
    assertEquals(SetDef.hasFourPieceSetActive(archer.equippedSetIds, setDefs, ClassId.Archer), false,
                 "an Archer wearing a full Warrior set should not count as an active 4-piece bonus"
    )
  }

  test("hasFourPieceSetActive is false for a setId absent from the loaded catalog") {
    val player = withSetPieces(makePlayer(), "unknown_set", 4)
    assertEquals(SetDef.hasFourPieceSetActive(player.equippedSetIds, Map.empty, ClassId.Warrior), false)
  }

  // -----------------------------------------------------------------------
  // FlatAttack / FlatDefense: show up exactly in combat math
  // -----------------------------------------------------------------------

  test("FlatAttack set bonus adds exactly to the player's attack") {
    val setDefs = Map(twoPieceOnly.id -> twoPieceOnly)
    val base    = makePlayer()
    val withSet = withSetPieces(base, twoPieceOnly.id, 2) // 2pc only: FlatAttack(3), no FlatDefense yet

    val (nextBase, _, _)    = CombatResolver(Random(5), setDefs = setDefs)
      .resolve(makeCombatState(base), CombatAction(CombatActionType.Attack))
    val (nextWithSet, _, _) = CombatResolver(Random(5), setDefs = setDefs)
      .resolve(makeCombatState(withSet), CombatAction(CombatActionType.Attack))

    val dmgBase    = 500 - extractEnemyHp(nextBase)
    val dmgWithSet = 500 - extractEnemyHp(nextWithSet)
    assertEquals(dmgWithSet - dmgBase, 3, s"expected the +3 FlatAttack bonus to add exactly 3 damage")
  }

  test("FlatDefense set bonus (4pc) reduces exactly the damage taken") {
    val setDefs = Map(twoPieceOnly.id -> twoPieceOnly)
    val base    = makePlayer()
    val withSet = withSetPieces(base, twoPieceOnly.id, 4) // 4pc: adds FlatDefense(4) on top of 2pc

    val (nextBase, _, _)    = CombatResolver(Random(9), setDefs = setDefs)
      .resolve(makeCombatState(base), CombatAction(CombatActionType.Attack))
    val (nextWithSet, _, _) = CombatResolver(Random(9), setDefs = setDefs)
      .resolve(makeCombatState(withSet), CombatAction(CombatActionType.Attack))

    val playerDmgBase    = 200 - extractPlayerHp(nextBase)
    val playerDmgWithSet = 200 - extractPlayerHp(nextWithSet)
    // 2pc's FlatAttack(3) also increases the player's own hit (already covered above) but has no
    // effect on damage taken; only the 4pc FlatDefense(4) should reduce it, by exactly 4.
    assertEquals(playerDmgBase - playerDmgWithSet, 4,
                 s"expected the +4 FlatDefense bonus to reduce damage taken by exactly 4"
    )
  }

  // -----------------------------------------------------------------------
  // CritChancePercent and AttackDamagePercent
  // -----------------------------------------------------------------------

  test("CritChancePercent set bonus can push critChance to a guaranteed crit") {
    val guaranteedCritSet = SetDef(
      id = "crit_set",
      name = "Crit Set",
      classId = ClassId.Warrior,
      twoPiece = SetBonus(SetBonusEffect.CritChancePercent(100), "+100% crit chance"),
      fourPiece = SetBonus(SetBonusEffect.FlatAttack(0), "no-op")
    )
    val setDefs = Map(guaranteedCritSet.id -> guaranteedCritSet)
    val base    = makePlayer()
    val withSet = withSetPieces(base, guaranteedCritSet.id, 2)

    val (nextBase, _, _)    = CombatResolver(Random(3), setDefs = setDefs)
      .resolve(makeCombatState(base), CombatAction(CombatActionType.Attack))
    val (nextWithSet, log, _) = CombatResolver(Random(3), setDefs = setDefs)
      .resolve(makeCombatState(withSet), CombatAction(CombatActionType.Attack))

    val dmgBase    = 500 - extractEnemyHp(nextBase)
    val dmgWithSet = 500 - extractEnemyHp(nextWithSet)
    assert(log.exists(_.contains("Critical hit!")), "expected the guaranteed crit to be logged")
    assert(dmgWithSet > dmgBase, s"expected a crit to deal more damage (base=$dmgBase, withSet=$dmgWithSet)")
  }

  test("AttackDamagePercent set bonus scales the final hit by exactly its percentage") {
    val dmgSet = SetDef(
      id = "dmg_set",
      name = "Dmg Set",
      classId = ClassId.Warrior,
      twoPiece = SetBonus(SetBonusEffect.FlatAttack(0), "no-op"),
      fourPiece = SetBonus(SetBonusEffect.AttackDamagePercent(50), "+50% attack damage")
    )
    val setDefs = Map(dmgSet.id -> dmgSet)
    val base    = makePlayer()
    val withSet = withSetPieces(base, dmgSet.id, 4)

    val (nextBase, _, _)    = CombatResolver(Random(5), setDefs = setDefs)
      .resolve(makeCombatState(base), CombatAction(CombatActionType.Attack))
    val (nextWithSet, _, _) = CombatResolver(Random(5), setDefs = setDefs)
      .resolve(makeCombatState(withSet), CombatAction(CombatActionType.Attack))

    val dmgBase    = 500 - extractEnemyHp(nextBase)
    val dmgWithSet = 500 - extractEnemyHp(nextWithSet)
    assertEquals(dmgWithSet, math.round(dmgBase * 1.5).toInt)
  }

  // -----------------------------------------------------------------------
  // FirstAttackAlwaysCrit, AbilityCostReductionPercent, HealOnKillPercent
  // -----------------------------------------------------------------------

  test("FirstAttackAlwaysCrit guarantees a crit on the first Attack, not on the second") {
    val firstAttackSet = SetDef(
      id = "faac_set",
      name = "First Attack Set",
      classId = ClassId.Warrior,
      twoPiece = SetBonus(SetBonusEffect.FlatAttack(0), "no-op"),
      fourPiece = SetBonus(SetBonusEffect.FirstAttackAlwaysCrit, "first attack always crits")
    )
    val setDefs = Map(firstAttackSet.id -> firstAttackSet)
    val player  = withSetPieces(makePlayer(), firstAttackSet.id, 4) // critChance is otherwise 0

    val resolver = CombatResolver(Random(1), setDefs = setDefs)
    val (afterFirst, firstLog, _) = resolver.resolve(makeCombatState(player), CombatAction(CombatActionType.Attack))
    assert(firstLog.exists(_.contains("Critical hit!")), "expected the first attack to be a guaranteed crit")

    val stateAfterFirst = afterFirst match
      case cs: CombatState => cs
      case _                => fail("expected CombatState after the first attack (enemy has 500 HP)")

    val (_, secondLog, _) = resolver.resolve(stateAfterFirst, CombatAction(CombatActionType.Attack))
    assert(!secondLog.exists(_.contains("Critical hit!")),
           "expected the second attack to not be forced (critChance is otherwise 0)"
    )
  }

  test("AbilityCostReductionPercent reduces the resource cost actually deducted") {
    val costReductionSet = SetDef(
      id = "cost_set",
      name = "Cost Set",
      classId = ClassId.Warrior,
      twoPiece = SetBonus(SetBonusEffect.FlatAttack(0), "no-op"),
      fourPiece = SetBonus(SetBonusEffect.AbilityCostReductionPercent(50), "-50% ability cost")
    )
    val setDefs = Map(costReductionSet.id -> costReductionSet)
    val ability = AbilityDef(ClassId.Warrior,
                             id = "test_ability",
                             name = "Test Strike",
                             cost = 40,
                             resourceName = "Rage",
                             description = "test",
                             effect = AbilityEffect.FlatDamage(1)
    )
    val abilityDefs = Map(ClassId.Warrior -> ability)

    val base    = makePlayer().copy(resourceCurrent = 40, resourceMax = 100)
    val withSet = withSetPieces(base, costReductionSet.id, 4)

    // Defend-only enemy: Warrior gains +10 Rage on being hit, which would confound the resource
    // assertion below if the enemy's counter-attack landed.
    val passiveEnemy = tankEnemy.copy(actions = List(EnemyActionWeight("DEFEND", 100)))
    def stateFor(p: Player): CombatState = CombatState(p, makeDungeon, 0, 0, Combat(enemy = passiveEnemy), "dummy")

    val (nextBase, _, _) = CombatResolver(Random(1), abilityDefs = abilityDefs, setDefs = setDefs)
      .resolve(stateFor(base), CombatAction(CombatActionType.Ability))
    val (nextWithSet, _, _) = CombatResolver(Random(1), abilityDefs = abilityDefs, setDefs = setDefs)
      .resolve(stateFor(withSet), CombatAction(CombatActionType.Ability))

    val resourceBase    = nextBase match { case cs: CombatState => cs.player.resourceCurrent; case _ => fail("expected CombatState") }
    val resourceWithSet = nextWithSet match { case cs: CombatState => cs.player.resourceCurrent; case _ => fail("expected CombatState") }
    assertEquals(resourceBase, 0, "full cost (40) should be deducted without the set")
    assertEquals(resourceWithSet, 20, "half cost (20) should be deducted with the -50% set bonus")
  }

  test("HealOnKillPercent heals a percentage of the killing blow's damage") {
    val healOnKillSet = SetDef(
      id = "heal_set",
      name = "Heal Set",
      classId = ClassId.Warrior,
      twoPiece = SetBonus(SetBonusEffect.HealOnKillPercent(50), "heal 50% of kill damage"),
      fourPiece = SetBonus(SetBonusEffect.FlatDefense(0), "no-op")
    )
    val setDefs = Map(healOnKillSet.id -> healOnKillSet)
    val player  = withSetPieces(makePlayer(hp = 100, maxHp = 200), healOnKillSet.id, 2)

    val fragileEnemy = EnemyInstance(
      entityId = "dummy",
      typeId = "goblin",
      label = "Goblin",
      hp = 1,
      maxHp = 1,
      attack = 0,
      defense = 0,
      xpReward = 10,
      actions = Nil,
      dropChance = 0,
      lootTable = Nil
    )
    val room = Room("r",
                    RoomType.Combat,
                    3,
                    3,
                    Vector.fill(3)(Vector.fill(3)(Tile.Floor)),
                    List(Enemy("dummy", 1, 1, "goblin", "Goblin"))
    )
    val dungeon = Dungeon.fromRooms(List(room)).getOrElse(throw IllegalStateException("test dungeon"))
    val state   = CombatState(player, dungeon, 0, 0, Combat(enemy = fragileEnemy), "dummy")

    val (next, log, events) = CombatResolver(Random(1), setDefs = setDefs).resolve(state, CombatAction(CombatActionType.Attack))
    val nextPlayer = next match
      case es: roguelite.engine.ExplorationState => es.player
      case other                                  => fail(s"expected the 1-HP enemy to die and return ExplorationState, got $other")

    assert(nextPlayer.hp > 100, s"expected the kill to heal the player above their starting 100 HP, got ${nextPlayer.hp}")
    assert(log.exists(_.contains("heals you")), s"expected a heal-on-kill log line: $log")
    assert(events.exists { case GameEvent.Healed(_) => true; case _ => false }, s"expected a Healed event: $events")
  }

  // -----------------------------------------------------------------------
  // Helpers
  // -----------------------------------------------------------------------

  private def extractEnemyHp(state: roguelite.engine.GameState): Int = state match
    case cs: CombatState => cs.combat.enemy.hp
    case _                => fail("expected CombatState (enemy should survive with 500 HP and 0 defense)")

  private def extractPlayerHp(state: roguelite.engine.GameState): Int = state match
    case cs: CombatState => cs.player.hp
    case _                => fail("expected CombatState")
