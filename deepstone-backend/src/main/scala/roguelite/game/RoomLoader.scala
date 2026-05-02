package roguelite.game

import cats.effect.IO
import cats.syntax.either.*
import io.circe.{ Decoder, HCursor }
import io.circe.parser.decode
import roguelite.engine.Direction

import scala.io.Source

/** Loads and parses room definitions from the JSON data file at startup.
  *
  * Room data lives in `resources/data/rooms.json` and is treated as immutable reference data — it
  * is read once and held in memory for the lifetime of the server.
  *
  * The loader is intentionally strict: a malformed rooms.json is a programming error, so it raises
  * an IO failure rather than silently skipping bad entries.
  */
object RoomLoader:

  private val RoomsResourcePath = "data/rooms.json"

  /** Load all rooms from the bundled JSON resource.
    *
    * @return
    *   A map from room id to parsed [[Room]], wrapped in IO.
    */
  def loadAll(): IO[Map[String, Room]] =
    for
      json <- readResource(RoomsResourcePath)
      rooms <- IO.fromEither(
        parseRooms(json).leftMap(
          err => RuntimeException(s"Failed to parse rooms.json: $err")
        )
      )
    yield rooms
      .map(
        r => r.id -> r
      )
      .toMap

  // ---------------------------
  // JSON parsing
  // ---------------------------

  private def parseRooms(json: String): Either[String, List[Room]] =
    decode[List[RoomJson]](json)
      .leftMap(_.getMessage)
      .flatMap(
        rjs => rjs.traverse(toRoom)
      )

  private def toRoom(rj: RoomJson): Either[String, Room] =
    for
      roomType <- RoomType.fromString(rj.`type`)
      tiles    <- parseTiles(rj.tiles)
      entities <- rj.entities.traverse(toEntity)
    yield Room(
      id = rj.id,
      roomType = roomType,
      width = rj.width,
      height = rj.height,
      tiles = tiles,
      entities = entities
    )

  private def parseTiles(raw: List[List[String]]): Either[String, Vector[Vector[Tile]]] =
    raw
      .traverse(
        row => row.traverse(Tile.fromString)
      )
      .map(
        rows => rows.map(_.toVector).toVector
      )

  private def toEntity(ej: EntityJson): Either[String, Entity] =
    ej.kind.toLowerCase match
      case "enemy" =>
        ej.label
          .toRight("Enemy entity is missing 'label' field")
          .map:
            label => Enemy(id = ej.id, x = ej.x, y = ej.y, label = label)

      case "chest" =>
        Right(Chest(id = ej.id, x = ej.x, y = ej.y))

      case "door" =>
        for
          dirStr       <- ej.direction.toRight("Door entity is missing 'direction' field")
          direction    <- parseDirection(dirStr)
          targetRoomId <- ej.targetRoomId.toRight("Door entity is missing 'targetRoomId' field")
        yield Door(id = ej.id,
                   x = ej.x,
                   y = ej.y,
                   direction = direction,
                   targetRoomId = targetRoomId
        )

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

  private def readResource(path: String): IO[String] =
    IO.blocking:
      val stream = getClass.getClassLoader.getResourceAsStream(path)
      if stream == null then throw RuntimeException(s"Resource not found on classpath: $path")
      Source.fromInputStream(stream, "UTF-8").mkString

  // --------------------------
  // Internal JSON DTOs
  // --------------------------

  private case class EntityJson(
      kind: String,
      id: String,
      x: Int,
      y: Int,
      label: Option[String] = None,
      direction: Option[String] = None,
      targetRoomId: Option[String] = None
  )

  private case class RoomJson(
      id: String,
      `type`: String,
      width: Int,
      height: Int,
      tiles: List[List[String]],
      entities: List[EntityJson]
  )

  // Circe decoders
  private given Decoder[EntityJson] = Decoder.instance:
    (c: HCursor) =>
      for
        kind         <- c.get[String]("kind")
        id           <- c.get[String]("id")
        x            <- c.get[Int]("x")
        y            <- c.get[Int]("y")
        label        <- c.get[Option[String]]("label")
        direction    <- c.get[Option[String]]("direction")
        targetRoomId <- c.get[Option[String]]("targetRoomId")
      yield EntityJson(kind, id, x, y, label, direction, targetRoomId)

  private given Decoder[RoomJson] = Decoder.instance:
    (c: HCursor) =>
      for
        id       <- c.get[String]("id")
        roomType <- c.get[String]("type")
        width    <- c.get[Int]("width")
        height   <- c.get[Int]("height")
        tiles    <- c.get[List[List[String]]]("tiles")
        entities <- c.get[List[EntityJson]]("entities")
      yield RoomJson(id, roomType, width, height, tiles, entities)

  // Cats traverse helper for Either
  extension [A, B](list: List[A])
    private def traverse(f: A => Either[String, B]): Either[String, List[B]] =
      list.foldRight(Right(Nil): Either[String, List[B]]):
        (a, acc) => for b <- f(a); rest <- acc yield b :: rest
