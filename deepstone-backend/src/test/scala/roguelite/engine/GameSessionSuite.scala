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

  def testDungeon: Dungeon =
    val tiles = makeTiles()
    val r1    = Room("r1", RoomType.Combat, 8, 6, tiles, Nil)
    val r2    = Room("r2", RoomType.Loot, 8, 6, tiles, Nil)
    Dungeon(Map("r1" -> r1, "r2" -> r2), "r1")

  def sm: StateMachine =
    StateMachine(testDungeon,
                 Map("goblin" -> goblinStats),
                 Map.empty,
                 testClassDefs,
                 CombatResolver(Random(0L))
    )

  // One fresh in-memory DB per test
  val db = ResourceFixture(Database.inMemory())

  db.test("new session starts in Hub phase") {
    database =>
      for
        session <- GameSession.create(sm, database, Map.empty)
        update  <- session.currentUpdate
      yield assertEquals(update.phase, GamePhase.Hub)
  }

  db.test("hub state update includes upgrade list") {
    database =>
      for
        session <- GameSession.create(sm, database, Map.empty)
        update  <- session.currentUpdate
      yield
        assert(update.hub.isDefined, "hub should be present")
        assertEquals(update.hub.get.upgrades.length, UpgradeDef.all.length)
  }

  db.test("hub upgrade list shows zero unlocked upgrades on fresh DB") {
    database =>
      for
        session <- GameSession.create(sm, database, Map.empty)
        update  <- session.currentUpdate
      yield assert(update.hub.get.upgrades.forall(!_.unlocked), "no upgrades should be unlocked")
  }

  db.test("StartRun transitions session to Exploration") {
    database =>
      for
        session <- GameSession.create(sm, database, Map.empty)
        update <- session.handle(HubAction(HubActionType.StartRun, classId = Some(ClassId.Warrior)))
      yield assertEquals(update.phase, GamePhase.Exploration)
  }

  db.test("session state persists across multiple handle calls") {
    database =>
      for
        session <- GameSession.create(sm, database, Map.empty)
        _       <- session.handle(HubAction(HubActionType.StartRun, classId = Some(ClassId.Archer)))
        update  <- session.handle(Move(Direction.Right))
      yield
        assertEquals(update.phase, GamePhase.Exploration)
        assertEquals(update.player.classId, ClassId.Archer)
  }

  db.test("invalid action in wrong state returns log and does not crash") {
    database =>
      for
        session <- GameSession.create(sm, database, Map.empty)
        update  <- session.handle(Move(Direction.Up))
      yield
        assertEquals(update.phase, GamePhase.Hub)
        assert(update.log.nonEmpty)
  }

  db.test("StateUpdate always contains inventory list") {
    database =>
      for
        session <- GameSession.create(sm, database, Map.empty)
        update <- session.handle(HubAction(HubActionType.StartRun, classId = Some(ClassId.Warrior)))
      yield assertEquals(update.inventory, Nil)
  }

  db.test("handle is concurrency-safe") {
    database =>
      for
        session <- GameSession.create(sm, database, Map.empty)
        _       <- session.handle(HubAction(HubActionType.StartRun, classId = Some(ClassId.Mage)))
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
        session <- GameSession.create(sm, database, Map.empty) // starts with 0 currency
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
        session <- GameSession.create(sm, database, Map.empty)
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
        session <- GameSession.create(sm, database, Map.empty)
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
        session <- GameSession.create(sm, database, Map.empty)
        update  <- session.currentUpdate
      yield assertEquals(update.player.metaCurrency, 42)
  }
