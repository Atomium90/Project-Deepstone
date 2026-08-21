package roguelite.engine

import io.circe.generic.semiauto.{ deriveDecoder, deriveEncoder }
import io.circe.syntax.*
import io.circe.{ Decoder, Encoder }
import roguelite.game.{ EquipSlot, Rarity, UpgradeCategory }

// -------------------
// Shared enumerations
// -------------------

enum Direction:
  case Up, Down, Left, Right

enum GamePhase:
  case Hub, Exploration, Combat, GameOver

enum CombatActionType:
  case Attack, Ability, Item, Defend

enum HubActionType:
  case StartRun, BuyUpgrade, ReturnToHub

enum ClassId:
  case Warrior, Archer, Mage

/** Run difficulty, chosen alongside the class at StartRun.
  *
  * Values are starting points meant to be adjusted after playtesting.
  */
enum Difficulty:
  case Easy, Normal, Hard

  /** Multiplier applied to enemy maxHp/attack/defense/xpReward at instance creation. */
  def statMultiplier: Double = this match
    case Difficulty.Easy   => 0.75
    case Difficulty.Normal => 1.0
    case Difficulty.Hard   => 1.25

  /** Total room count passed to [[roguelite.game.DungeonBuilder.build]]. Normal matches today's
    * fixed baseline.
    */
  def totalRooms: Int = this match
    case Difficulty.Easy   => 3
    case Difficulty.Normal => 4
    case Difficulty.Hard   => 6

  /** Relative weight multiplier applied to a loot candidate based on its rarity. Only Hard biases
    * toward Uncommon for now; Easy/Normal keep today's unweighted behavior.
    */
  def rarityWeightMultiplier(rarity: Rarity): Double = (this, rarity) match
    case (Difficulty.Hard, Rarity.Uncommon) => 2.0
    case _                                  => 1.0

// ---------------------------------------------
// Client → Server: player actions
// ---------------------------------------------

/** Every message the client can send to the server. */
sealed trait PlayerAction

/** Move the player one tile in the given direction. Only valid during exploration. */
case class Move(direction: Direction) extends PlayerAction

/** Interact with an entity in the current room (enemy, chest, door). */
case class Interact(targetId: String) extends PlayerAction

/** Perform a combat action during the player's turn. */
case class CombatAction(
    action: CombatActionType,
    abilityId: Option[String] = None,
    itemId: Option[String] = None
) extends PlayerAction

/** Perform a hub action (start a run, buy an upgrade, or return to hub after game over). */
case class HubAction(
    action: HubActionType,
    classId: Option[ClassId] = None,
    upgradeId: Option[String] = None,
    difficulty: Option[Difficulty] = None,
    perkId: Option[String] = None
) extends PlayerAction

/** Resolve a pending equip choice (see [[PendingEquipChoiceView]]). `targetSlot = None` means
  * "keep what's currently equipped, discard the new item"; `Some(slot)` equips the new item into
  * that slot, which must be one of the slots the pending choice actually offered.
  */
case class EquipChoice(targetSlot: Option[EquipSlot] = None) extends PlayerAction

// ---------------------------------------------
// Server → Client: state views (read-only snapshots)
// ---------------------------------------------

/** Lightweight view of the player sent to the client every update.
  *
  * @param affinityTags
  *   The class's affinity tags (see [[roguelite.game.ClassDef]]). Purely informational - every
  *   [[ItemView.statLine]] already reflects whether the item's own `typeTag` benefits from the
  *   affinity doubling CombatResolver applies (resolved server-side, see
  *   [[roguelite.engine.GameState]]'s `itemToView`), so the client never needs to compare this
  *   list against `ItemView.typeTag` itself - it's carried mainly so the UI can gray out an
  *   off-affinity item's stat line as a secondary visual cue.
  */
case class PlayerView(
    classId: ClassId,
    hp: Int,
    maxHp: Int,
    resourceCurrent: Int,
    resourceMax: Int,
    level: Int,
    xp: Int,
    metaCurrency: Int,
    affinityTags: List[String] = Nil
)

/** Describes one entity visible in the room (enemy, chest, door, etc.).
  *
  * @param spriteId
  *   Atlas sprite key to draw (see frontend/public/atlas/entities.json). Only set for enemies -
  *   resolved from enemies.json's spriteId at the [[roguelite.game.Room.toView]] boundary, not
  *   carried on the entity itself, so it's never a second place to remember when adding an enemy.
  *   Absent for every other kind, which each render with one fixed sprite per kind client-side.
  */
case class EntityView(
    id: String,
    kind: String, // "enemy" | "chest" | "door" | "locked_door" | "npc"
    x: Int,
    y: Int,
    label: String, // display name shown in the UI
    spriteId: Option[String] = None
)

/** One line of NPC dialogue to show the player, produced by an [[Interact]] on an [[roguelite.game.Npc]].
  * Transient - only present on the single [[StateUpdate]] the interaction produced, not persisted.
  */
case class DialogueView(npcName: String, line: String)

/** The current room's layout and its entities. */
case class RoomView(
    roomId: String,
    width: Int,
    height: Int,
    tiles: Vector[Vector[String]], // "floor" | "wall"
    entities: List[EntityView],
    playerX: Int,
    playerY: Int
)

/** Snapshot of the ongoing combat. Null-equivalent: wrapped in Option at the top level.
  *
  * @param spriteId
  *   Atlas sprite key for the enemy portrait (see frontend/public/atlas/entities.json), resolved
  *   from enemies.json's spriteId - same pattern as [[EntityView.spriteId]] in the exploration
  *   view, resolved at the [[CombatState.toStateUpdate]] boundary rather than carried on the
  *   entity itself.
  */
case class CombatView(
    enemyId: String,
    enemyLabel: String,
    enemyHp: Int,
    enemyMaxHp: Int,
    isPlayerTurn: Boolean,
    spriteId: Option[String] = None,
    isBoss: Boolean = false,
    /** The player's class ability, resolved through any active set/perk cost discount (see
      * [[roguelite.game.AbilityDef.effectiveCost]]) - the real cost to check affordability
      * against, not the static per-class catalog value in `StateUpdate.abilities`. `None` only if
      * the player's class has no loaded ability def. */
    abilityCost: Option[Int] = None
)

/** One damage or heal event produced by the action that generated this [[StateUpdate]]. Transient:
  * only present on the single update the event happened on, same convention as [[DialogueView]].
  *
  * @param targetIsPlayer
  *   True if the player took the hit/heal, false if the enemy did.
  * @param kind
  *   `"damage"` or `"heal"` - drives which color/direction the client's floating number uses.
  */
case class DamageEventView(targetIsPlayer: Boolean, amount: Int, kind: String, crit: Boolean = false)

/** Hub data: available upgrades and their unlock status. */
case class UpgradeView(id: String,
                       label: String,
                       description: String,
                       cost: Int,
                       icon: String,
                       category: UpgradeCategory,
                       unlocked: Boolean
)

/** One run perk offered to the player, rolled fresh every HUB visit (see `HubState.perkOptions`). */
case class PerkView(id: String, label: String, description: String, icon: String)

case class HubView(upgrades: List[UpgradeView], perks: List[PerkView] = Nil)

case class ItemView(
    id: String,
    typeId: String,
    name: String,
    kind: String,    // "weapon" | "armor" | "accessory" | "consumable"
    rarity: String,  // "common" | "uncommon"
    statLine: String, // e.g. "+3 ATK", "Heal 30 HP"
    /** Atlas sprite key hint for the item's icon. `None` until an icon pack is chosen and
      * items.json gains real values - the client falls back to a plain colored box, same
      * "missing asset renders as a graceful no-op" convention used elsewhere (see
      * [[roguelite.game.AudioManager]]-equivalent client-side handling for sound/music).
      */
    iconId: Option[String] = None,
    /** Which equipment set this item belongs to, if any. See [[SetView]] for the set's actual
      * 2pc/4pc bonus text.
      */
    setId: Option[String] = None,
    /** Weapon/Armor/Accessory's affinity tag ("heavy"/"ranged"/"magic"/"light"), if any. `None`
      * for Consumable/Key and for untagged gear. Purely informational for the UI (see
      * [[PlayerView.affinityTags]]) - `statLine` above already reflects the affinity-doubled
      * value when applicable, this isn't needed to compute that.
      */
    typeTag: Option[String] = None,
    /** Charge count for a potion-belt stack (see [[roguelite.game.PotionStack]]). `None` for
      * every other item kind, which never stacks - the client only shows a count badge when this
      * is present.
      */
    count: Option[Int] = None
)

/** One key kind the player is currently holding, with how many. Coarse: `keyKind` collapses
  * [[roguelite.game.KeyKind.Specific]]/[[roguelite.game.KeyKind.Typed]]'s payload away (e.g.
  * `"specific"`, not the door id it targets) since only [[roguelite.game.KeyKind.Generic]] has
  * real content today and the client only ever needs a coarse count badge.
  */
case class KeyCountView(keyKind: String, count: Int)

/** The player's full equipment loadout: weapon/armor/accessory slots (auto-equip on pickup, no
  * generic bag - see [[roguelite.game.EquipmentResolver]]), the potion belt, and key counts.
  * Replaces the old flat `inventory: List[ItemView]` field.
  *
  * @param accessories always length [[roguelite.engine.Player.AccessorySlotCount]] (2).
  * @param potionBelt  length [[roguelite.engine.Player.BasePotionBeltSlots]] (2), or 3 once the
  *                    `extra_slot` hub upgrade is unlocked.
  */
case class EquipmentView(
    weapon: Option[ItemView],
    armor: Option[ItemView],
    accessories: List[Option[ItemView]],
    potionBelt: List[Option[ItemView]],
    keys: List[KeyCountView]
)

/** One occupied slot the player could replace with the incoming item - see
  * [[PendingEquipChoiceView]]. `slot` is what an [[EquipChoice]] targeting this option must send
  * back.
  */
case class EquipChoiceOptionView(slot: EquipSlot, current: ItemView)

/** A pickup offered to the player because every slot matching `newItem`'s kind was already
  * occupied: weapon/armor degenerate to a single `options` entry, accessories/potions can offer
  * up to 2-3. Durable, not transient like [[DialogueView]] or the other `Option`/`List` fields on
  * [[StateUpdate]] below - it must still be present after a reconnect, or if the player sends an
  * unrelated action before resolving it with an [[EquipChoice]].
  */
case class PendingEquipChoiceView(newItem: ItemView, options: List[EquipChoiceOptionView])

/** Static description of one class's combat ability. Sent as a small catalog on every
  * [[StateUpdate]] (not just during combat) so the client never needs to hardcode ability names,
  * costs, or resource labels. See [[roguelite.engine.GameSession]].
  */
case class AbilityView(
    classId: ClassId,
    id: String,
    name: String,
    cost: Int,
    resourceName: String, // e.g. "Rage": the resource pool this ability spends
    description: String
)

/** One achievement's display state. Sent as a full catalog on every [[StateUpdate]] (same
  * rationale as [[AbilityView]]: the client never hardcodes the list), independent of phase.
  */
case class AchievementView(id: String, label: String, description: String, unlocked: Boolean)

/** Static description of one equipment set's 2pc/4pc bonus. Sent as a full catalog on every
  * [[StateUpdate]] (same rationale as [[AbilityView]]: the client never hardcodes set names or
  * bonus text), independent of phase. Whether a given set's bonus is currently *active* isn't
  * carried here - the client already receives `setId` on every equipped [[ItemView]] and can
  * count matching pieces itself, the same way it already resolves rarity/kind locally.
  */
case class SetView(id: String,
                   name: String,
                   classId: ClassId,
                   bonus2pcLabel: String,
                   bonus4pcLabel: String
)

/** Full game state snapshot sent by the server after every action. */
case class StateUpdate(
    phase: GamePhase,
    player: PlayerView,
    room: Option[RoomView] = None,
    combat: Option[CombatView] = None,
    hub: Option[HubView] = None,
    /** Defaults to fully empty so tests/fixtures that don't care about equipment can keep using
      * the shorter constructor, same convention as [[roguelite.game.EnemyStats]]-carrying fields
      * elsewhere in this codebase.
      */
    equipment: EquipmentView = EquipmentView(
      weapon = None,
      armor = None,
      accessories = List.fill(Player.AccessorySlotCount)(None),
      potionBelt = List.fill(Player.BasePotionBeltSlots)(None),
      keys = Nil
    ),
    /** A pickup awaiting a keep/replace decision. Durable, not transient - see
      * [[PendingEquipChoiceView]]. Resolved with an [[EquipChoice]] action.
      */
    pendingEquipChoice: Option[PendingEquipChoiceView] = None,
    abilities: List[AbilityView] = Nil,
    achievements: List[AchievementView] = Nil,
    sets: List[SetView] = Nil,
    /** Only meaningful when phase is GameOver: true if the dungeon's boss was defeated, false if
      * the player died.
      */
    victory: Boolean = false,
    log: List[String] = Nil,
    dialogue: Option[DialogueView] = None,
    /** Achievements newly earned by the action that produced this update. Transient: only
      * non-empty on the single update where one or more achievements were just unlocked, same
      * convention as [[dialogue]]. A list, not an Option, since a single action can plausibly earn
      * more than one at once (e.g. a kill that is simultaneously a first kill and a level-up).
      */
    newlyUnlocked: List[AchievementView] = Nil,
    /** Damage/heal events produced by the action that generated this update. Transient, same
      * convention as [[newlyUnlocked]] - a list since a single action can produce more than one
      * (e.g. the player attacks and the enemy counter-attacks in the same response).
      */
    damageEvents: List[DamageEventView] = Nil,
    /** Sound cue tags (e.g. "pickup", "door_open") produced by the action that generated this
      * update. Transient, same convention as [[damageEvents]]. Plain strings rather than a
      * wrapper view: there's no extra structure to carry, just a client-side sound lookup key.
      */
    soundEvents: List[String] = Nil
)

// ---------------------------------------------
// Circe codecs
// ---------------------------------------------

object MessageProtocol:

  // Enums: encoded as plain strings
  given Encoder[Direction] = Encoder[String].contramap(_.toString.toUpperCase)
  given Decoder[Direction] = Decoder[String].emap(
    s =>
      Direction.values
        .find(_.toString.toUpperCase == s.toUpperCase)
        .toRight(s"Unknown direction: $s")
  )

  given Encoder[GamePhase] = Encoder[String].contramap(_.toString.toUpperCase)
  given Decoder[GamePhase] = Decoder[String].emap(
    s =>
      GamePhase.values.find(_.toString.toUpperCase == s.toUpperCase).toRight(s"Unknown phase: $s")
  )

  given Encoder[CombatActionType] = Encoder[String].contramap(_.toString.toUpperCase)
  given Decoder[CombatActionType] = Decoder[String].emap(
    s =>
      CombatActionType.values
        .find(_.toString.toUpperCase == s.toUpperCase)
        .toRight(s"Unknown combat action: $s")
  )

  given Encoder[HubActionType] = Encoder[String].contramap(_.toString.toUpperCase)
  given Decoder[HubActionType] = Decoder[String].emap(
    s =>
      HubActionType.values
        .find(_.toString.toUpperCase == s.toUpperCase)
        .toRight(s"Unknown hub action: $s")
  )

  given Encoder[ClassId] = Encoder[String].contramap(_.toString.toLowerCase)
  given Decoder[ClassId] = Decoder[String].emap(
    s => ClassId.values.find(_.toString.toLowerCase == s.toLowerCase).toRight(s"Unknown class: $s")
  )

  given Encoder[Difficulty] = Encoder[String].contramap(_.toString.toLowerCase)
  given Decoder[Difficulty] = Decoder[String].emap(
    s =>
      Difficulty.values
        .find(_.toString.toLowerCase == s.toLowerCase)
        .toRight(s"Unknown difficulty: $s")
  )

  given Encoder[UpgradeCategory] = Encoder[String].contramap(_.toString.toLowerCase)
  given Decoder[UpgradeCategory] = Decoder[String].emap(
    s =>
      UpgradeCategory.values
        .find(_.toString.toLowerCase == s.toLowerCase)
        .toRight(s"Unknown upgrade category: $s")
  )

  // EquipSlot: hand-written (not the toString.toUpperCase pattern above) since two cases carry
  // an index - WEAPON / ARMOR / ACCESSORY_0 / ACCESSORY_1 / POTION_0 / POTION_1 / POTION_2.
  given Encoder[EquipSlot] = Encoder[String].contramap {
    case EquipSlot.WeaponSlot       => "WEAPON"
    case EquipSlot.ArmorSlot        => "ARMOR"
    case EquipSlot.AccessorySlot(i) => s"ACCESSORY_$i"
    case EquipSlot.PotionSlot(i)    => s"POTION_$i"
  }
  given Decoder[EquipSlot] = Decoder[String].emap { s =>
    s.toUpperCase match {
      case "WEAPON" => Right(EquipSlot.WeaponSlot)
      case "ARMOR"  => Right(EquipSlot.ArmorSlot)
      case other if other.startsWith("ACCESSORY_") =>
        other.stripPrefix("ACCESSORY_").toIntOption
          .map(EquipSlot.AccessorySlot.apply)
          .toRight(s"Unknown equip slot: $s")
      case other if other.startsWith("POTION_") =>
        other.stripPrefix("POTION_").toIntOption
          .map(EquipSlot.PotionSlot.apply)
          .toRight(s"Unknown equip slot: $s")
      case _ => Left(s"Unknown equip slot: $s")
    }
  }

  // Actions (client → server)
  given Encoder[Move]         = deriveEncoder
  given Decoder[Move]         = deriveDecoder
  given Encoder[Interact]     = deriveEncoder
  given Decoder[Interact]     = deriveDecoder
  given Encoder[CombatAction] = deriveEncoder
  given Decoder[CombatAction] = deriveDecoder
  given Encoder[HubAction]    = deriveEncoder
  given Decoder[HubAction]    = deriveDecoder
  given Encoder[EquipChoice]  = deriveEncoder
  given Decoder[EquipChoice]  = deriveDecoder

  /** Decode a raw JSON string into a PlayerAction. The JSON must include a "type" discriminator
    * field: { "type": "MOVE", "direction": "UP" }
    */
  def decodeAction(json: String): Either[String, PlayerAction] =
    import io.circe.parser.parse
    for
      cursor     <- parse(json).left.map(_.message)
      actionType <- cursor.hcursor.get[String]("type").left.map(_.message)
      action <- actionType.toUpperCase match {
        case "MOVE"          => cursor.as[Move].left.map(_.message)
        case "INTERACT"      => cursor.as[Interact].left.map(_.message)
        case "COMBAT_ACTION" => cursor.as[CombatAction].left.map(_.message)
        case "HUB_ACTION"    => cursor.as[HubAction].left.map(_.message)
        case "EQUIP_CHOICE"  => cursor.as[EquipChoice].left.map(_.message)
        case other           => Left(s"Unknown action type: $other")
      }
    yield action

  // Views (server → client)
  given Encoder[PlayerView]  = deriveEncoder
  given Encoder[EntityView]  = deriveEncoder
  given Encoder[RoomView]    = deriveEncoder
  given Encoder[CombatView]  = deriveEncoder
  given Encoder[DamageEventView] = deriveEncoder
  given Encoder[UpgradeView]  = deriveEncoder
  given Encoder[PerkView]     = deriveEncoder
  given Encoder[HubView]      = deriveEncoder
  given Encoder[ItemView]     = deriveEncoder
  given Encoder[KeyCountView] = deriveEncoder
  given Encoder[EquipmentView] = deriveEncoder
  given Encoder[EquipChoiceOptionView]   = deriveEncoder
  given Encoder[PendingEquipChoiceView]  = deriveEncoder
  given Encoder[AbilityView]     = deriveEncoder
  given Encoder[AchievementView] = deriveEncoder
  given Encoder[SetView]         = deriveEncoder
  given Encoder[DialogueView]    = deriveEncoder
  given Encoder[StateUpdate]     = deriveEncoder

  /** Serialize a StateUpdate to a JSON string to be sent over the WebSocket. */
  def encodeUpdate(update: StateUpdate): String = update.asJson.noSpaces
