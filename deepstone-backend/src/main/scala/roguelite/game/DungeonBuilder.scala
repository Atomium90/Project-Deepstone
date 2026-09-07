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
 * [[RoomType.MiniBoss]] checkpoint except the last), and one boss room as the exit. Each biome may
 * also splice in one branching cluster - a [[RoomType.Fork]] room leading to 2 distinct rooms that
 * both reconverge on whatever follows (see [[insertFork]]). Door connectivity is wired
 * automatically by [[wireSegments]]: the builder pairs the exit door(s) of one segment with the
 * entrance door of the next.
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
      biomeSegs   = buildBiomeSegments(biomeCount.max(1), totalRooms.max(0), Set(entrance.id, boss.id))
      ordered     = Segment.Linear(entrance) :: biomeSegs ::: List(Segment.Linear(boss))
      dungeon    <- wireSegments(ordered)
      withVaults <- injectVaultRooms(dungeon)
    yield rollEliteEnemies(withVaults, difficulty)

  /** Builds the segment list between entrance and boss: `biomeCount` groups of `perBiome` middle
   * rooms, each pair of consecutive biomes separated by a [[RoomType.MiniBoss]] room where the
   * pool has one left to give - if not (including a pool with no MiniBoss room authored at all),
   * that boundary is silently skipped and the two biomes' rooms simply run together as one longer
   * stretch, rather than failing the whole build over missing optional structure. Mirrors
   * [[pickMiddle]]'s own existing "degrade gracefully, don't fail" behavior for an under-sized
   * middle-room pool. `exclude` accumulates across every biome, every fork cluster, and every
   * MiniBoss pick, so nothing repeats anywhere in the run.
   */
  private def buildBiomeSegments(biomeCount: Int, perBiome: Int, exclude: Set[String]): List[Segment] =
    @annotation.tailrec
    def loop(biomesLeft: Int, exclude: Set[String], acc: List[Segment]): List[Segment] =
      if biomesLeft <= 0 then acc
      else
        val biomeRooms                = pickMiddle(perBiome, exclude).getOrElse(Nil)
        val afterBiome                = exclude ++ biomeRooms.map(_.id)
        val (biomeSegments, afterFork) = insertFork(biomeRooms, afterBiome)
        if biomesLeft == 1 then acc ::: biomeSegments
        else
          pickOne(RoomType.MiniBoss, afterFork) match
            case Left(_) => loop(biomesLeft - 1, afterFork, acc ::: biomeSegments)
            case Right(miniBoss) =>
              loop(biomesLeft - 1,
                   afterFork + miniBoss.id,
                   acc ::: biomeSegments ::: List(Segment.Linear(miniBoss))
              )
    loop(biomeCount, exclude, Nil)

  /** Try to splice one branching cluster into a random position of this biome's already-picked
   * linear room list: a [[RoomType.Fork]] room plus 2 distinct branch rooms drawn from the same
   * middle-room pool [[pickMiddle]] uses, none repeating any room already used elsewhere in the
   * run. Falls back to a purely linear segment list (no branching this biome) if the pool can't
   * support it - same "degrade gracefully, don't fail the whole build" philosophy as
   * [[buildBiomeSegments]]'s own MiniBoss handling. No dedicated "merge" room content is needed:
   * both branch rooms simply become this segment's `exitRooms`, wired forward to whatever
   * ordinary room follows next by [[wireSegments]] - the room after the fork never needs to know
   * which of the two branches the player actually came from.
   */
  private def insertFork(biomeRooms: List[Room], exclude: Set[String]): (List[Segment], Set[String]) =
    val branches = pickMiddle(2, exclude).getOrElse(Nil)
    (pickOne(RoomType.Fork, exclude).toOption, branches) match
      case (Some(fork), List(branchA, branchB)) =>
        val position        = rng.nextInt(biomeRooms.size + 1)
        val (before, after) = biomeRooms.splitAt(position)
        val segments =
          before.map(Segment.Linear(_)) ::: List(Segment.Fork(fork, branchA, branchB)) ::: after.map(Segment.Linear(_))
        (segments, exclude + fork.id + branchA.id + branchB.id)
      case _ =>
        (biomeRooms.map(Segment.Linear(_)), exclude)

  /** Wire an explicit topology instead of `build`'s random linear-chain selection: every room id
   * mentioned in `edges` or `entranceId` is looked up in `pool`, then each edge resolves the
   * matching (role, branch) door on its `from` room to its `to` room, via the same [[resolveLinks]]
   * primitive [[wireSegments]] uses for the linear case.
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

  /** One node in the room sequence [[wireSegments]] connects end-to-end. A `Linear` segment
   * behaves exactly like a single room in the old purely-linear chain; a `Fork` segment bundles a
   * [[RoomType.Fork]] room with its two branch destinations - the fork room is this segment's
   * single entry point (wired from whatever precedes it), and both branches are its exit points
   * (each wired forward to whatever follows, converging on the same next room).
   */
  private enum Segment:
    case Linear(room: Room)
    case Fork(fork: Room, branchA: Room, branchB: Room)

    /** Every room belonging to this segment, used to seed the room map [[wireSegments]] mutates. */
    def rooms: List[Room] = this match
      case Linear(room)     => List(room)
      case Fork(fork, a, b) => List(fork, a, b)

    /** The single room whose Prev door(s) connect back to the previous segment. */
    def entryRoom: Room = this match
      case Linear(room)     => room
      case Fork(fork, _, _) => fork

    /** The room(s) whose Next door(s) connect forward to the next segment. */
    def exitRooms: List[Room] = this match
      case Linear(room)  => List(room)
      case Fork(_, a, b) => List(a, b)

  /** Wire an ordered sequence of segments into a Dungeon by resolving every Unresolved [[Door]]
   * link. Generalizes the old purely-linear per-pair wiring to also handle a [[Segment.Fork]]'s
   * branch-and-reconverge shape: first each Fork segment's own internal doors are wired (the fork
   * room's two branch-tagged Next doors to its two branches, each branch's Prev door back to the
   * fork room), then every consecutive pair of segments is wired exactly like the old linear case
   * - for each of segment A's `exitRooms`, its Next door points to segment B's single `entryRoom`,
   * and that door's Prev points back. When A has 2 exit rooms (a Fork segment), both attempt to
   * set B's entryRoom's Prev door - only the first actually resolves it (see [[resolveLinks]]),
   * the second is a harmless no-op. This means a room right after a fork cluster has a Prev door
   * pointing at only one of the two branches, not both - accepted, since nothing reads "which
   * branch did the player actually take" from that door once resolved (findSpawnPoint only cares
   * about travel direction, not which room sent the player).
   */
  private def wireSegments(segments: List[Segment]): Either[String, Dungeon] =
    if segments.isEmpty then return Left("Cannot wire an empty segment list.")

    val initial = segments.flatMap(_.rooms).map(r => r.id -> r).toMap

    val withForkInternals = segments.foldLeft(initial):
      case (acc, Segment.Fork(fork, branchA, branchB)) =>
        val wiredFork = List("a" -> branchA, "b" -> branchB).foldLeft(acc(fork.id)):
          case (f, (branch, dest)) => resolveLinks(f, ConnectorRole.Next, Some(branch), dest.id)
        val wiredA = resolveLinks(acc(branchA.id), ConnectorRole.Prev, None, fork.id)
        val wiredB = resolveLinks(acc(branchB.id), ConnectorRole.Prev, None, fork.id)
        acc.updated(fork.id, wiredFork).updated(branchA.id, wiredA).updated(branchB.id, wiredB)
      case (acc, Segment.Linear(_)) => acc

    val wired = segments.sliding(2).foldLeft(withForkInternals):
      case (acc, List(a, b)) =>
        a.exitRooms.foldLeft(acc):
          case (acc2, exitRoom) =>
            val updatedExit  = resolveLinks(acc2(exitRoom.id), ConnectorRole.Next, None, b.entryRoom.id)
            val updatedEntry = resolveLinks(acc2(b.entryRoom.id), ConnectorRole.Prev, None, exitRoom.id)
            acc2.updated(exitRoom.id, updatedExit).updated(b.entryRoom.id, updatedEntry)
      case (acc, _) => acc

    Right(Dungeon(rooms = wired, currentRoomId = segments.head.entryRoom.id))

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
