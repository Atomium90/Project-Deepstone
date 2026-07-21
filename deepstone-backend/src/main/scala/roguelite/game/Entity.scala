package roguelite.game

import roguelite.engine.{ Direction, EntityView }

/** An interactive object placed on a tile in a room.
  *
  * All entities are static during exploration — they do not move. The player interacts with them by
  * clicking (INTERACT action).
  */
sealed trait Entity:
  def id: String
  def x: Int
  def y: Int

  /** Project to the lightweight view sent to the client. */
  def toView: EntityView

/** A hostile creature. Clicking it starts a combat.
  *
  * @param typeId
  *   Matches a key in enemies.json — used to look up combat stats.
  * @param label
  *   Display name shown in the UI and combat log.
  */
case class Enemy(
    id: String,
    x: Int,
    y: Int,
    typeId: String,
    label: String
) extends Entity:
  def toView: EntityView = EntityView(id = id, kind = "enemy", x = x, y = y, label = label)

/** A loot container. Clicking it grants items - unless it's trapped, in which case it spawns
  * enemies instead.
  *
  * @param trapped
  *   Not exposed to the client via [[toView]] - staying trapped should be a surprise.
  */
case class Chest(
    id: String,
    x: Int,
    y: Int,
    trapped: Boolean = false
) extends Entity:
  def toView: EntityView = EntityView(id = id, kind = "chest", x = x, y = y, label = "Chest")

/** A passage to an adjacent room. Clicking it navigates to that room. */
case class Door(
    id: String,
    x: Int,
    y: Int,
    direction: Direction,
    targetRoomId: String
) extends Entity:
  def toView: EntityView =
    EntityView(id = id, kind = "door", x = x, y = y, label = direction.toString)

/** A passage gated by a matching [[Key]] in the player's inventory. Once `unlocked`, behaves
  * exactly like a normal [[Door]].
  *
  * @param doorTag
  *   Unused by [[KeyKind.Generic]] (reserved for future [[KeyKind.Typed]] content).
  */
case class LockedDoor(
    id: String,
    x: Int,
    y: Int,
    direction: Direction,
    targetRoomId: String,
    doorTag: Option[String] = None,
    unlocked: Boolean = false
) extends Entity:
  def toView: EntityView =
    EntityView(id = id, kind = "locked_door", x = x, y = y, label = direction.toString)
