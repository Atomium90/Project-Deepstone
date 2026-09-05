package roguelite.game

import roguelite.engine.Difficulty

import scala.util.Random

/** One edge in an explicit dungeon topology: the door on room `from` carrying the (`role`,
  * `branch`) key resolves to room `to`. See [[DungeonBuilder.buildFromTopology]].
  */
case class TopologyEdge(from: String, role: ConnectorRole, branch: Option[String], to: String)

/** Assembles a [[Dungeon]] from a pool of hand-crafted rooms.
 *
 * The builder picks rooms randomly while enforcing a minimal structure:
 * one combat room as the entrance, a mix of combat and loot rooms in the
 * middle, and one boss room as the exit. Door connectivity between rooms
 * is wired automatically: the builder pairs the exit door of one room
 * with the entrance door of the next.
 *
 * @param pool   All available rooms keyed by id.
 * @param rng    Random instance: inject a seeded one for reproducible dungeons.
 */
class DungeonBuilder(pool: Map[String, Room], rng: Random = Random()):

  /** Build a dungeon with the given number of rooms.
   *
   * @param totalRooms Total number of rooms including entrance and boss.
   *                   Must be at least 2 (entrance + boss). Clamped to the
   *                   number of available rooms if the pool is smaller.
   * @param difficulty Drives the per-enemy Elite roll rate (see [[rollEliteEnemies]]).
   * @return A freshly assembled [[Dungeon]], or an error message if the
   *         pool does not contain the required room types.
   */
  def build(totalRooms: Int = 4, difficulty: Difficulty = Difficulty.Normal): Either[String, Dungeon] =
    val count = totalRooms.max(2).min(pool.size)

    for
      entrance   <- pickOne(RoomType.Combat, exclude = Set.empty)
      boss       <- pickOne(RoomType.Boss, exclude = Set(entrance.id))
      midCount    = count - 2
      middle     <- pickMiddle(midCount, exclude = Set(entrance.id, boss.id))
      ordered     = entrance :: middle ::: List(boss)
      dungeon    <- wire(ordered)
      withVaults <- injectVaultRooms(dungeon)
    yield rollEliteEnemies(withVaults, difficulty)

  /** Wire an explicit topology instead of `build`'s random linear-chain selection: every room id
   * mentioned in `edges` or `entranceId` is looked up in `pool`, then each edge resolves the
   * matching (role, branch) door on its `from` room to its `to` room, via the same [[resolveLinks]]
   * primitive `wire` uses for the linear case.
   *
   * Proof-of-concept for Phase 4's branching dungeons (fork/merge rooms): demonstrates that
   * primitive already generalizes to a non-linear shape without further changes, now that a room
   * can carry more than one door per role (see [[roguelite.game.DoorLink]]'s `branch`). Not a
   * general topology generator - real content-driven branching dungeons are later authoring work;
   * this only proves the mechanism against a hardcoded shape in [[DungeonBuilderSuite]].
   *
   * @return A freshly assembled [[Dungeon]], or an error message if `entranceId` or any edge
   *         references a room id absent from `pool`.
   */
  def buildFromTopology(edges: List[TopologyEdge], entranceId: String): Either[String, Dungeon] =
    val referencedIds = edges.flatMap(e => List(e.from, e.to)).toSet + entranceId
    referencedIds.find(id => !pool.contains(id)) match
      case Some(missing) => Left(s"Topology references unknown room '$missing'.")
      case None =>
        val initial = referencedIds.map(id => id -> pool(id)).toMap
        val wired = edges.foldLeft(initial):
          case (acc, edge) =>
            acc.updated(edge.from, resolveLinks(acc(edge.from), edge.role, edge.branch, edge.to))
        injectVaultRooms(Dungeon(rooms = wired, currentRoomId = entranceId))

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

  /** Wire the ordered room list into a Dungeon by resolving every Unresolved [[Door]] link:
   * each room's Next-role doors point forward to the next room in the list; the next room's
   * Prev-role doors point back to it. Every door sharing a (role, branch) key in a room resolves
   * to the same neighbor (a room can have more than one Next door - e.g. a secret or trapped
   * alternate exit alongside the main one), not just the first match.
   */
  private def wire(rooms: List[Room]): Either[String, Dungeon] =
    if rooms.isEmpty then return Left("Cannot wire an empty room list.")

    // For each consecutive pair (A, B): A's Next doors point to B, B's Prev doors point to A
    val wired = rooms.sliding(2).foldLeft(rooms.map(r => r.id -> r).toMap):
      case (acc, List(a, b)) =>
        val updatedA = resolveLinks(acc(a.id), role = ConnectorRole.Next, branch = None, roomId = b.id)
        val updatedB = resolveLinks(acc(b.id), role = ConnectorRole.Prev, branch = None, roomId = a.id)
        acc.updated(a.id, updatedA).updated(b.id, updatedB)
      case (acc, _) => acc

    Right(Dungeon(rooms = wired, currentRoomId = rooms.head.id))

  /** Resolve every Unresolved door in a room matching the given (role, branch) key to `roomId`. */
  private def resolveLinks(room: Room, role: ConnectorRole, branch: Option[String], roomId: String): Room =
    val updated = room.entities.map:
      case d: Door if d.link == DoorLink.Unresolved(role, branch) =>
        d.copy(link = DoorLink.Resolved(role, branch, roomId))
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

  /** Roll Elite status onto at most one enemy per non-boss room. Each hand-placed [[Enemy]] entity
   * in a room rolls independently at `difficulty.eliteChance`; the roll stops being applied (but
   * the loop keeps iterating) once one enemy in that room has already succeeded, capping at 1
   * Elite per room. Boss rooms are excluded entirely - bosses stay unique/scripted, never Elite.
   *
   * Produces fresh Room/Enemy copies for the returned Dungeon only - never mutates the
   * server-lifetime `pool` itself.
   */
  private def rollEliteEnemies(dungeon: Dungeon, difficulty: Difficulty): Dungeon =
    val chance = difficulty.eliteChance
    val updatedRooms = dungeon.rooms.map:
      case (id, room) if room.roomType == RoomType.Boss => id -> room
      case (id, room) =>
        var alreadyElite = false
        val newEntities = room.entities.map:
          case e: Enemy if !alreadyElite && rng.nextDouble() < chance =>
            alreadyElite = true
            e.copy(isElite = true)
          case other => other
        id -> room.copy(entities = newEntities)
    dungeon.copy(rooms = updatedRooms)
