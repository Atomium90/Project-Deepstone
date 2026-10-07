package roguelite.game

import roguelite.engine.{ MinimapEdgeView, MinimapNodeView, MinimapSectionView, MinimapView }

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
        lane = slot.lane
      )

    val edges = layout.links.map: link =>
      MinimapEdgeView(nodeIds(link.from), nodeIds(link.to), link.exit.map(_.toString.toUpperCase))

    val sections = layout.themes.toList.sortBy((index, _) => index).map((index, theme) => MinimapSectionView(index, theme))

    MinimapView(sections, nodes, edges)

  /** The wire name of a room type, the same spelling [[RoomType.fromString]] reads. */
  private def typeName(roomType: RoomType): String = roomType.toString.toLowerCase
