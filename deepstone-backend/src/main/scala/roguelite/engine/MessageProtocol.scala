package roguelite.engine

import io.circe.generic.semiauto.{ deriveDecoder, deriveEncoder }
import io.circe.syntax.*
import io.circe.{ Decoder, Encoder }

// -------------------
// Shared enumerations
// -------------------

enum Direction:
  case Up, Down, Left, Right

enum GamePhase:
  case Hub, Exploration, Combat, GameOver

enum CombatActionType:
  case Attack, Ability, Item, Defend

enum HubActionType:
  case StartRun, BuyUpgrade

enum ClassId:
  case Warrior, Archer, Mage

// ---------------------------------------------
// Client → Server: player actions
// ---------------------------------------------

/** Every message the client can send to the server. */
sealed trait PlayerAction

/** Move the player one tile in the given direction. Only valid during exploration. */
case class Move(direction: Direction) extends PlayerAction

/** Interact with an entity in the current room (enemy, chest, door). */
case class Interact(targetId: String) extends PlayerAction

/** Perform a combat action during the player's turn. */
case class CombatAction(
    action: CombatActionType,
    abilityId: Option[String] = None,
    itemId: Option[String] = None
) extends PlayerAction

/** Perform a hub action (start a run, buy an upgrade). */
case class HubAction(
    action: HubActionType,
    classId: Option[ClassId] = None,
    upgradeId: Option[String] = None
) extends PlayerAction

// ---------------------------------------------
// Server → Client: state views (read-only snapshots)
// ---------------------------------------------

/** Lightweight view of the player sent to the client every update. */
case class PlayerView(
    classId: ClassId,
    hp: Int,
    maxHp: Int,
    resourceCurrent: Int,
    resourceMax: Int,
    level: Int,
    xp: Int,
    metaCurrency: Int
)

/** Describes one entity visible in the room (enemy, chest, door, etc.). */
case class EntityView(
    id: String,
    kind: String, // "enemy" | "chest" | "door"
    x: Int,
    y: Int,
    label: String // display name shown in the UI
)

/** The current room's layout and its entities. */
case class RoomView(
    width: Int,
    height: Int,
    tiles: Vector[Vector[String]], // "floor" | "wall"
    entities: List[EntityView],
    playerX: Int,
    playerY: Int
)

/** Snapshot of the ongoing combat. Null-equivalent: wrapped in Option at the top level. */
case class CombatView(
    enemyId: String,
    enemyLabel: String,
    enemyHp: Int,
    enemyMaxHp: Int,
    isPlayerTurn: Boolean
)

/** Hub data: available upgrades and their unlock status. */
case class UpgradeView(id: String, cost: Int, unlocked: Boolean)
case class HubView(upgrades: List[UpgradeView])

/** Full game state snapshot sent by the server after every action. */
case class StateUpdate(
    phase: GamePhase,
    player: PlayerView,
    room: Option[RoomView] = None,
    combat: Option[CombatView] = None,
    hub: Option[HubView],
    log: List[String] = Nil
)

// ---------------------------------------------
// Circe codecs
// ---------------------------------------------

object MessageProtocol:

  // Enums: encoded as plain strings
  given Encoder[Direction] = Encoder[String].contramap(_.toString.toUpperCase)
  given Decoder[Direction] = Decoder[String].emap(
    s => Direction.values.find(_.toString.toUpperCase == s).toRight(s"Unknown direction: $s")
  )

  given Encoder[GamePhase] = Encoder[String].contramap(_.toString.toUpperCase)
  given Decoder[GamePhase] = Decoder[String].emap(
    s => GamePhase.values.find(_.toString.toUpperCase == s).toRight(s"Unknown phase :$s")
  )

  given Encoder[CombatActionType] = Encoder[String].contramap(_.toString.toUpperCase)
  given Decoder[CombatActionType] = Decoder[String].emap(
    s =>
      CombatActionType.values
        .find(_.toString.toUpperCase == s)
        .toRight(s"Unknown combat action: $s")
  )

  given Encoder[HubActionType] = Encoder[String].contramap(_.toString.toUpperCase)
  given Decoder[HubActionType] = Decoder[String].emap(
    s => HubActionType.values.find(_.toString.toUpperCase == s).toRight(s"Unknown hub action: $s")
  )

  given Encoder[ClassId] = Encoder[String].contramap(_.toString.toLowerCase)
  given Decoder[ClassId] = Decoder[String].emap(
    s => ClassId.values.find(_.toString.toLowerCase == s).toRight(s"Unknown class: $s")
  )

  // Actions (client → server)
  given Encoder[Move]         = deriveEncoder
  given Decoder[Move]         = deriveDecoder
  given Encoder[Interact]     = deriveEncoder
  given Decoder[Interact]     = deriveDecoder
  given Encoder[CombatAction] = deriveEncoder
  given Decoder[CombatAction] = deriveDecoder
  given Encoder[HubAction]    = deriveEncoder
  given Decoder[HubAction]    = deriveDecoder

  /** Decode a raw JSON string into a PlayerAction. The JSON must include a "type" discriminator
    * field: { "type": "MOVE", "direction": "UP" }
    */
  def decodeAction(json: String): Either[String, PlayerAction] =
    import io.circe.parser.parse
    for
      cursor     <- parse(json).left.map(_.message)
      actionType <- cursor.hcursor.get[String]("type").left.map(_.message)
      action <- actionType.toUpperCase match {
        case "MOVE"          => cursor.as[Move].left.map(_.message)
        case "INTERACT"      => cursor.as[Interact].left.map(_.message)
        case "COMBAT_ACTION" => cursor.as[CombatAction].left.map(_.message)
        case other           => Left(s"Unknown action type: $other")
      }
    yield action

  // Views (server → client)
  given Encoder[PlayerView]  = deriveEncoder
  given Encoder[EntityView]  = deriveEncoder
  given Encoder[RoomView]    = deriveEncoder
  given Encoder[CombatView]  = deriveEncoder
  given Encoder[UpgradeView] = deriveEncoder
  given Encoder[HubView]     = deriveEncoder
  given Encoder[StateUpdate] = deriveEncoder

  /** Serialize a StateUpdate to a JSON string to be sent over the WebSocket. */
  def encodeUpdate(update: StateUpdate): String = update.asJson.noSpaces
