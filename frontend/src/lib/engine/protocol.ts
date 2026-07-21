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
  kind: "enemy" | "chest" | "door" | "locked_door";
  x: number;
  y: number;
  label: string;
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
  /** Only meaningful when phase is "GAMEOVER": true if the boss was defeated, false if the player died. */
  victory: boolean;
  log: string[];
}
