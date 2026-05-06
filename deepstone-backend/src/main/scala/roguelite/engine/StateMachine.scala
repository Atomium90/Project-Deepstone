package roguelite.engine

import cats.effect.IO
import roguelite.game.{ Chest, Door, Dungeon, Enemy, Room }
import roguelite.game.{
  Chest,
  Combat,
  Door,
  Dungeon,
  Enemy,
  Room
}

// ---------------------------------------------
// Internal game states (server-side)
//
// These are richer than the client-facing views.
// The views in MessageProtocol are read-only projections of these.
// ---------------------------------------------

/** Full player data as stored on the server. */
case class Player(
    classId: ClassId,
    hp: Int,
    maxHp: Int,
    resourceCurrent: Int,
    resourceMax: Int,
    level: Int,
    xp: Int,
    metaCurrency: Int
):
  def toView: PlayerView = PlayerView(
    classId = classId,
    hp = hp,
    maxHp = maxHp,
    resourceCurrent = resourceCurrent,
    resourceMax = resourceMax,
    level = level,
    xp = xp,
    metaCurrency = metaCurrency
  )

  def isAlive: Boolean = hp > 0

object Player:
  def startingPlayer(classId: ClassId): Player = classId match {
    case ClassId.Warrior =>
      Player(ClassId.Warrior,
             hp = 120,
             maxHp = 120,
             resourceCurrent = 0,
             resourceMax = 100,
             level = 1,
             xp = 0,
             metaCurrency = 0
      )
    case ClassId.Archer =>
      Player(ClassId.Archer,
             hp = 90,
             maxHp = 90,
             resourceCurrent = 50,
             resourceMax = 50,
             level = 1,
             xp = 0,
             metaCurrency = 0
      )
    case ClassId.Mage =>
      Player(ClassId.Mage,
             hp = 70,
             maxHp = 70,
             resourceCurrent = 80,
             resourceMax = 80,
             level = 1,
             xp = 0,
             metaCurrency = 0
      )
  }

// ---------------------------------------------
// Game states (one per game phase)
// ---------------------------------------------

/** The four possible states the server-side game can be in. */
sealed trait GameState:
  def player: Player

  /** Project this game state into a StateUpdate for the client. */
  def toStateUpdate(log: List[String] = Nil): StateUpdate

case class HubState(player: Player) extends GameState:
  def toStateUpdate(log: List[String] = Nil): StateUpdate =
    StateUpdate(
      phase = GamePhase.Hub,
      player = player.toView,
      hub = Some(HubView(upgrades = Nil)),
      log = log
    )

case class ExplorationState(player: Player, dungeon: Dungeon, playerX: Int, playerY: Int)
    extends GameState:
  def toStateUpdate(log: List[String] = Nil): StateUpdate =
    StateUpdate(phase = GamePhase.Exploration,
                player = player.toView,
                room = Some(dungeon.currentRoom.toView(playerX, playerY)),
                log = log
    )

/** Active combat state.
  *
  * @param combat
  *   Runtime state of the current fight.
  * @param enemyEntityId
  *   Id of the [[Enemy]] entity in the room, used to remove it after a victorious combat.
  */
case class CombatState(player: Player,
                       dungeon: Dungeon,
                       playerX: Int,
                       playerY: Int,
                       combat: Combat,
                       enemyEntityId: String
) extends GameState:
  def toStateUpdate(log: List[String] = Nil): StateUpdate =
    StateUpdate(
      phase = GamePhase.Combat,
      player = player.toView,
      room = Some(dungeon.currentRoom.toView(playerX, playerY)),
      combat = Some(
        CombatView(enemyId = combat.enemy.typeId,
                   enemyLabel = combat.enemy.label,
                   enemyHp = combat.enemy.hp,
                   enemyMaxHp = combat.enemy.maxHp,
                   isPlayerTurn = combat.isPlayerTurn
        )
      ),
      log = log
    )

case class GameOverState(player: Player) extends GameState:
  def toStateUpdate(log: List[String]): StateUpdate =
    StateUpdate(phase = GamePhase.GameOver, player = player.toView, log = log)

// ---------------------------------------------
// State machine
// ---------------------------------------------

/** Processes player actions and produces new game states.
  *
  * Each `transition` call is the single point where game rules are enforced. Illegal action/state
  * combinations are ignored with a log message rather than crashing, so the client always gets a
  * valid response. A [[Dungeon]] must be provided at construction time so the state machine can
  * build an [[ExplorationState]] when a run starts.
  *
  * @param dungeon
  *   The dungeon to use when starting a new run.
  *
  * Returns the new state and a list of log lines describing what happened.
  */
class StateMachine(dungeon: Dungeon):
  def transition(state: GameState, action: PlayerAction): IO[(GameState, List[String])] =
    IO.pure(applyActionPure(state, action))

  /** Pure (non-IO) version used internally and by GameSession. */
  def applyActionPure(state: GameState, action: PlayerAction): (GameState, List[String]) =
    (state, action) match

      // -- Hub --------------------------------------------------------------

      case (hub: HubState, HubAction(HubActionType.StartRun, Some(classId), _)) =>
        val player    = Player.startingPlayer(classId)
        val nextState = ExplorationState(player, dungeon, playerX = 1, playerY = 1)
        (nextState, List(s"A new run begins. Good luck, $classId."))

      case (hub: HubState, HubAction(HubActionType.BuyUpgrade, Some(classId), _)) =>
        // Upgrade logic will be added later
        (hub, List(s"Upgrades are not yet available."))

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
        val room = exp.dungeon.currentRoom

        if !room.isWalkable(newX, newY)
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
                val newRoom    = newDungeon.currentRoom
                val spawnPoint = findSpawnPoint(newRoom, door.direction)
                val nextState =
                  exp.copy(dungeon = newDungeon, playerX = spawnPoint._1, playerY = spawnPoint._2)
                (nextState, List(s"You pass through the door heading ${door.direction}."))
            }

          case Some(enemy: Enemy) =>
            val nextState = CombatState(exp.player, exp.dungeon, exp.playerX, exp.playerY)
            (nextState, List(s"You engage the ${enemy.label}!"))

          case Some(chest: Chest) =>
            // Chest opening will be implemented later
            val updatedRoom = exp.dungeon.currentRoom.removeEntity(chest.id)
            val updatedDungeon =
              exp.dungeon.copy(rooms = exp.dungeon.rooms.updated(updatedRoom.id, updatedRoom))
            (exp.copy(dungeon = updatedDungeon), List("You open the chest."))
        }

      // -- Combat -----------------------------------------------------------

      case (combat: CombatState, _: CombatAction) =>
        // Combat will be implemented later
        (combat, List(s"Combat resolutino is not yet implemented."))

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
