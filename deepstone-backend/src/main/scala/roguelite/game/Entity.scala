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

/** Sub-behavior of a [[Door]]. Normal doors always navigate to `targetRoomId`; Trapped doors
  * ignore it and kick the player back through the room's entrance instead; Secret doors stay
  * absent from the client's [[EntityView]] (and their tile stays a Wall) until `revealed`.
  */
enum DoorKind:
  case Normal, Trapped, Secret

/** A passage to an adjacent room. Clicking it navigates to that room. */
case class Door(
    id: String,
    x: Int,
    y: Int,
    direction: Direction,
    targetRoomId: String,
    doorKind: DoorKind = DoorKind.Normal,
    revealed: Boolean = true
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

/** A static, friendly character. Clicking it shows one line of dialogue and advances to the next
  * on later interactions - see [[InteractionResolver]] for the cooldown/rotation rules.
  *
  * @param name
  *   Duplicated from the matching entry in npcs.json (same pattern as [[Enemy.label]] duplicating
  *   enemies.json), so [[toView]] never needs the dialogue catalog just to render a label.
  * @param dialogueIndex
  *   How many lines of the main `dialogue` list have been shown so far. Internal only, never
  *   exposed via [[toView]] (same convention as [[Chest.trapped]]/[[Door.doorKind]]).
  * @param fallbackIndex
  *   `None` until the main list is exhausted and the first fallback line is shown; `Some(i)` means
  *   fallback line `i` was shown last, so the next one is `(i + 1) % fallbackDialogue.length`.
  *   Kept as `Option` rather than defaulting to 0 so the first fallback trigger doesn't skip
  *   `fallbackDialogue(0)`.
  * @param lastShown
  *   The (timestamp, line) last displayed, both set together. While defined and within
  *   [[InteractionResolver.NpcInteractCooldownMillis]] of now, re-interacting redisplays this same
  *   line instead of advancing - so a misclick can't skip past a line before it's been read.
  */
case class Npc(
    id: String,
    x: Int,
    y: Int,
    name: String,
    dialogueIndex: Int = 0,
    fallbackIndex: Option[Int] = None,
    lastShown: Option[(Long, String)] = None
) extends Entity:
  def toView: EntityView = EntityView(id = id, kind = "npc", x = x, y = y, label = name)
