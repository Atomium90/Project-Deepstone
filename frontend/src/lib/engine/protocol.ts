// ---------------------------------------------
// Enumerations (must match server-side values)
// ---------------------------------------------

export type Direction = "UP" | "DOWN" | "LEFT" | "RIGHT";
export type GamePhase = "HUB" | "EXPLORATION" | "COMBAT" | "GAMEOVER";
export type CombatActionType = "ATTACK" | "ABILITY" | "ITEM" | "DEFEND";
export type HubActionType = "STARTRUN" | "BUYUPGRADE" | "RETURNTOHUB";
export type ClassId = "warrior" | "archer" | "mage";
export type Difficulty = "easy" | "normal" | "hard";

export type ItemKind = "weapon" | "armor" | "accessory" | "consumable" | "key";
export type ItemRarity = "common" | "uncommon";

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
}

export type PlayerAction =
  | MoveAction
  | InteractAction
  | CombatAction
  | HubAction;

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
}

export interface EntityView {
  id: string;
  kind: "enemy" | "chest" | "door" | "locked_door" | "npc";
  x: number;
  y: number;
  label: string;
  /** Atlas sprite key to draw (see public/atlas/entities.json). Only set for enemies - resolved
   * server-side from enemies.json, never a mapping the client needs to keep in sync itself. */
  spriteId?: string;
}

/** One line of NPC dialogue, shown in a transient overlay. Only present on the single
 * StateUpdate the interaction produced - it's gone again on the next action. */
export interface DialogueView {
  npcName: string;
  line: string;
}

export interface RoomView {
  width: number;
  height: number;
  tiles: string[][]; // "floor" | "wall"
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
}

/** One damage or heal event produced by the action that generated this StateUpdate. Transient -
 * only present on the single update the event happened on, same convention as DialogueView. */
export interface DamageEventView {
  /** True if the player took the hit/heal, false if the enemy did. */
  targetIsPlayer: boolean;
  amount: number;
  kind: "damage" | "heal";
}

export interface UpgradeView {
  id: string;
  label: string;
  description: string;
  cost: number;
  unlocked: boolean;
}

export interface HubView {
  upgrades: UpgradeView[];
}

/** A single item in the player's inventory as seen by the client. */
export interface ItemView {
  id: string;
  typeId: string;
  name: string;
  kind: ItemKind;
  rarity: ItemRarity;
  /** One-line stat summary, e.g. "+3 ATK" or "Heal 30 HP". */
  statLine: string;
}

/** Static description of one class's combat ability, sent by the server so the client never
 * hardcodes ability names, costs, or resource labels. */
export interface AbilityView {
  classId: ClassId;
  id: string;
  name: string;
  cost: number;
  /** e.g. "Rage" — the resource pool this ability spends. */
  resourceName: string;
  description: string;
}

/** One achievement's display state, sent as a full catalog on every StateUpdate (same rationale
 * as AbilityView — the client never hardcodes the list), independent of phase. */
export interface AchievementView {
  id: string;
  label: string;
  description: string;
  unlocked: boolean;
}

export interface StateUpdate {
  phase: GamePhase;
  player: PlayerView;
  room?: RoomView;
  combat?: CombatView;
  hub?: HubView;
  /** Current contents of the player's inventory (up to 6 items). */
  inventory: ItemView[];
  /** Per-class ability catalog — always present, independent of game phase. */
  abilities: AbilityView[];
  /** Full achievement catalog (locked and unlocked) — always present, independent of phase. */
  achievements: AchievementView[];
  /** Only meaningful when phase is "GAMEOVER": true if the boss was defeated, false if the player died. */
  victory: boolean;
  log: string[];
  dialogue?: DialogueView;
  /** Achievements newly earned by the action that produced this update. Transient — only present
   * on the single update where one or more achievements were just unlocked, same convention as
   * `dialogue`. A list, not a single value, since one action can plausibly earn more than one at
   * once. */
  newlyUnlocked: AchievementView[];
  /** Damage/heal events produced by the action that generated this update. Transient, same
   * convention as `newlyUnlocked` - a list since a single action can produce more than one (e.g.
   * the player attacks and the enemy counter-attacks in the same response). */
  damageEvents: DamageEventView[];
}
