package roguelite.engine

import cats.effect.IO
import cats.effect.kernel.Ref
import cats.syntax.foldable.*
import roguelite.game.MetaProgression
import roguelite.db.Database
import roguelite.game.Item
import doobie.util.update
import roguelite.game.UpgradeDef
import roguelite.game.UpgradeEffect
import roguelite.game.AbilityDef
import roguelite.game.{ EquipmentResolver, PickupOutcome, SetDef }
import roguelite.game.{ AchievementChecker, AchievementDef, AchievementProgress, AchievementStats, GameEvent }
import roguelite.game.{ DifficultyTierDef, DifficultyUnlocks }
import roguelite.game.{ ChapterDef, DeathContext, EndingRules, Encounter, Enemy, GodMoment, GodVoice, MinimapLayout, Npc, NpcDef, PendingScene, RoomType, RunOutcome, SceneDef, StoryProgress, StoryRules, StorySelector, VowRules }
import roguelite.game.PerkDef
import roguelite.game.RoomLoader
import roguelite.game.ShardTopUp

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
    abilityCatalog: List[AbilityView],
    debugMode: Boolean,
    setDefs: Map[String, SetDef] = Map.empty,
    setCatalog: List[SetView] = Nil,
    perkDefs: Map[String, PerkDef] = Map.empty,
    rng: Random = Random(),
    abilityDefs: Map[ClassId, AbilityDef] = Map.empty,
    difficultyDefs: Map[Difficulty, DifficultyTierDef] = Map.empty,
    sceneDefs: Map[String, SceneDef] = Map.empty,
    chapterDefs: Map[Int, ChapterDef] = Map.empty,
    npcDefs: Map[String, NpcDef] = Map.empty
):

  /** The vow each NPC offers, from the scene catalog. */
  private val vowTypes = VowRules.vowTypes(sceneDefs)

  /** Roll a fresh random subset of the perk catalog, offered until consumed by a `StartRun`
    * action or replaced by the next fresh hub entry, or nothing while perks are still locked. See
    * `HubState.perkOptions` and [[GameSession.perkOptionsFor]].
    */
  private def rollPerkOptions(): IO[List[PerkDef]] =
    achievementRef.get.map:
      progress =>
        GameSession.perkOptionsFor(perkDefs, DifficultyUnlocks.unlockedTiers(difficultyDefs, progress.stats), rng)

  /** Process a player action, update the internal state, and return the new state snapshot to be
    * serialized and sent to the client.
    */
  def handle(action: PlayerAction): IO[StateUpdate] =
    val result = action match
      case HubAction(HubActionType.BuyUpgrade, _, Some(upgradeId), _, _, _) =>
        handleBuyUpgrade(upgradeId)
      case HubAction(HubActionType.StartRun, _, _, difficulty, _, _) =>
        handleStartRun(action, difficulty.getOrElse(StateMachine.DefaultDifficulty))
      case HubAction(HubActionType.DebugLoadRoom, _, _, _, _, Some(roomId)) =>
        if debugMode then handleDebugLoadRoom(roomId) else rejectDebugLoadRoom
      case Interact(targetId) =>
        handleInteract(action, targetId)
      case SceneDone(accept) =>
        handleSceneDone(accept)
      case CardSeen(cardId) =>
        handleCardSeen(cardId)
      case _ =>
        handleTransition(action)
    for
      update   <- result
      state    <- stateRef.get
      progress <- achievementRef.get
      story    <- storyRef.get
      withDebug <- withDebugRooms(update, state)
    yield withStory(
      withAbilityCost(withHubProgress(withAchievements(withCatalog(withDebug), progress), progress, story), state),
      story
    )

  /** Return the current state snapshot without changing anything. Useful for sending the initial
    * state right after connection.
    */
  def currentUpdate: IO[StateUpdate] =
    for
      state     <- stateRef.get
      progress  <- achievementRef.get
      story     <- storyRef.get
      withDebug <- withDebugRooms(state.toStateUpdate(), state)
    yield withStory(
      withAbilityCost(withHubProgress(withAchievements(withCatalog(withDebug), progress), progress, story), state),
      story
    )

  /** Add what only the story knows: the card still to be shown, if any (see `StateUpdate.pendingCard`),
    * which is read from the story and not from the game state so it is the same in every phase and
    * survives a reconnection, whether the inscription of a relic on offer can be read yet, and what
    * the NPC catalog says of the NPCs on screen: their sprite, and whether the one speaking has given
    * its real name away.
    */
  private def withStory(update: StateUpdate, story: StoryProgress): StateUpdate =
    val readable = story.chapter >= StoryRules.RelicReadableFromChapter
    def withSprite(entity: EntityView): EntityView =
      if entity.kind == "npc" then entity.copy(spriteId = npcDefs.get(entity.id).map(_.spriteId)) else entity
    def withName(scene: PendingSceneView): PendingSceneView =
      scene.copy(revealed = npcDefs.get(scene.npc).flatMap(_.revealedBy).forall(story.scenesSeen.contains))
    update.copy(
      room = update.room.map(room => room.copy(entities = room.entities.map(withSprite))),
      pendingScene = update.pendingScene.map(withName),
      pendingEndingChoice =
        update.pendingEndingChoice.map(_.copy(candidates = EndingRules.candidates(npcDefs, sceneDefs, story))),
      pendingCard = story.pendingCard,
      pendingRewardChoice =
        update.pendingRewardChoice.map(choice => choice.copy(relic = choice.relic.map(_.copy(readable = readable))))
    )

  /** Attach how many runs the player has finished, and which difficulty tiers are unlocked, to the
    * hub view (see [[HubView]]), the same "enrich the update with live meta outside the pure state"
    * step as [[withAchievements]]. A no-op outside the hub.
    */
  private def withHubProgress(update: StateUpdate, progress: AchievementProgress, story: StoryProgress): StateUpdate =
    update.copy(hub =
      update.hub.map(
        _.copy(runsCompleted = progress.stats.runsCompleted, difficulties = difficultyViews(progress.stats, story))
      )
    )

  /** The tiers the hub shows. A tier only the story opens is left out until the story has opened it. */
  private def difficultyViews(stats: AchievementStats, story: StoryProgress): List[DifficultyView] =
    Difficulty.values.toList.filter(DifficultyUnlocks.isShown(difficultyDefs, _, story.openedTiers)).map:
      difficulty =>
        DifficultyView(
          id = difficulty,
          unlocked = DifficultyUnlocks.isUnlocked(difficultyDefs, difficulty, stats, story.openedTiers),
          requirement = DifficultyUnlocks.progress(difficultyDefs, difficulty, stats).map:
            p =>
              TierRequirementView(winsAt = p.requirement.winsAt,
                                  wins = p.requirement.wins,
                                  winsDone = p.winsDone,
                                  shardsSpent = p.requirement.shardsSpent,
                                  shardsSpentDone = p.shardsSpentDone
              )
        )

  /** Resolve `CombatView.abilityCost` against the player's live set/perk discounts (see
    * [[roguelite.game.AbilityDef.effectiveCost]]) - a no-op outside combat, or if the player's
    * class has no loaded ability def.
    */
  private def withAbilityCost(update: StateUpdate, state: GameState): StateUpdate =
    state match
      case combat: CombatState =>
        abilityDefs.get(combat.player.classId) match
          case None => update
          case Some(ability) =>
            val cost = AbilityDef.effectiveCost(combat.player, ability, setDefs, perkDefs)
            update.copy(combat = update.combat.map(_.copy(abilityCost = Some(cost))))
      case _ => update

  /** Attach the static per-class ability catalog and the equipment set catalog to every outgoing
    * update, so the client never has to hardcode ability names/costs/resource labels or set
    * names/bonus text. See [[AbilityView]] and [[SetView]].
    */
  private def withCatalog(update: StateUpdate): StateUpdate =
    update.copy(abilities = abilityCatalog, sets = setCatalog)

  /** Attach the full achievement catalog (locked and unlocked) to every outgoing update, so the
    * client never has to hardcode achievement labels or descriptions. See [[AchievementView]].
    */
  private def withAchievements(update: StateUpdate, progress: AchievementProgress): StateUpdate =
    update.copy(achievements = achievementCatalog(progress))

  /** Every achievement, except a hidden one that has not been unlocked yet. */
  private def achievementCatalog(progress: AchievementProgress): List[AchievementView] =
    achievementDefs.values.toList
      .filter(d => !d.hidden || progress.isUnlocked(d.id))
      .sortBy(_.displayOrder)
      .map(d => toAchievementView(d, progress.isUnlocked(d.id)))

  private def toAchievementView(d: AchievementDef, unlocked: Boolean): AchievementView =
    AchievementView(id = d.id, label = d.label, description = d.description, unlocked = unlocked)

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
    stateRef.get.map(_.toStateUpdate(List("Debug rooms are off: start the backend with --debug to use them.")))

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
      postResult    <- handlePostTransition(prev, transitionResult.state)
      (finalNext, extraLog) = postResult
      newlyUnlocked <- processAchievementEvents(transitionResult.events)
      _             <- processStoryEvents(transitionResult.events)
      _             <- syncStoryFlags
      story         <- storyRef.get
      stats         <- achievementRef.get.map(_.stats)
    yield finalNext
      .toStateUpdate(transitionResult.log ++ extraLog)
      .copy(newlyUnlocked = newlyUnlocked,
            damageEvents = toDamageEventViews(transitionResult.events),
            soundEvents = toSoundEventTags(transitionResult.events),
            godLine = godLine(prev, finalNext, story, stats)
      )

  /** The line the god's voice says on a transition, if it is a moment it speaks at: a run starting
    * (one time in [[GodVoice.RunStartOneIn]]) or a death, for which it reacts to what killed the
    * player. `story` and `stats` are the ones after the transition, so a death is already counted.
    */
  private def godLine(prev: GameState, next: GameState, story: StoryProgress, stats: AchievementStats): Option[GodLineView] =
    val phase = GodVoice.phase(story.chapter)
    (prev, next) match
      case (_: HubState, _: ExplorationState) =>
        Option.when(GodVoice.speaksAtRunStart(rng))(GodLineView(GodMoment.RunStart.id, phase.id))
      case (combat: CombatState, over: GameOverState) if !over.victory =>
        val context = DeathContext(
          firstDeath = stats.runsCompleted - stats.runsWon == 1,
          streak = story.deathStreak,
          killedByBoss = combat.dungeon.isAtBoss,
          section = MinimapLayout.of(combat.dungeon).slots.get(combat.dungeon.currentRoomId).map(_.section).getOrElse(0)
        )
        Some(GodLineView(GodVoice.deathMoment(context, phase).id, phase.id))
      case _ => None

  /** Pure projection of the damage/heal facts in a transition's events - no DB/IO needed, unlike
    * achievements, since nothing here is persisted.
    */
  private def toDamageEventViews(events: List[GameEvent]): List[DamageEventView] =
    events.collect {
      case GameEvent.DamageDealt(targetIsPlayer, amount, crit) =>
        DamageEventView(targetIsPlayer = targetIsPlayer, amount = amount, kind = "damage", crit = crit)
      case GameEvent.Healed(amount) =>
        DamageEventView(targetIsPlayer = true, amount = amount, kind = "heal")
    }

  /** Pure projection of the facts worth a client-side sound cue - same rationale as
    * [[toDamageEventViews]]. Plain string tags: the client owns the tag-to-file mapping.
    */
  private def toSoundEventTags(events: List[GameEvent]): List[String] =
    events.collect {
      case GameEvent.ItemPickedUp(_, _, _, _, _) => "pickup"
      case GameEvent.LeveledUp(_)        => "level_up"
      case GameEvent.DoorUnlockedWithKey => "door_unlock"
      case GameEvent.DoorOpened          => "door_open"
    }

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
  private def handlePostTransition(prev: GameState, next: GameState): IO[(GameState, List[String])] =
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
          grant = ShardTopUp.grantFor(currentMeta, progress.stats, upgradeDefs)
          meta        <- grant.fold(IO.pure(currentMeta))(applyShardTopUp(currentMeta, _))
          perkOptions <- rollPerkOptions()
          enriched = hub.copy(
            player = hub.player.copy(metaCurrency = meta.currency),
            meta = meta,
            perkOptions = perkOptions
          )
          _ <- stateRef.set(enriched)
        yield (enriched, grant.map(g => s"You received ${g.amount} bonus Shards.").toList)

      case (_: HubState, exp: ExplorationState) =>
        metaRef.get.flatMap:
          meta =>
            // The effects of the hub upgrades are blessings: a run that takes none keeps its player as it is.
            val boosted = if exp.difficulty.usesBlessings then applyMetaBonuses(exp, meta) else exp
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
          val occupant = StorySelector.chooseOccupant(sceneDefs, judged, slot.id, met, rng)
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
        val encounter = StorySelector.chooseEncounter(sceneDefs, progress, npc.id)
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
                  case _                                      => finished.as(done.toStateUpdate())
      case state => IO.pure(state.toStateUpdate(List("There is no conversation to finish.")))

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
        else IO.pure(state.toStateUpdate(List("There is no such card to see.")))
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
            case (current, GameEvent.RunEnded(victory, difficulty, _)) =>
              val outcome = if victory then RunOutcome.Won else RunOutcome.Lost
              StoryRules.onRunEnded(chapterDefs, current, outcome, difficulty, vowTypes)
            case (current, _) => current
          updateStory(progress, afterRun)

  /** Keeps what the pure resolvers cannot see of the story in step with it: [[ExplorationState.trapsInert]]
    * with the vow now running, and [[ExplorationState.endingOpen]] with whether the story asks the
    * question of the light on this run. Done after every transition that can start, fail or end a
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
            if exp.trapsInert != inert || exp.endingOpen != open then exp.copy(trapsInert = inert, endingOpen = open)
            else exp
          case other => other

  /** The story opens the Hardcore tier: the tier becomes available, and its announcement card is
    * earned. Nothing in the game calls it yet, the ending that leaves the cult will.
    */
  private[engine] def unlockHardcore: IO[Unit] =
    storyRef.get.flatMap(story => updateStory(story, StoryRules.unlockHardcore(story)))

  /** Save what changed between two states of the story, then keep the new one. */
  private def updateStory(prev: StoryProgress, next: StoryProgress): IO[Unit] =
    GameSession.persistStory(database, prev, next) *> storyRef.set(next)

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
      yield newlyUnlockedDefs.map(d => toAchievementView(d, unlocked = true))

  /** Same as [[processAchievementEvents]], for the two conditions only reachable from a successful
    * upgrade purchase ([[roguelite.game.AchievementCondition.TotalShardsSpent]],
    * [[roguelite.game.AchievementCondition.AllUpgradesUnlocked]]).
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
    yield newlyUnlockedDefs.map(d => toAchievementView(d, unlocked = true))

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
        else stateRef.get.map(_.toStateUpdate(List(s"$difficulty is not unlocked yet.")))
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
      update <- meta.purchase(upgradeId, upgradeDefs) match
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
            currentPerkOptions = state match {
              case hub: HubState => hub.perkOptions
              case _             => Nil
            }
            newState = HubState(newPlayer, upgradeDefs, newMeta, currentPerkOptions)
            _ <- stateRef.set(newState)
            label = upgradeDefs.get(upgradeId).map(_.label).getOrElse(upgradeId)
            newlyUnlocked <- processAchievementPurchase(spent, newMeta.unlockedUpgrades.size)
            story         <- storyRef.get
            // The first purchase is what the prologue card is for: earning it again later changes nothing.
            _ <- updateStory(story, story.withCardEarned(StoryRules.PrologueCard))
          yield newState.toStateUpdate(List(s"$label purchased!")).copy(newlyUnlocked = newlyUnlocked)
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
              IO.pure(state.toStateUpdate(List(s"Debug room \"$roomId\" not found (${err.getMessage}).")))
            case Right(json) =>
              RoomLoader.loadAllFromJson(s"[$json]").attempt.flatMap {
                case Left(err) =>
                  IO.pure(state.toStateUpdate(List(s"Debug room \"$roomId\" failed to parse: ${err.getMessage}")))
                case Right(rooms) =>
                  rooms.values.headOption match
                    case None =>
                      IO.pure(state.toStateUpdate(List(s"Debug room file \"$roomId\" contained no room.")))
                    case Some(room) =>
                      stateMachine.loadDebugRoom(state.player, room) match
                        case Left(err) =>
                          IO.pure(state.toStateUpdate(List(s"Debug room \"$roomId\" failed to load: $err")))
                        case Right(nextState) =>
                          stateRef.set(nextState) *>
                            IO.pure(nextState.toStateUpdate(List(s"Loaded debug room \"$roomId\".")))
              }
          }
        case _ =>
          IO.pure(state.toStateUpdate(List("Debug rooms can only be loaded from the hub or while exploring a debug room.")))
    yield update

  /** Apply every unlocked upgrade's [[UpgradeEffect]] to the player at the start of a new run.
    *
    * Generic over the effect kind: adding a new upgrade only means adding an entry to
    * `upgrades.json` (and, if it's a genuinely new *kind* of effect, one case here and in
    * [[UpgradeEffect]]), never a change keyed by upgrade id.
    */
  private def applyMetaBonuses(state: ExplorationState, meta: MetaProgression): ExplorationState =
    val effects       = meta.unlockedUpgrades.toList.flatMap(upgradeDefs.get).map(_.effect)
    val boostedPlayer = effects.foldLeft(state.player)(applyUpgradeEffect)
    state.copy(player = boostedPlayer)

  private def applyUpgradeEffect(player: Player, effect: UpgradeEffect): Player = effect match
    case UpgradeEffect.MaxHpBoost(amount) =>
      player.copy(maxHp = player.maxHp + amount, hp = player.hp + amount)

    case UpgradeEffect.ExtraPotionSlot =>
      if player.potionBelt.length <= Player.BasePotionBeltSlots
      then player.copy(potionBelt = player.potionBelt :+ None)
      else player

    case UpgradeEffect.ExtraPotionCapacity =>
      player.copy(potionCapacity = Player.UpgradedPotionCapacity)

    case UpgradeEffect.StartingItem(typeId) =>
      itemDefs.get(typeId) match
        case None => player
        case Some(proto) =>
          EquipmentResolver.resolvePickup(player, proto.withNewId, setDefs) match
            case PickupOutcome.Equipped(p)      => p
            case PickupOutcome.KeyCollected(p)  => p
            case PickupOutcome.ChoicePending(_) => player // no room => silently skip
            case PickupOutcome.Discarded(p)     => p

    case UpgradeEffect.FlatAttackBoost(amount) =>
      player.copy(bonusAttack = player.bonusAttack + amount)

    case UpgradeEffect.GuaranteedChestRarity(minRarity) =>
      player.copy(chestRarityFloor = Some(minRarity))

    case UpgradeEffect.UnlockClass(_) =>
      // Gates class selection at StartRun (see StateMachine); no player-state effect to apply.
      player

    case UpgradeEffect.UnlockStartingKit(_) =>
      // Gates the starting-kit fold itself inside StateMachine's StartRun case (runs before
      // applyMetaBonuses ever sees the player); no player-state effect to apply here.
      player

object GameSession:

  /** Create a new session.
    *
    * Loads [[MetaProgression]] from the database so the hub screen shows the correct currency
    * balance and upgrade unlock status from the first frame.
    *
    * @param stateMachine  Shared across all connections for a server instance.
    * @param database      Per-server (shared) persistence layer.
    * @param itemDefs      Item prototype map, used to resolve the `StartingItem` upgrade effect.
    * @param upgradeDefs   The loaded upgrade catalog (see [[roguelite.game.UpgradeLoader]]).
    * @param abilityDefs   The loaded ability catalog (see [[roguelite.game.AbilityLoader]]),
    *                      projected once into the [[AbilityView]] catalog sent on every update.
    * @param achievementDefs The loaded achievement catalog (see [[roguelite.game.AchievementLoader]]).
    * @param setDefs       The loaded equipment set catalog (see [[roguelite.game.SetLoader]]), used
    *                      to resolve the `StartingItem` upgrade effect's set HP reconciliation.
    * @param perkDefs      The loaded run-perk catalog (see [[roguelite.game.PerkLoader]]), a random
    *                      subset of which is offered on every fresh hub entry.
    * @param rng           Random instance for perk rolls. Inject a seeded one for deterministic
    *                      tests.
    * @param debugMode     Whether the dev-only Debug Rooms tooling is on (the backend was started
    *                      with `--debug`): the hub lists the rooms of `debug-rooms/` and
    *                      `DebugLoadRoom` is honoured. Off by default.
    * @param difficultyDefs The loaded difficulty tier catalog (see [[roguelite.game.DifficultyLoader]]),
    *                      which decides the tiers a `StartRun` may name and when the run perks open.
    *                      Empty by default, which leaves every tier available.
    * @param sceneDefs     The loaded NPC scene catalog (see [[roguelite.game.StoryLoader]]), which
    *                      decides who stands in a Rest room and what they say. Empty by default, in
    *                      which case an NPC only ever says a short line.
    * @param chapterDefs   The loaded chapter catalog (see [[roguelite.game.ChapterLoader]]), which
    *                      decides what a victory opens. Empty by default, in which case none does.
    * @param npcDefs       The loaded NPC catalog (see [[roguelite.game.NpcLoader]]), which gives each
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
      _ <- persistStory(database, savedStory, story)
      initPlayer = Player(classId = ClassId.Warrior,
                          hp = 100,
                          maxHp = 100,
                          resourceCurrent = 0,
                          resourceMax = 100,
                          level = 1,
                          xp = 0,
                          metaCurrency = meta.currency
      )
      initPerkOptions = perkOptionsFor(perkDefs, DifficultyUnlocks.unlockedTiers(difficultyDefs, achievementStats), rng)
      stateRef       <- Ref.of[IO, GameState](HubState(initPlayer, upgradeDefs, meta, initPerkOptions))
      metaRef        <- Ref.of[IO, MetaProgression](meta)
      achievementRef <- Ref.of[IO, AchievementProgress](AchievementProgress(unlockedAchievements, achievementStats))
      storyRef       <- Ref.of[IO, StoryProgress](story)
      abilityCatalog = abilityDefs.values.map(toAbilityView).toList
      setCatalog     = setDefs.values.map(toSetView).toList
    yield new GameSession(stateRef,
                          metaRef,
                          achievementRef,
                          storyRef,
                          stateMachine,
                          database,
                          itemDefs,
                          upgradeDefs,
                          achievementDefs,
                          abilityCatalog,
                          debugMode,
                          setDefs,
                          setCatalog,
                          perkDefs,
                          rng,
                          abilityDefs,
                          difficultyDefs,
                          sceneDefs,
                          chapterDefs,
                          npcDefs
    )

  /** Save what changed between two states of the story: the chapter and the last outcome, the scenes
    * newly played, every vow that is not as it was, the streak of deaths, the relics newly read, whether
    * Hardcore has just been opened and the cards newly earned or seen.
    */
  private def persistStory(database: Database, prev: StoryProgress, next: StoryProgress): IO[Unit] =
    for
      _ <- IO.whenA(next.chapter != prev.chapter || next.lastRun != prev.lastRun)(
        database.saveStoryState(next.chapter, next.lastRun)
      )
      _ <- (next.scenesSeen -- prev.scenesSeen).toList.traverse_(database.recordSceneSeen)
      _ <- next.vows.toList
        .filterNot((npc, record) => prev.vows.get(npc).contains(record))
        .traverse_((npc, record) => database.saveVow(npc, record))
      _ <- IO.whenA(next.deathStreak != prev.deathStreak)(database.saveDeathStreak(next.deathStreak))
      _ <- IO.whenA(next.hardcoreUnlocked && !prev.hardcoreUnlocked)(database.saveHardcoreUnlocked())
      _ <- next.ending.filter(prev.ending != Some(_)).traverse_(database.saveEnding)
      _ <- (next.relicsRead -- prev.relicsRead).toList.sorted.traverse_(database.recordRelicRead)
      _ <- next.cards.filterNot(card => prev.cards.exists(_.id == card.id)).traverse_(card => database.recordCardEarned(card.id))
      _ <- next.cards.filter(card => card.seen && !prev.cards.contains(card)).traverse_(card => database.markCardSeen(card.id))
    yield ()

  /** Number of perks offered per hub visit, out of the full catalog. */
  private val PerkOptionsCount = 3

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
    else rng.shuffle(perkDefs.values.toList).take(PerkOptionsCount)

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

  private def toAbilityView(a: AbilityDef): AbilityView =
    AbilityView(
      classId = a.classId,
      id = a.id,
      name = a.name,
      cost = a.cost,
      resourceName = a.resourceName,
      description = a.description
    )

  private def toSetView(s: SetDef): SetView =
    SetView(id = s.id,
           name = s.name,
           classId = s.classId,
           bonus2pcLabel = s.twoPiece.label,
           bonus4pcLabel = s.fourPiece.label
    )
