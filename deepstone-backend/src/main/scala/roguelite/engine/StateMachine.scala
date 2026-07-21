package roguelite.engine

import cats.effect.IO
import roguelite.game.{
  ClassDef,
  CombatResolver,
  DungeonBuilder,
  EnemyStats,
  InteractionResolver,
  Item,
  Room
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
  */
class StateMachine(roomPool: Map[String, Room],
                   enemyStats: Map[String, EnemyStats],
                   itemDefs: Map[String, Item],
                   classDefs: Map[ClassId, ClassDef],
                   upgradeDefs: Map[String, UpgradeDef],
                   resolver: CombatResolver,
                   rng: Random = Random()
):
  private val interactionResolver = InteractionResolver(enemyStats, itemDefs, rng)

  def transition(state: GameState, action: PlayerAction): IO[(GameState, List[String])] =
    IO.pure(applyActionPure(state, action))

  /** Pure (non-IO) version used internally and by GameSession. */
  def applyActionPure(state: GameState, action: PlayerAction): (GameState, List[String]) =
    (state, action) match

      // -- Hub --------------------------------------------------------------

      case (hub: HubState, HubAction(HubActionType.StartRun, Some(classId), _, difficultyOpt)) =>
        val lockedBehind = upgradeDefs.values.find {
          u => u.effect == UpgradeEffect.UnlockClass(classId) && !hub.meta.isUnlocked(u.id)
        }

        (lockedBehind, classDefs.get(classId)) match {
          case (Some(u), _) =>
            (hub, List(s"${u.label} required — purchase it in the hub to unlock this class."))

          case (None, None) => (hub, List(s"Unknown class '$classId'. Cannot start run."))

          case (None, Some(classDef)) =>
            val difficulty = difficultyOpt.getOrElse(Difficulty.Normal)

            DungeonBuilder(roomPool, rng).build(totalRooms = difficulty.totalRooms) match {
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

                // Resolve starting kit: unknown typeIds are skipped, full inventory is not expected
                val playerWithKit = classDef.startingKit.foldLeft(basePlayer):
                  (p, typeId) =>
                    itemDefs.get(typeId) match {
                      case None => p
                      case Some(proto) =>
                        p.withItemPickup(proto.withNewId) match {
                          case Right(updated) => updated
                          case Left(_)        => p
                        }
                    }

                val nextState =
                  ExplorationState(playerWithKit, dungeon, playerX = 1, playerY = 1, difficulty)
                (nextState, List(s"A new run begins. Good luck, $classId."))
            }
        }

      case (hub: HubState, HubAction(HubActionType.BuyUpgrade, Some(classId), _, _)) =>
        // BuyUpgrade is intercepted by GameSession (needs DB access); reject here as a safety net
        (hub, List("Upgrade purchases must be routed through GameSession."))

      // Return to hub after death: GameSession enriches the HubState with real meta
      case (gameOver: GameOverState, HubAction(HubActionType.ReturnToHub, _, _, _)) =>
        // Placeholder — class is re-chosen on next StartRun
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
        (HubState(hubPlayer, upgradeDefs, MetaProgression.empty),
         List("You return to the hub, wiser from your journey.")
        )

      // -- Exploration ------------------------------------------------------

      case (exp: ExplorationState, Move(direction)) =>
        val (dx, dy) = direction match {
          case Direction.Up    => (0, -1)
          case Direction.Down  => (0, 1)
          case Direction.Left  => (-1, 0)
          case Direction.Right => (1, 0)
        }

        val newX = exp.playerX + dx
        val newY = exp.playerY + dy

        if !exp.dungeon.currentRoom.isWalkable(newX, newY)
        then (exp, Nil) // Silently blocked
        else
          val (revealedRoom, revealLog) =
            interactionResolver.revealSecretDoors(exp.dungeon.currentRoom, newX, newY)
          val updatedDungeon =
            exp.dungeon.copy(rooms = exp.dungeon.rooms.updated(revealedRoom.id, revealedRoom))
          (exp.copy(dungeon = updatedDungeon, playerX = newX, playerY = newY), revealLog)

      case (exp: ExplorationState, Interact(targetId)) =>
        interactionResolver.interact(exp, targetId)

      // -- Combat -----------------------------------------------------------

      case (combat: CombatState, action: CombatAction) =>
        resolver.resolve(combat, action)

      // -- Invalid combinations ----------------------------------------------

      case (currentState, invalidAction) =>
        (currentState,
         List(
           s"Action ${invalidAction.getClass.getSimpleName} is not valid in state ${currentState.getClass.getSimpleName}."
         )
        )
