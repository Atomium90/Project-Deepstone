package roguelite.engine

import munit.FunSuite
import roguelite.game.*

import scala.util.Random

class StateMachineSuite extends FunSuite:

  // --- Fixtures ------------------------------------------------------------

  def makeTiles(w: Int = 8, h: Int = 6): Vector[Vector[Tile]] =
    Vector.tabulate(h, w):
      (row, col) =>
        if row == 0 || row == h - 1 || col == 0 || col == w - 1 then Tile.Wall else Tile.Floor

  def makeRoom(id: String,
               w: Int = 8,
               h: Int = 6,
               roomType: RoomType = RoomType.Combat,
               entities: List[Entity] = Nil
  ): Room =
    Room(id, roomType, w, h, makeTiles(w, h), entities)

  def door(from: String, to: String): Door =
    Door(s"door_${from}_to_$to", x = 4, y = 5, direction = Direction.Down, targetRoomId = to)

  val goblinStats: EnemyStats = EnemyStats(
    typeId = "goblin",
    label = "Goblin",
    maxHp = 20,
    attack = 5,
    defense = 0,
    xpReward = 15,
    actions = List(EnemyActionWeight("ATTACK", 100))
  )

  val enemyStatsMap: Map[String, EnemyStats] = Map("goblin" -> goblinStats)

  /** Minimal class definitions mirroring classes.json — avoids file I/O in unit tests. */
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
    "archer_unlock" -> UpgradeDef("archer_unlock",
                                  "Ranger's Path",
                                  "Unlock the Archer class",
                                  cost = 50,
                                  displayOrder = 0,
                                  effect = UpgradeEffect.UnlockClass(ClassId.Archer)
    ),
    "mage_unlock" -> UpgradeDef("mage_unlock",
                                "Arcane Studies",
                                "Unlock the Mage class",
                                cost = 80,
                                displayOrder = 1,
                                effect = UpgradeEffect.UnlockClass(ClassId.Mage)
    )
  )

  def simpleDungeon(entities: List[Entity] = Nil): Dungeon =
    val r1 = makeRoom("r1", entities = entities)
    val r2 = makeRoom("r2")
    Dungeon(Map("r1" -> r1, "r2" -> r2), "r1")

  /** Minimal pool for DungeonBuilder: one entrance (Combat) and one boss room. Used only by
    * StartRun — every other test builds its own state directly via `explorationAt`/`simpleDungeon`
    * and never touches this pool.
    */
  def defaultRoomPool: Map[String, Room] =
    Map("entrance" -> makeRoom("entrance", roomType = RoomType.Combat),
        "boss"     -> makeRoom("boss", roomType = RoomType.Boss)
    )

  def sm(roomPool: Map[String, Room] = defaultRoomPool,
         upgradeDefs: Map[String, UpgradeDef] = Map.empty
  ): StateMachine =
    StateMachine(roomPool,
                 enemyStatsMap,
                 Map.empty[String, Item],
                 testClassDefs,
                 upgradeDefs,
                 CombatResolver(Random(0L))
    )

  def hubPlayer: Player =
    Player(ClassId.Warrior,
           hp = 100,
           maxHp = 100,
           resourceCurrent = 0,
           resourceMax = 100,
           level = 1,
           xp = 0,
           metaCurrency = 0
    )

  def explorationAt(x: Int, y: Int, entities: List[Entity] = Nil): ExplorationState =
    ExplorationState(PlayerFixtures.startingPlayer(ClassId.Warrior), simpleDungeon(entities), x, y)

  // --- Hub -----------------------------------------------------------------

  test("StartRun transitions to Exploration"):
    val TransitionResult(next, _, _, _) =
      sm().applyActionPure(HubState(hubPlayer),
                           HubAction(HubActionType.StartRun, classId = Some(ClassId.Archer))
      )
    assert(next.isInstanceOf[ExplorationState])

  test("StartRun creates player with chosen class"):
    val TransitionResult(next, _, _, _) =
      sm().applyActionPure(HubState(hubPlayer),
                           HubAction(HubActionType.StartRun, classId = Some(ClassId.Mage))
      )
    assertEquals(next.player.classId, ClassId.Mage)

  test("StartRun player has correct affinityTags from classDef"):
    val TransitionResult(next, _, _, _) =
      sm().applyActionPure(HubState(hubPlayer),
                           HubAction(HubActionType.StartRun, classId = Some(ClassId.Archer))
      )
    assertEquals(next.player.affinityTags, Set("ranged"))

  test("StartRun without classId stays in Hub"):
    val TransitionResult(next, _, _, _) =
      sm().applyActionPure(HubState(hubPlayer), HubAction(HubActionType.StartRun, classId = None))
    assert(next.isInstanceOf[HubState])

  // --- Hub — class unlock gating --------------------------------------------

  test("StartRun with a locked class is rejected and stays in Hub"):
    val hub = HubState(hubPlayer, testUpgradeDefs, MetaProgression.empty)
    val TransitionResult(next, log, _, _) = sm(upgradeDefs = testUpgradeDefs)
      .applyActionPure(hub, HubAction(HubActionType.StartRun, classId = Some(ClassId.Archer)))
    assert(next.isInstanceOf[HubState])
    assert(log.exists(_.contains("Ranger's Path")), s"expected lock message: $log")

  test("StartRun with an unlocked class succeeds"):
    val meta = MetaProgression(currency = 0, unlockedUpgrades = Set("archer_unlock"))
    val hub  = HubState(hubPlayer, testUpgradeDefs, meta)
    val TransitionResult(next, _, _, _) = sm(upgradeDefs = testUpgradeDefs)
      .applyActionPure(hub, HubAction(HubActionType.StartRun, classId = Some(ClassId.Archer)))
    assert(next.isInstanceOf[ExplorationState])

  test("StartRun with Warrior is never gated (no matching UnlockClass upgrade)"):
    val hub = HubState(hubPlayer, testUpgradeDefs, MetaProgression.empty)
    val TransitionResult(next, _, _, _) = sm(upgradeDefs = testUpgradeDefs)
      .applyActionPure(hub, HubAction(HubActionType.StartRun, classId = Some(ClassId.Warrior)))
    assert(next.isInstanceOf[ExplorationState])

  // --- Hub — difficulty -----------------------------------------------------

  /** Pool with enough middle rooms for Easy/Normal/Hard's totalRooms to actually differ. */
  def richRoomPool: Map[String, Room] =
    val middles = (1 to 6).map(i => s"middle$i" -> makeRoom(s"middle$i")).toMap
    middles ++ defaultRoomPool

  test("StartRun without difficulty defaults to Normal"):
    val TransitionResult(next, _, _, _) =
      sm().applyActionPure(HubState(hubPlayer),
                           HubAction(HubActionType.StartRun, classId = Some(ClassId.Warrior))
      )
    assertEquals(next.asInstanceOf[ExplorationState].difficulty, Difficulty.Normal)

  test("StartRun room count scales with difficulty"):
    val TransitionResult(easyNext, _, _, _) = sm(richRoomPool).applyActionPure(
      HubState(hubPlayer),
      HubAction(HubActionType.StartRun,
                classId = Some(ClassId.Warrior),
                difficulty = Some(Difficulty.Easy)
      )
    )
    val TransitionResult(hardNext, _, _, _) = sm(richRoomPool).applyActionPure(
      HubState(hubPlayer),
      HubAction(HubActionType.StartRun,
                classId = Some(ClassId.Warrior),
                difficulty = Some(Difficulty.Hard)
      )
    )
    val easyRooms = easyNext.asInstanceOf[ExplorationState].dungeon.rooms.size
    val hardRooms = hardNext.asInstanceOf[ExplorationState].dungeon.rooms.size
    assert(hardRooms > easyRooms, s"expected Hard ($hardRooms) > Easy ($easyRooms)")

  test("Invalid action in Hub produces log"):
    val TransitionResult(next, log, _, _) = sm().applyActionPure(HubState(hubPlayer), Move(Direction.Up))
    assert(next.isInstanceOf[HubState])
    assert(log.nonEmpty)

  // --- Exploration — movement -----------------------------------------------

  test("Move Up decreases playerY"):
    val TransitionResult(next, _, _, _) = sm().applyActionPure(explorationAt(3, 3), Move(Direction.Up))
    assertEquals(next.asInstanceOf[ExplorationState].playerY, 2)

  test("Move into wall is blocked"):
    val TransitionResult(next, _, _, _) = sm().applyActionPure(explorationAt(1, 1), Move(Direction.Up))
    assertEquals(next.asInstanceOf[ExplorationState].playerY, 1)

  /** Thorough revealSecretDoors behavior lives in InteractionResolverSuite. This test just confirms
    * the Move arm actually forwards InteractionResolver's events instead of dropping them. */
  test("Move that reveals a secret door forwards the SecretDoorRevealed event"):
    val secretDoor = Door("door_secret",
                          x = 4,
                          y = 2,
                          direction = Direction.Down,
                          targetRoomId = "r2",
                          doorKind = DoorKind.Secret,
                          revealed = false
    )
    val state = explorationAt(3, 3, entities = List(secretDoor))
    val TransitionResult(_, _, _, events) = sm().applyActionPure(state, Move(Direction.Up))
    assertEquals(events, List(GameEvent.SecretDoorRevealed))

  // --- Exploration — interaction --------------------------------------------

  /** Thorough Door/Enemy/Chest/LockedDoor/trap/secret-door/Npc interaction behavior now lives in
    * InteractionResolverSuite, testing InteractionResolver directly (mirrors how CombatResolverSuite
    * tests CombatResolver directly rather than through StateMachine). This one test stays here just
    * to confirm the Interact action is actually wired through to InteractionResolver. */
  test("Interact action routes through to InteractionResolver"):
    val d         = door("r1", "r2")
    val state     = explorationAt(3, 3, entities = List(d))
    val TransitionResult(next, _, _, _) = sm().applyActionPure(state, Interact(d.id))
    assertEquals(next.asInstanceOf[ExplorationState].dungeon.currentRoomId, "r2")

  // --- Combat routing -------------------------------------------------------

  test("CombatAction in CombatState is routed to resolver"):
    val enemy     = Enemy("e1", 3, 3, typeId = "goblin", label = "Goblin")
    val instance  = EnemyInstance.fromStats("e1", goblinStats)
    val state     = CombatState(hubPlayer, simpleDungeon(List(enemy)), 1, 1, Combat(instance), "e1")
    val TransitionResult(next, _, _, _) = sm().applyActionPure(state, CombatAction(CombatActionType.Attack))
    assert(
      next.isInstanceOf[CombatState] || next.isInstanceOf[ExplorationState] ||
        next.isInstanceOf[GameOverState]
    )

  // --- StateUpdate projection -----------------------------------------------

  test("CombatState.toStateUpdate includes CombatView"):
    val instance = EnemyInstance.fromStats("e1", goblinStats)
    val update =
      CombatState(hubPlayer, simpleDungeon(), 1, 1, Combat(instance), "e1").toStateUpdate()
    assert(update.combat.isDefined)
    assertEquals(update.combat.get.enemyLabel, "Goblin")

  test("GameOverState.toStateUpdate has GameOver phase"):
    assertEquals(GameOverState(hubPlayer).toStateUpdate().phase, GamePhase.GameOver)

  test("GameOverState.toStateUpdate defaults victory to false"):
    assertEquals(GameOverState(hubPlayer).toStateUpdate().victory, false)

  test("GameOverState.toStateUpdate reflects victory = true"):
    assertEquals(GameOverState(hubPlayer, victory = true).toStateUpdate().victory, true)

  test("StateUpdate always contains inventory list (empty at run start with empty startingKit)"):
    val TransitionResult(next, _, _, _) =
      sm().applyActionPure(HubState(hubPlayer),
                           HubAction(HubActionType.StartRun, classId = Some(ClassId.Warrior))
      )
    assertEquals(next.toStateUpdate().inventory, Nil)

  test("StateUpdate inventory reflects items in player inventory"):
    val potion = Consumable("p1",
                            "health_potion",
                            "Health Potion",
                            Rarity.Common,
                            ConsumableEffect.HealFixed(30)
    )
    val inv    = Inventory.empty.addItem(potion).getOrElse(fail("expected Right"))
    val player = PlayerFixtures.startingPlayer(ClassId.Warrior).copy(inventory = inv)
    val update = ExplorationState(player, simpleDungeon(), 1, 1).toStateUpdate()
    assertEquals(update.inventory.length, 1)
    assertEquals(update.inventory.head.typeId, "health_potion")
