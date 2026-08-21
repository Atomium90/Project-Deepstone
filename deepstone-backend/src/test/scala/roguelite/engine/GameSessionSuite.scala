package roguelite.engine

import cats.effect.IO
import munit.CatsEffectSuite
import roguelite.db.Database
import roguelite.game.*

import scala.util.Random

class GameSessionSuite extends CatsEffectSuite:

  def makeTiles(w: Int = 8, h: Int = 6): Vector[Vector[Tile]] =
    Vector.tabulate(h, w):
      (row, col) =>
        if row == 0 || row == h - 1 || col == 0 || col == w - 1 then Tile.Wall else Tile.Floor

  val goblinStats: EnemyStats = EnemyStats(
    typeId = "goblin",
    label = "Goblin",
    spriteId = "mob_orc_rogue_idle",
    maxHp = 20,
    attack = 5,
    defense = 0,
    xpReward = 15,
    actions = List(EnemyActionWeight("ATTACK", 100))
  )

  val testClassDefs: Map[ClassId, ClassDef] = Map(
    ClassId.Warrior -> ClassDef(ClassId.Warrior,
                                hp = 120,
                                resourceMax = 100,
                                resourceStart = 0,
                                affinityTags = Set("heavy"),
                                startingKit = Nil
    ),
    ClassId.Archer -> ClassDef(ClassId.Archer,
                               hp = 90,
                               resourceMax = 50,
                               resourceStart = 50,
                               affinityTags = Set("ranged"),
                               startingKit = Nil
    ),
    ClassId.Mage -> ClassDef(ClassId.Mage,
                             hp = 70,
                             resourceMax = 80,
                             resourceStart = 80,
                             affinityTags = Set("magic"),
                             startingKit = Nil
    )
  )

  /** Minimal upgrade catalog mirroring upgrades.json, avoids file I/O in unit tests. */
  val testUpgradeDefs: Map[String, UpgradeDef] = Map(
    "hp_boost_1" -> UpgradeDef("hp_boost_1",
                               "Iron Constitution I",
                               "+20 max HP for the next run",
                               cost = 30,
                               displayOrder = 0,
                               icon = "❤",
                               category = UpgradeCategory.Stat,
                               effect = UpgradeEffect.MaxHpBoost(20)
    ),
    "hp_boost_2" -> UpgradeDef("hp_boost_2",
                               "Iron Constitution II",
                               "+40 max HP for the next run",
                               cost = 75,
                               displayOrder = 1,
                               icon = "❤",
                               category = UpgradeCategory.Stat,
                               effect = UpgradeEffect.MaxHpBoost(40)
    ),
    "potion_start" -> UpgradeDef("potion_start",
                                 "Emergency Supplies",
                                 "Start each run with a Health Potion",
                                 cost = 40,
                                 displayOrder = 2,
                                 icon = "🧪",
                                 category = UpgradeCategory.Meta,
                                 effect = UpgradeEffect.StartingItem("health_potion")
    ),
    "archer_unlock" -> UpgradeDef("archer_unlock",
                                  "Ranger's Path",
                                  "Unlock the Archer class",
                                  cost = 50,
                                  displayOrder = 3,
                                  icon = "🏹",
                                  category = UpgradeCategory.Meta,
                                  effect = UpgradeEffect.UnlockClass(ClassId.Archer)
    ),
    "mage_unlock" -> UpgradeDef("mage_unlock",
                                "Arcane Studies",
                                "Unlock the Mage class",
                                cost = 80,
                                displayOrder = 4,
                                icon = "✦",
                                category = UpgradeCategory.Meta,
                                effect = UpgradeEffect.UnlockClass(ClassId.Mage)
    ),
    "extra_slot" -> UpgradeDef("extra_slot",
                               "Alchemist's Pouch",
                               "Expand your potion belt to 3 slots",
                               cost = 60,
                               displayOrder = 5,
                               icon = "⚗",
                               category = UpgradeCategory.Stat,
                               effect = UpgradeEffect.ExtraPotionSlot
    ),
    "extra_potion_capacity" -> UpgradeDef("extra_potion_capacity",
                                          "Alchemist's Reserve",
                                          "Each potion stack holds up to 5 charges",
                                          cost = 70,
                                          displayOrder = 6,
                                          icon = "🧴",
                                          category = UpgradeCategory.Stat,
                                          effect = UpgradeEffect.ExtraPotionCapacity
    ),
    "weapon_mastery" -> UpgradeDef("weapon_mastery",
                                   "Weapon Mastery",
                                   "+1 flat attack for every run",
                                   cost = 90,
                                   displayOrder = 7,
                                   icon = "⚔",
                                   category = UpgradeCategory.Stat,
                                   effect = UpgradeEffect.FlatAttackBoost(1)
    )
  )

  /** Wider-than-production perk pool (5 entries), so tests can exercise the "3 of N" roll instead
    * of the real catalog's single entry always being trivially offered in full.
    */
  val testPerkDefs: Map[String, PerkDef] =
    (1 to 5).map:
      i =>
        val id = s"perk_$i"
        id -> PerkDef(id, s"Perk $i", s"Test perk $i", icon = "*", effect = PerkEffect.ExtraStartingItem("health_potion"))
    .toMap

  /** Minimal achievement catalog mirroring achievements.json, avoids file I/O in unit tests.
    * Only a small representative subset is needed here (thorough per-condition coverage lives in
    * AchievementCheckerSuite); this just exercises the GameSession wiring end-to-end.
    */
  val testAchievementDefs: Map[String, AchievementDef] = Map(
    "first_blood" -> AchievementDef("first_blood",
                                    "First Blood",
                                    "Defeat your first enemy.",
                                    displayOrder = 0,
                                    condition = AchievementCondition.FirstKill
    ),
    "big_spender" -> AchievementDef("big_spender",
                                    "Big Spender",
                                    "Spend 50 Shards total on upgrades.",
                                    displayOrder = 1,
                                    condition = AchievementCondition.TotalShardsSpent(50)
    ),
    "completionist" -> AchievementDef("completionist",
                                      "Completionist",
                                      "Unlock every hub upgrade.",
                                      displayOrder = 2,
                                      condition = AchievementCondition.AllUpgradesUnlocked
    )
  )

  /** Minimal set catalog for StateUpdate.sets round-trip tests. */
  val testSetDefs: Map[String, SetDef] = Map(
    "light_soldier" -> SetDef("light_soldier",
                              "Light Soldier",
                              ClassId.Warrior,
                              twoPiece = SetBonus(SetBonusEffect.MaxHpPercent(5), "+5% max HP"),
                              fourPiece = SetBonus(SetBonusEffect.FlatDefense(2), "+2 flat DEF in combat")
    )
  )

  /** Room pool for DungeonBuilder, needs at least one Combat (entrance) and one Boss room. */
  def testRoomPool: Map[String, Room] =
    val tiles = makeTiles()
    val r1    = Room("r1", RoomType.Combat, 8, 6, tiles, Nil)
    val r2    = Room("r2", RoomType.Loot, 8, 6, tiles, Nil)
    val r3    = Room("r3", RoomType.Boss, 8, 6, tiles, Nil)
    Map("r1" -> r1, "r2" -> r2, "r3" -> r3)

  def sm: StateMachine =
    StateMachine(testRoomPool,
                 Map("goblin" -> goblinStats),
                 Map.empty,
                 testClassDefs,
                 testUpgradeDefs,
                 CombatResolver(Random(0L))
    )

  /** A single Combat room (guaranteed to be picked as the entrance, since it's the only Combat
    * room in the pool - see DungeonBuilder.pickOne) with one 1-HP goblin already placed in it, so
    * an achievement-triggering kill can be reached through a full session.handle(...) sequence
    * without needing to know where DungeonBuilder actually put the player.
    */
  def achievementRoomPool: Map[String, Room] =
    val tiles = makeTiles()
    val enemy = Enemy("e1", x = 2, y = 1, typeId = "goblin", label = "Goblin")
    val r1    = Room("r1", RoomType.Combat, 8, 6, tiles, List(enemy))
    val boss  = Room("boss", RoomType.Boss, 8, 6, tiles, Nil)
    Map("r1" -> r1, "boss" -> boss)

  val weakGoblinStats: EnemyStats = EnemyStats(
    typeId = "goblin",
    label = "Goblin",
    spriteId = "mob_orc_rogue_idle",
    maxHp = 1,
    attack = 1,
    defense = 0,
    xpReward = 15,
    actions = List(EnemyActionWeight("ATTACK", 100))
  )

  /** Survives the player's attack (maxHp far outsizes any player damage) and one-shots the player
    * back (attack far outsizes any player HP/defense), so a single Attack reliably reaches
    * GameOverState(victory = false) through the enemy's counter-attack.
    */
  val lethalGoblinStats: EnemyStats = EnemyStats(
    typeId = "goblin",
    label = "Goblin",
    spriteId = "mob_orc_rogue_idle",
    maxHp = 99999,
    attack = 9999,
    defense = 0,
    xpReward = 15,
    actions = List(EnemyActionWeight("ATTACK", 100))
  )

  def smWithLethalEnemy: StateMachine =
    StateMachine(achievementRoomPool,
                 Map("goblin" -> lethalGoblinStats),
                 Map.empty,
                 testClassDefs,
                 testUpgradeDefs,
                 CombatResolver(Random(0L))
    )

  def smWithEnemy: StateMachine =
    StateMachine(achievementRoomPool,
                 Map("goblin" -> weakGoblinStats),
                 Map.empty,
                 testClassDefs,
                 testUpgradeDefs,
                 CombatResolver(Random(0L))
    )

  // One fresh in-memory DB per test
  val db = ResourceFixture(Database.inMemory())

  db.test("new session starts in Hub phase") {
    database =>
      for
        session <- GameSession.create(sm, database, Map.empty, testUpgradeDefs, Map.empty, testAchievementDefs)
        update  <- session.currentUpdate
      yield assertEquals(update.phase, GamePhase.Hub)
  }

  db.test("hub state update includes upgrade list") {
    database =>
      for
        session <- GameSession.create(sm, database, Map.empty, testUpgradeDefs, Map.empty, testAchievementDefs)
        update  <- session.currentUpdate
      yield
        assert(update.hub.isDefined, "hub should be present")
        assertEquals(update.hub.get.upgrades.length, testUpgradeDefs.size)
  }

  db.test("hub upgrade list shows zero unlocked upgrades on fresh DB") {
    database =>
      for
        session <- GameSession.create(sm, database, Map.empty, testUpgradeDefs, Map.empty, testAchievementDefs)
        update  <- session.currentUpdate
      yield assert(update.hub.get.upgrades.forall(!_.unlocked), "no upgrades should be unlocked")
  }

  db.test("fresh session offers 3 perks out of a wider catalog") {
    database =>
      for
        session <- GameSession.create(sm, database, Map.empty, testUpgradeDefs, Map.empty, testAchievementDefs,
                                      perkDefs = testPerkDefs, rng = Random(0L)
                   )
        update  <- session.currentUpdate
      yield assertEquals(update.hub.get.perks.length, 3)
  }

  db.test("ReturnToHub after a run rerolls the offered perks") {
    database =>
      for
        session <- GameSession.create(smWithLethalEnemy, database, Map.empty, testUpgradeDefs, Map.empty,
                                      testAchievementDefs, perkDefs = testPerkDefs, rng = Random(0L)
                   )
        _         <- session.handle(HubAction(HubActionType.StartRun, classId = Some(ClassId.Warrior)))
        _         <- session.handle(Interact("e1"))
        afterHit  <- session.handle(CombatAction(CombatActionType.Attack)) // enemy survives, counters, kills player
        afterReturn <- session.handle(HubAction(HubActionType.ReturnToHub))
      yield
        assertEquals(afterHit.phase, GamePhase.GameOver, s"expected defeat to end the run: ${afterHit.log}")
        assertEquals(afterReturn.phase, GamePhase.Hub)
        assertEquals(afterReturn.hub.get.perks.length, 3)
  }

  db.test("BuyUpgrade does not reroll the offered perks") {
    database =>
      for
        session <- GameSession.create(sm, database, Map.empty, testUpgradeDefs, Map.empty, testAchievementDefs,
                                      perkDefs = testPerkDefs, rng = Random(0L)
                   )
        before <- session.currentUpdate
        after  <- session.handle(HubAction(HubActionType.BuyUpgrade, upgradeId = Some("hp_boost_1")))
      yield assertEquals(after.hub.get.perks.map(_.id), before.hub.get.perks.map(_.id))
  }

  db.test("CombatView.abilityCost reflects an active perk discount, not the static catalog cost") {
    database =>
      val ability = AbilityDef(ClassId.Warrior,
                               id = "berserker_slash",
                               name = "Berserker Slash",
                               cost = 40,
                               resourceName = "Rage",
                               description = "test",
                               effect = AbilityEffect.FlatDamage(1)
      )
      val discountPerk = PerkDef("efficient_casting", "Efficient Casting", "test", icon = "*",
                                 effect = PerkEffect.AbilityCostReductionPercent(20)
      )
      for
        session <- GameSession.create(smWithEnemy, database, Map.empty, testUpgradeDefs,
                                      Map(ClassId.Warrior -> ability), testAchievementDefs,
                                      perkDefs = Map(discountPerk.id -> discountPerk), rng = Random(0L)
                   )
        _      <- session.handle(HubAction(HubActionType.StartRun, classId = Some(ClassId.Warrior),
                                           perkId = Some("efficient_casting")
                   ))
        update <- session.handle(Interact("e1"))
      yield
        assertEquals(update.abilities.find(_.classId == ClassId.Warrior).map(_.cost), Some(40),
                     "the static catalog cost stays the raw, undiscounted value"
        )
        assertEquals(update.combat.flatMap(_.abilityCost), Some(32),
                     "the live per-player cost reflects the -20% perk (40 * 0.8 = 32)"
        )
  }

  db.test("StartRun transitions session to Exploration") {
    database =>
      for
        session <- GameSession.create(sm, database, Map.empty, testUpgradeDefs, Map.empty, testAchievementDefs)
        update <- session.handle(HubAction(HubActionType.StartRun, classId = Some(ClassId.Warrior)))
      yield assertEquals(update.phase, GamePhase.Exploration)
  }

  db.test("StartRun populates player.affinityTags from the class catalog") {
    database =>
      for
        session <- GameSession.create(sm, database, Map.empty, testUpgradeDefs, Map.empty, testAchievementDefs)
        update <- session.handle(HubAction(HubActionType.StartRun, classId = Some(ClassId.Warrior)))
      yield assertEquals(update.player.affinityTags, List("heavy"))
  }

  db.test("session state persists across multiple handle calls") {
    database =>
      for
        session <- GameSession.create(sm, database, Map.empty, testUpgradeDefs, Map.empty, testAchievementDefs)
        _ <- session.handle(HubAction(HubActionType.StartRun, classId = Some(ClassId.Warrior)))
        update <- session.handle(Move(Direction.Right))
      yield
        assertEquals(update.phase, GamePhase.Exploration)
        assertEquals(update.player.classId, ClassId.Warrior)
  }

  db.test("invalid action in wrong state returns log and does not crash") {
    database =>
      for
        session <- GameSession.create(sm, database, Map.empty, testUpgradeDefs, Map.empty, testAchievementDefs)
        update  <- session.handle(Move(Direction.Up))
      yield
        assertEquals(update.phase, GamePhase.Hub)
        assert(update.log.nonEmpty)
  }

  db.test("StateUpdate equipment is empty for a starting kit-less run") {
    database =>
      for
        session <- GameSession.create(sm, database, Map.empty, testUpgradeDefs, Map.empty, testAchievementDefs)
        update <- session.handle(HubAction(HubActionType.StartRun, classId = Some(ClassId.Warrior)))
      yield
        assertEquals(update.equipment.weapon, None)
        assertEquals(update.equipment.armor, None)
        assert(update.equipment.accessories.forall(_.isEmpty))
        assert(update.equipment.potionBelt.forall(_.isEmpty))
  }

  db.test("handle is concurrency-safe") {
    database =>
      for
        session <- GameSession.create(sm, database, Map.empty, testUpgradeDefs, Map.empty, testAchievementDefs)
        _ <- session.handle(HubAction(HubActionType.StartRun, classId = Some(ClassId.Warrior)))
        _ <- IO.both(
          session.handle(Move(Direction.Right)),
          session.handle(Move(Direction.Down))
        )
        update <- session.currentUpdate
      yield assertEquals(update.phase, GamePhase.Exploration)
  }

  db.test("BuyUpgrade with insufficient currency returns error log") {
    database =>
      for
        session <- GameSession.create(sm, database, Map.empty, testUpgradeDefs, Map.empty, testAchievementDefs) // starts with 0 currency
        update <- session.handle(
          HubAction(HubActionType.BuyUpgrade, upgradeId = Some("hp_boost_1"))
        )
      yield
        assertEquals(update.phase, GamePhase.Hub)
        assert(update.log.exists(_.contains("Not enough")),
               s"expected insufficient funds error: ${update.log}"
        )
  }

  db.test("BuyUpgrade succeeds and persists when currency is available") {
    database =>
      for
        _       <- database.saveCurrency(100)
        session <- GameSession.create(sm, database, Map.empty, testUpgradeDefs, Map.empty, testAchievementDefs)
        update <- session.handle(
          HubAction(HubActionType.BuyUpgrade, upgradeId = Some("hp_boost_1"))
        )
        meta <- database.loadMeta()
      yield
        assert(update.log.exists(_.contains("purchased")), s"expected success log: ${update.log}")
        assert(meta.isUnlocked("hp_boost_1"), "upgrade should be persisted in DB")
        assertEquals(meta.currency, 70) // 100 - 30
  }

  db.test("hub upgrade appears as unlocked after purchase") {
    database =>
      for
        _       <- database.saveCurrency(100)
        session <- GameSession.create(sm, database, Map.empty, testUpgradeDefs, Map.empty, testAchievementDefs)
        _ <- session.handle(HubAction(HubActionType.BuyUpgrade, upgradeId = Some("hp_boost_1")))
        update <- session.currentUpdate
      yield
        val hp1 = update.hub.get.upgrades.find(_.id == "hp_boost_1")
        assert(hp1.exists(_.unlocked), "hp_boost_1 should show as unlocked in hub view")
  }

  db.test("metaCurrency from previous session is loaded at session start") {
    database =>
      for
        _       <- database.saveCurrency(42)
        session <- GameSession.create(sm, database, Map.empty, testUpgradeDefs, Map.empty, testAchievementDefs)
        update  <- session.currentUpdate
      yield assertEquals(update.player.metaCurrency, 42)
  }

  // -----------------------------------------------------------------------
  // UnlockClass gating (StartRun rejects classes behind an unpurchased upgrade)
  // -----------------------------------------------------------------------

  db.test("StartRun for a class locked behind an unpurchased upgrade is rejected") {
    database =>
      for
        session <- GameSession.create(sm, database, Map.empty, testUpgradeDefs, Map.empty, testAchievementDefs)
        update <- session.handle(HubAction(HubActionType.StartRun, classId = Some(ClassId.Archer)))
      yield
        assertEquals(update.phase, GamePhase.Hub)
        assert(update.log.exists(_.contains("Ranger's Path")), s"expected lock message: ${update.log}")
  }

  db.test("StartRun succeeds for a class once its unlock upgrade is purchased") {
    database =>
      for
        _       <- database.saveCurrency(50)
        session <- GameSession.create(sm, database, Map.empty, testUpgradeDefs, Map.empty, testAchievementDefs)
        _ <- session.handle(HubAction(HubActionType.BuyUpgrade, upgradeId = Some("archer_unlock")))
        update <- session.handle(HubAction(HubActionType.StartRun, classId = Some(ClassId.Archer)))
      yield
        assertEquals(update.phase, GamePhase.Exploration)
        assertEquals(update.player.classId, ClassId.Archer)
  }

  // -----------------------------------------------------------------------
  // applyMetaBonuses (upgrade effects applied at Hub -> Exploration)
  // -----------------------------------------------------------------------

  db.test("hp_boost_1 increases maxHp on the run started right after purchase") {
    database =>
      for
        _       <- database.saveCurrency(30)
        session <- GameSession.create(sm, database, Map.empty, testUpgradeDefs, Map.empty, testAchievementDefs)
        _ <- session.handle(HubAction(HubActionType.BuyUpgrade, upgradeId = Some("hp_boost_1")))
        update <- session.handle(HubAction(HubActionType.StartRun, classId = Some(ClassId.Warrior)))
      yield assertEquals(update.player.maxHp, 140) // base Warrior 120 (test fixture) + 20
  }

  /** A goblin tanky enough to survive one hit (unlike weakGoblinStats' maxHp = 1) and harmless
    * enough not to kill the player back (attack = 0) - lets a single Attack's damage be read off
    * StateUpdate.damageEvents without the fight ending.
    */
  def smWithTankyEnemy: StateMachine =
    StateMachine(achievementRoomPool,
                 Map("goblin" -> weakGoblinStats.copy(maxHp = 999, attack = 0)),
                 Map.empty,
                 testClassDefs,
                 testUpgradeDefs,
                 CombatResolver(Random(0L))
    )

  db.test("weapon_mastery adds a flat +1 to attack damage, compared to an identical unboosted run") {
    database =>
      for
        baselineSession <- GameSession.create(smWithTankyEnemy, database, Map.empty, testUpgradeDefs,
                                              Map.empty, testAchievementDefs, rng = Random(0L)
                            )
        _ <- baselineSession.handle(HubAction(HubActionType.StartRun, classId = Some(ClassId.Warrior)))
        _ <- baselineSession.handle(Interact("e1"))
        baselineAfterAttack <- baselineSession.handle(CombatAction(CombatActionType.Attack))
        baselineDamage = baselineAfterAttack.damageEvents
          .find(!_.targetIsPlayer)
          .map(_.amount)
          .getOrElse(fail("expected a damage event against the enemy"))

        _ <- database.saveCurrency(90)
        _ <- GameSession
          .create(smWithTankyEnemy, database, Map.empty, testUpgradeDefs, Map.empty, testAchievementDefs)
          .flatMap(_.handle(HubAction(HubActionType.BuyUpgrade, upgradeId = Some("weapon_mastery"))))
        boostedSession <- GameSession.create(smWithTankyEnemy, database, Map.empty, testUpgradeDefs,
                                             Map.empty, testAchievementDefs, rng = Random(0L)
                           )
        _ <- boostedSession.handle(HubAction(HubActionType.StartRun, classId = Some(ClassId.Warrior)))
        _ <- boostedSession.handle(Interact("e1"))
        boostedAfterAttack <- boostedSession.handle(CombatAction(CombatActionType.Attack))
        boostedDamage = boostedAfterAttack.damageEvents
          .find(!_.targetIsPlayer)
          .map(_.amount)
          .getOrElse(fail("expected a damage event against the enemy"))
      yield assertEquals(boostedDamage, baselineDamage + 1)
  }

  // Note: unlike hp_boost_1/extra_slot, extra_potion_capacity's effect (Player.potionCapacity) has
  // no counterpart on PlayerView/StateUpdate to assert against at this level - applyUpgradeEffect's
  // ExtraPotionCapacity case is a one-line field set, structurally identical to the already-
  // uncovered-at-this-level ExtraPotionSlot case right above it. EquipmentResolverSuite separately
  // covers the field's actual effect (the pickup dedupe threshold), just not this wiring step.

  // -----------------------------------------------------------------------
  // Achievements
  // -----------------------------------------------------------------------

  db.test("fresh session's achievement catalog lists every def, all locked") {
    database =>
      for
        session <- GameSession.create(sm, database, Map.empty, testUpgradeDefs, Map.empty, testAchievementDefs)
        update  <- session.currentUpdate
      yield
        assertEquals(update.achievements.length, testAchievementDefs.size)
        assert(update.achievements.forall(!_.unlocked), "no achievements should be unlocked on a fresh session")
  }

  db.test("set catalog round-trips onto StateUpdate.sets") {
    database =>
      for
        session <- GameSession.create(sm,
                                      database,
                                      Map.empty,
                                      testUpgradeDefs,
                                      Map.empty,
                                      testAchievementDefs,
                                      testSetDefs
        )
        update <- session.currentUpdate
      yield
        assertEquals(update.sets.length, testSetDefs.size)
        val lightSoldier = update.sets.find(_.id == "light_soldier").getOrElse(fail("expected light_soldier"))
        assertEquals(lightSoldier.classId, ClassId.Warrior)
        assertEquals(lightSoldier.bonus2pcLabel, "+5% max HP")
        assertEquals(lightSoldier.bonus4pcLabel, "+2 flat DEF in combat")
  }

  db.test("winning the first combat unlocks first_blood and persists it") {
    database =>
      for
        session <- GameSession.create(smWithEnemy, database, Map.empty, testUpgradeDefs, Map.empty, testAchievementDefs)
        _         <- session.handle(HubAction(HubActionType.StartRun, classId = Some(ClassId.Warrior)))
        _         <- session.handle(Interact("e1"))
        afterKill <- session.handle(CombatAction(CombatActionType.Attack))
        afterNext <- session.handle(Move(Direction.Up)) // any harmless follow-up action
        unlockedInDb <- database.loadUnlockedAchievements()
      yield
        assert(afterKill.newlyUnlocked.exists(_.id == "first_blood"),
               s"expected first_blood in newlyUnlocked: ${afterKill.newlyUnlocked}"
        )
        assertEquals(afterNext.newlyUnlocked, Nil, "newlyUnlocked is transient, not re-sent")
        assert(unlockedInDb.contains("first_blood"))
  }

  db.test("using a potion in combat persists its typeId to potionTypesUsed in the DB") {
    database =>
      val wellStocked = PerkDef("well_stocked", "Well Stocked", "test", icon = "*",
                                effect = PerkEffect.ExtraStartingItem("health_potion")
      )
      val itemDefs: Map[String, Item] = Map(
        "health_potion" -> Consumable("", "health_potion", "Health Potion", Rarity.Common,
                                      ConsumableEffect.HealFixed(30)
        )
      )
      // StartRun's starting-kit/perk-item resolution reads the StateMachine's own itemDefs (not
      // GameSession's separate copy, used elsewhere) - smWithEnemy hardcodes Map.empty, so this
      // test needs its own instance with health_potion actually resolvable.
      val smWithEnemyAndItems = StateMachine(achievementRoomPool,
                                             Map("goblin" -> weakGoblinStats),
                                             itemDefs,
                                             testClassDefs,
                                             testUpgradeDefs,
                                             CombatResolver(Random(0L))
      )
      for
        session <- GameSession.create(smWithEnemyAndItems, database, itemDefs, testUpgradeDefs, Map.empty,
                                      testAchievementDefs, perkDefs = Map(wellStocked.id -> wellStocked),
                                      rng = Random(0L)
                   )
        afterStart <- session.handle(HubAction(HubActionType.StartRun, classId = Some(ClassId.Warrior),
                                               perkId = Some("well_stocked")
                      ))
        potionId = afterStart.equipment.potionBelt.flatten.head.id
        _         <- session.handle(Interact("e1"))
        _         <- session.handle(CombatAction(CombatActionType.Item, itemId = Some(potionId)))
        statsInDb <- database.loadAchievementStats()
      yield assert(statsInDb.potionTypesUsed.contains("health_potion"),
                   s"expected health_potion in potionTypesUsed: ${statsInDb.potionTypesUsed}"
      )
  }

  db.test("winning a boss fight with an active perk persists the perk id to perksWonWith in the DB") {
    database =>
      val heavyHand = PerkDef("heavy_hand", "Heavy Hand", "test", icon = "*",
                              effect = PerkEffect.FlatDamageBonus(1)
      )
      // A 2-room pool (exactly 1 Combat + 1 Boss room, nothing else) clamps DungeonBuilder's
      // totalRooms down to 2 regardless of difficulty (count = totalRooms.max(2).min(pool.size)),
      // so the entrance's exit door leads straight to the boss room - no middle rooms to navigate.
      val tiles         = makeTiles()
      val doorToBoss    = Door("door_to_boss", x = 4, y = 5, direction = Direction.Down, targetRoomId = "NEXT")
      val doorFromBoss  = Door("door_entrance", x = 4, y = 0, direction = Direction.Up, targetRoomId = "PREV")
      val entranceRoom  = Room("r1", RoomType.Combat, 8, 6, tiles, List(doorToBoss))
      val bossRoom = Room("boss",
                          RoomType.Boss,
                          8,
                          6,
                          tiles,
                          List(doorFromBoss, Enemy("e1", x = 2, y = 1, typeId = "goblin", label = "Goblin"))
      )
      val smWithBoss = StateMachine(Map("r1" -> entranceRoom, "boss" -> bossRoom),
                                    Map("goblin" -> weakGoblinStats),
                                    Map.empty,
                                    testClassDefs,
                                    testUpgradeDefs,
                                    CombatResolver(Random(0L))
      )
      for
        session <- GameSession.create(smWithBoss, database, Map.empty, testUpgradeDefs, Map.empty,
                                      testAchievementDefs, perkDefs = Map(heavyHand.id -> heavyHand),
                                      rng = Random(0L)
                   )
        _         <- session.handle(HubAction(HubActionType.StartRun, classId = Some(ClassId.Warrior),
                                              perkId = Some("heavy_hand")
                      ))
        _         <- session.handle(Interact("door_to_boss"))
        _         <- session.handle(Interact("e1"))
        afterKill <- session.handle(CombatAction(CombatActionType.Attack))
        statsInDb <- database.loadAchievementStats()
      yield
        assertEquals(afterKill.phase, GamePhase.GameOver)
        assert(afterKill.victory, "expected a boss kill to be a victory")
        assert(statsInDb.perksWonWith.contains("heavy_hand"),
               s"expected heavy_hand in perksWonWith: ${statsInDb.perksWonWith}"
        )
  }

  db.test("purchasing upgrades whose cumulative cost crosses the big_spender threshold unlocks it") {
    database =>
      for
        _       <- database.saveCurrency(200)
        session <- GameSession.create(sm, database, Map.empty, testUpgradeDefs, Map.empty, testAchievementDefs)
        afterFirst <- session.handle(HubAction(HubActionType.BuyUpgrade, upgradeId = Some("hp_boost_1"))) // cost 30
        afterSecond <-
          session.handle(HubAction(HubActionType.BuyUpgrade, upgradeId = Some("potion_start"))) // cost 40, cumulative 70
        unlockedInDb <- database.loadUnlockedAchievements()
      yield
        assertEquals(afterFirst.newlyUnlocked, Nil)
        assert(afterSecond.newlyUnlocked.exists(_.id == "big_spender"),
               s"expected big_spender in newlyUnlocked: ${afterSecond.newlyUnlocked}"
        )
        assert(unlockedInDb.contains("big_spender"))
  }

  db.test("purchasing the last remaining upgrade unlocks completionist") {
    database =>
      for
        _       <- database.saveCurrency(1000)
        session <- GameSession.create(sm, database, Map.empty, testUpgradeDefs, Map.empty, testAchievementDefs)
        _    <- session.handle(HubAction(HubActionType.BuyUpgrade, upgradeId = Some("hp_boost_1")))
        _    <- session.handle(HubAction(HubActionType.BuyUpgrade, upgradeId = Some("hp_boost_2")))
        _    <- session.handle(HubAction(HubActionType.BuyUpgrade, upgradeId = Some("potion_start")))
        _    <- session.handle(HubAction(HubActionType.BuyUpgrade, upgradeId = Some("archer_unlock")))
        _    <- session.handle(HubAction(HubActionType.BuyUpgrade, upgradeId = Some("mage_unlock")))
        _    <- session.handle(HubAction(HubActionType.BuyUpgrade, upgradeId = Some("extra_slot")))
        _    <- session.handle(HubAction(HubActionType.BuyUpgrade, upgradeId = Some("extra_potion_capacity")))
        last <- session.handle(HubAction(HubActionType.BuyUpgrade, upgradeId = Some("weapon_mastery")))
      yield
        assert(last.newlyUnlocked.exists(_.id == "completionist"),
               s"expected completionist in newlyUnlocked: ${last.newlyUnlocked}"
        )
  }

  db.test("an unlocked achievement survives reconnect (fresh GameSession against the same DB)") {
    database =>
      for
        _        <- database.saveCurrency(200)
        session1 <- GameSession.create(sm, database, Map.empty, testUpgradeDefs, Map.empty, testAchievementDefs)
        _        <- session1.handle(HubAction(HubActionType.BuyUpgrade, upgradeId = Some("hp_boost_1")))
        _ <- session1.handle(HubAction(HubActionType.BuyUpgrade, upgradeId = Some("potion_start"))) // crosses 50
        session2 <- GameSession.create(sm, database, Map.empty, testUpgradeDefs, Map.empty, testAchievementDefs)
        update   <- session2.currentUpdate
      yield
        val bigSpender = update.achievements.find(_.id == "big_spender")
        assert(bigSpender.exists(_.unlocked), s"expected big_spender unlocked on reconnect: ${update.achievements}")
  }
