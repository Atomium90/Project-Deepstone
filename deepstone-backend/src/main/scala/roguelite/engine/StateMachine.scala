package roguelite.engine

import cats.effect.IO
import roguelite.game.{
  ClassDef,
  CombatResolver,
  Dungeon,
  DungeonBuilder,
  EnemyStats,
  EquipmentResolver,
  InteractionResolver,
  Item,
  NpcDialogueDef,
  PerkDef,
  PerkEffect,
  PickupOutcome,
  Room,
  RewardChoiceResolver,
  Sanctuary,
  SetDef
}

import scala.util.Random
import roguelite.game.MetaProgression
import roguelite.game.UpgradeDef
import roguelite.game.UpgradeEffect

/** Processes player actions and produces new game states.
  *
  * The state machine is intentionally thin: it handles routing (which action is valid in which
  * state) but delegates heavy logic to dedicated classes:
  *   - [[CombatResolver]] for all combat math
  *   - [[LootTable]] for all drop rolls
  *
  * @param roomPool
  *   All available rooms, keyed by id. A fresh [[roguelite.game.Dungeon]] is assembled from this
  *   pool via [[DungeonBuilder]] on every `StartRun`, so each run gets a different layout.
  * @param enemyStats
  *   Lookup table of enemy stats keyed by typeId.
  * @param itemDefs
  *   Prototype item map keyed by typeId, used by [[LootTable]] for chest rolls.
  * @param classDefs
  *   Definitions of all playable classes (warrior, mage…), used to build the player at run start.
  * @param upgradeDefs
  *   The loaded upgrade catalog, used to build [[HubState]] and to gate `StartRun` behind any
  *   matching `UnlockClass` upgrade (see [[roguelite.game.UpgradeEffect.UnlockClass]]).
  * @param resolver
  *   Resolves combat turns.
  * @param rng
  *   Random instance for dungeon assembly and chest loot rolls. Inject a seeded one for
  *   deterministic tests.
  * @param npcDialogueDefs
  *   NPC dialogue catalog (see [[roguelite.game.NpcDialogueLoader]]), passed through to
  *   [[InteractionResolver]].
  * @param perkDefs
  *   The loaded run-perk catalog, passed through to [[InteractionResolver]] for the Lucky Find
  *   chest hook. `StartRun`'s own perk validation doesn't need this separately - it reads the
  *   full `PerkDef` straight off `HubState.perkOptions`.
  */
class StateMachine(roomPool: Map[String, Room],
                   enemyStats: Map[String, EnemyStats],
                   itemDefs: Map[String, Item],
                   classDefs: Map[ClassId, ClassDef],
                   upgradeDefs: Map[String, UpgradeDef],
                   resolver: CombatResolver,
                   rng: Random = Random(),
                   npcDialogueDefs: Map[String, NpcDialogueDef] = Map.empty,
                   setDefs: Map[String, SetDef] = Map.empty,
                   perkDefs: Map[String, PerkDef] = Map.empty
):
  private val interactionResolver =
    InteractionResolver(enemyStats, itemDefs, rng, npcDialogueDefs, setDefs = setDefs, perkDefs = perkDefs)

  /** Dev tooling only: builds an `ExplorationState` containing just the given single room,
    * bypassing `DungeonBuilder` entirely - used by the Tiled room-authoring debug tool (see
    * `GameSession.handleDebugLoadRoom`) to preview one hand-converted room without a full run.
    * Reuses whichever player is already active rather than minting a fresh one, so switching
    * between a few debug rooms in a row keeps whatever HP/gear you were just looking at.
    */
  def loadDebugRoom(player: Player, room: Room): Either[String, ExplorationState] =
    Dungeon.fromRooms(List(room)).map {
      dungeon =>
        val (x, y) = StateMachine.findAnyWalkableTile(room)
        ExplorationState(player,
                         dungeon,
                         playerX = x,
                         playerY = y,
                         difficulty = Difficulty.Normal,
                         enemyStats = enemyStats
        )
    }

  /** Lifts a plain (state, log) transition result into the richer type `applyActionPure` returns,
    * so every action that never produces dialogue (everything except Interact on an Npc) can keep
    * its existing shape untouched. */
  private def lift(r: (GameState, List[String])): TransitionResult = TransitionResult(r._1, r._2)

  def transition(state: GameState, action: PlayerAction): IO[TransitionResult] =
    IO.pure(applyActionPure(state, action))

  /** Pure (non-IO) version used internally and by GameSession. */
  def applyActionPure(state: GameState, action: PlayerAction): TransitionResult =
    (state, action) match

      // -- Hub --------------------------------------------------------------

      case (hub: HubState, HubAction(HubActionType.StartRun, Some(classId), _, difficultyOpt, perkIdOpt, _)) =>
        lift({
          val lockedBehind = upgradeDefs.values.find {
            u => u.effect == UpgradeEffect.UnlockClass(classId) && !hub.meta.isUnlocked(u.id)
          }
          // Unlike lockedBehind, an un-purchased kit doesn't block the run - it just starts with
          // nothing instead of the class's usual weapon/armor. Every class needs its own kit
          // upgrade, including Warrior (which has no UnlockClass gate at all) - the two gates are
          // independent concepts.
          val kitUnlocked = upgradeDefs.values.exists {
            u => u.effect == UpgradeEffect.UnlockStartingKit(classId) && hub.meta.isUnlocked(u.id)
          }

          (lockedBehind, classDefs.get(classId)) match {
            case (Some(u), _) =>
              (hub, List(s"${u.label} required — purchase it in the hub to unlock this class."))

            case (None, None) => (hub, List(s"Unknown class '$classId'. Cannot start run."))

            case (None, Some(classDef)) =>
              val difficulty = difficultyOpt.getOrElse(Difficulty.Normal)

              DungeonBuilder(roomPool, rng).build(difficulty = difficulty)() match {
                case Left(err) =>
                  (hub, List(s"Failed to build dungeon: $err"))

                case Right(dungeon) =>
                  val basePlayer = Player(
                    classId = classId,
                    hp = classDef.hp,
                    maxHp = classDef.hp,
                    resourceCurrent = classDef.resourceStart,
                    resourceMax = classDef.resourceMax,
                    level = 1,
                    xp = 0,
                    metaCurrency = hub.player.metaCurrency,
                    affinityTags = classDef.affinityTags
                  )

                  // Resolve starting kit: unknown typeIds are skipped, slot collisions are not
                  // expected. Skipped entirely (basePlayer unchanged) if this class's kit-unlock
                  // upgrade hasn't been purchased.
                  val playerWithKit =
                    if !kitUnlocked then basePlayer
                    else
                      classDef.startingKit.foldLeft(basePlayer):
                        (p, typeId) =>
                          itemDefs.get(typeId) match {
                            case None => p
                            case Some(proto) =>
                              EquipmentResolver.resolvePickup(p, proto.withNewId, setDefs) match {
                                case PickupOutcome.Equipped(updated)     => updated
                                case PickupOutcome.KeyCollected(updated) => updated
                                case PickupOutcome.ChoicePending(_)      => p
                                case PickupOutcome.Discarded(updated)    => updated
                              }
                          }

                  // A perkId not currently among hub.perkOptions (stale/tampered) is silently
                  // ignored, same discipline as EquipChoice's invalid-slot handling.
                  val chosenPerk = perkIdOpt.flatMap(id => hub.perkOptions.find(_.id == id))
                  val playerWithPerk = chosenPerk match {
                    case None => playerWithKit
                    case Some(perk) =>
                      val withEffect = perk.effect match {
                        case PerkEffect.ExtraStartingItem(typeId) =>
                          itemDefs.get(typeId) match {
                            case None => playerWithKit
                            case Some(proto) =>
                              EquipmentResolver.resolvePickup(playerWithKit, proto.withNewId, setDefs) match {
                                case PickupOutcome.Equipped(updated)     => updated
                                case PickupOutcome.KeyCollected(updated) => updated
                                case PickupOutcome.ChoicePending(_)      => playerWithKit
                                case PickupOutcome.Discarded(updated)    => updated
                              }
                          }
                        // Every other perk kind is read live from activePerkId wherever it
                        // applies (CombatResolver/InteractionResolver) - no StartRun-time effect.
                        case _ => playerWithKit
                      }
                      withEffect.copy(activePerkId = Some(perk.id))
                  }

                  val nextState =
                    ExplorationState(playerWithPerk,
                                      dungeon,
                                      playerX = 1,
                                      playerY = 1,
                                      difficulty,
                                      enemyStats = enemyStats
                    )
                  val perkLog = chosenPerk.map(p => s"Run perk active: ${p.label}").toList
                  (nextState, s"A new run begins. Good luck, $classId." :: perkLog)
              }
          }
        })

      case (hub: HubState, HubAction(HubActionType.BuyUpgrade, Some(classId), _, _, _, _)) =>
        // BuyUpgrade is intercepted by GameSession (needs DB access); reject here as a safety net
        lift((hub, List("Upgrade purchases must be routed through GameSession.")))

      case (hub: HubState, HubAction(HubActionType.DebugLoadRoom, _, _, _, _, _)) =>
        // DebugLoadRoom is intercepted by GameSession (needs IO to read the room file); reject
        // here as a safety net, same precedent as BuyUpgrade above.
        lift((hub, List("Debug room loading must be routed through GameSession.")))

      // Return to hub after death: GameSession enriches the HubState with real meta
      case (gameOver: GameOverState, HubAction(HubActionType.ReturnToHub, _, _, _, _, _)) =>
        // Placeholder: class is re-chosen on next StartRun
        val hubPlayer = Player(
          classId = ClassId.Warrior,
          hp = 100,
          maxHp = 100,
          resourceCurrent = 0,
          resourceMax = 100,
          level = 1,
          xp = 0,
          metaCurrency = gameOver.player.metaCurrency
        )
        // MetaProgression.empty is a placeholder; GameSession replaces it with the real meta
        lift(
          (HubState(hubPlayer, upgradeDefs, MetaProgression.empty),
           List("You return to the hub, wiser from your journey.")
          )
        )

      // -- Exploration ------------------------------------------------------

      case (exp: ExplorationState, Move(_) | Interact(_))
          if exp.pendingEquipChoice.isDefined || exp.pendingRewardChoice.isDefined =>
        // A pending keep/replace or reward choice must be resolved before the player can move or
        // interact again - same "silently blocked" precedent as a wall/entity collision below.
        // Interacting is blocked too: opening another chest would overwrite the pending choice, and
        // leaving through a door would strand the chest the choice came from.
        TransitionResult(exp, Nil)

      case (exp: ExplorationState, Move(direction)) =>
        val (dx, dy) = direction match {
          case Direction.Up    => (0, -1)
          case Direction.Down  => (0, 1)
          case Direction.Left  => (-1, 0)
          case Direction.Right => (1, 0)
        }

        val newX = exp.playerX + dx
        val newY = exp.playerY + dy

        if !exp.dungeon.currentRoom.isWalkable(newX, newY) then
          // Walking into the Sanctuary is the only way it ever triggers - the client deliberately
          // never offers an E-key path to it (no keycap badge, no fallback), so this reuses the
          // same `interact` entry point Interact(targetId) normally goes through. Every other
          // blocking entity keeps the plain silent-block behavior.
          exp.dungeon.currentRoom.entityAt(newX, newY) match
            case Some(sanctuary: Sanctuary) => interactionResolver.interact(exp, sanctuary.id)
            case _                          => TransitionResult(exp, Nil) // Silently blocked
        else
          val (revealedRoom, revealLog, revealEvents) =
            interactionResolver.revealSecretDoors(exp.dungeon.currentRoom, newX, newY)
          val updatedDungeon =
            exp.dungeon.copy(rooms = exp.dungeon.rooms.updated(revealedRoom.id, revealedRoom))
          TransitionResult(exp.copy(dungeon = updatedDungeon, playerX = newX, playerY = newY),
                           revealLog,
                           events = revealEvents
          )

      case (exp: ExplorationState, Interact(targetId)) =>
        interactionResolver.interact(exp, targetId)

      case (exp: ExplorationState, EquipChoice(targetSlot)) =>
        val (next, log, events) = EquipmentResolver.resolveChoice(exp, targetSlot, setDefs)
        TransitionResult(next, log, events = events)

      case (exp: ExplorationState, RewardChoice(itemId)) =>
        val (next, log, events) = RewardChoiceResolver.resolve(exp, itemId, setDefs)
        TransitionResult(next, log, events = events)

      // -- Combat -----------------------------------------------------------

      case (combat: CombatState, action: CombatAction) =>
        val (next, log, events) = resolver.resolve(combat, action)
        TransitionResult(next, log, events = events)

      // -- Invalid combinations ----------------------------------------------

      case (currentState, invalidAction) =>
        lift(
          (currentState,
           List(
             s"Action ${invalidAction.getClass.getSimpleName} is not valid in state ${currentState.getClass.getSimpleName}."
           )
          )
        )

object StateMachine:

  /** Dev tooling only (see `loadDebugRoom`): a freshly-converted room has no natural "spawn from
    * this door" context the way a real dungeon transition does, so this just picks the room's own
    * center, falling back to a tile in the room's largest connected walkable region if the center
    * itself is a wall. Falling back to the first walkable cell found by raster order (the original
    * approach) could land the player in a small, disconnected floor pocket - a stray tile with no
    * path to the room's real interior - which reads as "spawned outside the room". Picking from the
    * largest connected region, closest to center, avoids that regardless of the room's shape. */
  private def findAnyWalkableTile(room: Room): (Int, Int) =
    val center = (room.width / 2, room.height / 2)
    if room.isWalkable(center._1, center._2) then center
    else largestConnectedWalkableRegion(room).minByOption(manhattanDistance(_, center)).getOrElse((0, 0))

  /** Every walkable tile in `room`, grouped into 4-directionally-connected regions (BFS), returning
    * the largest one. */
  private def largestConnectedWalkableRegion(room: Room): List[(Int, Int)] =
    val visited    = scala.collection.mutable.Set.empty[(Int, Int)]
    val components = scala.collection.mutable.ListBuffer.empty[List[(Int, Int)]]
    for
      y <- 0 until room.height
      x <- 0 until room.width
      if room.isWalkable(x, y) && !visited.contains((x, y))
    do
      val queue     = scala.collection.mutable.Queue((x, y))
      val component = scala.collection.mutable.ListBuffer.empty[(Int, Int)]
      visited += ((x, y))
      while queue.nonEmpty do
        val (cx, cy) = queue.dequeue()
        component += ((cx, cy))
        for
          neighbor @ (nx, ny) <- List((cx + 1, cy), (cx - 1, cy), (cx, cy + 1), (cx, cy - 1))
          if room.isWalkable(nx, ny) && !visited.contains(neighbor)
        do
          visited += neighbor
          queue.enqueue(neighbor)
      components += component.toList
    components.maxByOption(_.size).getOrElse(Nil)

  private def manhattanDistance(a: (Int, Int), b: (Int, Int)): Int =
    math.abs(a._1 - b._1) + math.abs(a._2 - b._2)
