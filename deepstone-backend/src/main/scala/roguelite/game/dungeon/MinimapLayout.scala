package roguelite.game

import roguelite.engine.Direction

import scala.annotation.tailrec

/** Where each room of a dungeon sits on the map, worked out from the doors that lead forward
  * ([[ConnectorRole.Next]]) and nothing else. Vault rooms are left out, as is any room no forward
  * path reaches. Every forward door counts, a secret one included: no authored room has a secret
  * door today, and a map that hid one would need a rule for the rooms behind it.
  *
  * A section is a run of rooms that ends at a [[RoomType.Rest]]: the Rest closes the section it
  * follows, and the room after it opens the next one.
  *
  * @param slots
  *   Where each placed room sits, by room id.
  * @param links
  *   Every forward connection between two placed rooms, once per pair of rooms.
  * @param themes
  *   The theme of each section, taken from the room the section starts with.
  */
case class MinimapLayout(slots: Map[String, MinimapLayout.Slot],
                         links: List[MinimapLayout.Link],
                         themes: Map[Int, String]
)

object MinimapLayout:

  /** @param section
    *   Index of the section the room belongs to, from 0.
    * @param column
    *   How far along its section's row the room is, from 0. A room where branches meet sits one
    *   column past the longest branch.
    * @param lane
    *   0 for the upper branch of a fork and for every room outside one, 1 for the lower branch.
    */
  case class Slot(section: Int, column: Int, lane: Int)

  /** @param exit
    *   The wall the door sits on, only for a link leaving a fork: it tells the branches apart.
    */
  case class Link(from: String, to: String, exit: Option[Direction])

  private case class Forward(to: String, branch: Option[String], direction: Direction)

  private case class Placed(depth: Int, section: Int, lane: Int)

  def of(dungeon: Dungeon): MinimapLayout =
    val rooms   = dungeon.rooms.filterNot((_, room) => room.roomType == RoomType.Vault)
    val forward = rooms.map((id, room) => id -> forwardDoors(room, rooms.keySet))
    val order   = topologicalOrder(forward)
    val placed  = place(order, forward, rooms)

    val firstDepth = placed.values.groupMapReduce(_.section)(_.depth)(_ min _)
    val slots = placed.map((id, p) => id -> Slot(p.section, p.depth - firstDepth(p.section), p.lane))
    val links = order
      .flatMap: id =>
        val branching = branches(forward(id))
        branching.map(f => Link(id, f.to, Option.when(branching.sizeIs > 1)(f.direction)))
      .filter(link => placed.contains(link.to))
    val themes = placed.toList.groupBy((_, p) => p.section).map: (section, members) =>
      val (firstId, _) = members.minBy((id, p) => (p.depth, id))
      section -> rooms(firstId).theme

    MinimapLayout(slots, links, themes)

  /** The forward doors of a room that lead to a room still in the map. */
  private def forwardDoors(room: Room, known: Set[String]): List[Forward] =
    room.entities.flatMap:
      case door: Door =>
        door.link match
          case DoorLink.Resolved(ConnectorRole.Next, branch, target) if known.contains(target) =>
            List(Forward(target, branch, door.direction))
          case _ => Nil
      case _ => Nil

  /** The distinct rooms a room leads to, in the order they are drawn from the top lane down: the
    * door that comes first in the order up, right, down, left is on top, and two doors on the same
    * wall are told apart by their branch tag. Ordering by the door, rather than by which branch
    * holds what, keeps a lane from standing for any particular kind of branch.
    */
  private def branches(outs: List[Forward]): List[Forward] =
    outs.sortBy(f => (wallRank(f.direction), f.branch.getOrElse(""))).distinctBy(_.to)

  private def wallRank(direction: Direction): Int = direction match
    case Direction.Up    => 0
    case Direction.Right => 1
    case Direction.Down  => 2
    case Direction.Left  => 3

  /** Rooms in an order where every room comes after all the rooms that lead into it. A room that
    * can never be reached this way (a cycle) is left out.
    */
  private def topologicalOrder(forward: Map[String, List[Forward]]): List[String] =
    val targets  = forward.view.mapValues(_.map(_.to).distinct).toMap
    val indegree = targets.values.flatten.groupMapReduce(identity)(_ => 1)(_ + _)
    val roots    = forward.keys.filterNot(indegree.contains).toList.sorted

    @tailrec
    def loop(ready: List[String], remaining: Map[String, Int], acc: List[String]): List[String] =
      ready match
        case Nil => acc.reverse
        case id :: rest =>
          val (left, freed) = targets(id).foldLeft((remaining, List.empty[String])):
            case ((counts, freedSoFar), target) =>
              val count = counts(target) - 1
              (counts.updated(target, count), if count == 0 then target :: freedSoFar else freedSoFar)
          loop(rest ::: freed.sorted, left, id :: acc)

    loop(roots, indegree, Nil)

  /** Depth, section and lane of every room in `order`. A room's depth is one past its deepest
    * predecessor, which puts a merge room after the longest branch. A fork hands its first branch
    * its own lane and each next one the lane below, and a room reached from several lanes takes the
    * highest.
    */
  private def place(order: List[String],
                    forward: Map[String, List[Forward]],
                    rooms: Map[String, Room]
  ): Map[String, Placed] =
    val before = forward.toList
      .flatMap((from, outs) => outs.map(_.to).distinct.map(_ -> from))
      .groupMap(_._1)(_._2)

    val (placed, _) = order.foldLeft((Map.empty[String, Placed], Map.empty[String, List[Int]])):
      case ((done, offers), id) =>
        val preds = before.getOrElse(id, Nil)
        val depth = preds.map(p => done(p).depth + 1).maxOption.getOrElse(0)
        val section = preds
          .map(p => done(p).section + (if rooms(p).roomType == RoomType.Rest then 1 else 0))
          .maxOption
          .getOrElse(0)
        val lane = offers.getOrElse(id, Nil).minOption.getOrElse(0)
        val offered = branches(forward(id)).zipWithIndex.foldLeft(offers):
          case (acc, (branch, index)) => acc.updated(branch.to, (lane + index) :: acc.getOrElse(branch.to, Nil))
        (done.updated(id, Placed(depth, section, lane)), offered)

    placed
