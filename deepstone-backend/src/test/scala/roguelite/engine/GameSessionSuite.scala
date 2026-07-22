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

  /** Minimal upgrade catalog mirroring upgrades.json — avoids file I/O in unit tests. */
  val testUpgradeDefs: Map[String, UpgradeDef] = Map(
    "hp_boost_1" -> UpgradeDef("hp_boost_1",
                               "Iron Constitution I",
                               "+20 max HP for the next run",
                               cost = 30,
                               displayOrder = 0,
                               effect = UpgradeEffect.MaxHpBoost(20)
    ),
    "hp_boost_2" -> UpgradeDef("hp_boost_2",
                               "Iron Constitution II",
                               "+40 max HP for the next run",
                               cost = 75,
                               displayOrder = 1,
                               effect = UpgradeEffect.MaxHpBoost(40)
    ),
    "potion_start" -> UpgradeDef("potion_start",
                                 "Emergency Supplies",
                                 "Start each run with a Health Potion",
                                 cost = 40,
                                 displayOrder = 2,
                                 effect = UpgradeEffect.StartingItem("health_potion")
    ),
    "archer_unlock" -> UpgradeDef("archer_unlock",
                                  "Ranger's Path",
                                  "Unlock the Archer class",
                                  cost = 50,
                                  displayOrder = 3,
                                  effect = UpgradeEffect.UnlockClass(ClassId.Archer)
    ),
    "mage_unlock" -> UpgradeDef("mage_unlock",
                                "Arcane Studies",
                                "Unlock the Mage class",
                                cost = 80,
                                displayOrder = 4,
                                effect = UpgradeEffect.UnlockClass(ClassId.Mage)
    ),
    "extra_slot" -> UpgradeDef("extra_slot",
                               "Packrat",
                               "Expand your inventory to 7 item slots",
                               cost = 60,
                               displayOrder = 5,
                               effect = UpgradeEffect.ExtraInventorySlot
    )
  )

  /** Minimal achievement catalog mirroring achievements.json — avoids file I/O in unit tests.
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

  /** Room pool for DungeonBuilder — needs at least one Combat (entrance) and one Boss room. */
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
    maxHp = 1,
    attack = 1,
    defense = 0,
    xpReward = 15,
    actions = List(EnemyActionWeight("ATTACK", 100))
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

  db.test("StartRun transitions session to Exploration") {
    database =>
      for
        session <- GameSession.create(sm, database, Map.empty, testUpgradeDefs, Map.empty, testAchievementDefs)
        update <- session.handle(HubAction(HubActionType.StartRun, classId = Some(ClassId.Warrior)))
      yield assertEquals(update.phase, GamePhase.Exploration)
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

  db.test("StateUpdate always contains inventory list") {
    database =>
      for
        session <- GameSession.create(sm, database, Map.empty, testUpgradeDefs, Map.empty, testAchievementDefs)
        update <- session.handle(HubAction(HubActionType.StartRun, classId = Some(ClassId.Warrior)))
      yield assertEquals(update.inventory, Nil)
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
        last <- session.handle(HubAction(HubActionType.BuyUpgrade, upgradeId = Some("extra_slot")))
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
