package roguelite.game.dungeon

import roguelite.engine.{ MinimapEdgeView, MinimapNodeView, MinimapSectionView, MinimapView }

import scala.annotation.tailrec

/** Builds the dungeon map the client draws, from a [[MinimapLayout]] and what the player has seen.
  *
  * Every placed room is on the map from the start, so the shape of the dungeon is known, but a room
  * the player has not been in only shows its type when [[RevealedInAdvance]] says so. The client
  * never receives a hidden room's real id or type: node ids are made up here, since an authored room
  * id such as "combat_003" would give the type away.
  */
object MinimapBuilder:

  /** Room types shown on the map before the player has been in the room: the fights and the end of
    * the run the player is heading for. Every other type stays hidden until visited.
    */
  private val RevealedInAdvance: Set[RoomType] = Set(RoomType.Boss, RoomType.MiniBoss, RoomType.Sanctuary)

  /** The map of `dungeon` as the player sees it now. The room the player is in is marked current
    * when it is on the map: a room off the map (a Vault) has no marker, so no node is current then.
    */
  def build(dungeon: Dungeon): MinimapView =
    val layout  = MinimapLayout.of(dungeon)
    val visited = dungeon.visitedRoomIds

    val closed = closedBranches(dungeon, layout, visited)

    val placed = layout.slots.toList.sortBy((id, slot) => (slot.section, slot.column, slot.lane, id))
    val nodeIds = placed.zipWithIndex.map((entry, index) => entry._1 -> s"n$index").toMap

    val nodes = placed.map: (id, slot) =>
      val roomType = dungeon.rooms(id).roomType
      val seen     = visited.contains(id)
      MinimapNodeView(
        id = nodeIds(id),
        roomType = Option.when(seen || RevealedInAdvance.contains(roomType))(typeName(roomType)),
        visited = seen,
        current = id == dungeon.currentRoomId,
        section = slot.section,
        column = slot.column,
        lane = slot.lane,
        closed = closed.contains(id)
      )

    val edges = layout.links.map: link =>
      MinimapEdgeView(nodeIds(link.from), nodeIds(link.to), link.exit.map(_.toString.toUpperCase))

    val sections = layout.themes.toList.sortBy((index, _) => index).map((index, theme) => MinimapSectionView(index, theme))

    MinimapView(sections, nodes, edges)

  /** The rooms of a fork's branch the player did not take, once the player is in the other one: the
    * way back to the Fork room is shut (see InteractionResolver), so they can no longer be reached.
    * A branch is what leads on from one of the fork's two exits before the two meet again, which
    * is everything reachable from that exit but not from the other.
    */
  private def closedBranches(dungeon: Dungeon, layout: MinimapLayout, visited: Set[String]): Set[String] =
    val onward = layout.links.groupMap(_.from)(_.to)

    def reach(from: String): Set[String] =
      @tailrec
      def loop(pending: List[String], seen: Set[String]): Set[String] = pending match
        case Nil => seen
        case id :: rest =>
          val fresh = onward.getOrElse(id, Nil).filterNot(seen.contains)
          loop(fresh ::: rest, seen ++ fresh)
      loop(List(from), Set(from))

    layout.slots.keys.toList
      .filter(id => dungeon.rooms(id).roomType == RoomType.Fork)
      .flatMap: fork =>
        onward.getOrElse(fork, Nil).distinct match
          case List(first, second) =>
            val taken = List((first, second), (second, first)).find((head, other) => visited(head) && !visited(other))
            taken.map((head, other) => reach(other) -- reach(head)).getOrElse(Set.empty)
          case _ => Set.empty[String]
      .toSet

  /** The wire name of a room type, the same spelling [[RoomType.fromString]] reads. */
  private def typeName(roomType: RoomType): String = roomType.toString.toLowerCase
