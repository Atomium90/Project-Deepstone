// ---------------------------------------------
// Enumerations (must match server-side values)
// ---------------------------------------------

export type Direction = "UP" | "DOWN" | "LEFT" | "RIGHT";
export type GamePhase = "HUB" | "EXPLORATION" | "COMBAT" | "GAMEOVER";
export type CombatActionType = "ATTACK" | "ABILITY" | "ITEM" | "DEFEND";
export type HubActionType = "STARTRUN" | "BUYUPGRADE";
export type ClassId = "warrior" | "archer" | "mage";

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
}

export type PlayerAction = MoveAction | InteractAction | CombatAction | HubAction;

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
  kind: "enemy" | "chest" | "door";
  x: number;
  y: number;
  label: string;
}

export interface RoomView {
  width: number;
  height: number;
  tiles: string[][];    // "floor" | "wall"
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
  cost: number;
  unlocked: boolean;
}

export interface HubView {
  upgrades: UpgradeView[];
}

export interface StateUpdate {
  phase: GamePhase;
  player: PlayerView;
  room?: RoomView;
  combat?: CombatView;
  hub?: HubView;
  log: string[];
}