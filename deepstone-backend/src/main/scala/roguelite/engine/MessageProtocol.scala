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
  case StartRun, BuyUpgrade, ReturnToHub, DebugLoadRoom

enum ClassId:
  case Warrior, Archer, Mage

/** How the player answers the light of the Sanctuary (see [[EndingChoice]]). */
enum EndingAnswer:
  /** Go into the light. */
  case Enter

  /** Let an NPC whose story is over take the player's place. */
  case Replace

  /** Refuse the light, and leave. */
  case Refuse

/** Run difficulty, chosen alongside the class at StartRun.
  *
  * Values are starting points meant to be adjusted after playtesting.
  *
  * `Hardcore` is the descent without the god: it has the sections, Elites, traps and loot of `Hard`,
  * its enemies are half again as strong (x1.5, to be brought down to Hard's x1.25 if that proves too
  * much), and the run takes no blessing. It is opened by the story, not by victories.
  */
enum Difficulty:
  case Easy, Normal, Hard, Hardcore

  /** Whether a run on this tier takes the god's blessings: the effects of the hub upgrades, the class's
    * starting kit, a run perk, and the Shards its kills earn. The descent without the god takes none.
    * What a class unlock gives is access, not power, and is kept.
    */
  def usesBlessings: Boolean = this != Difficulty.Hardcore

  /** Multiplier applied to enemy maxHp/attack/defense/xpReward at instance creation. */
  def statMultiplier: Double = this match
    case Difficulty.Easy   => 0.75
    case Difficulty.Normal => 1.0
    case Difficulty.Hard     => 1.25
    case Difficulty.Hardcore => 1.5

  /** Number of sequential sections in a run, passed to [[roguelite.game.DungeonBuilder.build]].
    * Every section follows the same fixed room template and ends in its own
    * [[roguelite.game.RoomType.Boss]] room; every section but the last is followed by a guaranteed
    * [[roguelite.game.RoomType.Rest]] room before the next one starts (the last section ends at the
    * dungeon's one [[roguelite.game.RoomType.Sanctuary]] instead). Same per-difficulty scaling
    * convention as [[eliteChance]] - harder means more content, not just tougher content.
    */
  def biomeCount: Int = this match
    case Difficulty.Easy   => 1
    case Difficulty.Normal => 2
    case Difficulty.Hard | Difficulty.Hardcore => 3

  /** Per-enemy probability of rolling Elite at dungeon build time (see
    * [[roguelite.game.DungeonBuilder]]). Higher on harder difficulties.
    */
  def eliteChance: Double = this match
    case Difficulty.Easy   => 0.08
    case Difficulty.Normal => 0.10
    case Difficulty.Hard | Difficulty.Hardcore => 0.12

  /** Per-chest probability of being trapped, rolled at dungeon build time (see
    * [[roguelite.game.DungeonBuilder]]). Higher on harder difficulties. A room never holds more than
    * one rolled trap, so the share of rooms that actually end up trapped is lower than this suggests
    * once a room has several chests.
    */
  def trappedChestChance: Double = this match
    case Difficulty.Easy   => 0.08
    case Difficulty.Normal => 0.12
    case Difficulty.Hard | Difficulty.Hardcore => 0.16

  /** Per-door probability of being trapped, rolled at dungeon build time (see
    * [[roguelite.game.DungeonBuilder]]). Lower than [[trappedChestChance]]: a trapped door forces a
    * fight to get through, where a chest can simply be left closed.
    */
  def trappedDoorChance: Double = this match
    case Difficulty.Easy   => 0.04
    case Difficulty.Normal => 0.06
    case Difficulty.Hard | Difficulty.Hardcore => 0.08

  /** Relative weight multiplier applied to a loot candidate based on its rarity. Only Hard biases
    * toward Uncommon for now; Easy/Normal keep today's unweighted behavior.
    */
  def rarityWeightMultiplier(rarity: Rarity): Double = (this, rarity) match
    case (Difficulty.Hard | Difficulty.Hardcore, Rarity.Uncommon) => 2.0
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

/** Perform a hub action (start a run, buy an upgrade, return to hub after game over, or - dev
  * tooling only - load a single hand-converted Tiled room for a live preview).
  */
case class HubAction(
    action: HubActionType,
    classId: Option[ClassId] = None,
    upgradeId: Option[String] = None,
    difficulty: Option[Difficulty] = None,
    perkId: Option[String] = None,
    /** Only meaningful for `DebugLoadRoom` - the id of a room file under the backend's
      * `debug-rooms/` folder (see [[roguelite.engine.GameSession.handleDebugLoadRoom]]), without
      * the `.json` extension. The available ids are advertised to the client via
      * [[StateUpdate.debugRooms]].
      */
    debugRoomId: Option[String] = None
) extends PlayerAction

/** Resolve a pending equip choice (see [[PendingEquipChoiceView]]). `targetSlot = None` means
  * "keep what's currently equipped, discard the new item"; `Some(slot)` equips the new item into
  * that slot, which must be one of the slots the pending choice actually offered.
  */
case class EquipChoice(targetSlot: Option[EquipSlot] = None) extends PlayerAction

/** Resolve a pending Shrine reward choice (see [[PendingRewardChoiceView]]). `itemId = None` means
  * "walk away, take nothing"; `Some(id)` picks that option, which must be one of the ids the
  * pending choice actually offered.
  */
case class RewardChoice(itemId: Option[String] = None) extends PlayerAction

/** The player has read the pending conversation to its last line (see [[PendingSceneView]]).
  * `accept` answers the vow it ended by offering: `Some(true)` takes it, `Some(false)` turns it
  * down. It is present exactly when the conversation offered one, otherwise the server refuses it.
  */
case class SceneDone(accept: Option[Boolean] = None) extends PlayerAction

/** The player has read the card the server asked to show (see `StateUpdate.pendingCard`). The server
  * only accepts the card it is waiting for, and marks it seen so it is not shown again.
  */
case class CardSeen(cardId: String) extends PlayerAction

/** The player's answer to the light of the Sanctuary (see [[PendingEndingChoiceView]]). `npc` is who
  * takes the player's place: it is present exactly for [[EndingAnswer.Replace]], and must be one of
  * the candidates the question listed.
  */
case class EndingChoice(answer: EndingAnswer, npc: Option[String] = None) extends PlayerAction

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
  * @param isElite
  *   Only set for enemies, mirrors [[roguelite.game.Enemy.isElite]] directly (rolled once at
  *   dungeon build time, see [[roguelite.game.DungeonBuilder]]). Unlike `Chest.trapped`/
  *   `Door.doorKind`, which deliberately stay hidden from the client, Elite status must be visible
  *   before the player engages - the whole point is anticipation, not a combat-time surprise.
  */
case class EntityView(
    id: String,
    kind: String, // "enemy" | "chest" | "door" | "locked_door" | "npc" | "shrine" | "sanctuary"
    x: Int,
    y: Int,
    label: String, // display name shown in the UI
    spriteId: Option[String] = None,
    isElite: Option[Boolean] = None,
    /** Only set for "door"/"locked_door" - "UP"/"DOWN"/"LEFT"/"RIGHT", which wall of the room
      * this door sits on. The client needs this to orient its 2-tile door sprite correctly (the
      * sprite's native art faces a top-wall door; every other wall rotates/flips it - see
      * Renderer.ts's drawDoor).
      */
    direction: Option[String] = None,
    /** Only set for "chest" - "closed" | "open_full" | "open_empty" | "sprung" (see
      * [[roguelite.game.ChestState]]). The client picks the chest's sprite from it and knows whether
      * pressing E can still do anything. A trapped chest reports "closed" until its trap goes off,
      * so this never gives the trap away.
      */
    state: Option[String] = None
)

/** A line of the god's voice to show, said at the start of a run or when the player dies. The server
  * only says which kind of line and in which tone: the lines are texts of the client's language file,
  * under `story.god.<moment>.<phase>.<n>`, and the client picks one of them.
  *
  * @param moment
  *   When the voice speaks: "start", or "death.first", "death.streak", "death.boss", "death.depth" or
  *   "death.ordinary" for what it says about a death.
  * @param phase
  *   The tone of the voice: "a" (grateful host), "b" (possessive) or "c" (afraid of being forgotten).
  */
case class GodLineView(moment: String, phase: String)

/** The current room's layout and its entities.
  *
  * @param theme
  *   Which client-side sprite set to render this room with (e.g. "dungeon", "darkDungeon") - see
  *   [[roguelite.game.Room]]'s own doc.
  * @param floorSprite
  *   Same shape as `tiles`, one optional explicit floor sprite key per cell - meaningful
  *   regardless of whether that cell is "floor" or "wall" (a wall cell still has a floor drawn
  *   underneath it client-side). `None` means "use the room's `theme` default floor". See
  *   [[roguelite.game.Room]]'s own doc for the full authoring picture.
  * @param wallSprite
  *   Same shape as `tiles`, one optional explicit wall sprite key per cell - only meaningful where
  *   `tiles` is "wall". `None` means "use the room's `theme` default wall". See
  *   [[roguelite.game.Room]]'s own doc.
  * @param decoration
  *   Same shape as `tiles` again, one optional sprite drawn *on top of* whatever `floorSprite`/
  *   `wallSprite`/the theme default already resolved for that cell - never a replacement. See
  *   [[roguelite.game.Room]]'s own doc.
  */
case class RoomView(
    roomId: String,
    width: Int,
    height: Int,
    tiles: Vector[Vector[String]], // "floor" | "wall"
    theme: String,
    floorSprite: Vector[Vector[Option[String]]],
    wallSprite: Vector[Vector[Option[String]]],
    decoration: Vector[Vector[Option[String]]],
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
    /** True when the current room is a [[roguelite.game.RoomType.MiniBoss]] checkpoint. Resolved
      * the same way as `isBoss` (from the room's type, not a per-enemy flag) - deliberately
      * cosmetic only: unlike `isBoss`, this never drives run-ending logic, see
      * [[roguelite.game.Dungeon.isAtBoss]]. */
    isMiniBoss: Boolean = false,
    /** Mirrors [[roguelite.game.EnemyInstance.isElite]], resolved at
      * [[CombatState.toStateUpdate]] the same way `isBoss` is - always present once combat starts,
      * unlike the exploration-side [[EntityView.isElite]] which is optional. */
    isElite: Boolean = false,
    /** The player's class ability, resolved through any active set/perk cost discount (see
      * [[roguelite.game.AbilityDef.effectiveCost]]) - the real cost to check affordability
      * against, not the static per-class catalog value in `StateUpdate.abilities`. `None` only if
      * the player's class has no loaded ability def. */
    abilityCost: Option[Int] = None
)

/** One damage or heal event produced by the action that generated this [[StateUpdate]]. Transient:
  * only present on the single update the event happened on.
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

/** How far a save is from unlocking a difficulty tier: `winsDone` of the `wins` needed on tier
  * `winsAt`, and `shardsSpentDone` of the `shardsSpent` needed.
  */
case class TierRequirementView(winsAt: Difficulty,
                               wins: Int,
                               winsDone: Int,
                               shardsSpent: Int,
                               shardsSpentDone: Int
)

/** One difficulty tier as the hub shows it. `requirement` is `None` for a tier that needs nothing. */
case class DifficultyView(id: Difficulty, unlocked: Boolean, requirement: Option[TierRequirementView] = None)

/** @param runsCompleted
  *   How many runs the player has finished so far, won or lost. Lets the client tell a brand-new
  *   save (0) from a returning one, so the first run can skip the hub. Resolved by
  *   [[GameSession]] from the achievement stats, not carried by [[HubState]].
  * @param difficulties
  *   Every difficulty tier, lowest first, with whether it can be started and how far the save is from
  *   unlocking it. Resolved by [[GameSession]] from the achievement stats, like `runsCompleted`.
  * @param shopClosed
  *   Whether the altar is shut for good: the upgrades the player owns stay, none can be bought any more.
  *   Resolved by [[GameSession]] from the story.
  */
case class HubView(upgrades: List[UpgradeView],
                   perks: List[PerkView] = Nil,
                   runsCompleted: Int = 0,
                   difficulties: List[DifficultyView] = Nil,
                   shopClosed: Boolean = false
)

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
  * up to 2-3. Durable, not transient like the damage and unlock lists on
  * [[StateUpdate]] below - it must still be present after a reconnect, or if the player sends an
  * unrelated action before resolving it with an [[EquipChoice]].
  */
case class PendingEquipChoiceView(newItem: ItemView, options: List[EquipChoiceOptionView])

/** A Shrine's 3 rolled reward candidates, awaiting a pick. Durable, same discipline as
  * [[PendingEquipChoiceView]] - resolved with a [[RewardChoice]] action, picking by `options`
  * entry's `id` (each already carries its own distinct instance id, unlike an equip choice's
  * slot-keyed options).
  */
case class PendingRewardChoiceView(options: List[ItemView], relic: Option[RelicView] = None)

/** The relic a Boss left with its Shrine, which bears the name of the Devoted it belonged to.
  *
  * @param devoted
  *   Which Devoted, from 1. The inscription is the text `story.boss.<devoted>.relic`.
  * @param readable
  *   False while the inscription is worn smooth, before the story has gone far enough for the player
  *   to read it: the client then shows `story.boss.unreadable` instead.
  */
case class RelicView(devoted: Int, readable: Boolean)

/** The conversation the player is in with a Rest NPC, or the line of a Devoted before or after a
  * fight, awaiting a [[SceneDone]]. Durable, same
  * discipline as [[PendingEquipChoiceView]]. The server only says which conversation it is: the
  * lines are texts of the client's language file, under keys built from these fields.
  *
  * @param npc
  *   The speaking NPC, e.g. "npc.1", or "boss.2" for the second Devoted. The name shown is the text
  *   `story.<npc>.name`.
  * @param kind
  *   "scene" (a scene of the story, lines under `story.<sceneId>`), "vow_report" (the NPC comments on
  *   a vow, lines under `story.<npc>.vow.kept` or `story.<npc>.vow.failed`), "idle" (a short line
  *   under `story.<npc>.idle`), "epilogue" (the line about the way the story ended, under
  *   `story.<npc>.epilogue.<context>`) or "devoted" (the single line `story.<sceneId>` of a Devoted,
  *   said before the fight or as they fall).
  * @param sceneId
  *   Only for "scene" and "devoted".
  * @param kept
  *   Only for "vow_report": whether the vow was kept.
  * @param context
  *   Only for "idle": "general", "death" or "victory", the pool of lines the last run calls for. For
  *   "epilogue", the ending: "end1" (entered the light), "end2" (someone took the place), "end3" (refused).
  * @param offerScene
  *   The scene whose choice and reply texts apply when the conversation ends by offering a vow, which
  *   is when the player must answer with `accept` in [[SceneDone]]. `None` when it offers none.
  * @param revealed
  *   Whether the player knows the real name of the speaker, which some NPC give away in a scene of
  *   theirs (see [[roguelite.game.NpcDef.revealedBy]]). Until then the client shows the title,
  *   `story.<npc>.name`, afterwards `story.<npc>.name_revealed` when it exists. True for every NPC
  *   that gives its name from the start, and for a Devoted.
  */
case class PendingSceneView(npc: String,
                            kind: String,
                            sceneId: Option[String] = None,
                            kept: Option[Boolean] = None,
                            context: Option[String] = None,
                            offerScene: Option[String] = None,
                            revealed: Boolean = true
)

/** The question of the light, which the Sanctuary asks instead of ending the run once the story has
  * come to it. Durable, same discipline as [[PendingEquipChoiceView]]: it stays until the player
  * answers with an [[EndingChoice]].
  *
  * @param candidates
  *   The NPCs who could take the player's place, by id (e.g. "npc.2"), because their story is over.
  *   Empty while none is: the second answer is then closed.
  */
case class PendingEndingChoiceView(candidates: List[String])

/** One section of the dungeon map: a run of rooms drawn from a single theme, ending in its own boss.
  * The client draws one row per section.
  *
  * @param theme
  *   The theme the section's rooms belong to (e.g. "dungeon", "darkDungeon"), see
  *   [[RoomView.theme]].
  */
case class MinimapSectionView(index: Int, theme: String)

/** One room on the dungeon map.
  *
  * @param id
  *   Identifies the node within this map only, and is never the room's own id: authored room ids
  *   name their type (e.g. "combat_003"), which would give away a room the player has not seen.
  * @param roomType
  *   "combat" | "loot" | "rest" | "boss" | "miniboss" | "fork" | "sanctuary" | "vault". `None` for a
  *   room the player has not visited whose type is not revealed in advance.
  * @param visited
  *   True once the player has been in the room, including the one they are in now.
  * @param current
  *   True for the room the player is in.
  * @param section
  *   Index of the [[MinimapSectionView]] the room belongs to.
  * @param column
  *   Position along the section's row, starting at 0. Rooms on the two branches of a fork share
  *   columns.
  * @param lane
  *   0 for the upper branch of a fork and for every room outside one, 1 for the lower branch. The
  *   client centres the rooms outside a fork's branches on a row that has two lanes.
  */
case class MinimapNodeView(id: String,
                           roomType: Option[String],
                           visited: Boolean,
                           current: Boolean,
                           section: Int,
                           column: Int,
                           lane: Int
)

/** A way from one map node to another, always in the forward direction.
  *
  * @param exit
  *   Only set on the edges leaving a fork: "UP" | "DOWN" | "LEFT" | "RIGHT", the wall of the fork
  *   room the door to this branch sits on, so the player can tell which door leads where.
  */
case class MinimapEdgeView(from: String, to: String, exit: Option[String] = None)

/** The dungeon as a graph for the map panel: every room is present from the start, with its type
  * hidden until it is visited unless the type is one the player is meant to see coming.
  */
case class MinimapView(sections: List[MinimapSectionView], nodes: List[MinimapNodeView], edges: List[MinimapEdgeView])

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
    /** A Shrine's 3 rolled reward candidates awaiting a pick. Durable, same discipline as
      * `pendingEquipChoice` - see [[PendingRewardChoiceView]]. Resolved with a [[RewardChoice]]
      * action.
      */
    pendingRewardChoice: Option[PendingRewardChoiceView] = None,
    /** The conversation with a Rest NPC awaiting its end. Durable, same discipline as
      * `pendingEquipChoice` - see [[PendingSceneView]]. Resolved with a [[SceneDone]] action.
      */
    pendingScene: Option[PendingSceneView] = None,
    /** The question of the light, awaiting the player's answer. Durable, same discipline as
      * `pendingEquipChoice` - see [[PendingEndingChoiceView]]. Resolved with an [[EndingChoice]] action.
      */
    pendingEndingChoice: Option[PendingEndingChoiceView] = None,
    /** The story card waiting to be shown full screen, the oldest one the player has not seen yet.
      * Durable: it stays until the player answers with a [[CardSeen]], so a card never read is shown
      * again at the next visit. The texts are the client's, under `story.card.<id>`.
      */
    pendingCard: Option[String] = None,
    /** The line the god's voice says on this update, at the start of a run or on a death. Transient:
      * only present on the single update the moment happened on, same convention as `newlyUnlocked`.
      */
    godLine: Option[GodLineView] = None,
    abilities: List[AbilityView] = Nil,
    achievements: List[AchievementView] = Nil,
    sets: List[SetView] = Nil,
    /** Only meaningful when phase is GameOver: true if the dungeon's boss was defeated, false if
      * the player died.
      */
    victory: Boolean = false,
    log: List[String] = Nil,
    /** Achievements newly earned by the action that produced this update. Transient: only
      * non-empty on the single update where one or more achievements were just unlocked. A list,
      * not an Option, since a single action can plausibly earn more than one at once (e.g. a kill
      * that is simultaneously a first kill and a level-up).
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
    soundEvents: List[String] = Nil,
    /** Dev tooling only: ids of hand-converted Tiled rooms available under the backend's
      * `debug-rooms/` folder, offered as one-click buttons in the hub (see
      * [[roguelite.engine.GameSession.handleDebugLoadRoom]]). Only ever populated while
      * `phase == Hub` - empty everywhere else, and empty in a packaged build where that folder
      * doesn't exist at all.
      */
    debugRooms: List[String] = Nil,
    /** The dungeon map, only present while exploring a run: see [[MinimapView]]. */
    minimap: Option[MinimapView] = None
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

  given Encoder[EndingAnswer] = Encoder[String].contramap(_.toString.toUpperCase)
  given Decoder[EndingAnswer] = Decoder[String].emap(
    s =>
      EndingAnswer.values
        .find(_.toString.toUpperCase == s.toUpperCase)
        .toRight(s"Unknown answer to the light: $s")
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
  given Encoder[RewardChoice] = deriveEncoder
  given Decoder[RewardChoice] = deriveDecoder
  given Encoder[SceneDone]    = deriveEncoder
  given Decoder[SceneDone]    = deriveDecoder
  given Encoder[CardSeen]     = deriveEncoder
  given Decoder[CardSeen]     = deriveDecoder
  given Encoder[EndingChoice] = deriveEncoder
  given Decoder[EndingChoice] = deriveDecoder

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
        case "REWARD_CHOICE" => cursor.as[RewardChoice].left.map(_.message)
        case "SCENE_DONE"    => cursor.as[SceneDone].left.map(_.message)
        case "CARD_SEEN"     => cursor.as[CardSeen].left.map(_.message)
        case "ENDING_CHOICE" => cursor.as[EndingChoice].left.map(_.message)
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
  given Encoder[TierRequirementView] = deriveEncoder
  given Encoder[DifficultyView]      = deriveEncoder
  given Encoder[HubView]      = deriveEncoder
  given Encoder[ItemView]     = deriveEncoder
  given Encoder[KeyCountView] = deriveEncoder
  given Encoder[EquipmentView] = deriveEncoder
  given Encoder[EquipChoiceOptionView]   = deriveEncoder
  given Encoder[PendingEquipChoiceView]  = deriveEncoder
  given Encoder[RelicView] = deriveEncoder
  given Encoder[PendingRewardChoiceView] = deriveEncoder
  given Encoder[PendingSceneView]        = deriveEncoder
  given Encoder[PendingEndingChoiceView] = deriveEncoder
  given Encoder[AbilityView]     = deriveEncoder
  given Encoder[AchievementView] = deriveEncoder
  given Encoder[SetView]         = deriveEncoder
  given Encoder[GodLineView]     = deriveEncoder
  given Encoder[MinimapSectionView] = deriveEncoder
  given Encoder[MinimapNodeView]    = deriveEncoder
  given Encoder[MinimapEdgeView]    = deriveEncoder
  given Encoder[MinimapView]        = deriveEncoder
  given Encoder[StateUpdate]     = deriveEncoder

  /** Serialize a StateUpdate to a JSON string to be sent over the WebSocket. */
  def encodeUpdate(update: StateUpdate): String = update.asJson.noSpaces
