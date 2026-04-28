package roguelite.engine

import cats.effect.IO

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
             resourceCurrent = 0,
             resourceMax = 50,
             level = 1,
             xp = 0,
             metaCurrency = 0
      )
    case ClassId.Mage =>
      Player(ClassId.Mage,
             hp = 70,
             maxHp = 70,
             resourceCurrent = 0,
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
  def toStateUpdate(log: List[String]): StateUpdate =
    StateUpdate(
      phase = GamePhase.Hub,
      player = player.toView,
      hub = Some(HubView(upgrades = Nil)),
      log = log
    )

/** Placeholder room */
case class ExplorationState(player: Player, playerX: Int = 1, playerY: Int = 1) extends GameState:
  def toStateUpdate(log: List[String]): StateUpdate =
    StateUpdate(phase = GamePhase.Exploration,
                player = player.toView,
                room = Some(ExplorationState.emptyRoom(playerX, playerY)),
                log = log
    )

object ExplorationState:
  /** Temporary 10×8 empty room used until the dungeon system is implemented. */
  def emptyRoom(playerX: Int, playerY: Int): RoomView =
    val width  = 10
    val height = 8
    val tiles = Vector.tabulate(height, width):
      (row, col) =>
        if row == 0 || row == height - 1 || col == 0 || col == width - 1
        then "wall"
        else "floor"
    RoomView(width = width,
             height = height,
             tiles = tiles,
             entities = Nil,
             playerX = playerX,
             playerY = playerY
    )

case class CombatState(player: Player, playerX: Int, playerY: Int) extends GameState:
  def toStateUpdate(log: List[String]): StateUpdate =
    StateUpdate(phase = GamePhase.Combat, player = player.toView, log = log)

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
  * valid response.
  *
  * Returns the new state and a list of log lines describing what happened.
  */
class StateMachine:
  def transition(state: GameState, action: PlayerAction): IO[(GameState, List[String])] =
    IO.pure(applyActionPure(state, action))

  /** Pure (non-IO) version used internally and by GameSession. */
  def applyActionPure(state: GameState, action: PlayerAction): (GameState, List[String]) =
    (state, action) match

      // -- Hub --------------------------------------------------------------

      case (hub: HubState, HubAction(HubActionType.StartRun, Some(classId), _)) =>
        val player    = Player.startingPlayer(classId)
        val nextState = ExplorationState(player)
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
        val newX  = (exp.playerX + dx).max(1).min(8)
        val newY  = (exp.playerY + dy).max(1).min(6)
        val moved = exp.copy(playerX = newX, playerY = newY)
        (moved, Nil)

      case (exp: ExplorationState, Interact(targetId)) =>
        // Entity interaction will be added later
        (exp, List(s"Nothing to interact with yet."))

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
