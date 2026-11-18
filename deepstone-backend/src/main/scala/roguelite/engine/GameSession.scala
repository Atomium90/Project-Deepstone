package roguelite.engine

import cats.effect.IO
import cats.effect.kernel.Ref
import cats.syntax.foldable.*
import roguelite.game.progression.MetaProgression
import roguelite.db.Database
import roguelite.game.dungeon.{ Enemy, MinimapLayout, Npc, RoomType }
import roguelite.game.items.Item
import doobie.util.update
import roguelite.game.progression.UpgradeDef
import roguelite.game.combat.AbilityDef
import roguelite.game.core.{ GameEvent, LogLine }
import roguelite.game.items.SetDef
import roguelite.game.progression.{ AchievementChecker, AchievementDef, AchievementProgress, AchievementStats, DifficultyTierDef, DifficultyUnlocks }
import roguelite.game.story.{ ChapterDef, DeathContext, Encounter, Ending, EndingRules, GodMoment, GodVoice, NpcDef, PendingScene, RunOutcome, SceneDef, StoryProgress, StoryRules, StorySelector, VoicePhase, VowRules }
import roguelite.game.combat.CombatResolver
import roguelite.game.progression.PerkDef
import roguelite.game.dungeon.RoomLoader
import roguelite.game.progression.ShardTopUp

import java.nio.file.{ Files, NoSuchFileException, Path }
import scala.jdk.CollectionConverters.*
import scala.util.Random

/** Represents one active player connection.
  *
  * Each WebSocket connection gets its own GameSession. The session owns a `Ref[IO, GameState]`, an
  * atomically-updatable reference that keeps the authoritative game state for that player.
  *
  * Usage: call `handle` for every incoming player action; it transitions the state and returns the
  * `StateUpdate` to send back to the client.
  */
class GameSession private (
    stateRef: Ref[IO, GameState],
    metaRef: Ref[IO, MetaProgression],
    achievementRef: Ref[IO, AchievementProgress],
    storyRef: Ref[IO, StoryProgress],
    stateMachine: StateMachine,
    database: Database,
    itemDefs: Map[String, Item],
    upgradeDefs: Map[String, UpgradeDef],
    achievementDefs: Map[String, AchievementDef],
    projections: StateUpdateProjections,
    debugMode: Boolean,
    setDefs: Map[String, SetDef] = Map.empty,
    perkDefs: Map[String, PerkDef] = Map.empty,
    rng: Random = Random(),
    difficultyDefs: Map[Difficulty, DifficultyTierDef] = Map.empty,
    sceneDefs: Map[String, SceneDef] = Map.empty,
    chapterDefs: Map[Int, ChapterDef] = Map.empty,
    npcDefs: Map[String, NpcDef] = Map.empty
):

  /** The vow each NPC offers, from the scene catalog. */
  private val vowTypes = VowRules.vowTypes(sceneDefs)

  /** What the owned upgrades and the perks taken change in the player as a run starts. */
  private val runStartBonuses = RunStartBonuses(upgradeDefs, itemDefs, setDefs, perkDefs)

  /** Roll a fresh random subset of the perk catalog, offered until consumed by a `StartRun`
    * action or replaced by the next fresh hub entry, or nothing while perks are still locked. See
    * `HubState.perkOptions` and [[GameSession.perkOptionsFor]].
    */
  private def rollPerkOptions(): IO[List[PerkDef]] =
    achievementRef.get.map:
      progress =>
        GameSession.perkOptionsFor(perkDefs, DifficultyUnlocks.unlockedTiers(difficultyDefs, progress.stats), rng)

  /** Roll the risky perk to offer, a single one, as long as Hard is unlocked. See
    * `HubState.riskyPerkOptions` and [[GameSession.riskyPerkOptionsFor]].
    */
  private def rollRiskyPerkOptions(): IO[List[PerkDef]] =
    achievementRef.get.map:
      progress =>
        GameSession.riskyPerkOptionsFor(perkDefs, DifficultyUnlocks.unlockedTiers(difficultyDefs, progress.stats), rng)

  /** Process a player action, update the internal state, and return the new state snapshot to be
    * serialized and sent to the client.
    */
  def handle(action: PlayerAction): IO[StateUpdate] =
    val result = action match
      case HubAction(HubActionType.BuyUpgrade, _, Some(upgradeId), _, _, _, _) =>
        handleBuyUpgrade(upgradeId)
      case HubAction(HubActionType.StartRun, _, _, difficulty, _, _, _) =>
        handleStartRun(action, difficulty.getOrElse(StateMachine.DefaultDifficulty))
      case HubAction(HubActionType.DebugLoadRoom, _, _, _, _, Some(roomId), _) =>
        if debugMode then handleDebugLoadRoom(roomId) else rejectDebugLoadRoom
      case Interact(targetId) =>
        handleInteract(action, targetId)
      case SceneDone(accept) =>
        handleSceneDone(accept)
      case CardSeen(cardId) =>
        handleCardSeen(cardId)
      case EndingChoice(answer, npc) =>
        handleEndingChoice(answer, npc)
      case _ =>
        handleTransition(action)
    for
      update   <- result
      state    <- stateRef.get
      progress <- achievementRef.get
      story    <- storyRef.get
      withDebug <- withDebugRooms(update, state)
    yield projections.enrich(withDebug, state, progress, story)

  /** Return the current state snapshot without changing anything. Useful for sending the initial
    * state right after connection.
    */
  def currentUpdate: IO[StateUpdate] =
    for
      state     <- stateRef.get
      progress  <- achievementRef.get
      story     <- storyRef.get
      withDebug <- withDebugRooms(state.toStateUpdate(), state)
    yield projections.enrich(withDebug, state, progress, story)

  /** Dev tooling only, and only when the backend was started with `--debug`: attaches the list of
    * hand-converted Tiled rooms currently sitting in the backend's `debug-rooms/` folder, so the hub
    * can offer one-click "load this room" buttons (see `handleDebugLoadRoom`). Only scanned while in
    * the hub - irrelevant, and not worth an extra directory read, on every mid-run action. Without
    * debug mode the list stays empty, which is all the client needs to hide every debug control.
    */
  private def withDebugRooms(update: StateUpdate, state: GameState): IO[StateUpdate] =
    state match
      case _: HubState if debugMode => GameSession.listDebugRooms().map(ids => update.copy(debugRooms = ids))
      case _                        => IO.pure(update)

  /** A `DebugLoadRoom` that arrives while debug mode is off: nothing changes, and the log says how
    * to turn it on, same discipline as any other rejected dev action.
    */
  private def rejectDebugLoadRoom: IO[StateUpdate] =
    stateRef.get.map(_.toStateUpdate(List(LogLine.raw("Debug rooms are off: start the backend with --debug to use them."))))

  // -----------------------------------------------------------------------
  // Internal: transition handling
  // -----------------------------------------------------------------------

  private def handleTransition(action: PlayerAction): IO[StateUpdate] =
    for
      result <- stateRef.modify:
        state =>
          val transitionResult = stateMachine.applyActionPure(state, action)
          (transitionResult.state, (state, transitionResult))
      (prev, transitionResult) = result
      update <- settle(prev, transitionResult)
    yield update

  /** Everything that follows a transition once the state is decided: the enrichment of the new state,
    * the achievements and the story the transition's facts change, and the update for the client. The
    * state is already `transitionResult.state` in the session.
    */
  private def settle(prev: GameState, transitionResult: TransitionResult): IO[StateUpdate] =
    for
      postResult    <- handlePostTransition(prev, transitionResult.state)
      (finalNext, extraLog) = postResult
      newlyUnlocked <- processAchievementEvents(transitionResult.events)
      _             <- processStoryEvents(transitionResult.events)
      _             <- syncStoryFlags
      story         <- storyRef.get
      stats         <- achievementRef.get.map(_.stats)
      line = godLine(prev, finalNext, story, stats)
      // The blows of a fight that was just won come before the line that tells the enemy has fallen.
      fightLines = (prev, finalNext) match
        case (fought: CombatState, _: ExplorationState) =>
          transitionResult.log.indexWhere(_.key == CombatResolver.DefeatedKey).max(0)
        case _ => 0
      // The line of an ending is said once: the story remembers that it was.
      _ <- line.traverse_((moment, _) => updateStory(story, GodVoice.said(story, moment)))
    yield finalNext
      .toStateUpdate(transitionResult.log ++ extraLog)
      .copy(newlyUnlocked = newlyUnlocked,
            damageEvents = StateUpdateProjections.toDamageEventViews(transitionResult.events),
            soundEvents = StateUpdateProjections.toSoundEventTags(transitionResult.events),
            godLine = line.map((moment, phase) => GodLineView(moment.id, phase.id)),
            fightLines = fightLines
      )

  /** The line the god's voice says on a transition, if it is a moment it speaks at: a run starting
    * (one time in [[GodVoice.RunStartOneIn]]) or a death, for which it reacts to what killed the
    * player. `story` and `stats` are the ones after the transition, so a death is already counted.
    */
  private def godLine(prev: GameState, next: GameState, story: StoryProgress, stats: AchievementStats): Option[(GodMoment, VoicePhase)] =
    val phase = GodVoice.phase(story.chapter)
    (prev, next) match
      case (_: HubState, _: ExplorationState) =>
        Option.when(GodVoice.greetsRuns(story) && GodVoice.speaksAtRunStart(rng))((GodMoment.RunStart, phase))
      case (combat: CombatState, over: GameOverState) if !over.victory && GodVoice.speaksAtDeath(story) =>
        // The first death after the light was answered has a line of its own, before every other.
        GodVoice.afterEnd(story) match
          case Some(moment) => Some((moment, phase))
          case None =>
            val context = DeathContext(
              firstDeath = stats.runsCompleted - stats.runsWon == 1,
              streak = story.deathStreak,
              killedByBoss = combat.dungeon.isAtBoss,
              section = MinimapLayout.of(combat.dungeon).slots.get(combat.dungeon.currentRoomId).map(_.section).getOrElse(0)
            )
            Some((GodVoice.deathMoment(context, phase), phase))
      case _ => None

  /** Side-effects and state enrichment triggered by specific state transitions. Returns the state
    * to carry on with, plus any log lines the enrichment itself produced.
    *
    *   - `Combat → GameOver`  : persist metaCurrency immediately (browser-close safety)
    *   - `GameOver → HubState`: enrich the placeholder HubState with real MetaProgression, after
    *                            topping up the Shards of a player stuck without a kit
    *   - `Hub → Exploration`  : apply purchased upgrade bonuses to the starting player
    *   - `Exploration → Exploration` into a Rest not visited yet: judge the running vow and seat the
    *                            NPC who will be there (see [[onRestEntered]])
    *   - `Combat → Exploration`: the fight was won, and a Devoted who fell says a last line
    *   - `Exploration → Exploration` into the question of the light: the victory that brought the
    *                            player to the Sanctuary opens its chapter, whose card comes first
    */
  private def handlePostTransition(prev: GameState, next: GameState): IO[(GameState, List[LogLine])] =
    (prev, next) match
      case (_, gameOver: GameOverState) =>
        // Persist currency immediately
        val currency = gameOver.player.metaCurrency
        metaRef.update(_.copy(currency = currency)) *>
          database.saveCurrency(currency) *>
          IO.pure((gameOver, Nil))

      case (_: GameOverState, hub: HubState) =>
        // State machine puts MetaProgression.empty as placeholder; replace with real meta.
        // This is also a fresh hub entry, so roll a new set of perk options. The run that just
        // ended is already counted in the achievement stats (its events were processed when it
        // reached GameOver), which is what the top-up reads.
        for
          currentMeta <- metaRef.get
          progress    <- achievementRef.get
          story       <- storyRef.get
          // With the altar shut there is nothing left to top the Shards up for.
          grant = if story.shopClosed then None else ShardTopUp.grantFor(currentMeta, progress.stats, upgradeDefs)
          meta        <- grant.fold(IO.pure(currentMeta))(applyShardTopUp(currentMeta, _))
          perkOptions      <- rollPerkOptions()
          riskyPerkOptions <- rollRiskyPerkOptions()
          enriched = hub.copy(
            player = hub.player.copy(metaCurrency = meta.currency),
            meta = meta,
            perkOptions = perkOptions,
            riskyPerkOptions = riskyPerkOptions
          )
          _ <- stateRef.set(enriched)
        yield (enriched, grant.map(g => LogLine.of("log.shards.topUp", "count" -> g.amount)).toList)

      case (_: HubState, exp: ExplorationState) =>
        metaRef.get.flatMap:
          meta =>
            // The effects of the hub upgrades are blessings (a run that takes none keeps its player as it
            // is), and a risky perk's max HP malus comes last: see RunStartBonuses.
            val boosted = runStartBonuses(exp, meta)
            stateRef.set(boosted) *> IO.pure((boosted, Nil))

      case (before: ExplorationState, after: ExplorationState)
          if after.pendingEndingChoice && !before.pendingEndingChoice =>
        // The run does not end at the Sanctuary yet, but the victory is won: the chapter it opens and
        // its card, which says what the choice is, must come before the question.
        storyRef.get.flatMap:
          story => updateStory(story, StoryRules.openChapter(chapterDefs, story, after.difficulty)).as((after, Nil))

      case (before: ExplorationState, after: ExplorationState) if enteredNewRest(before, after) =>
        onRestEntered(after).map(seated => (seated, Nil))

      case (before: CombatState, after: ExplorationState) =>
        // The fight was won. A Devoted who fell says a last line before the player can move on.
        before.enemyStats.get(before.combat.enemy.typeId).flatMap(_.devoted) match
          case Some(devoted) =>
            val speaking = after.copy(pendingScene = Some(PendingScene(Encounter.DevotedAfter(devoted))))
            for
              story <- storyRef.get
              // A relic that could be read when its Devoted fell counts as read.
              _ <- IO.whenA(story.chapter >= StoryRules.RelicReadableFromChapter)(updateStory(story, story.withRelicRead(devoted)))
              _ <- stateRef.set(speaking)
            yield (speaking, Nil)
          case None => IO.pure((next, Nil))

      case _ =>
        IO.pure((next, Nil))

  /** Whether `after` is the first time the player walks into a Rest room: a room that is neither the
    * one they were in nor one they have already left. Going back into a Rest they know is not an
    * entry, so nothing is judged or seated a second time.
    */
  private def enteredNewRest(before: ExplorationState, after: ExplorationState): Boolean =
    val room = after.dungeon.currentRoom
    room.roomType == RoomType.Rest &&
    room.id != before.dungeon.currentRoomId &&
    !before.dungeon.visitedRoomIds.contains(room.id)

  /** The player has walked into a new Rest. The vow that was running ends here, kept, and the NPC
    * of the room is chosen from what the story remembers: its [[Npc]] entity, authored with the NPC
    * that stands there when nothing else has a scene to play, takes the chosen NPC's id. The NPCs
    * already met at the Rests of this run are the ones left on the rooms the player has visited.
    */
  private def onRestEntered(exp: ExplorationState): IO[ExplorationState] =
    for
      progress <- storyRef.get
      judged = VowRules.onRestEntered(progress, vowTypes)
      room   = exp.dungeon.currentRoom
      met = exp.dungeon.visitedRoomIds
        .filterNot(_ == room.id)
        .flatMap(exp.dungeon.rooms.get)
        .filter(_.roomType == RoomType.Rest)
        .flatMap(_.entities.collect { case npc: Npc => npc.id })
      seated = room.entities.collectFirst { case npc: Npc => npc }.fold(exp) {
        slot =>
          val occupant = StorySelector.chooseOccupant(sceneDefs, judged, slot.id, met, rng, npcDefs)
          val seatedRoom = room.updateEntity(slot.id):
            case npc: Npc => npc.copy(id = occupant)
            case other    => other
          exp.copy(dungeon = exp.dungeon.copy(rooms = exp.dungeon.rooms.updated(room.id, seatedRoom)))
      }
      _ <- updateStory(progress, judged)
      _ <- stateRef.set(seated)
    yield seated

  // -----------------------------------------------------------------------
  // Internal: conversations with the NPCs of the Rest rooms
  // -----------------------------------------------------------------------

  /** Speaking to a Rest NPC opens a conversation, which depends on what the story remembers, so it is
    * decided here rather than in the pure state machine. Interacting with anything else goes through
    * the state machine as usual, and so does speaking while a choice or a conversation is pending
    * (the state machine blocks it).
    */
  private def handleInteract(action: PlayerAction, targetId: String): IO[StateUpdate] =
    stateRef.get.flatMap:
      case exp: ExplorationState
          if exp.pendingEquipChoice.isEmpty && exp.pendingRewardChoice.isEmpty && exp.pendingScene.isEmpty &&
            !exp.pendingEndingChoice =>
        exp.dungeon.currentRoom.entityById(targetId) match
          case Some(npc: Npc) => startConversation(exp, npc)
          case Some(enemy: Enemy) =>
            // A Devoted speaks before the fight starts, once the line is read (see handleSceneDone).
            exp.enemyStats.get(enemy.typeId).flatMap(_.devoted) match
              case Some(devoted) => startDevotedLine(exp, enemy, devoted)
              case None          => handleTransition(action)
          case _ => handleTransition(action)
      case _ => handleTransition(action)

  private def startDevotedLine(exp: ExplorationState, enemy: Enemy, devoted: Int): IO[StateUpdate] =
    storyRef.get.flatMap:
      progress =>
        val next = exp.copy(pendingScene = Some(PendingScene(StorySelector.beforeFight(devoted, enemy.id, progress))))
        stateRef.set(next).as(next.toStateUpdate())

  private def startConversation(exp: ExplorationState, npc: Npc): IO[StateUpdate] =
    storyRef.get.flatMap:
      progress =>
        val encounter = StorySelector.chooseEncounter(sceneDefs, progress, npc.id, npcDefs)
        val next      = exp.copy(pendingScene = Some(PendingScene(encounter, StorySelector.offerScene(sceneDefs, encounter))))
        stateRef.set(next).as(next.toStateUpdate())

  /** The player has read the pending conversation to its last line, and answered the vow it offered
    * if it did. What changes is checked and saved, and the conversation ends. An answer the
    * conversation did not call for, or none when it did, leaves it pending.
    */
  private def handleSceneDone(accept: Option[Boolean]): IO[StateUpdate] =
    stateRef.get.flatMap:
      case exp: ExplorationState if exp.pendingScene.isDefined =>
        storyRef.get.flatMap:
          progress =>
            StoryRules.complete(progress, exp.pendingScene.get.encounter, accept) match
              case Left(err) => IO.pure(exp.toStateUpdate(List(err)))
              case Right(next) =>
                val done     = exp.copy(pendingScene = None)
                val finished = updateStory(progress, next) *> stateRef.set(done) *> syncStoryFlags
                exp.pendingScene.get.encounter match
                  // The line of a Devoted was the last thing between the player and the fight.
                  case Encounter.DevotedBefore(_, _, enemyId) => finished *> handleTransition(Interact(enemyId))
                  // The NPC who took the player's place has said their last words: the run ends.
                  case Encounter.Farewell(_) => finished *> endRun(done, List(LogLine.of("log.ending.farewell")))
                  case _                     => finished.as(done.toStateUpdate())
      case state => IO.pure(state.toStateUpdate(List(LogLine.of("log.story.noConversation"))))

  /** The player has read the card on their screen. Only the card the server is waiting for counts,
    * so a stale or made-up id changes nothing.
    */
  private def handleCardSeen(cardId: String): IO[StateUpdate] =
    for
      state    <- stateRef.get
      progress <- storyRef.get
      update <-
        if progress.pendingCard.contains(cardId) then
          updateStory(progress, progress.withCardSeen(cardId)).as(state.toStateUpdate())
        else IO.pure(state.toStateUpdate(List(LogLine.of("log.story.noCard"))))
    yield update

  /** What a transition's facts change in the story: a potion drunk fails a vow against potions, and a
    * run that ends is judged (the running vow, the chapter a victory opens, the outcome the NPCs
    * comment on).
    */
  private def processStoryEvents(events: List[GameEvent]): IO[Unit] =
    if events.isEmpty then IO.unit
    else
      storyRef.get.flatMap:
        progress =>
          val afterFacts = VowRules.onEvents(progress, vowTypes, events)
          val afterRun = events.foldLeft(afterFacts):
            case (current, GameEvent.RunEnded(victory, difficulty, _, _)) =>
              val outcome = if victory then RunOutcome.Won else RunOutcome.Lost
              StoryRules.onRunEnded(chapterDefs, current, outcome, difficulty, vowTypes)
            case (current, _) => current
          updateStory(progress, afterRun)

  /** Keeps what the pure resolvers cannot see of the story in step with it: [[ExplorationState.trapsInert]]
    * with the vow now running, [[ExplorationState.endingOpen]] with whether the story asks the question
    * of the light on this run, and [[ExplorationState.altarShut]] with whether the light was refused. Done after every transition that can start, fail or end a
    * vow, which also gives them to a run that has just begun and back to the states a combat builds
    * anew.
    */
  private val syncStoryFlags: IO[Unit] =
    storyRef.get.flatMap:
      story =>
        val inert = VowRules.runningVow(story, vowTypes).exists(_.inertTraps)
        stateRef.update:
          case exp: ExplorationState =>
            val open = EndingRules.isOffered(story, exp.difficulty)
            if exp.trapsInert != inert || exp.endingOpen != open || exp.altarShut != story.shopClosed then
              exp.copy(trapsInert = inert, endingOpen = open, altarShut = story.shopClosed)
            else exp
          case other => other

  // -----------------------------------------------------------------------
  // Internal: the answer to the light
  // -----------------------------------------------------------------------

  /** The player answers the question the Sanctuary asked. The answer is checked against the story (an
    * NPC can only take the player's place once their story is over), then what it changes is saved.
    * An answer that makes no sense leaves the question pending.
    */
  private def handleEndingChoice(answer: EndingAnswer, npc: Option[String]): IO[StateUpdate] =
    stateRef.get.flatMap:
      case exp: ExplorationState if exp.pendingEndingChoice =>
        storyRef.get.flatMap:
          story =>
            val candidates = EndingRules.candidates(npcDefs, sceneDefs, story)
            EndingRules.ending(answer, npc, candidates) match
              case Left(err)     => IO.pure(exp.toStateUpdate(List(err)))
              case Right(ending) => answerLight(exp, story, ending)
      case state => IO.pure(state.toStateUpdate(List(LogLine.of("log.ending.noQuestion"))))

  /** The answer is valid: the story keeps it, and the run goes on to its end. Entering the light and
    * refusing it end the run now (refusing first takes every Shard away: the altar is shut, and the
    * hub saves the balance as the run ends). Letting someone take the player's place first plays that
    * NPC's last scene, and the run ends when it has been read.
    */
  private def answerLight(exp: ExplorationState, story: StoryProgress, ending: Ending): IO[StateUpdate] =
    val next: IO[StateUpdate] = ending match
      case Ending.Entered =>
        endRun(exp, List(LogLine.of("log.ending.enter")))
      case Ending.Refused =>
        endRun(exp.copy(player = exp.player.copy(metaCurrency = 0)), List(LogLine.of("log.ending.refuse")), paysBonus = false)
      case Ending.Replaced(who) =>
        val farewell =
          exp.copy(pendingEndingChoice = false, pendingScene = Some(PendingScene(Encounter.Farewell(who))))
        stateRef.set(farewell).as(farewell.toStateUpdate(List(LogLine.of("log.ending.replace"))))
    updateStory(story, EndingRules.answered(story, ending)) *> next

  /** The run ends in victory, once the light has been answered: the same end as a Sanctuary that asks
    * nothing, with the same facts for the achievements and the story.
    */
  private def endRun(exp: ExplorationState, log: List[LogLine], paysBonus: Boolean = true): IO[StateUpdate] =
    // The Shards a risky perk pays for a win, unless the light was refused, which gives everything up.
    val bonus    = if paysBonus then PerkDef.winBonus(exp.player, perkDefs, exp.difficulty.usesBlessings, exp.altarShut) else 0
    val bonusLog = if bonus > 0 then List(LogLine.of("log.run.victoryBonus", "count" -> bonus)) else Nil
    val over     = GameOverState(exp.player.copy(metaCurrency = exp.player.metaCurrency + bonus), victory = true)
    val ended = GameEvent.RunEnded(victory = true,
                                   difficulty = exp.difficulty,
                                   activePerkId = exp.player.activePerkId,
                                   riskyPerkId = exp.player.activeRiskyPerkId
    )
    stateRef.set(over) *> settle(exp, TransitionResult(over, log ++ bonusLog, List(ended)))

  /** Save what changed between two states of the story, then keep the new one. */
  private def updateStory(prev: StoryProgress, next: StoryProgress): IO[Unit] =
    StoryPersistence.save(database, prev, next) *> storyRef.set(next)

  /** Add a [[ShardTopUp.Grant]] to the balance, in memory and in the database. */
  private def applyShardTopUp(meta: MetaProgression, grant: ShardTopUp.Grant): IO[MetaProgression] =
    val toppedUp = meta.copy(currency = meta.currency + grant.amount)
    database.saveCurrency(toppedUp.currency) *> metaRef.set(toppedUp).as(toppedUp)

  /** Check the achievement catalog against the events emitted by this transition, persist any
    * newly-unlocked achievements and updated counters, and return the freshly-unlocked views for
    * the outgoing [[StateUpdate]].
    */
  private def processAchievementEvents(events: List[GameEvent]): IO[List[AchievementView]] =
    if events.isEmpty then IO.pure(Nil)
    else
      for
        progress <- achievementRef.get
        (updatedStats, newlyUnlockedDefs) =
          AchievementChecker.checkEvents(achievementDefs, progress.unlocked, progress.stats, events)
        _ <-
          if newlyUnlockedDefs.isEmpty && updatedStats == progress.stats then IO.unit
          else persistAchievementProgress(progress, updatedStats, newlyUnlockedDefs)
      yield newlyUnlockedDefs.map(d => projections.toAchievementView(d, unlocked = true))

  /** Same as [[processAchievementEvents]], for the two conditions only reachable from a successful
    * upgrade purchase ([[roguelite.game.progression.AchievementCondition.TotalShardsSpent]],
    * [[roguelite.game.progression.AchievementCondition.AllUpgradesUnlocked]]).
    */
  private def processAchievementPurchase(spent: Int,
                                         unlockedUpgradeCount: Int
  ): IO[List[AchievementView]] =
    for
      progress <- achievementRef.get
      (updatedStats, newlyUnlockedDefs) = AchievementChecker.checkPurchase(
        achievementDefs,
        progress.unlocked,
        progress.stats,
        spent,
        unlockedUpgradeCount,
        upgradeDefs.size
      )
      _ <- persistAchievementProgress(progress, updatedStats, newlyUnlockedDefs)
    yield newlyUnlockedDefs.map(d => projections.toAchievementView(d, unlocked = true))

  private def persistAchievementProgress(prevProgress: AchievementProgress,
                                         updatedStats: AchievementStats,
                                         newlyUnlocked: List[AchievementDef]
  ): IO[Unit] =
    val newIds = newlyUnlocked.map(_.id)
    val newPotionTypesUsed = updatedStats.potionTypesUsed -- prevProgress.stats.potionTypesUsed
    val newPerksWonWith    = updatedStats.perksWonWith -- prevProgress.stats.perksWonWith
    val updatedProgress =
      prevProgress.copy(unlocked = prevProgress.unlocked ++ newIds, stats = updatedStats)
    for
      _ <- database.saveAchievementStats(updatedStats)
      _ <- newIds.foldLeft(IO.unit)((acc, id) => acc *> database.unlockAchievement(id))
      _ <- newPotionTypesUsed.foldLeft(IO.unit)((acc, id) => acc *> database.recordPotionTypeUsed(id))
      _ <- newPerksWonWith.foldLeft(IO.unit)((acc, id) => acc *> database.recordPerkWonWith(id))
      _ <- achievementRef.set(updatedProgress)
    yield ()

  /** Refuses a `StartRun` on a difficulty tier the save has not unlocked, whatever the client sent:
    * the hub greys such a tier out, but the server is what enforces it. Like `BuyUpgrade`, this is
    * decided here rather than in the pure state machine because the stats it reads live in the
    * session. Every other run start goes through the state machine as usual.
    */
  private def handleStartRun(action: PlayerAction, difficulty: Difficulty): IO[StateUpdate] =
    for
      progress <- achievementRef.get
      story    <- storyRef.get
      update <-
        if DifficultyUnlocks.isUnlocked(difficultyDefs, difficulty, progress.stats, story.openedTiers) then
          handleTransition(action)
        else stateRef.get.map(_.toStateUpdate(List(LogLine.of("log.run.tierLocked", "difficulty" -> difficulty.toString.toLowerCase))))
    yield update

  /** Validate, persist, and apply an upgrade purchase atomically from the session's perspective.
    *
    * The DB write and the in-memory update happen sequentially. A failure in `database.purchaseUpgrade`
    * leaves `metaRef` unchanged (it is only updated after the DB call succeeds), so the session state
    * is always consistent with what is persisted.
    */
  private def handleBuyUpgrade(upgradeId: String): IO[StateUpdate] =
    for
      state <- stateRef.get
      meta  <- metaRef.get
      shut  <- storyRef.get.map(_.shopClosed)
      // Once the light is refused the altar is shut for good, whatever the hub shows.
      purchase = if shut then Left(LogLine.of("log.shop.closed")) else meta.purchase(upgradeId, upgradeDefs)
      update <- purchase match
        case Left(err) =>
          IO.pure(state.toStateUpdate(List(err)))

        case Right(newMeta) =>
          val spent = upgradeDefs.get(upgradeId).map(_.cost).getOrElse(0)
          for
            _ <- database.purchaseUpgrade(upgradeId, newMeta.currency)
            _ <- metaRef.set(newMeta)
            newPlayer = state.player.copy(metaCurrency = newMeta.currency)
            // A purchase isn't a fresh hub entry: carry the already-rolled perk options forward
            // instead of rerolling them.
            (currentPerkOptions, currentRiskyOptions) = state match {
              case hub: HubState => (hub.perkOptions, hub.riskyPerkOptions)
              case _             => (Nil, Nil)
            }
            newState = HubState(newPlayer, upgradeDefs, newMeta, currentPerkOptions, currentRiskyOptions)
            _ <- stateRef.set(newState)
            newlyUnlocked <- processAchievementPurchase(spent, newMeta.unlockedUpgrades.size)
            story         <- storyRef.get
            // The first purchase is what the prologue card is for: earning it again later changes nothing.
            _ <- updateStory(story, story.withCardEarned(StoryRules.PrologueCard))
          yield newState.toStateUpdate(List(LogLine.of("log.shop.purchased", "upgrade" -> upgradeId))).copy(newlyUnlocked = newlyUnlocked)
    yield update

  /** Dev tooling only: loads one hand-converted Tiled room from `debug-rooms/<roomId>.json` and
    * drops the player straight into it, bypassing `DungeonBuilder` entirely - see
    * `frontend/scripts/convert-tiled-room.mjs` for how that file gets produced. Reading the file
    * needs IO, same reason `BuyUpgrade` is intercepted here rather than in the pure `StateMachine`.
    * Valid from the hub or while already exploring a debug room (the latter is what powers
    * ExplorationHUD's next/prev debug-room shortcut - jumping straight to a different room without
    * a round trip back to the hub's button list); any failure (missing file, bad JSON, no room in
    * it) is reported as a log message on the unchanged state rather than crashing the session, same
    * discipline as an invalid upgrade purchase.
    */
  private def handleDebugLoadRoom(roomId: String): IO[StateUpdate] =
    for
      state <- stateRef.get
      update <- state match
        case _: HubState | _: ExplorationState =>
          GameSession.readDebugRoomFile(roomId).attempt.flatMap {
            case Left(err) =>
              IO.pure(state.toStateUpdate(List(LogLine.raw(s"Debug room \"$roomId\" not found (${err.getMessage})."))))
            case Right(json) =>
              RoomLoader.loadAllFromJson(s"[$json]").attempt.flatMap {
                case Left(err) =>
                  IO.pure(state.toStateUpdate(List(LogLine.raw(s"Debug room \"$roomId\" failed to parse: ${err.getMessage}"))))
                case Right(rooms) =>
                  rooms.values.headOption match
                    case None =>
                      IO.pure(state.toStateUpdate(List(LogLine.raw(s"Debug room file \"$roomId\" contained no room."))))
                    case Some(room) =>
                      stateMachine.loadDebugRoom(state.player, room) match
                        case Left(err) =>
                          IO.pure(state.toStateUpdate(List(LogLine.raw(s"Debug room \"$roomId\" failed to load: $err"))))
                        case Right(nextState) =>
                          stateRef.set(nextState) *>
                            IO.pure(nextState.toStateUpdate(List(LogLine.raw(s"Loaded debug room \"$roomId\"."))))
              }
          }
        case _ =>
          IO.pure(state.toStateUpdate(List(LogLine.raw("Debug rooms can only be loaded from the hub or while exploring a debug room."))))
    yield update

object GameSession:

  /** Create a new session.
    *
    * Loads [[MetaProgression]] from the database so the hub screen shows the correct currency
    * balance and upgrade unlock status from the first frame.
    *
    * @param stateMachine  Shared across all connections for a server instance.
    * @param database      Per-server (shared) persistence layer.
    * @param itemDefs      Item prototype map, used to resolve the `StartingItem` upgrade effect.
    * @param upgradeDefs   The loaded upgrade catalog (see [[roguelite.game.progression.UpgradeLoader]]).
    * @param abilityDefs   The loaded ability catalog (see [[roguelite.game.combat.AbilityLoader]]),
    *                      projected once into the [[AbilityView]] catalog sent on every update.
    * @param achievementDefs The loaded achievement catalog (see [[roguelite.game.progression.AchievementLoader]]).
    * @param setDefs       The loaded equipment set catalog (see [[roguelite.game.items.SetLoader]]), used
    *                      to resolve the `StartingItem` upgrade effect's set HP reconciliation.
    * @param perkDefs      The loaded run-perk catalog (see [[roguelite.game.progression.PerkLoader]]), a random
    *                      subset of which is offered on every fresh hub entry.
    * @param rng           Random instance for perk rolls. Inject a seeded one for deterministic
    *                      tests.
    * @param debugMode     Whether the dev-only Debug Rooms tooling is on (the backend was started
    *                      with `--debug`): the hub lists the rooms of `debug-rooms/` and
    *                      `DebugLoadRoom` is honoured. Off by default.
    * @param difficultyDefs The loaded difficulty tier catalog (see [[roguelite.game.progression.DifficultyLoader]]),
    *                      which decides the tiers a `StartRun` may name and when the run perks open.
    *                      Empty by default, which leaves every tier available.
    * @param sceneDefs     The loaded NPC scene catalog (see [[roguelite.game.story.StoryLoader]]), which
    *                      decides who stands in a Rest room and what they say. Empty by default, in
    *                      which case an NPC only ever says a short line.
    * @param chapterDefs   The loaded chapter catalog (see [[roguelite.game.story.ChapterLoader]]), which
    *                      decides what a victory opens. Empty by default, in which case none does.
    * @param npcDefs       The loaded NPC catalog (see [[roguelite.game.story.NpcLoader]]), which gives each
    *                      NPC its sprite and tells when its real name is known. Empty by default, in
    *                      which case an NPC has no sprite of its own and is always known by its name.
    */
  def create(stateMachine: StateMachine,
             database: Database,
             itemDefs: Map[String, Item],
             upgradeDefs: Map[String, UpgradeDef],
             abilityDefs: Map[ClassId, AbilityDef],
             achievementDefs: Map[String, AchievementDef],
             setDefs: Map[String, SetDef] = Map.empty,
             perkDefs: Map[String, PerkDef] = Map.empty,
             rng: Random = Random(),
             debugMode: Boolean = false,
             difficultyDefs: Map[Difficulty, DifficultyTierDef] = Map.empty,
             sceneDefs: Map[String, SceneDef] = Map.empty,
             chapterDefs: Map[Int, ChapterDef] = Map.empty,
             npcDefs: Map[String, NpcDef] = Map.empty
  ): IO[GameSession] =
    for
      meta                 <- database.loadMeta()
      achievementStats     <- database.loadAchievementStats()
      unlockedAchievements <- database.loadUnlockedAchievements()
      savedStory           <- database.loadStoryProgress()
      // A vow still running was taken in a run that this session cannot continue.
      story = VowRules.onSessionStart(savedStory)
      _ <- StoryPersistence.save(database, savedStory, story)
      initPlayer = Player(classId = ClassId.Warrior,
                          hp = 100,
                          maxHp = 100,
                          resourceCurrent = 0,
                          resourceMax = 100,
                          level = 1,
                          xp = 0,
                          metaCurrency = meta.currency
      )
      initTiers = DifficultyUnlocks.unlockedTiers(difficultyDefs, achievementStats)
      initPerkOptions = perkOptionsFor(perkDefs, initTiers, rng)
      initRiskyOptions = riskyPerkOptionsFor(perkDefs, initTiers, rng)
      stateRef       <- Ref.of[IO, GameState](HubState(initPlayer, upgradeDefs, meta, initPerkOptions, initRiskyOptions))
      metaRef        <- Ref.of[IO, MetaProgression](meta)
      achievementRef <- Ref.of[IO, AchievementProgress](AchievementProgress(unlockedAchievements, achievementStats))
      storyRef       <- Ref.of[IO, StoryProgress](story)
      projections = StateUpdateProjections(
        abilityCatalog = abilityDefs.values.map(StateUpdateProjections.toAbilityView).toList,
        setCatalog = setDefs.values.map(StateUpdateProjections.toSetView).toList,
        achievementDefs = achievementDefs,
        abilityDefs = abilityDefs,
        setDefs = setDefs,
        perkDefs = perkDefs,
        difficultyDefs = difficultyDefs,
        sceneDefs = sceneDefs,
        npcDefs = npcDefs
      )
    yield new GameSession(stateRef,
                          metaRef,
                          achievementRef,
                          storyRef,
                          stateMachine,
                          database,
                          itemDefs,
                          upgradeDefs,
                          achievementDefs,
                          projections,
                          debugMode,
                          setDefs,
                          perkDefs,
                          rng,
                          difficultyDefs,
                          sceneDefs,
                          chapterDefs,
                          npcDefs
    )

  /** Number of perks offered per hub visit, out of the full catalog. */
  private val PerkOptionsCount = 3

  private val RiskyPerkOptionsCount = 1

  /** The risky perk to offer on a hub visit: a random one (take it or leave it), once the player can start a Hard run, and
    * none before, enforced here like the ordinary perks are so that a client cannot unlock them. A
    * locked visit does not consume the rng.
    */
  private[engine] def riskyPerkOptionsFor(perkDefs: Map[String, PerkDef],
                                          unlockedTiers: Set[Difficulty],
                                          rng: Random
  ): List[PerkDef] =
    if !unlockedTiers.contains(Difficulty.Hard) then Nil
    else rng.shuffle(perkDefs.values.toList.filter(_.risky)).take(RiskyPerkOptionsCount)

  /** The perks to offer on a hub visit: a random subset of the catalog, or none while the Normal
    * tier is still locked, the perks being the first rung of the difficulty ladder. Enforced here,
    * on the server, by offering no perk at all, so a client cannot unlock them (the hub already
    * hides an empty perk list). A locked visit does not consume the rng.
    */
  private[engine] def perkOptionsFor(perkDefs: Map[String, PerkDef],
                                     unlockedTiers: Set[Difficulty],
                                     rng: Random
  ): List[PerkDef] =
    if !unlockedTiers.contains(Difficulty.Normal) then Nil
    // A risky perk is offered in a row of its own, never among the ordinary ones.
    else rng.shuffle(perkDefs.values.toList.filterNot(_.risky)).take(PerkOptionsCount)

  /** Dev tooling only: where `frontend/scripts/convert-tiled-room.mjs --out=...` output is meant
    * to be pointed at. Resolved relative to the backend's own working directory (`sbt run` /
    * the packaged launcher both run from `deepstone-backend/`), gitignored - never real,
    * shippable game content, just a local scratch folder for previewing a room while authoring it.
    */
  private val DebugRoomsDir: Path = Path.of("debug-rooms")

  private def listDebugRooms(): IO[List[String]] = listDebugRoomsIn(DebugRoomsDir)

  private def readDebugRoomFile(roomId: String): IO[String] = readDebugRoomFileIn(DebugRoomsDir, roomId)

  /** The ids of the rooms under `dir`: every `*.json` file at any depth, by its bare file name,
    * sorted. Converted rooms may sit in subfolders that mirror the Tiled project
    * (`debug-rooms/darkDungeon/...`), so the folder is not part of an id, which stays equal to the
    * room's own `id` (the converter names the file after it). A missing or unreadable folder lists
    * nothing.
    */
  private[engine] def listDebugRoomsIn(dir: Path): IO[List[String]] =
    IO.blocking(debugRoomFiles(dir).map(_.getFileName.toString.stripSuffix(".json")).distinct.sorted)
      .handleErrorWith(_ => IO.pure(Nil))

  /** The content of the room file called `<roomId>.json` anywhere under `dir`. An id is matched
    * against bare file names only, so one carrying a folder (or `..`) never reaches a file; if two
    * folders hold the same name, the first path in sorted order wins.
    */
  private[engine] def readDebugRoomFileIn(dir: Path, roomId: String): IO[String] =
    IO.blocking {
      debugRoomFiles(dir).find(_.getFileName.toString == s"$roomId.json") match
        case Some(file) => Files.readString(file)
        case None       => throw NoSuchFileException(dir.resolve(s"$roomId.json").toString)
    }

  private def debugRoomFiles(dir: Path): List[Path] =
    if Files.isDirectory(dir) then
      val walk = Files.walk(dir)
      try
        walk
          .iterator()
          .asScala
          .filter(p => Files.isRegularFile(p) && p.getFileName.toString.endsWith(".json"))
          .toList
          .sortBy(_.toString)
      finally walk.close()
    else Nil
