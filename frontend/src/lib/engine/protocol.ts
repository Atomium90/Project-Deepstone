// ---------------------------------------------
// Enumerations (must match server-side values)
// ---------------------------------------------

export type Direction = "UP" | "DOWN" | "LEFT" | "RIGHT";
export type GamePhase = "HUB" | "EXPLORATION" | "COMBAT" | "GAMEOVER";
export type CombatActionType = "ATTACK" | "ABILITY" | "ITEM" | "DEFEND";
export type HubActionType = "STARTRUN" | "BUYUPGRADE" | "RETURNTOHUB" | "DEBUGLOADROOM";
export type ClassId = "warrior" | "archer" | "mage";
export type Difficulty = "easy" | "normal" | "hard";
export type UpgradeCategory = "stat" | "meta";

export type ItemKind = "weapon" | "armor" | "accessory" | "consumable" | "key";
export type ItemRarity = "common" | "uncommon" | "rare" | "epic";

/** Identifies one equipment or potion-belt slot. `ACCESSORY_i`/`POTION_i` carry the slot index
 * (0-based) since both families are fixed-size arrays, not one enum case per physical slot. */
export type EquipSlot =
  | "WEAPON"
  | "ARMOR"
  | `ACCESSORY_${number}`
  | `POTION_${number}`;

// ---------------------------------------------
// Client → Server actions
// ---------------------------------------------

export interface MoveAction {
  type: "MOVE";
  direction: Direction;
}

export interface InteractAction {
  type: "INTERACT";
  targetId: string;
}

export interface CombatAction {
  type: "COMBAT_ACTION";
  action: CombatActionType;
  abilityId?: string;
  itemId?: string;
}

export interface HubAction {
  type: "HUB_ACTION";
  action: HubActionType;
  classId?: ClassId;
  upgradeId?: string;
  difficulty?: Difficulty;
  perkId?: string;
  /** Only meaningful for DEBUGLOADROOM - dev tooling only, see StateUpdate.debugRooms. */
  debugRoomId?: string;
}

/** Resolve a pending equip choice. `targetSlot` omitted means "keep what's currently equipped,
 * discard the new item"; otherwise it must be one of the slots the choice actually offered. */
export interface EquipChoiceAction {
  type: "EQUIP_CHOICE";
  targetSlot?: EquipSlot;
}

/** Resolve a pending Shrine reward choice. `itemId` omitted means "walk away, take nothing";
 * otherwise it must be one of the item ids the choice actually offered. */
export interface RewardChoiceAction {
  type: "REWARD_CHOICE";
  itemId?: string;
}

export type PlayerAction =
  | MoveAction
  | InteractAction
  | CombatAction
  | HubAction
  | EquipChoiceAction
  | RewardChoiceAction;

// ---------------------------------------------
// Server → Client views
// ---------------------------------------------

export interface PlayerView {
  classId: ClassId;
  hp: number;
  maxHp: number;
  resourceCurrent: number;
  resourceMax: number;
  level: number;
  xp: number;
  metaCurrency: number;
  /** The class's affinity tags (e.g. ["heavy"]). ItemView.statLine already reflects whether an
   * item's own typeTag benefits from the affinity doubling CombatResolver applies - this is
   * carried mainly so the UI can gray out an off-affinity item's stat line as a visual cue. */
  affinityTags: string[];
}

export interface EntityView {
  id: string;
  kind: "enemy" | "chest" | "door" | "locked_door" | "npc" | "shrine" | "sanctuary";
  x: number;
  y: number;
  label: string;
  /** Atlas sprite key to draw (see public/atlas/entities.json). Only set for enemies - resolved
   * server-side from enemies.json, never a mapping the client needs to keep in sync itself. */
  spriteId?: string;
  /** Only set for enemies. Rolled once per run at dungeon build time - unlike a trapped chest or
   * secret door, Elite status is meant to be visible before the player engages, not a surprise. */
  isElite?: boolean;
  /** Only set for "door"/"locked_door" - which wall of the room this door sits on. Used to
   * orient the 2-tile door sprite (see Renderer.ts's drawDoor). */
  direction?: "UP" | "DOWN" | "LEFT" | "RIGHT";
  /** Only set for "chest". Picks the chest's sprite and says whether pressing E can still do
   * anything. A trapped chest reports "closed" until its trap goes off, so this never gives the
   * trap away. */
  state?: "closed" | "open_full" | "open_empty" | "sprung";
}

/** One line of NPC dialogue, shown in a transient overlay. Only present on the single
 * StateUpdate the interaction produced - it's gone again on the next action. */
export interface DialogueView {
  npcName: string;
  line: string;
}

export interface RoomView {
  roomId: string;
  width: number;
  height: number;
  tiles: string[][]; // "floor" | "wall"
  /** Which client-side sprite set to render this room with (e.g. "dungeon", "darkDungeon"). */
  theme: string;
  /** Same shape as `tiles` - meaningful for every cell regardless of `tiles`' own value, since a
   * wall cell still has a floor drawn underneath it (many wall sprites have transparent padding
   * so the floor shows through around them). `null` means "use `theme`'s default floor"; a string
   * names an exact atlas sprite key. */
  floorSprite: (string | null)[][];
  /** Same shape as `tiles` - only meaningful where `tiles` is "wall". `null` means "use `theme`'s
   * default wall"; a string names an exact atlas sprite key (a column, a torch wall, anything the
   * room author placed deliberately). */
  wallSprite: (string | null)[][];
  /** Same shape as `tiles` again - a sprite drawn on top of whatever floorSprite/wallSprite/the
   * theme default already resolved for that cell, never a replacement (a column's cap tapering
   * into an ordinary floor tile underneath it, say). Almost entirely null for a typical room. */
  decoration: (string | null)[][];
  entities: EntityView[];
  playerX: number;
  playerY: number;
}

export interface CombatView {
  enemyId: string;
  enemyLabel: string;
  enemyHp: number;
  enemyMaxHp: number;
  isPlayerTurn: boolean;
  /** Atlas sprite key for the enemy portrait, same resolution convention as EntityView.spriteId. */
  spriteId?: string;
  /** True if the current room is a boss room - drives boss music. */
  isBoss: boolean;
  /** True if the current room is a mini-boss checkpoint (a biome transition, not the run's final
   * boss) - cosmetic only, never ends the run the way isBoss's room does. */
  isMiniBoss: boolean;
  /** True if this enemy rolled Elite at dungeon build time - same value already seen on
   * EntityView.isElite during exploration, just always present here instead of optional. */
  isElite: boolean;
  /** The player's class ability cost, resolved through any active set/perk discount - the real
   * cost to check affordability against, not the static per-class value in StateUpdate.abilities.
   * Undefined only if the player's class has no loaded ability def. */
  abilityCost?: number;
}

/** One damage or heal event produced by the action that generated this StateUpdate. Transient -
 * only present on the single update the event happened on, same convention as DialogueView. */
export interface DamageEventView {
  /** True if the player took the hit/heal, false if the enemy did. */
  targetIsPlayer: boolean;
  amount: number;
  kind: "damage" | "heal";
  /** True only for a player Attack that rolled a critical hit. Always false for enemy damage and
   * for other player actions (Ability's FlatDamage is crit-exempt). Not yet rendered differently
   * client-side - a later change picks this up for combat-juice feedback. */
  crit: boolean;
}

export interface UpgradeView {
  id: string;
  label: string;
  description: string;
  cost: number;
  icon: string;
  category: UpgradeCategory;
  unlocked: boolean;
}

/** One run perk offered to the player, rolled fresh every HUB visit. */
export interface PerkView {
  id: string;
  label: string;
  description: string;
  icon: string;
}

export interface HubView {
  upgrades: UpgradeView[];
  perks: PerkView[];
  /** Runs finished so far, won or lost. 0 means a brand-new save. */
  runsCompleted: number;
}

/** A single item as seen by the client, whether equipped, in the potion belt, or shown in a
 * pickup choice. */
export interface ItemView {
  id: string;
  typeId: string;
  name: string;
  kind: ItemKind;
  rarity: ItemRarity;
  /** One-line stat summary, e.g. "+3 ATK" or "Heal 30 HP". Already resolved server-side for the
   * viewing player's class - the affinity-doubled value when applicable, not a flat baseline. */
  statLine: string;
  /** Atlas sprite key hint for the item's icon. Undefined until an icon pack is chosen and
   * items.json gains real values - falls back to a plain color box client-side. */
  iconId?: string;
  /** Which equipment set this item belongs to, if any. See StateUpdate.sets for the set's actual
   * 2pc/4pc bonus text. */
  setId?: string;
  /** Weapon/Armor/Accessory's affinity tag ("heavy"/"ranged"/"magic"/"light"), if any. Undefined
   * for consumables/keys and untagged gear. `statLine` above already reflects the affinity-doubled
   * value when applicable - this is only needed to decide whether to gray the stat line out. */
  typeTag?: string;
  /** Charge count for a potion-belt stack. Undefined for every other item kind, which never
   * stacks - the UI only shows a count badge when this is present. */
  count?: number;
}

/** One key kind the player is holding, with how many. Coarse: collapses Specific/Typed's target
 * payload away since only Generic has real content today and the client only needs a count. */
export interface KeyCountView {
  keyKind: string;
  count: number;
}

/** The player's full equipment loadout: weapon/armor/accessory slots (auto-equip on pickup, no
 * generic bag) and the potion belt. Array elements use `| null`, not `?`, since a JSON array
 * can't have a "missing" slot, only an empty one. */
export interface EquipmentView {
  weapon: ItemView | null;
  armor: ItemView | null;
  /** Always length 2. */
  accessories: (ItemView | null)[];
  /** Length 2, or 3 once the extra_slot hub upgrade is unlocked. */
  potionBelt: (ItemView | null)[];
  keys: KeyCountView[];
}

/** One occupied slot the player could replace with the incoming item. `slot` is what an
 * EquipChoiceAction targeting this option must send back as `targetSlot`. */
export interface EquipChoiceOptionView {
  slot: EquipSlot;
  current: ItemView;
}

/** A pickup offered to the player because every slot matching `newItem`'s kind was already
 * occupied: weapon/armor degenerate to a single `options` entry, accessories/potions can offer up
 * to 2-3. Durable, not transient like `dialogue` or the other optional/list fields on StateUpdate
 * below - it must still be present after a reconnect, or if the player sends an unrelated action
 * before resolving it with an EquipChoiceAction. */
export interface PendingEquipChoiceView {
  newItem: ItemView;
  options: EquipChoiceOptionView[];
}

/** A Shrine's 3 rolled reward candidates awaiting a pick. Durable, same discipline as
 * PendingEquipChoiceView - resolved with a RewardChoiceAction, picking by an option's own `id`. */
export interface PendingRewardChoiceView {
  options: ItemView[];
}

/** Static description of one class's combat ability, sent by the server so the client never
 * hardcodes ability names, costs, or resource labels. */
export interface AbilityView {
  classId: ClassId;
  id: string;
  name: string;
  cost: number;
  /** e.g. "Rage" (the resource pool this ability spends). */
  resourceName: string;
  description: string;
}

/** One achievement's display state, sent as a full catalog on every StateUpdate (same rationale
 * as AbilityView: the client never hardcodes the list), independent of phase. */
export interface AchievementView {
  id: string;
  label: string;
  description: string;
  unlocked: boolean;
}

/** Static description of one equipment set's 2pc/4pc bonus, sent as a full catalog on every
 * StateUpdate (same rationale as AbilityView: the client never hardcodes set names or bonus
 * text). Whether a set's bonus is currently active isn't carried here - the client already has
 * `setId` on every equipped ItemView and counts matching pieces itself. */
export interface SetView {
  id: string;
  name: string;
  classId: ClassId;
  bonus2pcLabel: string;
  bonus4pcLabel: string;
}

/** A room type as named on the wire. */
export type RoomTypeName = "combat" | "loot" | "rest" | "boss" | "miniboss" | "fork" | "sanctuary" | "vault";

/** One section of the dungeon map: a run of rooms drawn from one theme, ending in its own boss. The
 * map draws one row per section. */
export interface MinimapSectionView {
  index: number;
  /** The theme the section's rooms belong to, e.g. "dungeon" or "darkDungeon". */
  theme: string;
}

/** One room on the dungeon map. */
export interface MinimapNodeView {
  /** Identifies the node within this map only, never the room's own id (an authored id such as
   * "combat_003" would give the type of a room the player has not seen away). */
  id: string;
  /** Null for a room the player has not visited whose type is not revealed in advance. */
  roomType: RoomTypeName | null;
  /** True once the player has been in the room, the one they are in now included. */
  visited: boolean;
  /** True for the room the player is in. */
  current: boolean;
  /** Index of the MinimapSectionView the room belongs to. */
  section: number;
  /** Position along the section's row, from 0. Rooms on a fork's two branches share columns. */
  column: number;
  /** 0 for the upper branch of a fork and for every room outside one, 1 for the lower branch. A
   * room outside a fork's branches sits on the middle line of a row that has two lanes. */
  lane: number;
}

/** A way from one map node to another, always forward. */
export interface MinimapEdgeView {
  from: string;
  to: string;
  /** Only on the edges leaving a fork: the wall of the fork room its door sits on. */
  exit: Direction | null;
}

/** The dungeon as a graph for the map panel. Only sent while exploring a run. */
export interface MinimapView {
  sections: MinimapSectionView[];
  nodes: MinimapNodeView[];
  edges: MinimapEdgeView[];
}

export interface StateUpdate {
  phase: GamePhase;
  player: PlayerView;
  room?: RoomView;
  combat?: CombatView;
  hub?: HubView;
  /** The player's current equipment loadout. */
  equipment: EquipmentView;
  /** A pickup awaiting a keep/replace decision. Durable, not transient - see
   * PendingEquipChoiceView. Resolved with an EquipChoiceAction. */
  pendingEquipChoice?: PendingEquipChoiceView;
  /** A Shrine's 3 rolled reward candidates awaiting a pick. Durable, same discipline as
   * pendingEquipChoice - see PendingRewardChoiceView. Resolved with a RewardChoiceAction. */
  pendingRewardChoice?: PendingRewardChoiceView;
  /** Per-class ability catalog, always present, independent of game phase. */
  abilities: AbilityView[];
  /** Full achievement catalog (locked and unlocked), always present, independent of phase. */
  achievements: AchievementView[];
  /** Full equipment set catalog, always present, independent of phase. */
  sets: SetView[];
  /** Only meaningful when phase is "GAMEOVER": true if the boss was defeated, false if the player died. */
  victory: boolean;
  log: string[];
  dialogue?: DialogueView;
  /** Achievements newly earned by the action that produced this update. Transient: only present
   * on the single update where one or more achievements were just unlocked, same convention as
   * `dialogue`. A list, not a single value, since one action can plausibly earn more than one at
   * once. */
  newlyUnlocked: AchievementView[];
  /** Damage/heal events produced by the action that generated this update. Transient, same
   * convention as `newlyUnlocked` - a list since a single action can produce more than one (e.g.
   * the player attacks and the enemy counter-attacks in the same response). */
  damageEvents: DamageEventView[];
  /** Sound cue tags (e.g. "pickup", "door_open") produced by the action that generated this
   * update. Transient, same convention as `damageEvents`. */
  soundEvents: string[];
  /** Dev tooling only: ids of hand-converted Tiled rooms available under the backend's
   * `debug-rooms/` folder (see `frontend/scripts/convert-tiled-room.mjs`), offered as one-click
   * "load this room" buttons in the hub. Only ever populated while `phase === "HUB"` - empty
   * everywhere else, and empty in a packaged build where that folder doesn't exist at all. */
  debugRooms: string[];
  /** The dungeon map, only present while exploring a run. */
  minimap?: MinimapView;
}
