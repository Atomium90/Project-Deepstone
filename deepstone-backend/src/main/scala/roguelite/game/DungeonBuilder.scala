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
 * entrance, one or more sequential biome segments of combat/loot/rest rooms (each ending in its
 * own [[RoomType.Boss]] room, with a [[RoomType.MiniBoss]] checkpoint between consecutive biomes
 * where the pool has one to give), and one [[RoomType.Sanctuary]] room as the dungeon's true final
 * segment - defeating a biome's Boss never ends the run by itself, only interacting with the
 * Sanctuary does (see `InteractionResolver.handleSanctuary`). Each biome may also splice in one
 * branching cluster - a [[RoomType.Fork]] room leading to 2 distinct rooms that both reconverge on
 * whatever follows (see [[insertFork]]). Door connectivity is wired automatically by
 * [[wireSegments]]: the builder pairs the exit door(s) of one segment with the entrance door of
 * the next.
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
   *                   ends in its own [[RoomType.Boss]] room (required - the whole build fails
   *                   if any biome can't get one). Every biome but the last is *also* followed by
   *                   a [[RoomType.MiniBoss]] checkpoint before the next one starts, when the pool
   *                   has one available (see [[buildBiomeSegments]] - a missing MiniBoss room
   *                   silently skips that one checkpoint rather than failing the build, same
   *                   philosophy as the middle-room truncation above).
   * @param difficulty Drives the per-enemy Elite roll rate (see [[rollEliteEnemies]]).
   * @return A freshly assembled [[Dungeon]], or an error message if the pool has no Combat room
   *         for the entrance, no Sanctuary room for the dungeon's true final segment, or any biome
   *         can't get its own Boss room.
   */
  def build(totalRooms: Int = 4, biomeCount: Int = 1, difficulty: Difficulty = Difficulty.Normal): Either[String, Dungeon] =
    for
      entrance   <- pickOne(RoomType.Combat, exclude = Set.empty)
      sanctuary  <- pickOne(RoomType.Sanctuary, exclude = Set(entrance.id))
      biomeSegs  <- buildBiomeSegments(biomeCount.max(1), totalRooms.max(0), Set(entrance.id, sanctuary.id))
      ordered     = Segment.Linear(entrance) :: biomeSegs ::: List(Segment.Linear(sanctuary))
      dungeon    <- wireSegments(ordered)
      // The entrance's Prev door is never wired by anything (there's nothing before the entrance) -
      // it may still be authored, since Combat rooms are a shared pool with mid-chain rooms that
      // normally do need one. Strip it rather than leaving a dead, non-functional door in place.
      // The Sanctuary needs no equivalent cleanup: unlike Combat, it's never reused as a mid-chain
      // room, so it's authored with only its own single Prev door in the first place - there's
      // nothing left Unresolved to strip.
      cleaned     = removeUnresolvedDoors(dungeon, entrance.id, ConnectorRole.Prev)
      withVaults <- injectVaultRooms(cleaned)
    yield rollEliteEnemies(withVaults, difficulty)

  /** Builds the segment list between entrance and Sanctuary: `biomeCount` groups of `perBiome`
   * middle rooms, each ending in its own [[RoomType.Boss]] room (required per biome - see `build`'s
   * own doc), and each pair of consecutive biomes further separated by a [[RoomType.MiniBoss]]
   * room where the pool has one left to give - if not (including a pool with no MiniBoss room
   * authored at all), that boundary is silently skipped and the two biomes simply run together as
   * one longer stretch, rather than failing the whole build over missing optional structure.
   * Mirrors [[pickMiddle]]'s own existing "degrade gracefully, don't fail" behavior for an
   * under-sized middle-room pool. `exclude` accumulates across every biome, every fork cluster,
   * every Boss pick, and every MiniBoss pick, so nothing repeats anywhere in the run.
   */
  private def buildBiomeSegments(biomeCount: Int, perBiome: Int, exclude: Set[String]): Either[String, List[Segment]] =
    @annotation.tailrec
    def loop(biomesLeft: Int, exclude: Set[String], acc: List[Segment]): Either[String, List[Segment]] =
      if biomesLeft <= 0 then Right(acc)
      else
        val biomeRooms                 = pickMiddle(perBiome, exclude).getOrElse(Nil)
        val afterBiome                 = exclude ++ biomeRooms.map(_.id)
        val (biomeSegments, afterFork) = insertFork(biomeRooms, afterBiome)
        pickOne(RoomType.Boss, afterFork) match
          case Left(err) => Left(err)
          case Right(boss) =>
            val afterBoss        = afterFork + boss.id
            val segmentsWithBoss = biomeSegments ::: List(Segment.Linear(boss))
            if biomesLeft == 1 then Right(acc ::: segmentsWithBoss)
            else
              pickOne(RoomType.MiniBoss, afterBoss) match
                case Left(_) => loop(biomesLeft - 1, afterBoss, acc ::: segmentsWithBoss)
                case Right(miniBoss) =>
                  loop(biomesLeft - 1,
                       afterBoss + miniBoss.id,
                       acc ::: segmentsWithBoss ::: List(Segment.Linear(miniBoss))
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

  /** Removes every [[Door]] in room `roomId` whose link is still `Unresolved` for the given role,
   * regardless of branch - used on the dungeon's entrance, whose Prev door is never wired by
   * anything (there's nothing before it) but may still be authored, since Combat rooms are a
   * shared pool with mid-chain rooms that normally do need one. Rather than failing (a door is
   * optional content, unlike a missing room type), the dead door is stripped instead of left
   * Unresolved and interactable-but-broken. `LockedDoor` is untouched - it has no `Unresolved`
   * concept, always pre-resolved to its `targetRoomId`.
   */
  private def removeUnresolvedDoors(dungeon: Dungeon, roomId: String, role: ConnectorRole): Dungeon =
    val room = dungeon.rooms(roomId)
    val cleaned = room.entities.filterNot:
      case d: Door =>
        d.link match
          case DoorLink.Unresolved(r, _) => r == role
          case _                          => false
      case _ => false
    dungeon.copy(rooms = dungeon.rooms.updated(roomId, room.copy(entities = cleaned)))

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
