package roguelite.game

/** A dungeon is a set of rooms connected by doors.
  *
  * The dungeon tracks which room the player is currently in. Navigation happens by interacting with
  * a [[Door]] entity — the door's `targetRoomId` is looked up in `rooms` to produce a new `Dungeon`
  * with a different `currentRoomId`.
  *
  * @param rooms
  *   All rooms in this dungeon, keyed by room id.
  * @param currentRoomId
  *   The room the player is currently in.
  */
case class Dungeon(
    rooms: Map[String, Room],
    currentRoomId: String
):
  /** The room the player is currently in. Throws if the dungeon is malformed (currentRoomId not
    * present in rooms — should never happen at runtime).
    */
  def currentRoom: Room =
    rooms.getOrElse(
      currentRoomId,
      throw IllegalStateException(s"Current room '$currentRoomId' not found in dungeon rooms.")
    )

  /** Attempt to navigate to a different room by id.
    *
    * Returns a new [[Dungeon]] with the updated current room, or an error message if the target
    * room does not exist in this dungeon.
    */
  def navigateTo(targetRoomId: String): Either[String, Dungeon] =
    if rooms.contains(targetRoomId)
    then Right(copy(currentRoomId = targetRoomId))
    else Left(s"Room '$targetRoomId' does not exist in this dungeon.")

  /** True if the current room is the boss room. Used to determine when the dungeon has been
    * completed.
    */
  def isAtBoss: Boolean =
    currentRoom.roomType == RoomType.Boss

object Dungeon:
  /** Build a dungeon from a sequence of rooms. The first room in the sequence becomes the starting
    * room.
    *
    * @param rooms
    *   An ordered list of rooms; must be non-empty.
    */
  def fromRooms(rooms: List[Room]): Either[String, Dungeon] =
    rooms match {
      case Nil => Left("Cannot create a dungeon with no rooms.")
      case first :: rest =>
        Right(
          Dungeon(
            rooms = (first :: rest)
              .map(
                r => r.id -> r
              )
              .toMap,
            currentRoomId = first.id
          )
        )
    }
