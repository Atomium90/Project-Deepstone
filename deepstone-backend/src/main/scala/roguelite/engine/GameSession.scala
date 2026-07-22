package roguelite.engine

import cats.effect.IO
import cats.effect.kernel.Ref
import roguelite.game.MetaProgression
import roguelite.db.Database
import roguelite.game.Item
import doobie.util.update
import roguelite.game.UpgradeDef
import roguelite.game.UpgradeEffect
import roguelite.game.AbilityDef
import roguelite.game.Inventory
import roguelite.game.{ AchievementChecker, AchievementDef, AchievementProgress, AchievementStats, GameEvent }

/** Represents one active player connection.
  *
  * Each WebSocket connection gets its own GameSession. The session owns a `Ref[IO, GameState]` — an
  * atomically-updatable reference that keeps the authoritative game state for that player.
  *
  * Usage: call `handle` for every incoming player action; it transitions the state and returns the
  * `StateUpdate` to send back to the client.
  */
class GameSession private (
    stateRef: Ref[IO, GameState],
    metaRef: Ref[IO, MetaProgression],
    achievementRef: Ref[IO, AchievementProgress],
    stateMachine: StateMachine,
    database: Database,
    itemDefs: Map[String, Item],
    upgradeDefs: Map[String, UpgradeDef],
    achievementDefs: Map[String, AchievementDef],
    abilityCatalog: List[AbilityView]
):

  /** Process a player action, update the internal state, and return the new state snapshot to be
    * serialized and sent to the client.
    */
  def handle(action: PlayerAction): IO[StateUpdate] =
    val result = action match
      case HubAction(HubActionType.BuyUpgrade, _, Some(upgradeId), _) =>
        handleBuyUpgrade(upgradeId)
      case _ =>
        handleTransition(action)
    for
      update   <- result
      progress <- achievementRef.get
    yield withAchievements(withCatalog(update), progress)

  /** Return the current state snapshot without changing anything. Useful for sending the initial
    * state right after connection.
    */
  def currentUpdate: IO[StateUpdate] =
    for
      state    <- stateRef.get
      progress <- achievementRef.get
    yield withAchievements(withCatalog(state.toStateUpdate()), progress)

  /** Attach the static per-class ability catalog to every outgoing update, so the client never
    * has to hardcode ability names, costs, or resource labels — see [[AbilityView]].
    */
  private def withCatalog(update: StateUpdate): StateUpdate =
    update.copy(abilities = abilityCatalog)

  /** Attach the full achievement catalog (locked and unlocked) to every outgoing update, so the
    * client never has to hardcode achievement labels or descriptions — see [[AchievementView]].
    */
  private def withAchievements(update: StateUpdate, progress: AchievementProgress): StateUpdate =
    update.copy(achievements = achievementCatalog(progress))

  private def achievementCatalog(progress: AchievementProgress): List[AchievementView] =
    achievementDefs.values.toList
      .sortBy(_.displayOrder)
      .map(d => toAchievementView(d, progress.isUnlocked(d.id)))

  private def toAchievementView(d: AchievementDef, unlocked: Boolean): AchievementView =
    AchievementView(id = d.id, label = d.label, description = d.description, unlocked = unlocked)

  // -----------------------------------------------------------------------
  // Internal — transition handling
  // -----------------------------------------------------------------------

  private def handleTransition(action: PlayerAction): IO[StateUpdate] =
    for
      result <- stateRef.modify:
        state =>
          val transitionResult = stateMachine.applyActionPure(state, action)
          (transitionResult.state, (state, transitionResult))
      (prev, transitionResult) = result
      finalNext     <- handlePostTransition(prev, transitionResult.state)
      newlyUnlocked <- processAchievementEvents(transitionResult.events)
    yield finalNext
      .toStateUpdate(transitionResult.log, transitionResult.dialogue)
      .copy(newlyUnlocked = newlyUnlocked)

  /** Side-effects and state enrichment triggered by specific state transitions.
    *
    *   - `Combat → GameOver`  : persist metaCurrency immediately (browser-close safety)
    *   - `GameOver → HubState`: enrich the placeholder HubState with real MetaProgression
    *   - `Hub → Exploration`  : apply purchased upgrade bonuses to the starting player
    */
  private def handlePostTransition(prev: GameState, next: GameState): IO[GameState] =
    (prev, next) match
      case (_, gameOver: GameOverState) =>
        // Persist currency immediately
        val currency = gameOver.player.metaCurrency
        metaRef.update(_.copy(currency = currency)) *>
          database.saveCurrency(currency) *>
          IO.pure(gameOver)

      case (_: GameOverState, hub: HubState) =>
        // State machine puts MetaProgression.empty as placeholder; replace with real meta
        metaRef.get.flatMap:
          meta =>
            val enriched = hub.copy(
              player = hub.player.copy(metaCurrency = meta.currency),
              meta = meta
            )
            stateRef.set(enriched) *> IO.pure(enriched)

      case (_: HubState, exp: ExplorationState) =>
        metaRef.get.flatMap:
          meta =>
            val boosted = applyMetaBonuses(exp, meta)
            stateRef.set(boosted) *> IO.pure(boosted)

      case _ =>
        IO.pure(next)

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
    val updatedProgress =
      prevProgress.copy(unlocked = prevProgress.unlocked ++ newIds, stats = updatedStats)
    for
      _ <- database.saveAchievementStats(updatedStats)
      _ <- newIds.foldLeft(IO.unit)((acc, id) => acc *> database.unlockAchievement(id))
      _ <- achievementRef.set(updatedProgress)
    yield ()

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
            newState  = HubState(newPlayer, upgradeDefs, newMeta)
            _ <- stateRef.set(newState)
            label = upgradeDefs.get(upgradeId).map(_.label).getOrElse(upgradeId)
            newlyUnlocked <- processAchievementPurchase(spent, newMeta.unlockedUpgrades.size)
          yield newState.toStateUpdate(List(s"$label purchased!")).copy(newlyUnlocked = newlyUnlocked)
    yield update

  /** Apply every unlocked upgrade's [[UpgradeEffect]] to the player at the start of a new run.
    *
    * Generic over the effect kind: adding a new upgrade only means adding an entry to
    * `upgrades.json` (and, if it's a genuinely new *kind* of effect, one case here and in
    * [[UpgradeEffect]]) — never a change keyed by upgrade id.
    */
  private def applyMetaBonuses(state: ExplorationState, meta: MetaProgression): ExplorationState =
    val effects       = meta.unlockedUpgrades.toList.flatMap(upgradeDefs.get).map(_.effect)
    val boostedPlayer = effects.foldLeft(state.player)(applyUpgradeEffect)
    state.copy(player = boostedPlayer)

  private def applyUpgradeEffect(player: Player, effect: UpgradeEffect): Player = effect match
    case UpgradeEffect.MaxHpBoost(amount) =>
      player.copy(maxHp = player.maxHp + amount, hp = player.hp + amount)

    case UpgradeEffect.ExtraInventorySlot =>
      if player.inventory.slots.length <= Inventory.MaxSlots
      then player.copy(inventory = Inventory(player.inventory.slots :+ None))
      else player

    case UpgradeEffect.StartingItem(typeId) =>
      itemDefs.get(typeId) match
        case None => player
        case Some(proto) =>
          player.withItemPickup(proto.withNewId) match
            case Right(p) => p
            case Left(_)  => player // inventory full => silently skip

    case UpgradeEffect.UnlockClass(_) =>
      // Gates class selection at StartRun (see StateMachine) — no player-state effect to apply.
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
    */
  def create(stateMachine: StateMachine,
             database: Database,
             itemDefs: Map[String, Item],
             upgradeDefs: Map[String, UpgradeDef],
             abilityDefs: Map[ClassId, AbilityDef],
             achievementDefs: Map[String, AchievementDef]
  ): IO[GameSession] =
    for
      meta                 <- database.loadMeta()
      achievementStats     <- database.loadAchievementStats()
      unlockedAchievements <- database.loadUnlockedAchievements()
      initPlayer = Player(classId = ClassId.Warrior,
                          hp = 100,
                          maxHp = 100,
                          resourceCurrent = 0,
                          resourceMax = 100,
                          level = 1,
                          xp = 0,
                          metaCurrency = meta.currency
      )
      stateRef       <- Ref.of[IO, GameState](HubState(initPlayer, upgradeDefs, meta))
      metaRef        <- Ref.of[IO, MetaProgression](meta)
      achievementRef <- Ref.of[IO, AchievementProgress](AchievementProgress(unlockedAchievements, achievementStats))
      abilityCatalog = abilityDefs.values.map(toAbilityView).toList
    yield new GameSession(stateRef,
                          metaRef,
                          achievementRef,
                          stateMachine,
                          database,
                          itemDefs,
                          upgradeDefs,
                          achievementDefs,
                          abilityCatalog
    )

  private def toAbilityView(a: AbilityDef): AbilityView =
    AbilityView(
      classId = a.classId,
      id = a.id,
      name = a.name,
      cost = a.cost,
      resourceName = a.resourceName,
      description = a.description
    )
