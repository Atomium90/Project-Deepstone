package roguelite.engine

import cats.effect.IO
import cats.effect.kernel.Ref
import roguelite.game.MetaProgression
import roguelite.db.Database
import roguelite.game.Item
import doobie.util.update
import roguelite.game.UpgradeDef
import roguelite.game.Inventory

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
    stateMachine: StateMachine,
    database: Database,
    itemDefs: Map[String, Item]
):

  /** Process a player action, update the internal state, and return the new state snapshot to be
    * serialized and sent to the client.
    */
  def handle(action: PlayerAction): IO[StateUpdate] = action match
    case HubAction(HubActionType.BuyUpgrade, _, Some(upgradeId)) =>
      handleBuyUpgrade(upgradeId)
    case _ =>
      handleTransition(action)

  /** Return the current state snapshot without changing anything. Useful for sending the initial
    * state right after connection.
    */
  def currentUpdate: IO[StateUpdate] =
    stateRef.get.map(_.toStateUpdate())

  // -----------------------------------------------------------------------
  // Internal — transition handling
  // -----------------------------------------------------------------------

  private def handleTransition(action: PlayerAction): IO[StateUpdate] =
    for
      result <- stateRef.modify:
        state =>
          val (next, log) = stateMachine.applyActionPure(state, action)
          (next, (state, next, log))
      (prev, next, log) = result
      finalNext <- handlePostTransition(prev, next)
    yield finalNext.toStateUpdate(log)

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
      update <- meta.purchase(upgradeId) match
        case Left(err) =>
          IO.pure(state.toStateUpdate(List(err)))

        case Right(newMeta) =>
          for
            _ <- database.purchaseUpgrade(upgradeId, newMeta.currency)
            _ <- metaRef.set(newMeta)
            newPlayer = state.player.copy(metaCurrency = newMeta.currency)
            newState  = HubState(newPlayer, newMeta)
            _ <- stateRef.set(newState)
            label = UpgradeDef.byId.get(upgradeId).map(_.label).getOrElse(upgradeId)
          yield newState.toStateUpdate(List(s"$label purchased!"))
    yield update

  /** Apply purchased upgrade effects to the player at the start of a new run.
    *
    * Effects are cumulative and applied in definition order:
    *   - `hp_boost_1` / `hp_boost_2`: increase maxHp and current HP
    *   - `extra_slot`: expand inventory from 6 to 7 slots
    *   - `potion_start`: add 1 Health Potion to inventory (silently skipped if full)
    */
  private def applyMetaBonuses(state: ExplorationState, meta: MetaProgression): ExplorationState =
    var player = state.player

    if meta.isUnlocked("hp_boost_1") then
      player = player.copy(maxHp = player.maxHp + 20, hp = player.hp + 20)

    if meta.isUnlocked("hp_boost_2") then
      player = player.copy(maxHp = player.maxHp + 40, hp = player.hp + 40)

    if meta.isUnlocked("extra_slot") && player.inventory.slots.length < 7 then
      // Append one empty slot to the inventory vector
      val expanded = Inventory(player.inventory.slots :+ None)
      player = player.copy(inventory = expanded)

    if meta.isUnlocked("potion_start") then
      itemDefs
        .get("health_potion")
        .foreach:
          proto =>
            player.withItemPickup(proto.withNewId) match
              case Right(p) => player = p
              case Left(_)  => () // inventory full => silently skip

    state.copy(player = player)

object GameSession:

  /** Create a new session.
    *
    * Loads [[MetaProgression]] from the database so the hub screen shows the correct currency
    * balance and upgrade unlock status from the first frame.
    *
    * @param stateMachine  Shared across all connections for a server instance.
    * @param database      Per-server (shared) persistence layer.
    * @param itemDefs      Item prototype map, used to resolve the `potion_start` upgrade.
    */
  def create(stateMachine: StateMachine,
             database: Database,
             itemDefs: Map[String, Item]
  ): IO[GameSession] =
    for
      meta <- database.loadMeta()
      initPlayer = Player(classId = ClassId.Warrior,
                          hp = 100,
                          maxHp = 100,
                          resourceCurrent = 0,
                          resourceMax = 100,
                          level = 1,
                          xp = 0,
                          metaCurrency = meta.currency
      )
      stateRef <- Ref.of[IO, GameState](HubState(initPlayer, meta))
      metaRef  <- Ref.of[IO, MetaProgression](meta)
    yield new GameSession(stateRef, metaRef, stateMachine, database, itemDefs)
