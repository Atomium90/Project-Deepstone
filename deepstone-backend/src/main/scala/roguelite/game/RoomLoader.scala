package roguelite.game

import cats.syntax.either.*
import io.circe.{ Decoder, HCursor }
import io.circe.parser.decode
import roguelite.engine.Direction

/** Loads and parses room definitions from the JSON data file at startup.
  *
  * Room data lives in `resources/data/rooms.json` and is treated as immutable reference data: it
  * is read once and held in memory for the lifetime of the server. Resource reading and error
  * wrapping are handled by [[JsonResourceLoader]].
  */
object RoomLoader extends JsonResourceLoader[Room, String]:

  protected val resourcePath = "data/rooms.json"

  protected def keyOf(entry: Room): String = entry.id

  // ---------------------------
  // JSON parsing
  // ---------------------------

  protected def parseEntries(json: String): Either[String, List[Room]] =
    decode[List[RoomJson]](json)
      .leftMap(_.getMessage)
      .flatMap(
        rjs => rjs.traverse(toRoom)
      )

  private def toRoom(rj: RoomJson): Either[String, Room] =
    for
      roomType    <- RoomType.fromString(rj.`type`)
      tiles       <- parseTiles(rj.tiles)
      floorSprite <- parseSpriteGrid(rj.floorSprites, field = "floorSprites", width = rj.width, height = rj.height)
      wallSprite  <- parseSpriteGrid(rj.wallSprites, field = "wallSprites", width = rj.width, height = rj.height)
      decoration  <- parseSpriteGrid(rj.decorations, field = "decorations", width = rj.width, height = rj.height)
      entities    <- rj.entities.traverse(toEntity)
    yield Room(
      id = rj.id,
      roomType = roomType,
      width = rj.width,
      height = rj.height,
      tiles = tiles,
      entities = entities,
      theme = rj.theme.getOrElse("dungeon"),
      floorSprite = floorSprite,
      wallSprite = wallSprite,
      decoration = decoration
    )

  private def parseTiles(raw: List[List[String]]): Either[String, Vector[Vector[Tile]]] =
    raw
      .traverse(
        row => row.traverse(Tile.fromString)
      )
      .map(
        rows => rows.map(_.toVector).toVector
      )

  /** Shared by `floorSprites`, `wallSprites`, and `decorations` - each is a grid the same shape
    * as `tiles`, one optional sprite key per cell. Absent entirely (every room authored before
    * these fields existed) resolves to an all-`None` grid - no overrides, same visual result as
    * today. When present, its shape must match `tiles` exactly, since [[Room]]'s own invariant
    * assumes every one of its grids is addressed by the same (x, y).
    */
  private def parseSpriteGrid(
      raw: Option[List[List[Option[String]]]],
      field: String,
      width: Int,
      height: Int
  ): Either[String, Vector[Vector[Option[String]]]] =
    raw match
      case None => Right(Vector.fill(height)(Vector.fill(width)(None)))
      case Some(rows) =>
        if rows.length != height || rows.exists(_.length != width)
        then Left(s"'$field' must be exactly ${width}x$height (one entry per tile), matching 'tiles'.")
        else Right(rows.map(_.toVector).toVector)

  private def toEntity(ej: EntityJson): Either[String, Entity] =
    ej.kind.toLowerCase match
      case "enemy" =>
        for
          label  <- ej.label.toRight("Enemy entity is missing 'label' field")
          typeId <- ej.typeId.toRight("Enemy entity is missing 'typeId' field")
        yield Enemy(id = ej.id, x = ej.x, y = ej.y, typeId = typeId, label = label)

      case "chest" =>
        Right(Chest(id = ej.id, x = ej.x, y = ej.y, trapped = ej.trapped.getOrElse(false)))

      case "door" =>
        for
          dirStr    <- ej.direction.toRight("Door entity is missing 'direction' field")
          direction <- parseDirection(dirStr)
          roleStr   <- ej.role.toRight("Door entity is missing 'role' field")
          role      <- ConnectorRole.fromString(roleStr)
          doorKind  <- parseDoorKind(ej.doorKind.getOrElse("normal"))
        yield
          // `branch` distinguishes multiple doors sharing the same role in one room - a Fork
          // room's two "next" exits (see RoomType.Fork). None for every other door authored so far.
          val link = ej.targetRoomId match {
            case Some(roomId) => DoorLink.Resolved(role, branch = ej.branch, roomId = roomId)
            case None         => DoorLink.Unresolved(role, branch = ej.branch)
          }
          Door(id = ej.id,
               x = ej.x,
               y = ej.y,
               direction = direction,
               link = link,
               doorKind = doorKind,
               revealed = ej.revealed.getOrElse(doorKind != DoorKind.Secret)
          )

      case "locked_door" =>
        for
          dirStr       <- ej.direction.toRight("LockedDoor entity is missing 'direction' field")
          direction    <- parseDirection(dirStr)
          targetRoomId <- ej.targetRoomId.toRight("LockedDoor entity is missing 'targetRoomId' field")
        yield LockedDoor(id = ej.id,
                          x = ej.x,
                          y = ej.y,
                          direction = direction,
                          targetRoomId = targetRoomId,
                          doorTag = ej.doorTag
        )

      case "npc" =>
        ej.name.toRight("Npc entity is missing 'name' field").map(
          name => Npc(id = ej.id, x = ej.x, y = ej.y, name = name)
        )

      case "sanctuary" =>
        Right(Sanctuary(id = ej.id, x = ej.x, y = ej.y))

      case other =>
        Left(s"Unknown entity kind: '$other'")

  private def parseDirection(s: String): Either[String, Direction] =
    s.toUpperCase match {
      case "UP"    => Right(Direction.Up)
      case "DOWN"  => Right(Direction.Down)
      case "LEFT"  => Right(Direction.Left)
      case "RIGHT" => Right(Direction.Right)
      case other   => Left(s"Unknown direction: '$other'")
    }

  private def parseDoorKind(s: String): Either[String, DoorKind] =
    s.toLowerCase match {
      case "normal"  => Right(DoorKind.Normal)
      case "trapped" => Right(DoorKind.Trapped)
      case "secret"  => Right(DoorKind.Secret)
      case other     => Left(s"Unknown door kind: '$other'")
    }

  // --------------------------
  // Internal JSON DTOs
  // --------------------------

  private case class EntityJson(
      kind: String,
      id: String,
      x: Int,
      y: Int,
      typeId: Option[String] = None,
      label: Option[String] = None,
      direction: Option[String] = None,
      role: Option[String] = None,
      branch: Option[String] = None,
      targetRoomId: Option[String] = None,
      trapped: Option[Boolean] = None,
      doorKind: Option[String] = None,
      revealed: Option[Boolean] = None,
      doorTag: Option[String] = None,
      name: Option[String] = None
  )

  private case class RoomJson(
      id: String,
      `type`: String,
      width: Int,
      height: Int,
      tiles: List[List[String]],
      entities: List[EntityJson],
      theme: Option[String] = None,
      floorSprites: Option[List[List[Option[String]]]] = None,
      wallSprites: Option[List[List[Option[String]]]] = None,
      decorations: Option[List[List[Option[String]]]] = None
  )

  // Circe decoders
  private given Decoder[EntityJson] = Decoder.instance:
    (c: HCursor) =>
      for
        kind         <- c.get[String]("kind")
        id           <- c.get[String]("id")
        x            <- c.get[Int]("x")
        y            <- c.get[Int]("y")
        typeId       <- c.get[Option[String]]("typeId")
        label        <- c.get[Option[String]]("label")
        direction    <- c.get[Option[String]]("direction")
        role         <- c.get[Option[String]]("role")
        branch       <- c.get[Option[String]]("branch")
        targetRoomId <- c.get[Option[String]]("targetRoomId")
        trapped      <- c.get[Option[Boolean]]("trapped")
        doorKind     <- c.get[Option[String]]("doorKind")
        revealed     <- c.get[Option[Boolean]]("revealed")
        doorTag      <- c.get[Option[String]]("doorTag")
        name         <- c.get[Option[String]]("name")
      yield EntityJson(kind, id, x, y, typeId, label, direction, role, branch, targetRoomId, trapped, doorKind, revealed, doorTag, name)

  private given Decoder[RoomJson] = Decoder.instance:
    (c: HCursor) =>
      for
        id           <- c.get[String]("id")
        roomType     <- c.get[String]("type")
        width        <- c.get[Int]("width")
        height       <- c.get[Int]("height")
        tiles        <- c.get[List[List[String]]]("tiles")
        entities     <- c.get[List[EntityJson]]("entities")
        theme        <- c.get[Option[String]]("theme")
        floorSprites <- c.get[Option[List[List[Option[String]]]]]("floorSprites")
        wallSprites  <- c.get[Option[List[List[Option[String]]]]]("wallSprites")
        decorations  <- c.get[Option[List[List[Option[String]]]]]("decorations")
      yield RoomJson(id, roomType, width, height, tiles, entities, theme, floorSprites, wallSprites, decorations)
