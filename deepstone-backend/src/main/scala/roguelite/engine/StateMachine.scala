package roguelite.engine

import cats.effect.IO
import roguelite.game.{
  Accessory,
  Chest,
  Combat,
  CombatResolver,
  Door,
  Dungeon,
  Enemy,
  EnemyInstance,
  EnemyStats,
  Inventory,
  Item,
  LootTable,
  Room
}

import scala.util.Random

/** Convert a game-layer Item to the protocol ItemView. Defined at file level so all GameState
  * subtypes (which live in this file) can use it without repeating the mapping.
  */
private def itemToView(item: Item): ItemView =
  ItemView(
    id = item.id,
    typeId = item.typeId,
    name = item.name,
    kind = item.kind,
    rarity = item.rarity.label,
    statLine = item.statLine
  )

private def inventoryToViews(inventory: Inventory): List[ItemView] =
  inventory.items.map(itemToView)

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
    metaCurrency: Int,
    bonusAttack: Int = 0,
    bonusDefense: Int = 0,
    inventory: Inventory = Inventory.empty
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

  /** Add an item to the inventory.
    *
    * Accessories immediately increase `maxHp` (and top up current HP by the same amount) so the
    * client always sees the correct HP cap without needing to sum inventory bonuses client-side.
    *
    * @return
    *   Right(updatedPlayer) on success, Left(error) if the inventory is full.
    */
  def withItemPickup(item: Item): Either[String, Player] =
    inventory
      .addItem(item)
      .map:
        newInventory =>
          item match {
            case acc: Accessory =>
              val newMax = maxHp + acc.hpBonus
              copy(inventory = newInventory, maxHp = newMax, hp = (hp + acc.hpBonus).min(newMax))
            case _ =>
              copy(inventory = newInventory)
          }

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
      inventory = inventoryToViews(player.inventory),
      log = log
    )

case class ExplorationState(player: Player, dungeon: Dungeon, playerX: Int, playerY: Int)
    extends GameState:
  def toStateUpdate(log: List[String] = Nil): StateUpdate =
    StateUpdate(
      phase = GamePhase.Exploration,
      player = player.toView,
      room = Some(dungeon.currentRoom.toView(playerX, playerY)),
      inventory = inventoryToViews(player.inventory),
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
      inventory = inventoryToViews(player.inventory),
      log = log
    )

case class GameOverState(player: Player) extends GameState:
  def toStateUpdate(log: List[String]): StateUpdate =
    StateUpdate(phase = GamePhase.GameOver,
                player = player.toView,
                inventory = inventoryToViews(player.inventory),
                log = log
    )

// ---------------------------------------------
// State machine
// ---------------------------------------------

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
  * @param resolver
  *   Resolves combat turns.
  * @param rng
  *   Random instance for chest loot rolls. Inject a seeded one for deterministic tests.
  */
class StateMachine(dungeon: Dungeon,
                   enemyStats: Map[String, EnemyStats],
                   itemDefs: Map[String, Item],
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
