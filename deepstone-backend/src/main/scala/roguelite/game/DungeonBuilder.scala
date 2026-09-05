package roguelite.game

import roguelite.engine.Difficulty

import scala.util.Random

/** One edge in an explicit dungeon topology: the door on room `from` carrying the (`role`,
  * `branch`) key resolves to room `to`. See [[DungeonBuilder.buildFromTopology]].
  */
case class TopologyEdge(from: String, role: ConnectorRole, branch: Option[String], to: String)

/** Assembles a [[Dungeon]] from a pool of hand-crafted rooms.
 *
 * The builder picks rooms randomly while enforcing a minimal structure: one combat room as the
 * entrance, one or more sequential biome segments of combat/loot/rest rooms (each ending in a
 * [[RoomType.MiniBoss]] checkpoint except the last), and one boss room as the exit. Door
 * connectivity between rooms is wired automatically: the builder pairs the exit door of one room
 * with the entrance door of the next.
 *
 * @param pool   All available rooms keyed by id.
 * @param rng    Random instance: inject a seeded one for reproducible dungeons.
 */
class DungeonBuilder(pool: Map[String, Room], rng: Random = Random()):

  /** Build a dungeon out of `biomeCount` sequential biome segments.
   *
   * @param totalRooms Middle-room count *per biome* (not the whole dungeon - see
   *                   [[roguelite.engine.Difficulty.totalRooms]]'s own doc). Each biome's rooms
   *                   are picked without repeating any room already used elsewhere in the run.
   *                   Silently yields fewer than requested if the pool runs short (same
   *                   graceful-truncation behavior [[pickMiddle]] already had).
   * @param biomeCount Number of sequential biome segments, clamped to at least 1. Every biome
   *                   but the last ends in a [[RoomType.MiniBoss]] checkpoint before the next
   *                   one starts, when the pool has one available (see [[buildBiomeSegments]] -
   *                   a missing MiniBoss room silently skips that one checkpoint rather than
   *                   failing the build, same philosophy as the middle-room truncation above).
   *                   The final biome always ends in the run's one [[RoomType.Boss]] room.
   * @param difficulty Drives the per-enemy Elite roll rate (see [[rollEliteEnemies]]).
   * @return A freshly assembled [[Dungeon]], or an error message if the pool has no Combat room
   *         for the entrance or no Boss room for the exit.
   */
  def build(totalRooms: Int = 4, biomeCount: Int = 1, difficulty: Difficulty = Difficulty.Normal): Either[String, Dungeon] =
    for
      entrance   <- pickOne(RoomType.Combat, exclude = Set.empty)
      boss       <- pickOne(RoomType.Boss, exclude = Set(entrance.id))
      biomeRooms  = buildBiomeSegments(biomeCount.max(1), totalRooms.max(0), Set(entrance.id, boss.id))
      ordered     = entrance :: biomeRooms ::: List(boss)
      dungeon    <- wire(ordered)
      withVaults <- injectVaultRooms(dungeon)
    yield rollEliteEnemies(withVaults, difficulty)

  /** Builds the room list between entrance and boss: `biomeCount` groups of `perBiome` middle
   * rooms, each pair of consecutive biomes separated by a [[RoomType.MiniBoss]] room where the
   * pool has one left to give - if not (including a pool with no MiniBoss room authored at all),
   * that boundary is silently skipped and the two biomes' rooms simply run together as one longer
   * stretch, rather than failing the whole build over missing optional structure. Mirrors
   * [[pickMiddle]]'s own existing "degrade gracefully, don't fail" behavior for an under-sized
   * middle-room pool. `exclude` accumulates across every biome and every MiniBoss pick, so nothing
   * repeats anywhere in the run - still linear overall, no branching yet (that's later, separate
   * work built on top of this same room-list shape).
   */
  private def buildBiomeSegments(biomeCount: Int, perBiome: Int, exclude: Set[String]): List[Room] =
    @annotation.tailrec
    def loop(biomesLeft: Int, exclude: Set[String], acc: List[Room]): List[Room] =
      if biomesLeft <= 0 then acc
      else
        val biomeRooms = pickMiddle(perBiome, exclude).getOrElse(Nil)
        val afterBiome = exclude ++ biomeRooms.map(_.id)
        if biomesLeft == 1 then acc ::: biomeRooms
        else
          pickOne(RoomType.MiniBoss, afterBiome) match
            case Left(_)         => loop(biomesLeft - 1, afterBiome, acc ::: biomeRooms)
            case Right(miniBoss) => loop(biomesLeft - 1, afterBiome + miniBoss.id, acc ::: biomeRooms ::: List(miniBoss))
    loop(biomeCount, exclude, Nil)

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
   * Elite per room. Boss and MiniBoss rooms are excluded entirely - their enemies are already
   * scripted/unique checkpoints, never randomly Elite on top of that.
   *
   * Produces fresh Room/Enemy copies for the returned Dungeon only - never mutates the
   * server-lifetime `pool` itself.
   */
  private def rollEliteEnemies(dungeon: Dungeon, difficulty: Difficulty): Dungeon =
    val chance = difficulty.eliteChance
    val updatedRooms = dungeon.rooms.map:
      case (id, room) if room.roomType == RoomType.Boss || room.roomType == RoomType.MiniBoss => id -> room
      case (id, room) =>
        var alreadyElite = false
        val newEntities = room.entities.map:
          case e: Enemy if !alreadyElite && rng.nextDouble() < chance =>
            alreadyElite = true
            e.copy(isElite = true)
          case other => other
        id -> room.copy(entities = newEntities)
    dungeon.copy(rooms = updatedRooms)
