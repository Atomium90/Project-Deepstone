package roguelite.game

import roguelite.engine.Difficulty

import scala.util.Random

/** One edge in an explicit dungeon topology: the door on room `from` carrying the (`role`,
  * `branch`) key resolves to room `to`. See [[DungeonBuilder.buildFromTopology]].
  */
case class TopologyEdge(from: String, role: ConnectorRole, branch: Option[String], to: String)

/** Assembles a [[Dungeon]] from a pool of hand-crafted rooms.
 *
 * The builder picks rooms randomly while enforcing a minimal structure: one or more sequential
 * sections, each drawn entirely from a single [[Room.theme]] (see [[pickNextTheme]]) and following
 * the same fixed room template (see [[buildSection]]), ending in its own [[RoomType.Boss]] room,
 * and one [[RoomType.Sanctuary]] room - matched to the *last* section's theme - as the dungeon's
 * true final segment. Defeating a section's Boss never ends the run by itself, only interacting
 * with the Sanctuary does (see `InteractionResolver.handleSanctuary`). Every section but the last
 * is followed by a guaranteed [[RoomType.Rest]] room, matched to the section that just ended, before
 * the next section starts. There is no separately-tracked "entrance" room: the dungeon's starting
 * room is simply whichever room ends up first in section 1 (see `build`'s own doc for what that
 * means when section 1's own lead-in rooms can't be found). Door connectivity is wired
 * automatically by [[wireSegments]]: the builder pairs the exit door(s) of one segment with the
 * entrance door of the next.
 *
 * @param pool   All available rooms keyed by id.
 * @param rng    Random instance: inject a seeded one for reproducible dungeons.
 */
class DungeonBuilder(pool: Map[String, Room], rng: Random = Random()):

  /** Build a dungeon out of `biomeCount` sequential sections.
   *
   * @param difficulty Drives the per-enemy Elite roll rate (see [[rollEliteEnemies]]), the per-chest
   *                   trap rate (see [[rollTrappedChests]]) and `biomeCount`'s own default below.
   * @param biomeCount Number of sequential sections, clamped to at least 1. Defaults to
   *                   `difficulty.biomeCount` - a bare default of e.g. `1` here, independent of
   *                   `difficulty`'s own default, would silently desync the moment either default
   *                   changed on its own. Every section ends in its own [[RoomType.Boss]] room
   *                   (required - the whole build fails if any section can't get one, in its
   *                   section's own theme). Every section but the last is followed by a guaranteed
   *                   [[RoomType.Rest]] room where the pool has one available in that section's own
   *                   theme (see [[buildSections]] - a missing Rest room silently skips that one
   *                   slot rather than failing the build, same philosophy as every other optional
   *                   template slot).
   * @return A freshly assembled [[Dungeon]], or an error message if the pool has no room of any
   *         theme at all, no Sanctuary room matching the last section's theme, or any section
   *         can't get its own Boss room in its theme.
   */
  def build(difficulty: Difficulty = Difficulty.Normal)(biomeCount: Int = difficulty.biomeCount): Either[String, Dungeon] =
    for
      sectionsResult        <- buildSections(biomeCount.max(1))
      (sections, lastTheme)  = sectionsResult
      sanctuary             <- pickOne(RoomType.Sanctuary, lastTheme, exclude = Set.empty)
      rawOrdered             = sections ::: List(Segment.Linear(sanctuary))
      // Two different sections can independently pick the same authored room (exclude resets per
      // section - see buildSections's own doc for why) - give every placement after the first a
      // fresh runtime id before wiring, or the two placements would collide into a single entry in
      // Dungeon.rooms and silently corrupt each other's door links.
      ordered                = deduplicateRoomIds(rawOrdered)
      dungeon                <- wireSegments(ordered)
      // Whatever room ends up first (section 1's own first segment - see the class doc) has a Prev
      // door that's never wired by anything, same reasoning as the old dedicated-entrance pick
      // used to need: it may still be authored, since these rooms are a shared pool with mid-chain
      // rooms that normally do need one. Strip it rather than leaving a dead, non-functional door
      // in place. The Sanctuary needs no equivalent cleanup - see removeUnresolvedDoors's own doc.
      cleaned                = removeUnresolvedDoors(dungeon, dungeon.currentRoomId, ConnectorRole.Prev)
      withVaults             <- injectVaultRooms(cleaned)
    yield rollTrappedChests(rollEliteEnemies(withVaults, difficulty), difficulty)

  /** Builds the segment list up to (not including) the Sanctuary: `sectionCount` sections (see
   * [[buildSection]]), each pair of consecutive sections further separated by a guaranteed
   * [[RoomType.Rest]] room, matched to the section that just ended, where the pool has one left to
   * give - if not, that boundary is silently skipped and the two sections simply run together as
   * one longer stretch, rather than failing the whole build over missing optional structure. Each
   * section picks its own theme fresh via [[pickNextTheme]] and rebuilds its own `exclude` set from
   * scratch - *not* threaded across sections, deliberately: a room reused in a later section is
   * acceptable (sections are independent, visually and mechanically - [[deduplicateRoomIds]] is
   * what actually makes a literal repeat safe, called once after every section is built, not here).
   * Returns the last section's theme alongside the segment list, so `build` can match the Sanctuary
   * pick to it.
   */
  private def buildSections(sectionCount: Int): Either[String, (List[Segment], String)] =
    val availableThemes = pool.values.map(_.theme).toList.distinct
    if availableThemes.isEmpty then Left("No rooms available in the pool (no themes found).")
    else
      @annotation.tailrec
      def loop(sectionsLeft: Int, usedThemes: List[String], acc: List[Segment]): Either[String, (List[Segment], String)] =
        val theme = pickNextTheme(usedThemes, availableThemes)
        buildSection(theme) match
          case Left(err) => Left(err)
          case Right(segments) =>
            if sectionsLeft <= 1 then Right((acc ::: segments, theme))
            else
              pickOne(RoomType.Rest, theme, exclude = Set.empty) match
                case Left(_)     => loop(sectionsLeft - 1, usedThemes :+ theme, acc ::: segments)
                case Right(rest) => loop(sectionsLeft - 1, usedThemes :+ theme, acc ::: segments ::: List(Segment.Linear(rest)))
      loop(sectionCount, Nil, Nil)

  /** Picks the theme for the next section. Current policy: prefer a theme not yet used anywhere
   * this run, picked at random among whichever themes qualify - only once every theme has been
   * used at least once does a repeat become eligible at all, and even then the immediately-previous
   * section's theme is excluded first (so two consecutive sections never look the same), unless
   * there's genuinely only one theme available, in which case that one theme is reused rather than
   * this picking nothing at all. Maximizes variety across a run instead of allowing an early repeat
   * while another theme sits completely unused (e.g. with themes A/B/C, a sequence like A, B, A
   * would waste C's own variety for no reason - this policy always finds C first). Deliberately
   * kept small and isolated, not inlined into [[buildSections]]'s loop: a future ordering rule (a
   * fixed sequence, or per-position eligibility like "these themes only ever open a run") only
   * needs to replace this one method, nothing else in this class needs to change to support it.
   */
  private def pickNextTheme(usedThemes: List[String], availableThemes: List[String]): String =
    val unused = availableThemes.filterNot(usedThemes.contains)
    val candidates =
      if unused.nonEmpty then unused
      else
        usedThemes.lastOption match
          case Some(prev) if availableThemes.sizeIs > 1 => availableThemes.filterNot(_ == prev)
          case _                                        => availableThemes
    candidates(rng.nextInt(candidates.size))

  /** Builds one section's segment list, following a fixed room template, every room drawn from
   * `theme`: 2 lead-in Combat rooms, one optional branching cluster (see
   * [[buildFixedForkCluster]]), 2 reconverging rooms (Combat then Loot), and a required terminal
   * [[RoomType.Boss]] room. Every slot except the Fork cluster and the Boss degrades gracefully
   * (that slot is simply skipped) if the pool has nothing left to give in this theme - same
   * philosophy as every other optional slot in this builder. The Boss stays a hard requirement,
   * same as `build`'s own doc describes. `exclude` starts fresh here, scoped to this section only -
   * see [[buildSections]]'s own doc for why.
   */
  private def buildSection(theme: String): Either[String, List[Segment]] =
    val (leadIn, afterLeadIn) = pickOptionalSequence(List(RoomType.Combat, RoomType.Combat), theme, Set.empty)
    val (forkSegment, afterFork) = buildFixedForkCluster(theme, afterLeadIn) match
      case Some((segment, used)) => (List(segment), used)
      case None                  => (Nil, afterLeadIn)
    val (reconverge, afterReconverge) = pickOptionalSequence(List(RoomType.Combat, RoomType.Loot), theme, afterFork)
    pickOne(RoomType.Boss, theme, afterReconverge) match
      case Left(err) => Left(err)
      case Right(boss) =>
        val segments =
          leadIn.map(Segment.Linear(_)) ::: forkSegment ::: reconverge.map(Segment.Linear(_)) :::
            List(Segment.Linear(boss))
        Right(segments)

  /** Attempts a section's one branching cluster, every room drawn from `theme`: a [[RoomType.Fork]]
   * room plus 2-room branches (branch A: Combat then Loot; branch B: Loot then
   * [[RoomType.MiniBoss]] - MiniBoss is reachable only via this optional side-path, never a
   * guaranteed per-section beat). All-or-nothing: if any of the 5 rooms can't be drawn from the
   * pool in this theme, the whole cluster is skipped rather than wiring a partial fork, same
   * "degrade gracefully, don't fail the whole build" philosophy as every other optional template
   * slot. No dedicated "merge" room content is needed: both branches' last rooms simply become this
   * segment's `exitRooms`, wired forward to whatever ordinary room follows next by [[wireSegments]]
   * - the room after the fork never needs to know which branch the player actually came from.
   */
  private def buildFixedForkCluster(theme: String, exclude: Set[String]): Option[(Segment.Fork, Set[String])] =
    for
      fork      <- pickOne(RoomType.Fork, theme, exclude).toOption
      afterFork  = exclude + fork.id
      branchA1  <- pickOne(RoomType.Combat, theme, afterFork).toOption
      afterA1    = afterFork + branchA1.id
      branchA2  <- pickOne(RoomType.Loot, theme, afterA1).toOption
      afterA2    = afterA1 + branchA2.id
      branchB1  <- pickOne(RoomType.Loot, theme, afterA2).toOption
      afterB1    = afterA2 + branchB1.id
      branchB2  <- pickOne(RoomType.MiniBoss, theme, afterB1).toOption
    yield (Segment.Fork(fork, List(branchA1, branchA2), List(branchB1, branchB2)), afterB1 + branchB2.id)

  /** Picks one room of `roomType` in `theme`, degrading gracefully (returns `None`, `exclude`
   * unchanged) if the pool has none left - used for every fixed-template slot except Boss/
   * Sanctuary/the Fork cluster as a whole, which stay hard requirements (see
   * [[buildSection]]/[[pickOne]]).
   */
  private def pickOptional(roomType: RoomType, theme: String, exclude: Set[String]): (Option[Room], Set[String]) =
    pickOne(roomType, theme, exclude) match
      case Left(_)     => (None, exclude)
      case Right(room) => (Some(room), exclude + room.id)

  /** Picks a sequence of rooms by type in order, all in `theme`, each slot degrading independently
   * via [[pickOptional]] - a later slot's pick still excludes whatever an earlier slot in the same
   * sequence already picked, but one slot's miss never skips a later one.
   */
  private def pickOptionalSequence(roomTypes: List[RoomType], theme: String, exclude: Set[String]): (List[Room], Set[String]) =
    roomTypes.foldLeft((List.empty[Room], exclude)):
      case ((acc, excl), roomType) =>
        pickOptional(roomType, theme, excl) match
          case (Some(room), newExcl) => (acc :+ room, newExcl)
          case (None, newExcl)       => (acc, newExcl)

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

  /** Pick one room of the given type and theme, excluding already-used ids. */
  private def pickOne(roomType: RoomType, theme: String, exclude: Set[String]): Either[String, Room] =
    val candidates =
      pool.values.filter(r => r.roomType == roomType && r.theme == theme && !exclude.contains(r.id)).toVector
    if candidates.isEmpty
    then Left(s"No available room of type $roomType in theme '$theme' (excluded: ${exclude.mkString(", ")}).")
    else Right(candidates(rng.nextInt(candidates.size)))

  /** One node in the room sequence [[wireSegments]] connects end-to-end. A `Linear` segment
   * behaves exactly like a single room in the old purely-linear chain; a `Fork` segment bundles a
   * [[RoomType.Fork]] room with its two branch destinations - the fork room is this segment's
   * single entry point (wired from whatever precedes it), and the last room of each branch is an
   * exit point (each wired forward to whatever follows, converging on the same next room). Each
   * branch is a room *list*, not a single room - a branch can be more than 1 room deep, wired as an
   * ordinary internal linear chain (see [[wireSegments]]).
   */
  private enum Segment:
    case Linear(room: Room)
    case Fork(fork: Room, branchA: List[Room], branchB: List[Room])

    /** Every room belonging to this segment, used to seed the room map [[wireSegments]] mutates. */
    def rooms: List[Room] = this match
      case Linear(room)     => List(room)
      case Fork(fork, a, b) => fork :: a ::: b

    /** The single room whose Prev door(s) connect back to the previous segment. */
    def entryRoom: Room = this match
      case Linear(room)     => room
      case Fork(fork, _, _) => fork

    /** The room(s) whose Next door(s) connect forward to the next segment - the last room of each
     * branch for a Fork segment, not the fork room itself. */
    def exitRooms: List[Room] = this match
      case Linear(room)  => List(room)
      case Fork(_, a, b) => List(a.last, b.last)

  /** Ensures every room placed across `segments` has a unique id, even when the same authored room
   * was independently picked by more than one section (allowed - see [[buildSections]]'s own doc on
   * why `exclude` resets per section). The *first* placement of a given authored id keeps it
   * unchanged; every placement after that gets a synthetic runtime id (e.g. `"boss_002#2"`) via a
   * fresh [[Room]] copy - same content, distinct identity - so [[wireSegments]] can safely key
   * everything by room id without two different sections silently colliding on one dungeon-graph
   * node (`Dungeon.rooms` is a `Map[String, Room]` - two rooms sharing one id would collapse into a
   * single entry, with whichever section's door-wiring ran last overwriting the other's). A room's
   * own entities (e.g. a `LockedDoor` targeting a Vault) are untouched by the rename - unaffected,
   * since [[injectVaultRooms]] looks up a Vault by its own literal authored id, never by whichever
   * room referenced it.
   */
  private def deduplicateRoomIds(segments: List[Segment]): List[Segment] =
    val placementCount = scala.collection.mutable.Map.empty[String, Int].withDefaultValue(0)
    def dedupe(room: Room): Room =
      val seenBefore = placementCount(room.id)
      placementCount(room.id) = seenBefore + 1
      if seenBefore == 0 then room else room.copy(id = s"${room.id}#${seenBefore + 1}")
    segments.map:
      case Segment.Linear(room) => Segment.Linear(dedupe(room))
      case Segment.Fork(fork, branchA, branchB) =>
        Segment.Fork(dedupe(fork), branchA.map(dedupe), branchB.map(dedupe))

  /** Wire an ordered sequence of segments into a Dungeon by resolving every Unresolved [[Door]]
   * link. Generalizes the old purely-linear per-pair wiring to also handle a [[Segment.Fork]]'s
   * branch-and-reconverge shape: first each Fork segment's own internal doors are wired (the fork
   * room's two branch-tagged Next doors to the *first* room of each branch, then each branch's own
   * room list wires internally as an ordinary linear chain, reusing the same pairwise
   * [[resolveLinks]] calls segment-to-segment wiring already does), then every consecutive pair of
   * segments is wired exactly like the old linear case - for each of segment A's `exitRooms` (the
   * *last* room of each branch for a Fork segment), its Next door points to segment B's single
   * `entryRoom`, and that door's Prev points back. When A has 2 exit rooms (a Fork segment), both
   * attempt to set B's entryRoom's Prev door - only the first actually resolves it (see
   * [[resolveLinks]]), the second is a harmless no-op. This means a room right after a fork cluster
   * has a Prev door pointing at only one of the two branches, not both - accepted, since nothing
   * reads "which branch did the player actually take" from that door once resolved
   * (findSpawnPoint only cares about travel direction, not which room sent the player).
   */
  private def wireSegments(segments: List[Segment]): Either[String, Dungeon] =
    if segments.isEmpty then return Left("Cannot wire an empty segment list.")

    val initial = segments.flatMap(_.rooms).map(r => r.id -> r).toMap

    /** Wires one branch's own room list as an ordinary internal linear chain - a no-op for a
     * single-room branch (nothing to wire internally), same pairwise pattern as top-level
     * segment-to-segment wiring below. */
    def wireBranchChain(acc: Map[String, Room], branch: List[Room]): Map[String, Room] =
      branch.sliding(2).foldLeft(acc):
        case (acc2, List(r1, r2)) =>
          val updated1 = resolveLinks(acc2(r1.id), ConnectorRole.Next, None, r2.id)
          val updated2 = resolveLinks(acc2(r2.id), ConnectorRole.Prev, None, r1.id)
          acc2.updated(r1.id, updated1).updated(r2.id, updated2)
        case (acc2, _) => acc2

    val withForkInternals = segments.foldLeft(initial):
      case (acc, Segment.Fork(fork, branchA, branchB)) =>
        val wiredFork = List("a" -> branchA.head, "b" -> branchB.head).foldLeft(acc(fork.id)):
          case (f, (branch, dest)) => resolveLinks(f, ConnectorRole.Next, Some(branch), dest.id)
        val wiredA = resolveLinks(acc(branchA.head.id), ConnectorRole.Prev, None, fork.id)
        val wiredB = resolveLinks(acc(branchB.head.id), ConnectorRole.Prev, None, fork.id)
        val afterEntries =
          acc.updated(fork.id, wiredFork).updated(branchA.head.id, wiredA).updated(branchB.head.id, wiredB)
        wireBranchChain(wireBranchChain(afterEntries, branchA), branchB)
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
   * regardless of branch - used on the dungeon's starting room (`dungeon.currentRoomId`, whichever
   * room that turns out to be - see the class doc), whose Prev door is never wired by anything
   * (there's nothing before it) but may still be authored, since its room type is drawn from the
   * same shared pool as mid-chain rooms that normally do need one. Rather than failing (a door is
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

  /** Room types whose chests can roll trapped. A Vault chest is the reward for a key already
   * spent, and Boss/MiniBoss/Rest/Sanctuary rooms are scripted beats rather than places for a random
   * ambush. Listing the eligible types, instead of excluding the ineligible ones, keeps any future
   * room type safe by default.
   */
  private val TrappedChestRoomTypes: Set[RoomType] = Set(RoomType.Combat, RoomType.Loot)

  /** Roll trapped status onto at most one chest per eligible room. Each [[Chest]] in a room rolls
   * independently at `difficulty.trappedChestChance`; once one chest in that room is trapped (rolled
   * here, or authored as trapped in rooms.json) no further chest in it rolls, capping a room at 1
   * trap. See [[TrappedChestRoomTypes]] for which rooms are eligible.
   *
   * Produces fresh Room/Chest copies for the returned Dungeon only - never mutates the
   * server-lifetime `pool` itself.
   */
  private def rollTrappedChests(dungeon: Dungeon, difficulty: Difficulty): Dungeon =
    val chance = difficulty.trappedChestChance
    val updatedRooms = dungeon.rooms.map:
      case (id, room) if !TrappedChestRoomTypes.contains(room.roomType) => id -> room
      case (id, room) =>
        var alreadyTrapped = room.entities.exists:
          case c: Chest => c.trapped
          case _        => false
        val newEntities = room.entities.map:
          case c: Chest if !alreadyTrapped && rng.nextDouble() < chance =>
            alreadyTrapped = true
            c.copy(trapped = true)
          case other => other
        id -> room.copy(entities = newEntities)
    dungeon.copy(rooms = updatedRooms)
