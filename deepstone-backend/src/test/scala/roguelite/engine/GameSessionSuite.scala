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

  /** Upgrade catalog for this suite's GameSession-level behavior tests, avoids file I/O in unit
    * tests. Every entry here is load-bearing: each of the 12 real UpgradeEffect kinds has its own
    * dedicated behavior test further down (e.g. "weapon_mastery adds a flat +1 to attack damage",
    * "rarity_insight guarantees at least Uncommon rarity..."), and the completionist test buys all
    * 12 by id. Unlike testAchievementDefs below, this can't be trimmed to a small subset without
    * deleting that per-effect coverage - it only happens to mirror upgrades.json's current ids
    * because every real effect kind is individually exercised here, not because it's meant to
    * track the production file. A genuinely new UpgradeEffect kind needs a new entry here (and a
    * new behavior test) the same way it needs a new case in UpgradeLoader/GameSession - that's
    * normal test growth, not drift to chase.
    */
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
    ),
    "rarity_insight" -> UpgradeDef("rarity_insight",
                                   "Rarity Insight",
                                   "Every chest is guaranteed at least Uncommon rarity",
                                   cost = 110,
                                   displayOrder = 8,
                                   icon = "🔮",
                                   category = UpgradeCategory.Stat,
                                   effect = UpgradeEffect.GuaranteedChestRarity(Rarity.Uncommon)
    ),
    "warrior_kit" -> UpgradeDef("warrior_kit",
                                "Warrior's Basic Kit",
                                "Start Warrior runs with a weapon and armor",
                                cost = 25,
                                displayOrder = 9,
                                icon = "🛡",
                                category = UpgradeCategory.Meta,
                                effect = UpgradeEffect.UnlockStartingKit(ClassId.Warrior)
    ),
    "archer_kit" -> UpgradeDef("archer_kit",
                               "Archer's Basic Kit",
                               "Start Archer runs with a weapon and armor",
                               cost = 25,
                               displayOrder = 10,
                               icon = "🛡",
                               category = UpgradeCategory.Meta,
                               effect = UpgradeEffect.UnlockStartingKit(ClassId.Archer)
    ),
    "mage_kit" -> UpgradeDef("mage_kit",
                             "Mage's Basic Kit",
                             "Start Mage runs with a weapon and armor",
                             cost = 25,
                             displayOrder = 11,
                             icon = "🛡",
                             category = UpgradeCategory.Meta,
                             effect = UpgradeEffect.UnlockStartingKit(ClassId.Mage)
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

  /** The tier rules the hub tests run against: Normal opens after a win on Easy, Hard after 4 wins
    * on Normal and 300 Shards spent. A suite fixture, so the tests never read `data/difficulty.json`.
    */
  val testTierDefs: Map[Difficulty, DifficultyTierDef] = Map(
    Difficulty.Easy -> DifficultyTierDef(Difficulty.Easy, None, List("dungeon")),
    Difficulty.Normal -> DifficultyTierDef(Difficulty.Normal,
                                           Some(TierRequirement(Difficulty.Easy, 1, 0)),
                                           List("dungeon", "darkDungeon")
    ),
    Difficulty.Hard -> DifficultyTierDef(Difficulty.Hard,
                                         Some(TierRequirement(Difficulty.Normal, 4, 300)),
                                         List("dungeon", "darkDungeon", "darkDungeon")
    ),
    Difficulty.Hardcore -> DifficultyTierDef(Difficulty.Hardcore, None, List("dungeon", "darkDungeon", "darkDungeon"), storyLocked = true)
  )

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

  /** Room pool for DungeonBuilder, needs at least one Combat (entrance), one Sanctuary (the
    * dungeon's real final room), and one Boss room *per biome* - every test here uses the default
    * Normal difficulty (biomeCount = 2), so 2 Boss rooms is the minimum, not 1. */
  def testRoomPool: Map[String, Room] =
    val tiles = makeTiles()
    val r1    = Room("r1", RoomType.Combat, "dungeon", 8, 6, tiles, Nil)
    val r2    = Room("r2", RoomType.Loot, "dungeon", 8, 6, tiles, Nil)
    val r3    = Room("r3", RoomType.Boss, "dungeon", 8, 6, tiles, Nil)
    val r4    = Room("r4", RoomType.Boss, "dungeon", 8, 6, tiles, Nil)
    val s1    = Room("s1", RoomType.Sanctuary, "dungeon", 8, 6, tiles, Nil)
    Map("r1" -> r1, "r2" -> r2, "r3" -> r3, "r4" -> r4, "s1" -> s1)

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
    val tiles  = makeTiles()
    val enemy  = Enemy("e1", x = 2, y = 1, typeId = "goblin", label = "Goblin")
    val r1     = Room("r1", RoomType.Combat, "dungeon", 8, 6, tiles, List(enemy))
    val boss1  = Room("boss1", RoomType.Boss, "dungeon", 8, 6, tiles, Nil)
    val boss2  = Room("boss2", RoomType.Boss, "dungeon", 8, 6, tiles, Nil)
    val s1     = Room("s1", RoomType.Sanctuary, "dungeon", 8, 6, tiles, Nil)
    Map("r1" -> r1, "boss1" -> boss1, "boss2" -> boss2, "s1" -> s1)

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

  /** A minimal pool (1 Boss + 1 Sanctuary room, nothing else), played on Easy (biomeCount = 1) so a
    * single Boss room is enough: Boss is required once per biome, and a boss kill does not end the
    * run by itself, so a run is won through the Sanctuary. The run starts in the Boss room itself:
    * boss -> sanctuary, wired automatically by DungeonBuilder from the Boss room's Unresolved door.
    * A Boss room never rolls a trapped door (see DungeonBuilder.rollTrappedDoors), so leaving it is
    * deterministic whatever the seed. To win: Interact "e1", Attack, Interact "door_to_sanctuary",
    * Interact "sanct_1".
    */
  def smWithBossRun: StateMachine = smWithBossRunOf(weakGoblinStats)

  /** [[smWithBossRun]] with the given stats for the Boss, to make the fight winnable or deadly. */
  def smWithBossRunOf(boss: EnemyStats): StateMachine =
    val tiles           = makeTiles()
    val doorToSanctuary = Door("door_to_sanctuary", x = 4, y = 5, direction = Direction.Down, link = DoorLink.Unresolved(ConnectorRole.Next))
    val bossRoom = Room("boss",
                        RoomType.Boss,
                        "dungeon",
                        8,
                        6,
                        tiles,
                        List(doorToSanctuary, Enemy("e1", x = 2, y = 1, typeId = "goblin", label = "Goblin"))
    )
    val sanctuaryRoom = Room("s1", RoomType.Sanctuary, "dungeon", 8, 6, tiles, List(Sanctuary("sanct_1", x = 2, y = 1)))
    StateMachine(
      Map("boss" -> bossRoom, "s1" -> sanctuaryRoom),
      Map("goblin" -> boss),
      Map.empty,
      testClassDefs,
      testUpgradeDefs,
      CombatResolver(Random(0L))
    )

  /** A save that has already won once, so Normal and the run perks are unlocked (under
    * [[testTierDefs]]).
    */
  def seedFirstVictory(database: Database): IO[Unit] =
    database.saveAchievementStats(AchievementStats(runsWon = 1))

  // One fresh in-memory DB per test
  val db = ResourceFixture(Database.inMemory())

  db.test("new session starts in Hub phase") {
    database =>
      for
        session <- GameSession.create(sm, database, Map.empty, testUpgradeDefs, Map.empty, testAchievementDefs)
        update  <- session.currentUpdate
      yield assertEquals(update.phase, GamePhase.Hub)
  }

  db.test("without debug mode a DebugLoadRoom is rejected and no debug room is listed") {
    database =>
      for
        session <- GameSession.create(sm, database, Map.empty, testUpgradeDefs, Map.empty, testAchievementDefs)
        update  <- session.handle(HubAction(HubActionType.DebugLoadRoom, debugRoomId = Some("any_room")))
      yield
        assertEquals(update.phase, GamePhase.Hub)
        assertEquals(update.debugRooms, Nil)
        assert(update.log.exists(_.contains("--debug")), s"expected a hint about --debug: ${update.log}")
  }

  db.test("with debug mode a DebugLoadRoom reaches the room loader") {
    database =>
      for
        session <- GameSession.create(sm, database, Map.empty, testUpgradeDefs, Map.empty, testAchievementDefs,
                                      debugMode = true
        )
        update <- session.handle(HubAction(HubActionType.DebugLoadRoom, debugRoomId = Some("no_such_debug_room")))
      yield
        assertEquals(update.phase, GamePhase.Hub)
        assert(update.log.exists(_.contains("not found")), s"expected the loader's not-found message: ${update.log}")
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

  db.test("a fresh save reports no completed run in the hub") {
    database =>
      for
        session <- GameSession.create(sm, database, Map.empty, testUpgradeDefs, Map.empty, testAchievementDefs)
        update  <- session.currentUpdate
      yield assertEquals(update.hub.map(_.runsCompleted), Some(0))
  }

  db.test("the hub reports the runs completed in earlier sessions") {
    database =>
      for
        _       <- database.saveAchievementStats(AchievementStats(runsCompleted = 4))
        session <- GameSession.create(sm, database, Map.empty, testUpgradeDefs, Map.empty, testAchievementDefs)
        update  <- session.currentUpdate
      yield assertEquals(update.hub.map(_.runsCompleted), Some(4))
  }

  db.test("finishing a run raises the completed count shown once back in the hub") {
    database =>
      for
        session <- GameSession.create(smWithLethalEnemy, database, Map.empty, testUpgradeDefs, Map.empty,
                                      testAchievementDefs
                   )
        _           <- session.handle(HubAction(HubActionType.StartRun, classId = Some(ClassId.Warrior)))
        _           <- session.handle(Interact("e1"))
        afterHit    <- session.handle(CombatAction(CombatActionType.Attack))
        afterReturn <- session.handle(HubAction(HubActionType.ReturnToHub))
      yield
        assertEquals(afterHit.phase, GamePhase.GameOver, s"expected defeat to end the run: ${afterHit.log}")
        assertEquals(afterReturn.hub.map(_.runsCompleted), Some(1))
  }

  db.test("a save with a victory is offered 3 perks out of a wider catalog") {
    database =>
      for
        _       <- seedFirstVictory(database)
        session <- GameSession.create(sm, database, Map.empty, testUpgradeDefs, Map.empty, testAchievementDefs,
                                      perkDefs = testPerkDefs, rng = Random(0L), difficultyDefs = testTierDefs
                   )
        update  <- session.currentUpdate
      yield assertEquals(update.hub.get.perks.length, 3)
  }

  db.test("a save with no victory yet is offered no perk, the catalog being locked") {
    database =>
      for
        session <- GameSession.create(sm, database, Map.empty, testUpgradeDefs, Map.empty, testAchievementDefs,
                                      perkDefs = testPerkDefs, rng = Random(0L), difficultyDefs = testTierDefs
                   )
        update  <- session.currentUpdate
      yield assertEquals(update.hub.get.perks, Nil)
  }

  db.test("losing a run keeps the perks locked on the way back to the hub") {
    database =>
      for
        session <- GameSession.create(smWithLethalEnemy, database, Map.empty, testUpgradeDefs, Map.empty,
                                      testAchievementDefs, perkDefs = testPerkDefs, rng = Random(0L),
                                      difficultyDefs = testTierDefs
                   )
        _           <- session.handle(HubAction(HubActionType.StartRun, classId = Some(ClassId.Warrior),
                                                difficulty = Some(Difficulty.Easy)
                       ))
        _           <- session.handle(Interact("e1"))
        afterHit    <- session.handle(CombatAction(CombatActionType.Attack))
        afterReturn <- session.handle(HubAction(HubActionType.ReturnToHub))
      yield
        assertEquals(afterHit.phase, GamePhase.GameOver, s"expected defeat to end the run: ${afterHit.log}")
        assertEquals(afterReturn.hub.get.perks, Nil)
  }

  /** Plays one lost run and goes back to the hub, returning the hub update. */
  private def loseARunAndReturn(session: GameSession): IO[StateUpdate] =
    for
      _        <- session.handle(HubAction(HubActionType.StartRun, classId = Some(ClassId.Warrior)))
      _        <- session.handle(Interact("e1"))
      afterHit <- session.handle(CombatAction(CombatActionType.Attack))
      _         = assertEquals(afterHit.phase, GamePhase.GameOver, s"expected defeat to end the run: ${afterHit.log}")
      back     <- session.handle(HubAction(HubActionType.ReturnToHub))
    yield back

  db.test("the third finished run tops the Shards up to a starting kit on the way back to the hub") {
    database =>
      for
        _       <- database.saveAchievementStats(AchievementStats(runsCompleted = 2))
        session <- GameSession.create(smWithLethalEnemy, database, Map.empty, testUpgradeDefs, Map.empty,
                                      testAchievementDefs
                   )
        back     <- loseARunAndReturn(session)
        metaInDb <- database.loadMeta()
      yield
        val kitCost = testUpgradeDefs("warrior_kit").cost
        assertEquals(back.player.metaCurrency, kitCost)
        assertEquals(back.log.lastOption, Some(s"You received $kitCost bonus Shards."))
        assertEquals(metaInDb.currency, kitCost, "the grant must be saved, not only shown")
  }

  db.test("the top-up is not given before the third finished run") {
    database =>
      for
        _       <- database.saveAchievementStats(AchievementStats(runsCompleted = 1))
        session <- GameSession.create(smWithLethalEnemy, database, Map.empty, testUpgradeDefs, Map.empty,
                                      testAchievementDefs
                   )
        back <- loseARunAndReturn(session)
      yield
        assertEquals(back.player.metaCurrency, 0)
        assert(!back.log.exists(_.contains("bonus Shards")), s"unexpected top-up: ${back.log}")
  }

  db.test("the top-up is not given to a player who owns a kit") {
    database =>
      for
        _       <- database.saveAchievementStats(AchievementStats(runsCompleted = 2))
        _       <- database.purchaseUpgrade("warrior_kit", newCurrency = 0)
        session <- GameSession.create(smWithLethalEnemy, database, Map.empty, testUpgradeDefs, Map.empty,
                                      testAchievementDefs
                   )
        back <- loseARunAndReturn(session)
      yield
        assertEquals(back.player.metaCurrency, 0)
        assert(!back.log.exists(_.contains("bonus Shards")), s"unexpected top-up: ${back.log}")
  }

  db.test("the first victory unlocks the perks at the next hub visit") {
    database =>
      for
        session <- GameSession.create(smWithBossRun, database, Map.empty, testUpgradeDefs, Map.empty,
                                      testAchievementDefs, perkDefs = testPerkDefs, rng = Random(0L),
                                      difficultyDefs = testTierDefs
                   )
        _           <- session.handle(HubAction(HubActionType.StartRun, classId = Some(ClassId.Warrior),
                                                difficulty = Some(Difficulty.Easy)
                       ))
        _           <- session.handle(Interact("e1"))
        _           <- session.handle(CombatAction(CombatActionType.Attack))
        _           <- session.handle(Interact("door_to_sanctuary"))
        afterWin    <- session.handle(Interact("sanct_1"))
        afterReturn <- session.handle(HubAction(HubActionType.ReturnToHub))
      yield
        assert(afterWin.victory, s"expected the Sanctuary to end the run in victory: ${afterWin.log}")
        assertEquals(afterReturn.hub.get.perks.length, 3)
  }

  test("perkOptionsFor offers nothing while Normal is locked, whatever the catalog") {
    val none = GameSession.perkOptionsFor(testPerkDefs, Set(Difficulty.Easy), Random(0L))
    assertEquals(none, Nil)
  }

  test("perkOptionsFor offers 3 distinct perks once Normal is unlocked") {
    val offered = GameSession.perkOptionsFor(testPerkDefs, Set(Difficulty.Easy, Difficulty.Normal), Random(0L))
    assertEquals(offered.length, 3)
    assertEquals(offered.map(_.id).distinct.length, 3)
  }

  test("perkOptionsFor offers a whole catalog smaller than 3") {
    val small = testPerkDefs.take(2)
    assertEquals(GameSession.perkOptionsFor(small, Set(Difficulty.Easy, Difficulty.Normal), Random(0L)).length, 2)
  }

  // ---------------------------------------------
  // Difficulty tiers
  // ---------------------------------------------

  private def startRunOn(session: GameSession, difficulty: Option[Difficulty]): IO[StateUpdate] =
    session.handle(HubAction(HubActionType.StartRun, classId = Some(ClassId.Warrior), difficulty = difficulty))

  private def tierSession(database: Database): IO[GameSession] =
    GameSession.create(smWithBossRun, database, Map.empty, testUpgradeDefs, Map.empty, testAchievementDefs,
                       rng = Random(0L), difficultyDefs = testTierDefs
    )

  db.test("a new save can start Easy but neither Normal, Hard, nor a run that names no tier") {
    database =>
      for
        session <- tierSession(database)
        normal  <- startRunOn(session, Some(Difficulty.Normal))
        hard    <- startRunOn(session, Some(Difficulty.Hard))
        unnamed <- startRunOn(session, None)
        easy    <- startRunOn(session, Some(Difficulty.Easy))
      yield
        List(normal, hard, unnamed).foreach:
          refused =>
            assertEquals(refused.phase, GamePhase.Hub)
            assert(refused.log.exists(_.contains("not unlocked")), s"expected a locked-tier message: ${refused.log}")
        assertEquals(easy.phase, GamePhase.Exploration)
  }

  db.test("the hub lists every tier, lowest first, with how far the save is from each") {
    database =>
      for
        session <- tierSession(database)
        update  <- session.currentUpdate
      yield
        val tiers = update.hub.get.difficulties
        assertEquals(tiers.map(_.id), List(Difficulty.Easy, Difficulty.Normal, Difficulty.Hard))
        assertEquals(tiers.map(_.unlocked), List(true, false, false))
        assertEquals(tiers(0).requirement, None)
        assertEquals(tiers(1).requirement, Some(TierRequirementView(Difficulty.Easy, 1, 0, 0, 0)))
        assertEquals(tiers(2).requirement, Some(TierRequirementView(Difficulty.Normal, 4, 0, 300, 0)))
  }

  private val wonEverything =
    AchievementStats(runsWon = 50, totalShardsSpent = 5000, winsByDifficulty = Map(Difficulty.Easy -> 1, Difficulty.Normal -> 40, Difficulty.Hard -> 9))

  db.test("the hub does not show Hardcore while the story has not opened it") {
    database =>
      for
        _       <- database.saveAchievementStats(wonEverything)
        session <- tierSession(database)
        update  <- session.currentUpdate
      yield assertEquals(update.hub.get.difficulties.map(_.id), List(Difficulty.Easy, Difficulty.Normal, Difficulty.Hard))
  }

  db.test("a run on Hardcore is refused while the story has not opened it, however much was won") {
    database =>
      for
        _       <- database.saveAchievementStats(wonEverything)
        session <- tierSession(database)
        refused <- startRunOn(session, Some(Difficulty.Hardcore))
      yield
        assertEquals(refused.phase, GamePhase.Hub)
        assert(refused.log.exists(_.contains("not unlocked")), s"expected a locked-tier message: ${refused.log}")
  }

  db.test("once the story opens Hardcore the hub shows it unlocked, with no requirement, last") {
    database =>
      for
        _       <- database.saveHardcoreUnlocked()
        session <- tierSession(database)
        update  <- session.currentUpdate
      yield
        val tiers = update.hub.get.difficulties
        assertEquals(tiers.map(_.id), List(Difficulty.Easy, Difficulty.Normal, Difficulty.Hard, Difficulty.Hardcore))
        assertEquals(tiers.last.unlocked, true)
        assertEquals(tiers.last.requirement, None)
  }

  db.test("a run on Hardcore starts once the story has opened it, on a save that has won nothing") {
    database =>
      for
        _       <- database.saveHardcoreUnlocked()
        session <- tierSession(database)
        started <- startRunOn(session, Some(Difficulty.Hardcore))
      yield assertEquals(started.phase, GamePhase.Exploration)
  }

  db.test("a hidden achievement is left out of the catalog until it is unlocked") {
    database =>
      val secret = AchievementDef("secret", "Secret", "d", 99, AchievementCondition.FirstKill, hidden = true)
      val defs   = testAchievementDefs + (secret.id -> secret)
      for
        hidden  <- GameSession.create(sm, database, Map.empty, testUpgradeDefs, Map.empty, defs).flatMap(_.currentUpdate)
        _       <- database.unlockAchievement("secret")
        shown   <- GameSession.create(sm, database, Map.empty, testUpgradeDefs, Map.empty, defs).flatMap(_.currentUpdate)
      yield
        assertEquals(hidden.achievements.map(_.id).contains("secret"), false)
        assertEquals(hidden.achievements.length, testAchievementDefs.size)
        assertEquals(shown.achievements.find(_.id == "secret").map(_.unlocked), Some(true))
  }

  db.test("a win on Easy unlocks Normal at the next hub visit") {
    database =>
      for
        session <- tierSession(database)
        _           <- startRunOn(session, Some(Difficulty.Easy))
        _           <- session.handle(Interact("e1"))
        _           <- session.handle(CombatAction(CombatActionType.Attack))
        _           <- session.handle(Interact("door_to_sanctuary"))
        afterWin    <- session.handle(Interact("sanct_1"))
        afterReturn <- session.handle(HubAction(HubActionType.ReturnToHub))
        normal      <- startRunOn(session, Some(Difficulty.Normal))
      yield
        assert(afterWin.victory, s"expected the Sanctuary to end the run in victory: ${afterWin.log}")
        assertEquals(afterReturn.hub.get.difficulties.map(_.unlocked), List(true, true, false))
        assertEquals(normal.phase, GamePhase.Exploration)
  }

  db.test("a save that won before wins were counted per tier keeps Normal but not Hard") {
    database =>
      for
        _       <- seedFirstVictory(database)
        session <- tierSession(database)
        update  <- session.currentUpdate
        normal  <- startRunOn(session, Some(Difficulty.Normal))
      yield
        assertEquals(update.hub.get.difficulties.map(_.unlocked), List(true, true, false))
        assertEquals(normal.phase, GamePhase.Exploration)
  }

  db.test("Hard stays locked until the Shards are spent as well as the wins on Normal") {
    database =>
      val wins = Map(Difficulty.Easy -> 1, Difficulty.Normal -> 4)
      for
        _       <- database.saveAchievementStats(AchievementStats(runsWon = 5, totalShardsSpent = 299, winsByDifficulty = wins))
        short   <- tierSession(database).flatMap(_.currentUpdate)
        _       <- database.saveAchievementStats(AchievementStats(runsWon = 5, totalShardsSpent = 300, winsByDifficulty = wins))
        enough  <- tierSession(database).flatMap(_.currentUpdate)
      yield
        assertEquals(short.hub.get.difficulties.map(_.unlocked), List(true, true, false))
        assertEquals(short.hub.get.difficulties(2).requirement, Some(TierRequirementView(Difficulty.Normal, 4, 4, 300, 299)))
        assertEquals(enough.hub.get.difficulties.map(_.unlocked), List(true, true, true))
  }

  // ---------------------------------------------
  // The story at the Rest rooms
  // ---------------------------------------------

  private def storyDoor(id: String, role: ConnectorRole): Door =
    if role == ConnectorRole.Next then Door(id, x = 4, y = 5, direction = Direction.Down, link = DoorLink.Unresolved(role))
    else Door(id, x = 4, y = 0, direction = Direction.Up, link = DoorLink.Unresolved(role))

  /** Boss and Rest rooms only, since neither ever rolls a trapped door: walking through a dungeon built
    * from it is deterministic whatever the seed. A Normal run is Boss, Rest, Boss, Sanctuary and a
    * Hard one Boss, Rest, Boss, Rest, Boss, Sanctuary. Doors are crossed with `Interact("door_exit")`,
    * the server not checking distances, and the Rest's NPC is authored as `npc.1`.
    */
  private def smWithRest: StateMachine = smWithRestExit(DoorKind.Normal, Map.empty)

  /** [[smWithRest]] whose Rest room leaves through a door of the given kind, with the given enemy catalog. */
  private def smWithRestExit(restExit: DoorKind, enemyStats: Map[String, EnemyStats]): StateMachine =
    def room(id: String, roomType: RoomType, extra: Entity*): Room =
      val exit = storyDoor("door_exit", ConnectorRole.Next).copy(doorKind = if roomType == RoomType.Rest then restExit else DoorKind.Normal)
      Room(id, roomType, "dungeon", 8, 6, makeTiles(),
           List(storyDoor("door_entrance", ConnectorRole.Prev), exit) ::: extra.toList
      )
    val sanctuary = Room("sanct", RoomType.Sanctuary, "dungeon", 8, 6, makeTiles(),
                         List(storyDoor("door_entrance", ConnectorRole.Prev), Sanctuary("sanct_1", x = 2, y = 1))
    )
    StateMachine(
      Map("b1" -> room("b1", RoomType.Boss),
          "r1" -> room("r1", RoomType.Rest, Npc("npc.1", x = 4, y = 2, name = "Wren")),
          "sanct" -> sanctuary
      ),
      enemyStats, Map.empty, testClassDefs, testUpgradeDefs, CombatResolver(Random(0L))
    )

  private val testScenes: Map[String, SceneDef] = List(
    SceneDef("npc.1.s1", "npc.1", 1),
    SceneDef("npc.1.s2", "npc.1", 2, requires = Some("npc.1.s1"), offersVow = Some(VowType.NoPotion)),
    SceneDef("npc.2.s1", "npc.2", 1)
  ).map(scene => scene.id -> scene).toMap

  private val testChapters: Map[Int, ChapterDef] =
    Map(1 -> ChapterDef(1, Difficulty.Easy), 2 -> ChapterDef(2, Difficulty.Easy))

  /** npc.2 goes by its title until it has been seen in its first scene. */
  private val testNpcs: Map[String, NpcDef] = List(
    NpcDef("npc.1", "npc_a"),
    NpcDef("npc.2", "npc_b", revealedBy = Some("npc.2.s1"))
  ).map(npc => npc.id -> npc).toMap

  private def storySession(database: Database): IO[GameSession] =
    GameSession.create(smWithRest, database, Map.empty, testUpgradeDefs, Map.empty, testAchievementDefs,
                       rng = Random(0L), sceneDefs = testScenes, chapterDefs = testChapters, npcDefs = testNpcs
    )

  private def startRun(session: GameSession, difficulty: Difficulty): IO[StateUpdate] =
    session.handle(HubAction(HubActionType.StartRun, classId = Some(ClassId.Warrior), difficulty = Some(difficulty)))

  private def goThroughDoor(session: GameSession): IO[StateUpdate] = session.handle(Interact("door_exit"))

  /** The id of the NPC the room shows, as the client would see it. */
  private def npcIn(update: StateUpdate): Option[String] =
    update.room.flatMap(_.entities.find(_.kind == "npc")).map(_.id)

  /** Starts a Normal run and walks into its Rest. */
  private def walkToRest(session: GameSession): IO[StateUpdate] =
    startRun(session, Difficulty.Normal) *> goThroughDoor(session)

  db.test("a Rest keeps the NPC its room was authored with when nobody has a scene to play") {
    database =>
      for
        session <- storySession(database)
        inRest  <- walkToRest(session)
      yield assertEquals(npcIn(inRest), Some("npc.1"))
  }

  db.test("a Rest seats the NPC who has a scene to play") {
    database =>
      for
        _       <- database.saveStoryState(1, None)
        _       <- database.recordSceneSeen("npc.1.s1")
        session <- storySession(database)
        inRest  <- walkToRest(session)
      yield assertEquals(npcIn(inRest), Some("npc.2"))
  }

  db.test("speaking to the NPC of a Rest opens its next scene and holds the player there") {
    database =>
      for
        _        <- database.saveStoryState(1, None)
        _        <- database.recordSceneSeen("npc.2.s1")
        session  <- storySession(database)
        inRest   <- walkToRest(session)
        speaking <- session.handle(Interact("npc.1"))
        moved    <- session.handle(Move(Direction.Left))
        doorTry  <- goThroughDoor(session)
      yield
        assertEquals(speaking.pendingScene, Some(PendingSceneView("npc.1", "scene", sceneId = Some("npc.1.s1"))))
        assertEquals(moved.room.map(_.playerX), inRest.room.map(_.playerX))
        assertEquals(doorTry.room.map(_.roomId), inRest.room.map(_.roomId))
        assert(doorTry.pendingScene.isDefined, "the conversation must stay pending")
  }

  db.test("finishing a scene marks it as played and saves it") {
    database =>
      for
        _       <- database.saveStoryState(1, None)
        _       <- database.recordSceneSeen("npc.2.s1")
        session <- storySession(database)
        _       <- walkToRest(session)
        _       <- session.handle(Interact("npc.1"))
        done    <- session.handle(SceneDone(None))
        saved   <- database.loadStoryProgress()
      yield
        assertEquals(done.pendingScene, None)
        assert(saved.scenesSeen.contains("npc.1.s1"), s"expected the scene in the save: ${saved.scenesSeen}")
  }

  db.test("a scene that offers a vow stays pending until it is answered, and taking the vow is saved") {
    database =>
      for
        _        <- database.saveStoryState(2, None)
        _        <- database.recordSceneSeen("npc.1.s1")
        _        <- database.recordSceneSeen("npc.2.s1")
        session  <- storySession(database)
        _        <- walkToRest(session)
        speaking <- session.handle(Interact("npc.1"))
        noAnswer <- session.handle(SceneDone(None))
        answered <- session.handle(SceneDone(Some(true)))
        saved    <- database.loadStoryProgress()
      yield
        assertEquals(speaking.pendingScene.flatMap(_.offerScene), Some("npc.1.s2"))
        assert(noAnswer.pendingScene.isDefined, "a vow offered must be answered")
        assert(noAnswer.log.exists(_.contains("vow")), s"expected a hint about the vow: ${noAnswer.log}")
        assertEquals(answered.pendingScene, None)
        assertEquals(saved.vows("npc.1"), VowRecord(VowState.Active))
  }

  db.test("an answer to a vow that was not offered is refused and leaves the scene pending") {
    database =>
      for
        _        <- database.saveStoryState(1, None)
        _        <- database.recordSceneSeen("npc.2.s1")
        session  <- storySession(database)
        _        <- walkToRest(session)
        _        <- session.handle(Interact("npc.1"))
        refused  <- session.handle(SceneDone(Some(true)))
      yield assert(refused.pendingScene.isDefined, "the scene must stay pending")
  }

  db.test("finishing a conversation when there is none changes nothing") {
    database =>
      for
        session <- storySession(database)
        update  <- session.handle(SceneDone(None))
      yield assert(update.log.exists(_.contains("no conversation")), s"expected a message: ${update.log}")
  }

  /** The only scene offers the vow of no ordinary kill, so a Rest whose exit is trapped can show what the vow does to the trap. */
  private val inertVowScenes: Map[String, SceneDef] =
    Map("npc.1.s1" -> SceneDef("npc.1.s1", "npc.1", 1, offersVow = Some(VowType.NoOrdinaryKill)))

  private def trappedExitSession(database: Database): IO[GameSession] =
    GameSession.create(smWithRestExit(DoorKind.Trapped, Map("goblin" -> weakGoblinStats)), database, Map.empty,
                       testUpgradeDefs, Map.empty, testAchievementDefs, rng = Random(0L),
                       sceneDefs = inertVowScenes, chapterDefs = testChapters, npcDefs = testNpcs
    )

  private def enemiesIn(update: StateUpdate): Int = update.room.map(_.entities.count(_.kind == "enemy")).getOrElse(0)

  db.test("the traps summon nothing while the vow of no ordinary kill runs") {
    database =>
      for
        _       <- database.saveStoryState(1, None)
        session <- trappedExitSession(database)
        _       <- walkToRest(session)
        _       <- session.handle(Interact("npc.1"))
        _       <- session.handle(SceneDone(Some(true)))
        sprung  <- goThroughDoor(session)
      yield
        assertEquals(enemiesIn(sprung), 0)
        assert(sprung.log.exists(_.toLowerCase.contains("nothing emerges")), s"expected a nothing-emerges message: ${sprung.log}")
  }

  db.test("the same trap summons its guardian when the vow was turned down") {
    database =>
      for
        _       <- database.saveStoryState(1, None)
        session <- trappedExitSession(database)
        _       <- walkToRest(session)
        _       <- session.handle(Interact("npc.1"))
        _       <- session.handle(SceneDone(Some(false)))
        sprung  <- goThroughDoor(session)
      yield assertEquals(enemiesIn(sprung), 1)
  }

  db.test("an NPC in a Rest room is drawn with the sprite of the NPC catalog") {
    database =>
      for
        _       <- database.saveStoryState(1, None)
        _       <- database.recordSceneSeen("npc.1.s1") // only npc.2 has a scene left to play
        session <- storySession(database)
        inRest  <- walkToRest(session)
      yield
        assertEquals(npcIn(inRest), Some("npc.2"))
        assertEquals(inRest.room.flatMap(_.entities.find(_.kind == "npc")).flatMap(_.spriteId), Some("npc_b"))
  }

  db.test("an NPC the catalog does not know is drawn with no sprite of its own") {
    database =>
      for
        session <- GameSession.create(smWithRest, database, Map.empty, testUpgradeDefs, Map.empty, testAchievementDefs,
                                      rng = Random(0L), sceneDefs = testScenes, chapterDefs = testChapters
                   )
        inRest  <- walkToRest(session)
      yield assertEquals(inRest.room.flatMap(_.entities.find(_.kind == "npc")).flatMap(_.spriteId), None)
  }

  db.test("an NPC that gives its name away goes by its title until it has been seen in that scene") {
    database =>
      for
        _        <- database.saveStoryState(1, None)
        _        <- database.recordSceneSeen("npc.1.s1")
        session  <- storySession(database)
        _        <- walkToRest(session)
        first    <- session.handle(Interact("npc.2"))
        _        <- session.handle(SceneDone(None))
        later    <- session.handle(Interact("npc.2"))
      yield
        assertEquals(first.pendingScene.map(_.revealed), Some(false))
        assertEquals(later.pendingScene.map(_.revealed), Some(true))
  }

  db.test("an NPC that gives its name from the start is always known by it") {
    database =>
      for
        _        <- database.saveStoryState(1, None)
        _        <- database.recordSceneSeen("npc.2.s1")
        session  <- storySession(database)
        _        <- walkToRest(session)
        speaking <- session.handle(Interact("npc.1"))
      yield assertEquals(speaking.pendingScene.map(_.revealed), Some(true))
  }

  db.test("winning a run opens the next chapter and is remembered") {
    database =>
      for
        session <- storySession(database)
        _       <- startRun(session, Difficulty.Normal)
        _       <- goThroughDoor(session) // Rest
        _       <- goThroughDoor(session) // second Boss room
        _       <- goThroughDoor(session) // Sanctuary room
        won     <- session.handle(Interact("sanct_1"))
        saved   <- database.loadStoryProgress()
      yield
        assert(won.victory, s"expected the Sanctuary to end the run in victory: ${won.log}")
        assertEquals(saved.chapter, 1)
        assertEquals(saved.lastRun, Some(RunOutcome.Won))
  }

  // ---------------------------------------------
  // The light at the end of the story
  // ---------------------------------------------

  private val endingChapters: Map[Int, ChapterDef] = Map(
    1 -> ChapterDef(1, Difficulty.Easy),
    2 -> ChapterDef(2, Difficulty.Easy),
    3 -> ChapterDef(3, Difficulty.Normal),
    4 -> ChapterDef(4, Difficulty.Normal),
    5 -> ChapterDef(5, Difficulty.Hard),
    6 -> ChapterDef(6, Difficulty.Hard)
  )

  /** npc.2 can take the player's place once its only scene, `npc.2.s1`, has been played. */
  private val endingNpcs: Map[String, NpcDef] =
    testNpcs + ("npc.2" -> NpcDef("npc.2", "npc_b", revealedBy = Some("npc.2.s1"), candidate = true))

  private def endingSession(database: Database): IO[GameSession] =
    GameSession.create(smWithRest, database, Map.empty, testUpgradeDefs, Map.empty, testAchievementDefs,
                       rng = Random(0L), sceneDefs = testScenes, chapterDefs = endingChapters, npcDefs = endingNpcs
    )

  /** Starts a Hard run and walks to the Sanctuary room: Boss, Rest, Boss, Rest, Boss, Sanctuary. */
  private def walkToSanctuary(session: GameSession): IO[Unit] =
    startRun(session, Difficulty.Hard) *> List.fill(5)(goThroughDoor(session)).foldLeft(IO.unit)((acc, door) => acc *> door.void)

  db.test("on Hard, from chapter 5, the Sanctuary asks the question of the light instead of ending the run") {
    database =>
      for
        _       <- database.saveStoryState(5, None)
        session <- endingSession(database)
        _       <- walkToSanctuary(session)
        asked   <- session.handle(Interact("sanct_1"))
        saved   <- database.loadStoryProgress()
      yield
        assertEquals(asked.phase, GamePhase.Exploration)
        assertEquals(asked.victory, false)
        assertEquals(asked.pendingEndingChoice, Some(PendingEndingChoiceView(Nil)))
        assertEquals(saved.lastRun, None, "the run has not ended, so nothing about it is judged yet")
  }

  db.test("the victory that brings the player to the light opens the last chapter, whose card comes before the question") {
    database =>
      for
        _       <- database.saveStoryState(5, None)
        session <- endingSession(database)
        _       <- walkToSanctuary(session)
        before  <- session.currentUpdate
        asked   <- session.handle(Interact("sanct_1"))
        saved   <- database.loadStoryProgress()
      yield
        assertEquals(before.pendingCard, None)
        assertEquals(saved.chapter, 6)
        assertEquals(asked.pendingCard, Some("ch6"))
  }

  db.test("the question lists the NPCs whose story is over, and none when nobody has finished") {
    database =>
      for
        _       <- database.saveStoryState(5, None)
        none    <- endingSession(database).flatMap(session => walkToSanctuary(session) *> session.handle(Interact("sanct_1")))
        _       <- database.recordSceneSeen("npc.2.s1")
        ready   <- endingSession(database).flatMap(session => walkToSanctuary(session) *> session.handle(Interact("sanct_1")))
      yield
        assertEquals(none.pendingEndingChoice.map(_.candidates), Some(Nil))
        assertEquals(ready.pendingEndingChoice.map(_.candidates), Some(List("npc.2")))
  }

  db.test("the player can neither move nor leave while the question of the light is pending") {
    database =>
      for
        _       <- database.saveStoryState(5, None)
        session <- endingSession(database)
        _       <- walkToSanctuary(session)
        asked   <- session.handle(Interact("sanct_1"))
        moved   <- session.handle(Move(Direction.Right))
        left    <- session.handle(Interact("door_entrance"))
      yield
        assertEquals(moved.room.map(r => (r.playerX, r.playerY)), asked.room.map(r => (r.playerX, r.playerY)))
        assertEquals(left.room.map(_.roomId), asked.room.map(_.roomId))
        assertEquals(left.pendingEndingChoice, asked.pendingEndingChoice)
  }

  db.test("entering the light leaves the question to be asked again, without a new chapter") {
    database =>
      for
        _       <- database.saveStoryState(6, Some(RunOutcome.Won))
        _       <- database.saveEnding(Ending.Entered)
        session <- endingSession(database)
        _       <- walkToSanctuary(session)
        asked   <- session.handle(Interact("sanct_1"))
      yield
        assertEquals(asked.phase, GamePhase.Exploration)
        assert(asked.pendingEndingChoice.isDefined, "expected the question again")
        assertEquals(asked.pendingCard, None)
  }

  db.test("a final answer, or a victory on an easier difficulty, ends the run at the Sanctuary as it always did") {
    database =>
      for
        _       <- database.saveStoryState(6, Some(RunOutcome.Won))
        _       <- database.saveEnding(Ending.Refused)
        final_  <- endingSession(database).flatMap(session => walkToSanctuary(session) *> session.handle(Interact("sanct_1")))
        normal  <- endingSession(database).flatMap:
          session =>
            startRun(session, Difficulty.Normal) *> goThroughDoor(session) *> goThroughDoor(session) *> goThroughDoor(session) *>
              session.handle(Interact("sanct_1"))
      yield
        assert(final_.victory, s"expected a plain victory after a final answer: ${final_.log}")
        assert(normal.victory, s"expected a plain victory on Normal: ${normal.log}")
        assertEquals(final_.pendingEndingChoice, None)
        assertEquals(normal.pendingEndingChoice, None)
  }

  /** Walks a Hard run to the Sanctuary, walks into it, and reads the chapter card the way the client does,
    * so that the next card to come is the one of the answer.
    */
  private def askTheLight(session: GameSession): IO[StateUpdate] =
    walkToSanctuary(session) *> session.handle(Interact("sanct_1")) <* session.handle(CardSeen("ch6"))

  db.test("entering the light ends the run in victory, keeps the Shards, and brings the first card of the end") {
    database =>
      for
        _       <- database.saveStoryState(5, None)
        _       <- database.saveCurrency(120)
        session <- endingSession(database)
        _       <- askTheLight(session)
        entered <- session.handle(EndingChoice(EndingAnswer.Enter))
        saved   <- database.loadStoryProgress()
        meta    <- database.loadMeta()
      yield
        assertEquals(entered.phase, GamePhase.GameOver)
        assert(entered.victory, s"expected a victory: ${entered.log}")
        assertEquals(entered.pendingEndingChoice, None)
        assertEquals(entered.pendingCard, Some("end1"))
        assertEquals(saved.ending, Some(Ending.Entered))
        assertEquals(saved.lastRun, Some(RunOutcome.Won), "the run is judged now that it has ended")
        assertEquals(saved.cards.map(_.id), List("ch6", "end1"))
        assertEquals(meta.currency, 120)
  }

  db.test("refusing the light takes every Shard, shuts the altar, opens Hardcore, and no top-up gives the Shards back") {
    database =>
      for
        _       <- database.saveStoryState(5, None)
        _       <- database.saveCurrency(120)
        _       <- database.saveAchievementStats(AchievementStats(runsCompleted = 5))
        session <- endingSession(database)
        _       <- askTheLight(session)
        refused <- session.handle(EndingChoice(EndingAnswer.Refuse))
        _       <- session.handle(CardSeen("end3"))
        next    <- session.currentUpdate
        saved   <- database.loadStoryProgress()
        meta    <- database.loadMeta()
        _       <- session.handle(CardSeen("hardcore"))
        back    <- session.handle(HubAction(HubActionType.ReturnToHub))
        metaEnd <- database.loadMeta()
      yield
        assertEquals(refused.phase, GamePhase.GameOver)
        assert(refused.victory, s"expected a victory: ${refused.log}")
        assertEquals(refused.player.metaCurrency, 0)
        assertEquals(refused.pendingCard, Some("end3"))
        assertEquals(next.pendingCard, Some("hardcore"), "the card of Hardcore follows the one of the answer")
        assertEquals(saved.ending, Some(Ending.Refused))
        assertEquals(saved.shopClosed, true)
        assertEquals(saved.hardcoreUnlocked, true)
        assertEquals(saved.cards.map(_.id), List("ch6", "end3", "hardcore"))
        assertEquals(meta.currency, 0, "the Shards are gone from the save, not only from the screen")
        assertEquals(back.phase, GamePhase.Hub)
        assertEquals(back.player.metaCurrency, 0)
        assert(!back.log.exists(_.contains("bonus Shards")), s"no top-up once the altar is shut: ${back.log}")
        assertEquals(metaEnd.currency, 0)
  }

  db.test("letting an NPC take the place plays their last scene first, and ends the run when it has been read") {
    database =>
      for
        _        <- database.saveStoryState(5, None)
        _        <- database.recordSceneSeen("npc.2.s1")
        session  <- endingSession(database)
        _        <- askTheLight(session)
        replaced <- session.handle(EndingChoice(EndingAnswer.Replace, Some("npc.2")))
        midway   <- database.loadStoryProgress()
        over     <- session.handle(SceneDone())
        saved    <- database.loadStoryProgress()
      yield
        assertEquals(replaced.phase, GamePhase.Exploration)
        assertEquals(replaced.pendingEndingChoice, None)
        assertEquals(replaced.pendingScene.map(s => (s.npc, s.kind, s.sceneId)), Some(("npc.2", "scene", Some("npc.2.end"))))
        assertEquals(replaced.pendingCard, None, "the card comes after the NPC's last words")
        assertEquals(midway.ending, Some(Ending.Replaced("npc.2")))
        assertEquals(midway.lastRun, None, "the run has not ended yet")
        assertEquals(over.phase, GamePhase.GameOver)
        assert(over.victory, s"expected a victory: ${over.log}")
        assertEquals(over.pendingCard, Some("end2"))
        assertEquals(saved.lastRun, Some(RunOutcome.Won))
        assertEquals(saved.replacedNpc, Some("npc.2"))
  }

  db.test("an answer that makes no sense leaves the question pending") {
    database =>
      for
        _        <- database.saveStoryState(5, None)
        session  <- endingSession(database)
        _        <- askTheLight(session)
        nobody   <- session.handle(EndingChoice(EndingAnswer.Replace, Some("npc.2")))
        unnamed  <- session.handle(EndingChoice(EndingAnswer.Replace))
        named    <- session.handle(EndingChoice(EndingAnswer.Enter, Some("npc.2")))
        saved    <- database.loadStoryProgress()
      yield
        List(nobody, unnamed, named).foreach:
          refused =>
            assertEquals(refused.phase, GamePhase.Exploration)
            assert(refused.pendingEndingChoice.isDefined, s"the question must stay pending: ${refused.log}")
            assert(refused.log.nonEmpty, "expected a reason")
        assertEquals(saved.ending, None)
  }

  db.test("an answer to the light when none was asked changes nothing") {
    database =>
      for
        session <- endingSession(database)
        hub     <- session.handle(EndingChoice(EndingAnswer.Refuse))
        saved   <- database.loadStoryProgress()
      yield
        assertEquals(hub.phase, GamePhase.Hub)
        assert(hub.log.exists(_.contains("no question")), s"expected a refusal: ${hub.log}")
        assertEquals(saved.ending, None)
  }

  /** npc.2, whose only scene is `npc.2.s1`, has something to say about the ending. */
  private val epilogueNpcs: Map[String, NpcDef] =
    testNpcs + ("npc.2" -> NpcDef("npc.2", "npc_b", revealedBy = Some("npc.2.s1"), epilogue = true))

  private def afterTheEnd(database: Database): IO[GameSession] =
    GameSession.create(smWithRest, database, Map.empty, testUpgradeDefs, Map.empty, testAchievementDefs,
                       rng = Random(0L), sceneDefs = testScenes, chapterDefs = endingChapters, npcDefs = epilogueNpcs
    )

  private val everySceneSeen = List("npc.1.s1", "npc.1.s2", "npc.2.s1")

  db.test("after the end, a Rest seats the NPC with something to say about it, who says it and is remembered") {
    database =>
      for
        _        <- database.saveStoryState(6, Some(RunOutcome.Won))
        _        <- database.saveEnding(Ending.Entered)
        _        <- everySceneSeen.foldLeft(IO.unit)((acc, id) => acc *> database.recordSceneSeen(id))
        session  <- afterTheEnd(database)
        inRest   <- walkToRest(session)
        speaking <- session.handle(Interact("npc.2"))
        _        <- session.handle(SceneDone())
        saved    <- database.loadStoryProgress()
      yield
        assertEquals(npcIn(inRest), Some("npc.2"))
        assertEquals(speaking.pendingScene.map(s => (s.npc, s.kind, s.context)), Some(("npc.2", "epilogue", Some("end1"))))
        assert(saved.scenesSeen.contains("npc.2.epilogue.end1"), s"expected the epilogue to be remembered: ${saved.scenesSeen}")
  }

  db.test("after refusing the light, an NPC who has said their epilogue is gone from the Rests") {
    database =>
      for
        _       <- database.saveStoryState(6, Some(RunOutcome.Won))
        _       <- database.saveEnding(Ending.Refused)
        _       <- (everySceneSeen :+ "npc.2.epilogue.end3").foldLeft(IO.unit)((acc, id) => acc *> database.recordSceneSeen(id))
        session <- afterTheEnd(database)
        inRest  <- walkToRest(session)
      yield assertEquals(npcIn(inRest), Some("npc.1"), "the Rest keeps the NPC its room was authored with")
  }

  db.test("the first purchase earns the prologue card, shown until it has been seen") {
    database =>
      for
        _       <- database.saveCurrency(100)
        session <- GameSession.create(sm, database, Map.empty, testUpgradeDefs, Map.empty, testAchievementDefs)
        before  <- session.currentUpdate
        bought  <- session.handle(HubAction(HubActionType.BuyUpgrade, upgradeId = Some("hp_boost_1")))
        saved   <- database.loadStoryProgress()
      yield
        assertEquals(before.pendingCard, None)
        assertEquals(bought.pendingCard, Some("prologue"))
        assertEquals(saved.cards, List(CardRecord("prologue")))
  }

  db.test("a later purchase does not bring the prologue back once it has been seen") {
    database =>
      for
        _       <- database.saveCurrency(100)
        session <- GameSession.create(sm, database, Map.empty, testUpgradeDefs, Map.empty, testAchievementDefs)
        _       <- session.handle(HubAction(HubActionType.BuyUpgrade, upgradeId = Some("hp_boost_1")))
        _       <- session.handle(CardSeen("prologue"))
        second  <- session.handle(HubAction(HubActionType.BuyUpgrade, upgradeId = Some("potion_start")))
        saved   <- database.loadStoryProgress()
      yield
        assertEquals(second.pendingCard, None)
        assertEquals(saved.cards, List(CardRecord("prologue", seen = true)))
  }

  db.test("winning a run that opens a chapter earns its card, on the victory update") {
    database =>
      for
        session <- storySession(database)
        _       <- startRun(session, Difficulty.Normal)
        _       <- goThroughDoor(session) // Rest
        _       <- goThroughDoor(session) // second Boss room
        _       <- goThroughDoor(session) // Sanctuary room
        won     <- session.handle(Interact("sanct_1"))
        saved   <- database.loadStoryProgress()
      yield
        assert(won.victory, s"expected the Sanctuary to end the run in victory: ${won.log}")
        assertEquals(won.pendingCard, Some("ch1"))
        assertEquals(saved.cards, List(CardRecord("ch1")))
  }

  db.test("seeing the pending card marks it and saves it") {
    database =>
      for
        _       <- database.recordCardEarned("ch1")
        session <- storySession(database)
        shown   <- session.currentUpdate
        seen    <- session.handle(CardSeen("ch1"))
        saved   <- database.loadStoryProgress()
      yield
        assertEquals(shown.pendingCard, Some("ch1"))
        assertEquals(seen.pendingCard, None)
        assertEquals(saved.cards, List(CardRecord("ch1", seen = true)))
  }

  db.test("only the card that is waiting can be seen, and the cards come one at a time") {
    database =>
      for
        _       <- database.recordCardEarned("prologue")
        _       <- database.recordCardEarned("ch1")
        session <- storySession(database)
        early   <- session.handle(CardSeen("ch1"))
        unknown <- session.handle(CardSeen("ch9"))
        first   <- session.handle(CardSeen("prologue"))
        second  <- session.handle(CardSeen("ch1"))
        saved   <- database.loadStoryProgress()
      yield
        assertEquals(early.pendingCard, Some("prologue"))
        assert(early.log.exists(_.contains("no such card")), s"expected a refusal: ${early.log}")
        assertEquals(unknown.pendingCard, Some("prologue"))
        assertEquals(first.pendingCard, Some("ch1"))
        assertEquals(second.pendingCard, None)
        assertEquals(saved.cards.map(_.seen), List(true, true))
  }

  db.test("a card that was not seen is shown again by the next session") {
    database =>
      for
        _     <- database.recordCardEarned("ch1")
        again <- storySession(database).flatMap(_.currentUpdate)
      yield assertEquals(again.pendingCard, Some("ch1"))
  }

  db.test("a vow taken at the Rest is kept by winning the run, and left to be commented on") {
    database =>
      for
        _       <- database.saveStoryState(2, None)
        _       <- database.recordSceneSeen("npc.1.s1")
        _       <- database.recordSceneSeen("npc.2.s1")
        session <- storySession(database)
        _       <- walkToRest(session)
        _       <- session.handle(Interact("npc.1"))
        _       <- session.handle(SceneDone(Some(true)))
        _       <- goThroughDoor(session)
        _       <- goThroughDoor(session)
        _       <- session.handle(Interact("sanct_1"))
        saved   <- database.loadStoryProgress()
      yield assertEquals(saved.vows("npc.1"), VowRecord(VowState.Kept, reported = false))
  }

  db.test("walking back into a Rest already visited does not judge the running vow") {
    database =>
      for
        _       <- database.saveStoryState(2, None)
        _       <- database.recordSceneSeen("npc.1.s1")
        _       <- database.recordSceneSeen("npc.2.s1")
        session <- storySession(database)
        _       <- walkToRest(session)
        _       <- session.handle(Interact("npc.1"))
        _       <- session.handle(SceneDone(Some(true)))
        _       <- session.handle(Interact("door_entrance")) // back to the Boss room
        _       <- goThroughDoor(session)                    // and into the same Rest again
        saved   <- database.loadStoryProgress()
      yield assertEquals(saved.vows("npc.1"), VowRecord(VowState.Active))
  }

  db.test("the next Rest of a Hard run judges the vow, and its NPC is the one waiting to comment") {
    database =>
      for
        _        <- database.saveStoryState(2, None)
        _        <- database.recordSceneSeen("npc.1.s1")
        _        <- database.recordSceneSeen("npc.2.s1")
        session  <- storySession(database)
        _        <- startRun(session, Difficulty.Hard)
        _        <- goThroughDoor(session) // first Rest
        _        <- session.handle(Interact("npc.1"))
        _        <- session.handle(SceneDone(Some(true)))
        _        <- goThroughDoor(session) // second Boss room
        secondRest <- goThroughDoor(session)
        saved    <- database.loadStoryProgress()
        speaking <- session.handle(Interact("npc.1"))
      yield
        assertEquals(saved.vows("npc.1"), VowRecord(VowState.Kept, reported = false))
        assertEquals(npcIn(secondRest), Some("npc.1"))
        assertEquals(speaking.pendingScene, Some(PendingSceneView("npc.1", "vow_report", kept = Some(true))))
  }

  db.test("a vow still running when a session starts is failed, its run being gone") {
    database =>
      for
        _     <- database.saveVow("npc.1", VowRecord(VowState.Active))
        _     <- storySession(database)
        saved <- database.loadStoryProgress()
      yield assertEquals(saved.vows("npc.1"), VowRecord(VowState.Failed, reported = false))
  }

  db.test("ReturnToHub after a run rerolls the offered perks") {
    database =>
      for
        _       <- seedFirstVictory(database)
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

  // ---------------------------------------------
  // The god's voice
  // ---------------------------------------------

  /** A Random whose every draw is `value` (kept under the bound), so a test decides each dice roll. */
  private class FixedRandom(value: Int) extends Random:
    override def nextInt(bound: Int): Int = math.min(value, bound - 1)

  /** A save that has already died a few times, so the next death is not the first of it. */
  private def seedPastDeaths(database: Database): IO[Unit] =
    database.saveAchievementStats(AchievementStats(runsCompleted = 4, runsWon = 1))

  private def voiceSession(database: Database, machine: StateMachine, rng: Random = Random(0L)): IO[GameSession] =
    GameSession.create(machine, database, Map.empty, testUpgradeDefs, Map.empty, testAchievementDefs, rng = rng)

  /** Starts a run and loses its first fight. */
  private def dieInFirstFight(session: GameSession): IO[StateUpdate] =
    session.handle(HubAction(HubActionType.StartRun, classId = Some(ClassId.Warrior))) *>
      session.handle(Interact("e1")) *>
      session.handle(CombatAction(CombatActionType.Attack))

  db.test("the voice greets a run when the dice say so, in the tone of the chapter reached") {
    database =>
      for
        _       <- database.saveStoryState(3, None)
        session <- voiceSession(database, sm, FixedRandom(0))
        started <- session.handle(HubAction(HubActionType.StartRun, classId = Some(ClassId.Warrior)))
      yield assertEquals(started.godLine, Some(GodLineView("start", "b")))
  }

  db.test("the voice stays silent at the start of a run when the dice say so") {
    database =>
      for
        session <- voiceSession(database, sm, FixedRandom(1))
        started <- session.handle(HubAction(HubActionType.StartRun, classId = Some(ClassId.Warrior)))
      yield assertEquals(started.godLine, None)
  }

  db.test("the first death of a save has its own line") {
    database =>
      for
        session <- voiceSession(database, smWithLethalEnemy)
        dead    <- dieInFirstFight(session)
      yield
        assertEquals(dead.phase, GamePhase.GameOver)
        assertEquals(dead.godLine, Some(GodLineView("death.first", "a")))
  }

  db.test("a later death by an ordinary enemy has the plain line, which leaves a streak of one") {
    database =>
      for
        _       <- seedPastDeaths(database)
        session <- voiceSession(database, smWithLethalEnemy)
        dead    <- dieInFirstFight(session)
        saved   <- database.loadStoryProgress()
      yield
        assertEquals(dead.godLine, Some(GodLineView("death.ordinary", "a")))
        assertEquals(saved.deathStreak, 1)
  }

  db.test("the third death in a row has the streak line, and the streak is saved") {
    database =>
      for
        _       <- seedPastDeaths(database)
        _       <- database.saveDeathStreak(2)
        session <- voiceSession(database, smWithLethalEnemy)
        dead    <- dieInFirstFight(session)
        saved   <- database.loadStoryProgress()
      yield
        assertEquals(dead.godLine, Some(GodLineView("death.streak", "a")))
        assertEquals(saved.deathStreak, 3)
  }

  db.test("a death at a Boss has the Boss line") {
    database =>
      for
        _       <- seedPastDeaths(database)
        session <- voiceSession(database, smWithBossRunOf(lethalGoblinStats))
        dead    <- dieInFirstFight(session)
      yield assertEquals(dead.godLine, Some(GodLineView("death.boss", "a")))
  }

  db.test("the first death of a save has no line in the later phases, the next case speaks instead") {
    database =>
      for
        _       <- database.saveStoryState(5, None)
        session <- voiceSession(database, smWithLethalEnemy)
        dead    <- dieInFirstFight(session)
      yield assertEquals(dead.godLine, Some(GodLineView("death.ordinary", "c")))
  }

  /** Combat, Boss and Rest rooms, the Combat one holding a deadly enemy. A Normal run goes Combat, Boss,
    * Rest, Combat, Boss, Sanctuary, so the second Combat room is the first fight of the second section.
    * A Combat room may roll a trapped door when the dungeon is built, which the dice decide: `combatExit`
    * lets a test settle it.
    */
  private def smWithDeadlySecondSection(combatExit: DoorKind = DoorKind.Normal): StateMachine =
    def room(id: String, roomType: RoomType, extra: Entity*): Room =
      val exit = storyDoor("door_exit", ConnectorRole.Next).copy(doorKind = if roomType == RoomType.Combat then combatExit else DoorKind.Normal)
      Room(id, roomType, "dungeon", 8, 6, makeTiles(),
           List(storyDoor("door_entrance", ConnectorRole.Prev), exit) ::: extra.toList
      )
    val sanctuary = Room("sanct", RoomType.Sanctuary, "dungeon", 8, 6, makeTiles(),
                         List(storyDoor("door_entrance", ConnectorRole.Prev), Sanctuary("sanct_1", x = 2, y = 1))
    )
    StateMachine(
      Map("c1" -> room("c1", RoomType.Combat, Enemy("e1", x = 2, y = 1, typeId = "goblin", label = "Goblin")),
          "b1" -> room("b1", RoomType.Boss),
          "r1" -> room("r1", RoomType.Rest),
          "sanct" -> sanctuary
      ),
      Map("goblin" -> lethalGoblinStats), Map.empty, testClassDefs, testUpgradeDefs, CombatResolver(Random(0L))
    )

  /** Goes through the exit door of the room the player is in. A trapped door only springs the first time,
    * and opens the second, so it is used again when the player is still in the same room.
    */
  private def leaveRoom(session: GameSession): IO[StateUpdate] =
    for
      before <- session.currentUpdate
      first  <- goThroughDoor(session)
      next   <- if first.room.map(_.roomId) == before.room.map(_.roomId) then goThroughDoor(session) else IO.pure(first)
    yield next

  // The dungeon rolls trapped doors by chance, so the test is run with the door as it comes and with
  // one that is sure to be trapped.
  List(DoorKind.Normal, DoorKind.Trapped).foreach:
    combatExit =>
      db.test(s"a death in the second section has the line for having gone deeper (first exit $combatExit)") {
        database =>
          for
            _       <- seedPastDeaths(database)
            session <- voiceSession(database, smWithDeadlySecondSection(combatExit))
            _       <- session.handle(HubAction(HubActionType.StartRun, classId = Some(ClassId.Warrior)))
            _       <- leaveRoom(session) // first Combat room to the first Boss room
            _       <- goThroughDoor(session) // Boss room to the Rest
            inFight <- goThroughDoor(session) // Rest to the first Combat room of the second section
            _       <- session.handle(Interact("e1"))
            dead    <- session.handle(CombatAction(CombatActionType.Attack))
          yield
            assertEquals(inFight.room.map(_.roomId), Some("c1#2"))
            assertEquals(dead.godLine, Some(GodLineView("death.depth", "a")))
      }

  // ---------------------------------------------
  // The Devoted
  // ---------------------------------------------

  /** The Boss run of [[smWithBossRun]], its Boss being the second Devoted. */
  private def devotedSession(database: Database, boss: EnemyStats = weakGoblinStats): IO[GameSession] =
    voiceSession(database, smWithBossRunOf(boss.copy(devoted = Some(2))))

  private def startEasyRun(session: GameSession): IO[StateUpdate] =
    session.handle(HubAction(HubActionType.StartRun, classId = Some(ClassId.Warrior), difficulty = Some(Difficulty.Easy)))

  db.test("a Devoted speaks before the fight, and the fight starts once the line is read") {
    database =>
      for
        session  <- devotedSession(database)
        _        <- startEasyRun(session)
        speaking <- session.handle(Interact("e1"))
        moved    <- session.handle(Move(Direction.Left))
        fighting <- session.handle(SceneDone(None))
      yield
        assertEquals(speaking.phase, GamePhase.Exploration)
        assertEquals(speaking.pendingScene, Some(PendingSceneView("boss.2", "devoted", sceneId = Some("boss.2.pre1"))))
        assertEquals(moved.room.map(_.playerX), speaking.room.map(_.playerX))
        assertEquals(fighting.phase, GamePhase.Combat)
        assertEquals(fighting.pendingScene, None)
  }

  db.test("from chapter 4 the Devoted says its second line before the fight") {
    database =>
      for
        _        <- database.saveStoryState(4, None)
        session  <- devotedSession(database)
        _        <- startEasyRun(session)
        speaking <- session.handle(Interact("e1"))
      yield assertEquals(speaking.pendingScene.flatMap(_.sceneId), Some("boss.2.pre2"))
  }

  db.test("an answer to a vow is refused on the line of a Devoted, which stays pending") {
    database =>
      for
        session <- devotedSession(database)
        _       <- startEasyRun(session)
        _       <- session.handle(Interact("e1"))
        refused <- session.handle(SceneDone(Some(true)))
      yield
        assert(refused.pendingScene.isDefined, "the line must stay pending")
        assertEquals(refused.phase, GamePhase.Exploration)
  }

  db.test("a Devoted who falls says a last line before the player can move on") {
    database =>
      for
        session <- devotedSession(database)
        _       <- startEasyRun(session)
        _       <- session.handle(Interact("e1"))
        _       <- session.handle(SceneDone(None))
        won     <- session.handle(CombatAction(CombatActionType.Attack))
        blocked <- session.handle(Interact("door_to_sanctuary"))
        done    <- session.handle(SceneDone(None))
        door    <- session.handle(Interact("door_to_sanctuary"))
      yield
        assertEquals(won.phase, GamePhase.Exploration)
        assertEquals(won.pendingScene, Some(PendingSceneView("boss.2", "devoted", sceneId = Some("boss.2.post"))))
        assertEquals(blocked.room.map(_.roomId), won.room.map(_.roomId))
        assertEquals(done.pendingScene, None)
        assertEquals(door.pendingScene, None)
        assertNotEquals(door.room.map(_.roomId), won.room.map(_.roomId))
  }

  /** Wins the fight with the Devoted, reads its last line and opens its Shrine. */
  private def defeatDevotedAndOpenShrine(session: GameSession): IO[StateUpdate] =
    startEasyRun(session) *>
      session.handle(Interact("e1")) *>
      session.handle(SceneDone(None)) *>
      session.handle(CombatAction(CombatActionType.Attack)) *>
      session.handle(SceneDone(None)) *>
      session.handle(Interact("shrine_e1"))

  db.test("the relic of a Devoted can be read from chapter 2, and counts as read once its Devoted has fallen") {
    database =>
      for
        _       <- database.saveStoryState(2, None)
        session <- devotedSession(database)
        shrine  <- defeatDevotedAndOpenShrine(session)
        saved   <- database.loadStoryProgress()
      yield
        assertEquals(shrine.pendingRewardChoice.flatMap(_.relic), Some(RelicView(2, readable = true)))
        assertEquals(saved.relicsRead, Set(2))
  }

  db.test("before chapter 2 the inscription is worn smooth, and the relic does not count as read") {
    database =>
      for
        _       <- database.saveStoryState(1, None)
        session <- devotedSession(database)
        shrine  <- defeatDevotedAndOpenShrine(session)
        saved   <- database.loadStoryProgress()
      yield
        assertEquals(shrine.pendingRewardChoice.flatMap(_.relic), Some(RelicView(2, readable = false)))
        assertEquals(saved.relicsRead, Set.empty[Int])
  }

  db.test("the Shrine of a Boss that is no Devoted shows no relic and reads none") {
    database =>
      for
        _       <- database.saveStoryState(3, None)
        session <- voiceSession(database, smWithBossRun)
        _       <- startEasyRun(session)
        _       <- session.handle(Interact("e1"))
        _       <- session.handle(CombatAction(CombatActionType.Attack))
        shrine  <- session.handle(Interact("shrine_e1"))
        saved   <- database.loadStoryProgress()
      yield
        assertEquals(shrine.pendingRewardChoice.map(_.relic), Some(None))
        assertEquals(saved.relicsRead, Set.empty[Int])
  }

  db.test("an enemy that is no Devoted starts its fight at once, with no line") {
    database =>
      for
        session  <- voiceSession(database, smWithBossRunOf(weakGoblinStats))
        _        <- startEasyRun(session)
        fighting <- session.handle(Interact("e1"))
      yield
        assertEquals(fighting.phase, GamePhase.Combat)
        assertEquals(fighting.pendingScene, None)
  }

  db.test("a win has no death line, and the next death starts a new streak") {
    database =>
      for
        _       <- seedPastDeaths(database)
        _       <- database.saveDeathStreak(2)
        session <- voiceSession(database, smWithBossRunOf(weakGoblinStats))
        _       <- session.handle(HubAction(HubActionType.StartRun, classId = Some(ClassId.Warrior), difficulty = Some(Difficulty.Easy)))
        _       <- session.handle(Interact("e1"))
        _       <- session.handle(CombatAction(CombatActionType.Attack))
        _       <- session.handle(Interact("door_to_sanctuary"))
        won     <- session.handle(Interact("sanct_1"))
        saved   <- database.loadStoryProgress()
      yield
        assert(won.victory, s"expected a win: ${won.log}")
        assertEquals(won.godLine, None)
        assertEquals(saved.deathStreak, 0)
  }

  db.test("BuyUpgrade does not reroll the offered perks") {
    database =>
      for
        _       <- seedFirstVictory(database)
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
        _       <- seedFirstVictory(database)
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

  db.test("once the altar is shut an upgrade cannot be bought, and the hub says so") {
    database =>
      for
        _       <- database.saveCurrency(100)
        _       <- database.saveEnding(Ending.Refused)
        session <- GameSession.create(sm, database, Map.empty, testUpgradeDefs, Map.empty, testAchievementDefs)
        before  <- session.currentUpdate
        refused <- session.handle(HubAction(HubActionType.BuyUpgrade, upgradeId = Some("hp_boost_1")))
        meta    <- database.loadMeta()
      yield
        assertEquals(before.hub.map(_.shopClosed), Some(true))
        assert(refused.log.exists(_.contains("shut")), s"expected a refusal: ${refused.log}")
        assertEquals(meta.currency, 100)
        assert(!meta.isUnlocked("hp_boost_1"), "the upgrade must not be bought")
  }

  db.test("the hub does not say the altar is shut on a save that has not refused the light") {
    database =>
      for
        _       <- database.saveEnding(Ending.Entered)
        session <- GameSession.create(sm, database, Map.empty, testUpgradeDefs, Map.empty, testAchievementDefs)
        update  <- session.currentUpdate
      yield assertEquals(update.hub.map(_.shopClosed), Some(false))
  }

  db.test("a kill earns Shards until the altar is shut, and none after") {
    database =>
      def shardsAfterAFight(session: GameSession): IO[Int] =
        startRunOn(session, Some(Difficulty.Easy)) *> session.handle(Interact("e1")) *>
          session.handle(CombatAction(CombatActionType.Attack)).map(_.player.metaCurrency)
      for
        open <- tierSession(database).flatMap(shardsAfterAFight)
        _    <- database.saveEnding(Ending.Refused)
        shut <- tierSession(database).flatMap(shardsAfterAFight)
      yield
        assert(open > 0, "the same fight earns Shards while the altar is open")
        assertEquals(shut, 0)
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

  db.test("the hub upgrades bought do nothing on a Hardcore run, which keeps the base class") {
    database =>
      for
        _        <- database.saveCurrency(30)
        _        <- database.saveHardcoreUnlocked()
        session  <- GameSession.create(sm, database, Map.empty, testUpgradeDefs, Map.empty, testAchievementDefs, difficultyDefs = testTierDefs)
        _        <- session.handle(HubAction(HubActionType.BuyUpgrade, upgradeId = Some("hp_boost_1")))
        hardcore <- session.handle(HubAction(HubActionType.StartRun, classId = Some(ClassId.Warrior), difficulty = Some(Difficulty.Hardcore)))
      yield assertEquals(hardcore.player.maxHp, 120) // base Warrior 120 (test fixture), without the +20
  }

  db.test("the same upgrade does boost a run on Normal, so it is Hardcore that ignores it") {
    database =>
      for
        _       <- database.saveCurrency(30)
        _       <- database.saveAchievementStats(AchievementStats(runsWon = 1, winsByDifficulty = Map(Difficulty.Easy -> 1)))
        session <- GameSession.create(sm, database, Map.empty, testUpgradeDefs, Map.empty, testAchievementDefs, difficultyDefs = testTierDefs)
        _       <- session.handle(HubAction(HubActionType.BuyUpgrade, upgradeId = Some("hp_boost_1")))
        normal  <- session.handle(HubAction(HubActionType.StartRun, classId = Some(ClassId.Warrior), difficulty = Some(Difficulty.Normal)))
      yield assertEquals(normal.player.maxHp, 140)
  }

  db.test("extra_slot grows the potion belt to 3 slots on the run started right after purchase") {
    database =>
      for
        _       <- database.saveCurrency(60)
        session <- GameSession.create(sm, database, Map.empty, testUpgradeDefs, Map.empty, testAchievementDefs)
        _ <- session.handle(HubAction(HubActionType.BuyUpgrade, upgradeId = Some("extra_slot")))
        update <- session.handle(HubAction(HubActionType.StartRun, classId = Some(ClassId.Warrior)))
      yield assertEquals(update.equipment.potionBelt.length, 3)
  }

  db.test("potion_start gives the player a Health Potion in the belt on the run started right after purchase") {
    database =>
      val potionItemDefs: Map[String, Item] = Map(
        "health_potion" -> Consumable("", "health_potion", "Health Potion", Rarity.Common, ConsumableEffect.HealFixed(30))
      )
      for
        _       <- database.saveCurrency(40)
        session <- GameSession.create(sm, database, potionItemDefs, testUpgradeDefs, Map.empty, testAchievementDefs)
        _ <- session.handle(HubAction(HubActionType.BuyUpgrade, upgradeId = Some("potion_start")))
        update <- session.handle(HubAction(HubActionType.StartRun, classId = Some(ClassId.Warrior)))
      yield assert(update.equipment.potionBelt.flatten.exists(_.typeId == "health_potion"),
                   s"expected a health potion in the belt: ${update.equipment.potionBelt}"
      )
  }

  // Note: like extra_potion_capacity above, warrior_kit's applyUpgradeEffect case is a no-op at
  // this level by design (the actual gating lives in StateMachine's StartRun case, which runs
  // before applyMetaBonuses ever sees the player - see StateMachineSuite's dedicated gating tests
  // for the real behavior). This just exercises the wiring so a future non-exhaustive match can't
  // silently reintroduce a MatchError the way it did for perks (see PR#13).
  db.test("StartRun after purchasing a kit-unlock upgrade still starts the run successfully") {
    database =>
      for
        _      <- database.saveCurrency(25)
        session <- GameSession.create(sm, database, Map.empty, testUpgradeDefs, Map.empty, testAchievementDefs)
        _      <- session.handle(HubAction(HubActionType.BuyUpgrade, upgradeId = Some("warrior_kit")))
        update <- session.handle(HubAction(HubActionType.StartRun, classId = Some(ClassId.Warrior)))
      yield assertEquals(update.phase, GamePhase.Exploration)
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

  db.test("rarity_insight guarantees at least Uncommon rarity on every chest, not just the first") {
    database =>
      val chestItemDefs: Map[String, Item] = Map(
        "health_potion" -> Consumable("", "health_potion", "Health Potion", Rarity.Common,
                                      ConsumableEffect.HealFixed(30)
        )
      )
      val tiles = makeTiles()
      // A Boss room never rolls a trapped chest (see DungeonBuilder.rollTrappedChests), so c1 stays
      // openable whatever the seed. As the pool's only Boss room it is also the dungeon's first room,
      // reused for every section.
      val chestRoom = Room("r1", RoomType.Boss, "dungeon", 8, 6, tiles, List(Chest("c1", x = 2, y = 1)))
      val sanctuary = Room("s1", RoomType.Sanctuary, "dungeon", 8, 6, tiles, Nil)
      val smWithChest = StateMachine(
        Map("r1" -> chestRoom, "s1" -> sanctuary),
        Map.empty,
        chestItemDefs,
        testClassDefs,
        testUpgradeDefs,
        CombatResolver(Random(0L)),
        rng = Random(0L)
      )
      for
        _       <- database.saveCurrency(110)
        session <- GameSession.create(smWithChest, database, chestItemDefs, testUpgradeDefs, Map.empty,
                                      testAchievementDefs, rng = Random(0L)
                   )
        _      <- session.handle(HubAction(HubActionType.BuyUpgrade, upgradeId = Some("rarity_insight")))
        _      <- session.handle(HubAction(HubActionType.StartRun, classId = Some(ClassId.Warrior)))
        update <- session.handle(Interact("c1"))
      yield
        val picked = update.equipment.potionBelt.flatten
          .find(_.typeId == "health_potion")
          .getOrElse(fail("expected a health potion in the belt"))
        // rollChest's rarityFloorOverride excludes every tier below the floor entirely (not just
        // reweights toward it), so "never Common" holds regardless of which seed rolls.
        assertNotEquals(picked.rarity, "common", s"expected at least Uncommon, got ${picked.rarity}")
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

  db.test("using a potion in combat produces a heal-kind damageEvent") {
    database =>
      val itemDefs: Map[String, Item] = Map(
        "health_potion" -> Consumable("", "health_potion", "Health Potion", Rarity.Common,
                                      ConsumableEffect.HealFixed(30)
        )
      )
      // Tanky enough to survive the first Attack, and hits back hard enough to leave the player
      // below max HP - unlike weakGoblinStats (1 HP, dies in one hit, no room for a follow-up
      // Item action) or smWithTankyEnemy (attack = 0, never actually damages the player).
      val smWithEnemyAndItems = StateMachine(achievementRoomPool,
                                             Map("goblin" -> weakGoblinStats.copy(maxHp = 999, attack = 20)),
                                             itemDefs,
                                             testClassDefs,
                                             testUpgradeDefs,
                                             CombatResolver(Random(0L))
      )
      for
        _       <- database.saveCurrency(40)
        session <- GameSession.create(smWithEnemyAndItems, database, itemDefs, testUpgradeDefs, Map.empty,
                                      testAchievementDefs, rng = Random(0L)
                   )
        _ <- session.handle(HubAction(HubActionType.BuyUpgrade, upgradeId = Some("potion_start")))
        afterStart <- session.handle(HubAction(HubActionType.StartRun, classId = Some(ClassId.Warrior)))
        potionId = afterStart.equipment.potionBelt.flatten.head.id
        _         <- session.handle(Interact("e1"))
        _         <- session.handle(CombatAction(CombatActionType.Attack)) // takes a counter-hit, now below max HP
        update <- session.handle(CombatAction(CombatActionType.Item, itemId = Some(potionId)))
      yield assert(update.damageEvents.exists(e => e.kind == "heal" && e.targetIsPlayer),
                   s"expected a heal-kind damageEvent: ${update.damageEvents}"
      )
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
        _       <- seedFirstVictory(database)
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
      // Perk persistence is checked via the Sanctuary: a boss kill does not end the run by itself.
      for
        _       <- seedFirstVictory(database)
        session <- GameSession.create(smWithBossRun, database, Map.empty, testUpgradeDefs, Map.empty,
                                      testAchievementDefs, perkDefs = Map(heavyHand.id -> heavyHand),
                                      rng = Random(0L)
                   )
        _         <- session.handle(HubAction(HubActionType.StartRun, classId = Some(ClassId.Warrior),
                                              difficulty = Some(Difficulty.Easy),
                                              perkId = Some("heavy_hand")
                      ))
        _         <- session.handle(Interact("e1"))
        afterKill <- session.handle(CombatAction(CombatActionType.Attack))
        _          = assertEquals(afterKill.phase, GamePhase.Exploration,
                                  "a boss kill should return to exploration now, not end the run by itself"
                     )
        _                   <- session.handle(Interact("door_to_sanctuary"))
        afterSanctuaryEnter <- session.handle(Interact("sanct_1"))
        statsInDb           <- database.loadAchievementStats()
      yield
        assertEquals(afterSanctuaryEnter.phase, GamePhase.GameOver)
        assert(afterSanctuaryEnter.victory, "expected interacting with the Sanctuary to end the run in victory")
        assert(statsInDb.perksWonWith.contains("heavy_hand"),
               s"expected heavy_hand in perksWonWith: ${statsInDb.perksWonWith}"
        )
  }

  db.test("a won run is saved as a win on its own difficulty") {
    database =>
      for
        session <- GameSession.create(smWithBossRun, database, Map.empty, testUpgradeDefs, Map.empty,
                                      testAchievementDefs, rng = Random(0L)
                   )
        _ <- session.handle(HubAction(HubActionType.StartRun, classId = Some(ClassId.Warrior),
                                      difficulty = Some(Difficulty.Easy)
             ))
        _         <- session.handle(Interact("e1"))
        _         <- session.handle(CombatAction(CombatActionType.Attack))
        _         <- session.handle(Interact("door_to_sanctuary"))
        afterWin  <- session.handle(Interact("sanct_1"))
        statsInDb <- database.loadAchievementStats()
      yield
        assert(afterWin.victory, "expected the Sanctuary to end the run in victory")
        assertEquals(statsInDb.winsByDifficulty, Map(Difficulty.Easy -> 1))
  }

  db.test("a lost run is saved as a win on no difficulty") {
    database =>
      for
        session <- GameSession.create(smWithLethalEnemy, database, Map.empty, testUpgradeDefs, Map.empty,
                                      testAchievementDefs, rng = Random(0L)
                   )
        _         <- session.handle(HubAction(HubActionType.StartRun, classId = Some(ClassId.Warrior)))
        _         <- session.handle(Interact("e1"))
        afterHit  <- session.handle(CombatAction(CombatActionType.Attack)) // enemy survives, counters, kills player
        statsInDb <- database.loadAchievementStats()
      yield
        assertEquals(afterHit.phase, GamePhase.GameOver, s"expected defeat to end the run: ${afterHit.log}")
        assertEquals(statsInDb.runsCompleted, 1)
        assertEquals(statsInDb.winsByDifficulty, Map.empty[Difficulty, Int])
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
        _    <- session.handle(HubAction(HubActionType.BuyUpgrade, upgradeId = Some("weapon_mastery")))
        _    <- session.handle(HubAction(HubActionType.BuyUpgrade, upgradeId = Some("rarity_insight")))
        _    <- session.handle(HubAction(HubActionType.BuyUpgrade, upgradeId = Some("warrior_kit")))
        _    <- session.handle(HubAction(HubActionType.BuyUpgrade, upgradeId = Some("archer_kit")))
        last <- session.handle(HubAction(HubActionType.BuyUpgrade, upgradeId = Some("mage_kit")))
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
