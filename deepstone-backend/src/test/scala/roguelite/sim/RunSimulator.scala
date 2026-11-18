package roguelite.sim

import roguelite.engine.{
  ClassId,
  CombatAction,
  CombatActionType,
  CombatState,
  Difficulty,
  ExplorationState,
  GameOverState,
  GameState,
  HubAction,
  HubActionType,
  HubState,
  Interact,
  Player,
  PlayerAction,
  RewardChoice,
  TransitionResult
}
import roguelite.game.core.{ GameEvent, LogLine, LogValue }
import roguelite.game.dungeon.{ Chest, ChestState, Dungeon, MinimapBuilder, RoomType }
import roguelite.game.items.{ Accessory, Armor, Item, Weapon }

import scala.collection.mutable

/** Plays whole runs on the pure state machine, as fast as the CPU goes, and writes down what happened.
  *
  * Nothing here touches the game's code: it only feeds actions to `StateMachine.applyActionPure`, the
  * same entry point the server uses, and reads the state and the events that come back.
  */
final class RunSimulator(world: SimWorld, policy: BotPolicy, maxActions: Int = 20000):

  /** Same state this many times in a row means the bot is going round in circles. */
  private val StuckAfter = 8

  /** A run of `setup` on a save that owns what the setup's profile says. */
  def play(run: Int, seed: Long, setup: SimSetup): PlayedRun =
    play(run, seed, setup, world.hub(setup), setup.profile.label)

  /** A run starting from the given hub (the upgrades it owns, the Shards in the pocket), which a
    * campaign builds from what its save has bought so far. `profileLabel` is what the run row says
    * about the save.
    */
  def play(run: Int, seed: Long, setup: SimSetup, hub: HubState, profileLabel: String): PlayedRun =
    val sm      = world.stateMachine(seed)
    val bot     = Bot(world, policy)
    val tracker = RunTracker(world, run)

    val start = sm.applyActionPure(
      hub,
      HubAction(HubActionType.StartRun,
                classId = Some(setup.classId),
                difficulty = Some(setup.difficulty),
                perkId = setup.perkId,
                riskyPerkId = setup.riskyPerkId
      )
    )
    // The server adds the blessings of the owned upgrades as the run starts: so does the simulation.
    var state: GameState = start.state match
      case exp: ExplorationState => world.runStart(exp, hub.meta)
      case other                 => other
    var actions          = 0
    var unchanged        = 0
    var outcome          = "cap"

    var running = state.isInstanceOf[ExplorationState]
    if !running then outcome = "stuck"

    while running do
      bot.decide(state) match
        case None =>
          running = false
        case Some(action) =>
          val before = state
          val result = sm.applyActionPure(before, action)
          tracker.observe(before, action, result)
          state = result.state
          actions += 1
          unchanged = if state == before then unchanged + 1 else 0
          if unchanged >= StuckAfter then
            outcome = "stuck"
            running = false
          else if actions >= maxActions then running = false

    state match
      case GameOverState(_, true)  => outcome = "victory"
      case GameOverState(_, false) => outcome = "death"
      case _                       => ()

    tracker.finish(seed, setup, profileLabel, outcome, actions, state)

/** The bookkeeping of one run: counters, the fight in progress, the milestones reached. */
private final class RunTracker(world: SimWorld, run: Int):

  private var kills        = 0
  private var eliteKills   = 0
  private var chestsOpened = 0
  private var drops        = 0
  private var potionsUsed  = 0
  private var combatsCount = 0
  private var killedBy     = ""
  private var lastPlayer   = Option.empty[Player]
  private var lastDungeon  = Option.empty[Dungeon]
  private var lastSection  = 0

  private val events     = mutable.ListBuffer.empty[GameEvent]
  private val combats    = mutable.ListBuffer.empty[CombatRow]
  private val dropRows   = mutable.ListBuffer.empty[DropRow]
  private val milestones = mutable.LinkedHashMap.empty[String, Milestone]
  private val sectionOfRoom = mutable.Map.empty[String, Int]

  /** The fight in progress, if any. */
  private var fight = Option.empty[Fight]

  private final case class Fight(
      section: Int,
      roomType: RoomType,
      kind: String,
      enemy: String,
      level: Int,
      attack: Int,
      defense: Int,
      maxHp: Int,
      hpBefore: Int,
      var turns: Int = 0,
      var dealt: Int = 0,
      var taken: Int = 0,
      var crits: Int = 0,
      var potions: Int = 0,
      var abilities: Int = 0
  )

  def observe(before: GameState, action: PlayerAction, result: TransitionResult): Unit =
    val after = result.state
    after match
      case s: ExplorationState => sectionOf(s.dungeon)
      case s: CombatState      => sectionOf(s.dungeon)
      case _                   => ()
    events ++= result.events
    countEvents(result.events)
    watchFight(before, action, result)
    watchChest(before, action, after)
    recordDrops(before, action, result)
    watchSets(after)
    after match
      case s: ExplorationState =>
        lastDungeon = Some(s.dungeon)
        lastPlayer = Some(s.player)
      case s: CombatState =>
        lastDungeon = Some(s.dungeon)
        lastPlayer = Some(s.player)
      case s: GameOverState => lastPlayer = Some(s.player)
      case _                => ()

  def finish(seed: Long,
             setup: SimSetup,
             profileLabel: String,
             outcome: String,
             actions: Int,
             finalState: GameState
  ): PlayedRun =
    val player = finalState.player
    val row = RunRow(
      run = run,
      seed = seed,
      classId = setup.classId.toString.toLowerCase,
      difficulty = setup.difficulty.toString.toLowerCase,
      profile = profileLabel,
      outcome = outcome,
      killedBy = killedBy,
      section = lastSection,
      roomsVisited = lastDungeon.fold(0)(_.visitedRoomIds.size),
      level = player.level,
      shards = player.metaCurrency,
      actions = actions,
      combats = combatsCount,
      kills = kills,
      eliteKills = eliteKills,
      chestsOpened = chestsOpened,
      drops = drops,
      potionsUsed = potionsUsed,
      maxHp = player.maxHp,
      attack = world.stats.attack(player),
      defense = world.stats.defense(player),
      milestones = milestones.toMap
    )
    PlayedRun(row, combats.toList, dropRows.toList, events.toList)

  // -- events ------------------------------------------------------------------------------------

  private def countEvents(events: List[GameEvent]): Unit =
    events.foreach:
      case GameEvent.EnemyDefeated(_, _, wasElite, _) =>
        kills += 1
        if wasElite then eliteKills += 1
      case GameEvent.ConsumableUsed(_) => potionsUsed += 1
      case _                           => ()

  // -- fights ------------------------------------------------------------------------------------

  private def watchFight(before: GameState, action: PlayerAction, result: TransitionResult): Unit =
    (before, result.state) match
      case (exp: ExplorationState, next: CombatState) =>
        val room = exp.dungeon.currentRoom
        val kind =
          if room.roomType == RoomType.Boss then "boss"
          else if room.roomType == RoomType.MiniBoss then "miniboss"
          else if next.combat.enemy.isElite then "elite"
          else "normal"
        combatsCount += 1
        fight = Some(
          Fight(
            section = sectionOf(exp.dungeon),
            roomType = room.roomType,
            kind = kind,
            enemy = next.combat.enemy.typeId,
            level = exp.player.level,
            attack = world.stats.attack(exp.player),
            defense = world.stats.defense(exp.player),
            maxHp = exp.player.maxHp,
            hpBefore = exp.player.hp
          )
        )
      case (_: CombatState, next) =>
        fight.foreach:
          f =>
            f.turns += 1
            action match
              case CombatAction(CombatActionType.Item, _, _)    => f.potions += 1
              case CombatAction(CombatActionType.Ability, _, _) => f.abilities += 1
              case _                                            => ()
            result.events.foreach:
              case GameEvent.DamageDealt(true, amount, _)    => f.taken += amount
              case GameEvent.DamageDealt(false, amount, crit) =>
                f.dealt += amount
                if crit then f.crits += 1
              case _ => ()
        next match
          case _: CombatState => ()
          case over =>
            val won = over.isInstanceOf[ExplorationState]
            fight.foreach:
              f =>
                combats += CombatRow(run, f.section, f.roomType.toString.toLowerCase, f.kind, f.enemy, won, f.turns,
                                     f.level, f.attack, f.defense, f.maxHp, f.hpBefore, over.player.hp, f.dealt,
                                     f.taken, f.crits, f.potions, f.abilities
                )
                if !won then killedBy = f.enemy
            fight = None
      case _ => ()

  /** The section (0-based) of the room the player is in. Computed once per room. */
  private def sectionOf(dungeon: Dungeon): Int =
    val section = sectionOfRoom.getOrElseUpdate(
      dungeon.currentRoomId,
      MinimapBuilder.build(dungeon).nodes.find(_.current).map(_.section).getOrElse(lastSection)
    )
    lastSection = section
    section

  // -- chests and drops --------------------------------------------------------------------------

  /** A closed chest that is open afterwards (a sprung trap does not count: it holds no loot). */
  private def watchChest(before: GameState, action: PlayerAction, after: GameState): Unit =
    (before, action, after) match
      case (b: ExplorationState, Interact(id), a: ExplorationState) =>
        val wasClosed = b.dungeon.currentRoom.entityById(id).collect { case c: Chest => c }.exists(_.state == ChestState.Closed)
        val nowOpen = a.dungeon.rooms.get(b.dungeon.currentRoomId).flatMap(_.entityById(id)).collect { case c: Chest => c }
          .exists(c => c.state == ChestState.OpenFull || c.state == ChestState.OpenEmpty)
        if wasClosed && nowOpen then chestsOpened += 1
      case _ => ()

  private val ChestKeys  = Map("log.chest.found" -> "equipped", "log.chest.foundChoice" -> "choice", "log.chest.foundWorse" -> "discarded")
  private val EnemyKeys  = Map("log.loot.dropped" -> "equipped", "log.loot.droppedChoice" -> "choice", "log.loot.droppedWorse" -> "discarded")
  private val ShrineKeys = Map("log.reward.take" -> "equipped", "log.reward.takeChoice" -> "choice", "log.reward.takeWorse" -> "discarded")

  private def recordDrops(before: GameState, action: PlayerAction, result: TransitionResult): Unit =
    val sourceKeys: Option[(String, Map[String, String])] = (before, action) match
      case (_: ExplorationState, Interact(_))  => Some("chest" -> ChestKeys)
      case (_: CombatState, _)                 => Some("enemy" -> EnemyKeys)
      case (_: ExplorationState, RewardChoice(_)) => Some("shrine" -> ShrineKeys)
      case _                                   => None
    sourceKeys.foreach:
      (source, keys) =>
        result.log.foreach:
          line =>
            (keys.get(line.key), line.params.get("item")) match
              case (Some(outcome), Some(LogValue.Text(typeId))) =>
                drops += 1
                val item   = world.itemDefs.get(typeId)
                val rarity = identifyRarity(before, result.state, typeId)
                val setId  = item.flatMap(setIdOf)
                dropRows += DropRow(run, lastSection, source, typeId, item.fold("?")(_.kind),
                                    rarity.fold("")(_.toString.toLowerCase), setId.getOrElse(""), outcome,
                                    result.state.player.level, chestsOpened, kills, drops
                )
                reachMilestones(rarity.map(_.toString.toLowerCase), setId, result.state.player)
              case _ => ()

  /** The rarity the game rolled for `typeId`, found where the item ended up (a new instance in the
    * loadout, the choice waiting to be answered, or a chest it stayed in). None when no trace is left.
    */
  private def identifyRarity(before: GameState, after: GameState, typeId: String) =
    val known = heldItems(before.player).map(_.id).toSet
    val equipped = heldItems(after.player).filter(i => i.typeId == typeId && !known.contains(i.id))
    val pending = after match
      case e: ExplorationState =>
        e.pendingEquipChoice.map(_.newItem).filter(_.typeId == typeId).toList ++
          e.dungeon.currentRoom.entities.collect { case c: Chest => c.contents }.flatten.filter(_.typeId == typeId)
      case _ => Nil
    (equipped ++ pending).headOption.map(_.rarity)

  private def heldItems(player: Player): List[Item] =
    player.equippedWeapon.toList ++ player.equippedArmor.toList ++ player.equippedAccessories.flatten ++
      player.potionBelt.flatten.map(_.item)

  private def setIdOf(item: Item): Option[String] = item match
    case w: Weapon      => w.setId
    case a: Armor       => a.setId
    case acc: Accessory => acc.setId
    case _              => None

  // -- milestones --------------------------------------------------------------------------------

  private def reach(name: String): Unit =
    if !milestones.contains(name) then milestones(name) = Milestone(chestsOpened, kills, drops)

  private def reachMilestones(rarity: Option[String], setId: Option[String], player: Player): Unit =
    if rarity.exists(r => r == "rare" || r == "epic") then reach("rare")
    if rarity.contains("epic") then reach("epic")
    setId.foreach: id =>
      reach("setPiece")
      if world.setDefs.get(id).exists(_.classId == player.classId) then reach("ownSetPiece")

  /** Set bonuses come from pieces worn, not from pieces found: checked on what is equipped. */
  private def watchSets(after: GameState): Unit =
    val player = after.player
    val mostPieces = player.equippedSetIds
      .filter(id => world.setDefs.get(id).exists(_.classId == player.classId))
      .groupBy(identity)
      .values
      .map(_.size)
      .maxOption
      .getOrElse(0)
    if mostPieces >= 2 then reach("set2pc")
    if mostPieces >= 4 then reach("set4pc")
