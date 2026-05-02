package roguelite.game

import roguelite.engine.RoomView

// Tile

/** The two possible tile types in a room. */
enum Tile:
  case Floor, Wall

  /** Serialize to the string format expected by the client protocol. */
  def toProtocolString: String = this match {
    case Tile.Floor => "floor"
    case Tile.Wall  => "wall"
  }

object Tile:
  /** Parse a tile from the string format used in rooms.json. */
  def fromString(s: String): Either[String, Tile] = s.toLowerCase match {
    case "floor" => Right(Tile.Floor)
    case "wall"  => Right(Tile.Wall)
    case other   => Left(s"Unknown tile type: '$other'")
  }

// Room type

/** Classification of a room that drives gameplay rules (loot tables, music, etc.). */
enum RoomType:
  case Combat, Loot, Rest, Boss

object RoomType:
  def fromString(s: String): Either[String, RoomType] = s.toLowerCase match {
    case "combat" => Right(RoomType.Combat)
    case "loot"   => Right(RoomType.Loot)
    case "rest"   => Right(RoomType.Rest)
    case "boss"   => Right(RoomType.Boss)
    case other    => Left(s"Unknown room type: '$other'")
  }

// Room

/** Authoritative server-side representation of a dungeon room.
  *
  * Tiles are stored as a row-major 2D vector: `tiles(row)(col)`. This is the internal model; the
  * client only ever sees a [[RoomView]].
  *
  * @param id
  *   Unique identifier matching the rooms.json entry.
  * @param roomType
  *   Determines loot, enemy density, and special behavior.
  * @param width
  *   Number of tiles horizontally.
  * @param height
  *   Number of tiles vertically.
  * @param tiles
  *   Row-major tile grid. Invariant: tiles.length == height, tiles(n).length == width for all n.
  * @param entities
  *   All interactive objects currently present in the room.
  */
case class Room(
    id: String,
    roomType: RoomType,
    width: Int,
    height: Int,
    tiles: Vector[Vector[Tile]],
    entities: List[Entity]
):
  /** Check whether a tile coordinate is within the room bounds. */
  def inBounds(x: Int, y: Int): Boolean =
    x >= 0 && x < width && y >= 0 && y < height

  /** Return the tile at (x, y), or Wall if out of bounds. */
  def tileAt(x: Int, y: Int): Tile =
    if inBounds(x, y) then tiles(y)(x) else Tile.Wall

  /** Return true if the given tile is walkable (floor and no entity blocking it). */
  def isWalkable(x: Int, y: Int): Boolean =
    tileAt(x, y) == Tile.Floor

  /** Find an entity by id. */
  def entityById(id: String): Option[Entity] =
    entities.find(_.id == id)

  /** Find an entity occupying the given tile position. */
  def entityAt(x: Int, y: Int): Option[Entity] =
    entities.find(
      e => e.x == x && e.y == y
    )

  /** Remove an entity from the room (e.g. after a chest is looted). */
  def removeEntity(id: String): Room =
    copy(entities = entities.filterNot(_.id == id))

  /** Project to the client-facing view.
    *
    * @param playerX
    *   Current player tile X (included in the view so the client can position the player without a
    *   separate message).
    * @param playerY
    *   Current player tile Y.
    */
  def toView(playerX: Int, playerY: Int): RoomView =
    RoomView(
      width = width,
      height = height,
      tiles = tiles.map(
        row => row.map(_.toProtocolString)
      ),
      entities = entities.map(_.toView),
      playerX = playerX,
      playerY = playerY
    )
