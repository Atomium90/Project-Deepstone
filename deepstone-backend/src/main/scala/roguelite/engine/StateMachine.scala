package roguelite.engine

import cats.effect.IO
import roguelite.game.{
  Chest,
  ClassDef,
  Combat,
  CombatResolver,
  Door,
  Dungeon,
  Enemy,
  EnemyInstance,
  EnemyStats,
  Item,
  LootTable,
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
  * @param dungeon
  *   The dungeon to enter when a new run starts.
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
  *   Random instance for chest loot rolls. Inject a seeded one for deterministic tests.
  */
class StateMachine(dungeon: Dungeon,
                   enemyStats: Map[String, EnemyStats],
                   itemDefs: Map[String, Item],
                   classDefs: Map[ClassId, ClassDef],
                   upgradeDefs: Map[String, UpgradeDef],
                   resolver: CombatResolver,
                   rng: Random = Random()
):
  def transition(state: GameState, action: PlayerAction): IO[(GameState, List[String])] =
    IO.pure(applyActionPure(state, action))

  /** Pure (non-IO) version used internally and by GameSession. */
  def applyActionPure(state: GameState, action: PlayerAction): (GameState, List[String]) =
    (state, action) match

      // -- Hub --------------------------------------------------------------

      case (hub: HubState, HubAction(HubActionType.StartRun, Some(classId), _)) =>
        val lockedBehind = upgradeDefs.values.find {
          u => u.effect == UpgradeEffect.UnlockClass(classId) && !hub.meta.isUnlocked(u.id)
        }

        (lockedBehind, classDefs.get(classId)) match {
          case (Some(u), _) =>
            (hub, List(s"${u.label} required — purchase it in the hub to unlock this class."))

          case (None, None) => (hub, List(s"Unknown class '$classId'. Cannot start run."))

          case (None, Some(classDef)) =>
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

            val nextState = ExplorationState(playerWithKit, dungeon, playerX = 1, playerY = 1)
            (nextState, List(s"A new run begins. Good luck, $classId."))
        }

      case (hub: HubState, HubAction(HubActionType.BuyUpgrade, Some(classId), _)) =>
        // BuyUpgrade is intercepted by GameSession (needs DB access); reject here as a safety net
        (hub, List("Upgrade purchases must be routed through GameSession."))

      // Return to hub after death: GameSession enriches the HubState with real meta
      case (gameOver: GameOverState, HubAction(HubActionType.ReturnToHub, _, _)) =>
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
        else (exp.copy(playerX = newX, playerY = newY), Nil)

      case (exp: ExplorationState, Interact(targetId)) =>
        exp.dungeon.currentRoom.entityById(targetId) match {
          case None =>
            (exp, List(s"No entity found with id '$targetId'."))

          case Some(door: Door) =>
            exp.dungeon.navigateTo(door.targetRoomId) match {
              case Left(err)         => (exp, List(err))
              case Right(newDungeon) =>
                // Place the player at the opposite door's position when entering
                val spawnPoint = findSpawnPoint(newDungeon.currentRoom, door.direction)
                val nextState =
                  exp.copy(dungeon = newDungeon, playerX = spawnPoint._1, playerY = spawnPoint._2)
                (nextState, List(s"You pass through the door heading ${door.direction}."))
            }

          case Some(enemy: Enemy) =>
            enemyStats.get(enemy.typeId) match {
              case None =>
                (exp, List(s"Unknown enemy type '${enemy.typeId}' — cannot start combat."))
              case Some(stats) =>
                val instance = EnemyInstance.fromStats(enemy.id, stats)
                val combat   = Combat(enemy = instance)
                val nextState =
                  CombatState(exp.player, exp.dungeon, exp.playerX, exp.playerY, combat, enemy.id)
                (nextState, List(s"You engage the ${stats.label}!"))
            }

          case Some(chest: Chest) =>
            val updatedRoom = exp.dungeon.currentRoom.removeEntity(chest.id)
            val updatedDungeon =
              exp.dungeon.copy(rooms = exp.dungeon.rooms.updated(updatedRoom.id, updatedRoom))

            LootTable.rollChest(itemDefs, rng) match {
              case None =>
                (exp.copy(dungeon = updatedDungeon), List("You open the chest. It's empty."))
              case Some(item) =>
                exp.player.withItemPickup(item) match {
                  case Left(_) =>
                    (exp.copy(dungeon = updatedDungeon),
                     List(s"You open the chest but your inventory is full — ${item.name} is lost!")
                    )

                  case Right(updatedPlayer) =>
                    (exp.copy(dungeon = updatedDungeon, player = updatedPlayer),
                     List(s"You open the chest and find ${item.name}! (${item.statLine})")
                    )
                }
            }
        }

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

  /** Find a sensible spawn point in the target room when entering through a door.
    *
    * The player arrives at the tile adjacent to the door on the opposite wall. For example,
    * entering through a DOWN door means the player came from below, so they spawn just inside the
    * top of the new room. Falls back to (1,1) if the computed position is not walkable.
    */
  private def findSpawnPoint(room: Room, fromDirection: Direction): (Int, Int) =
    val candidate = fromDirection match {
      case Direction.Down => (room.width / 2, 1) // entered from south → spawn near north
      case Direction.Up =>
        (room.width / 2, room.height - 2) // entered from north → spawn near south
      case Direction.Right => (1, room.height / 2) // entered from east  → spawn near west
      case Direction.Left =>
        (room.width - 2, room.height / 2) // entered from west  → spawn near east
    }

    if room.isWalkable(candidate._1, candidate._2) then candidate else (1, 1)
