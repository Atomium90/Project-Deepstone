package roguelite.game

import roguelite.engine.Direction

import scala.util.Random

/** Assembles a [[Dungeon]] from a pool of hand-crafted rooms.
 *
 * The builder picks rooms randomly while enforcing a minimal structure:
 * one combat room as the entrance, a mix of combat and loot rooms in the
 * middle, and one boss room as the exit. Door connectivity between rooms
 * is wired automatically — the builder pairs the exit door of one room
 * with the entrance door of the next.
 *
 * @param pool   All available rooms keyed by id.
 * @param rng    Random instance — inject a seeded one for reproducible dungeons.
 */
class DungeonBuilder(pool: Map[String, Room], rng: Random = Random()):

  /** Build a dungeon with the given number of rooms.
   *
   * @param totalRooms Total number of rooms including entrance and boss.
   *                   Must be at least 2 (entrance + boss). Clamped to the
   *                   number of available rooms if the pool is smaller.
   * @return A freshly assembled [[Dungeon]], or an error message if the
   *         pool does not contain the required room types.
   */
  def build(totalRooms: Int = 4): Either[String, Dungeon] =
    val count = totalRooms.max(2).min(pool.size)

    for
      entrance   <- pickOne(RoomType.Combat, exclude = Set.empty)
      boss       <- pickOne(RoomType.Boss, exclude = Set(entrance.id))
      midCount    = count - 2
      middle     <- pickMiddle(midCount, exclude = Set(entrance.id, boss.id))
      ordered     = entrance :: middle ::: List(boss)
      dungeon    <- wire(ordered)
      withVaults <- injectVaultRooms(dungeon)
    yield withVaults


  // ---------------------------------------------
  // Private helpers
  // ---------------------------------------------

  /** Pick one room of the given type, excluding already-used ids. */
  private def pickOne(roomType: RoomType, exclude: Set[String]): Either[String, Room] =
    val candidates = pool.values.filter(r => r.roomType == roomType && !exclude.contains(r.id)).toVector
    if candidates.isEmpty
    then Left(s"No available room of type $roomType (excluded: ${exclude.mkString(", ")}).")
    else Right(candidates(rng.nextInt(candidates.size)))

  /** Pick `count` middle rooms (combat or loot), without repetition. */
  private def pickMiddle(count: Int, exclude: Set[String]): Either[String, List[Room]] =
    val candidates = pool.values.filter(r => !exclude.contains(r.id) && midTypes.contains(r.roomType)).toVector
    Right(rng.shuffle(candidates).take(count).toList)

  private val midTypes = Set(RoomType.Combat, RoomType.Loot, RoomType.Rest)

  /** Wire the ordered room list into a Dungeon by replacing stub door targets
   * with the actual ids of adjacent rooms.
   *
   * Convention: each room's "exit" is its first DOWN door;
   * each room's "entrance" is its first UP door.
   * Doors not matching these roles (e.g. side doors) are left unchanged.
   */
  private def wire(rooms: List[Room]): Either[String, Dungeon] =
    if rooms.isEmpty then return Left("Cannot wire an empty room list.")

    // For each consecutive pair (A, B): A's exit door points to B, B's entrance door points to A
    val wired = rooms.sliding(2).foldLeft(rooms.map(r => r.id -> r).toMap):
      case (acc, List(a, b)) =>
        val updatedA = replaceExitTarget(acc(a.id),  newTarget = b.id)
        val updatedB = replaceEntranceTarget(acc(b.id), newTarget = a.id)
        acc.updated(a.id, updatedA).updated(b.id, updatedB)
      case (acc, _) => acc

    Right(Dungeon(rooms = wired, currentRoomId = rooms.head.id))

  /** Replace the target of the first DOWN door in a room with `newTarget`. */
  private def replaceExitTarget(room: Room, newTarget: String): Room =
    val updated = room.entities.map:
      case d: Door if d.direction == Direction.Down && d.targetRoomId == "NEXT" =>
        d.copy(targetRoomId = newTarget)
      case other => other
    room.copy(entities = updated)

  /** Replace the target of the first UP door in a room with `newTarget`. */
  private def replaceEntranceTarget(room: Room, newTarget: String): Room =
    val updated = room.entities.map:
      case d: Door if d.direction == Direction.Up && d.targetRoomId == "PREV" =>
        d.copy(targetRoomId = newTarget)
      case other => other
    room.copy(entities = updated)

  /** Merge any Vault room referenced by a [[LockedDoor]] in the wired chain into the dungeon,
   * looked up from the full pool (Vault rooms are deliberately excluded from random selection,
   * see [[RoomType.Vault]]). Fails fast if a LockedDoor references a room id that doesn't exist in
   * the pool, so a content typo surfaces at build time rather than mid-playtest.
   */
  private def injectVaultRooms(dungeon: Dungeon): Either[String, Dungeon] =
    val referenced = dungeon.rooms.values.flatMap(_.entities).collect:
      case d: LockedDoor => d.targetRoomId

    referenced.foldLeft[Either[String, Dungeon]](Right(dungeon)):
      case (acc, roomId) =>
        acc.flatMap: d =>
          if d.rooms.contains(roomId) then Right(d)
          else
            pool
              .get(roomId)
              .toRight(s"LockedDoor references unknown room '$roomId'.")
              .map(room => d.copy(rooms = d.rooms.updated(roomId, room)))
