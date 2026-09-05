package roguelite.game

import roguelite.engine.{ Direction, EntityView }

/** An interactive object placed on a tile in a room.
  *
  * All entities are static during exploration: they do not move. The player interacts with them by
  * pressing E (INTERACT action).
  */
sealed trait Entity:
  def id: String
  def x: Int
  def y: Int

  /** Project to the lightweight view sent to the client. */
  def toView: EntityView

/** A hostile creature. Interacting with it starts a combat.
  *
  * @param typeId
  *   Matches a key in enemies.json, used to look up combat stats.
  * @param label
  *   Display name shown in the UI and combat log.
  * @param isElite
  *   Rolled once at dungeon build time (see [[DungeonBuilder]]), never re-rolled on room
  *   re-entry. Unlike `Chest.trapped`/`Door.doorKind`, which deliberately stay hidden from
  *   `toView`, this flag is surfaced to the client - Elite status must be visible before the
  *   player engages, not a combat-time surprise.
  */
case class Enemy(
    id: String,
    x: Int,
    y: Int,
    typeId: String,
    label: String,
    isElite: Boolean = false
) extends Entity:
  def toView: EntityView = EntityView(id = id, kind = "enemy", x = x, y = y, label = label, isElite = Some(isElite))

/** A loot container. Interacting with it grants items - unless it's trapped, in which case it spawns
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

/** Sub-behavior of a [[Door]]. Normal doors always navigate to their resolved target; Trapped doors
  * ignore it and kick the player back through the room's entrance instead; Secret doors stay
  * absent from the client's [[EntityView]] (and their tile stays a Wall) until `revealed`.
  */
enum DoorKind:
  case Normal, Trapped, Secret

/** Which end of a room-to-room connection a [[Door]] represents. Purely topological - decoupled
  * from [[Direction]], which stays geometry-only (which wall the door sits on).
  */
enum ConnectorRole:
  case Next, Prev

object ConnectorRole:
  /** Parse a role from the string format used in rooms.json. Fails on anything but an exact
    * "next"/"prev" match, so a typo in content surfaces as a load-time error instead of silently
    * producing a dead connector nobody matches. */
  def fromString(s: String): Either[String, ConnectorRole] = s.toLowerCase match {
    case "next" => Right(ConnectorRole.Next)
    case "prev" => Right(ConnectorRole.Prev)
    case other  => Left(s"Unknown connector role: '$other'")
  }

/** Where a [[Door]] leads. `Unresolved` is a stub awaiting [[DungeonBuilder]] to pick a neighbor at
  * build time; `Resolved` already has one - either because the builder filled it in, or (rarely)
  * because it was authored with a fixed target from the start (e.g. a Vault's return door).
  *
  * @param branch
  *   Distinguishes multiple doors sharing the same `role` in one room (a fork's two exits, a
  *   merge's two entrances) - `None` for every room with at most one door per role. Deliberately a
  *   free string, not a closed enum: a typo here can't silently resolve to the wrong neighbor, it
  *   just won't match any topology edge, so [[DungeonBuilder.build]] fails loudly at wiring-
  *   validation time instead - the same pattern as its existing "LockedDoor references unknown
  *   room" check.
  */
enum DoorLink:
  case Unresolved(role: ConnectorRole, branch: Option[String] = None)
  case Resolved(role: ConnectorRole, branch: Option[String], roomId: String)

object DoorLink:
  extension (link: DoorLink)
    def role: ConnectorRole = link match {
      case DoorLink.Unresolved(role, _)  => role
      case DoorLink.Resolved(role, _, _) => role
    }

/** A passage to an adjacent room. Interacting with it navigates to `link`'s resolved room. */
case class Door(
    id: String,
    x: Int,
    y: Int,
    direction: Direction,
    link: DoorLink,
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

/** A static, friendly character. Interacting with it shows one line of dialogue and advances to the
  * next on later interactions - see [[InteractionResolver]] for the cooldown/rotation rules.
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

/** A reward marker left behind by a defeated [[RoomType.MiniBoss]] enemy (see
  * [[CombatResolver.victory]]) - never authored in rooms.json, only ever created at runtime.
  * Interacting with it rolls 3 candidate items and offers a choice (see
  * [[roguelite.game.RewardChoiceResolver]]), unlike [[Chest]]'s single immediate-resolve drop.
  */
case class Shrine(id: String, x: Int, y: Int) extends Entity:
  def toView: EntityView = EntityView(id = id, kind = "shrine", x = x, y = y, label = "Shrine")
