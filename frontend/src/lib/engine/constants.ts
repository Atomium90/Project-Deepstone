// ---------------------------------------------
// Renderer
// ---------------------------------------------

/** Size of one tile in pixels. All spatial calculations derive from this. */
export const TILE_SIZE = 64;

/** Lerp speed toward target position per frame (0–1). Higher = snappier. */
export const LERP_SPEED = 0.18;

/** Below this pixel distance, snap directly to target instead of lerping. */
export const LERP_SNAP_THRESHOLD = 0.5;

/** Fraction of TILE_SIZE used as the player circle radius. */
export const PLAYER_RADIUS_RATIO = 0.32;

/** Fraction of TILE_SIZE used as the entity circle radius. */
export const ENTITY_RADIUS_RATIO = 0.3;

/** Offset in pixels below an entity circle where its label is drawn. */
export const ENTITY_LABEL_OFFSET = 12;

// ---------------------------------------------
// Colors — tile
// ---------------------------------------------

export const COLOR_TILE_FLOOR = "#2a2a2a";
export const COLOR_TILE_WALL = "#0f0f0f";
export const COLOR_TILE_FLOOR_BORDER = "#333333";
export const COLOR_TILE_GRID_WIDTH = 0.5;

// ---------------------------------------------
// Colors — entities
// ---------------------------------------------

export const COLOR_ENTITY_ENEMY = "#c0392b";
export const COLOR_ENTITY_CHEST = "#d4ac0d";
export const COLOR_ENTITY_DOOR = "#5d6d7e";
export const COLOR_ENTITY_LABEL = "#ccc";

/** Fallback color for entity kinds not explicitly mapped. */
export const COLOR_ENTITY_FALLBACK = "#888";

// ---------------------------------------------
// Colors — player
// ---------------------------------------------

export const PLAYER_CLASS_COLORS = {
  warrior: "#e05c5c",
  archer: "#5ce07a",
  mage: "#5c9be0",
} as const;

export const COLOR_PLAYER_OUTLINE = "rgba(255,255,255,0.4)";
export const COLOR_PLAYER_OUTLINE_WIDTH = 2;
export const COLOR_PLAYER_INITIAL = "#fff";

// ---------------------------------------------
// Colors — loading state
// ---------------------------------------------

export const COLOR_LOADING_BG = "#1a1a1a";
export const COLOR_LOADING_TEXT = "#555";

// ---------------------------------------------
// Stats panel (ExplorationHUD)
// ---------------------------------------------

/** Width in pixels of the stats panel beside the canvas. */
export const STATS_PANEL_WIDTH = 160;

/** Resource bar color per class. */
export const RESOURCE_BAR_COLORS = {
  warrior: "#c0392b", // Rage — red
  archer: "#27ae60", // Focus — green
  mage: "#2980b9", // Mana — blue
} as const;

export const HP_BAR_COLOR = "#c0392b";

// ---------------------------------------------
// Hub screen
// ---------------------------------------------

export const CURRENCY_SYMBOL = "◈";
export const CURRENCY_NAME = "Shards";

/** Display metadata for each class shown in the hub selection. */
export const CLASS_INFO = {
  warrior: {
    icon: "⚔",
    label: "Warrior",
    description: "120 HP · Rage builds on hit",
    affinity: "Heavy weapons & armor",
  },
  archer: {
    icon: "🏹",
    label: "Archer",
    description: "90 HP · Focus regens each round",
    affinity: "Ranged weapons",
  },
  mage: {
    icon: "✦",
    label: "Mage",
    description: "70 HP · Fixed Mana pool",
    affinity: "Magic weapons & staves",
  },
} as const;

// ---------------------------------------------
// Item slot colors (inventory panel and pickers)
// ---------------------------------------------

/** Background fill color of the icon badge per item kind. */
export const ITEM_KIND_COLORS = {
  weapon: "#4a2222",
  armor: "#22354a",
  accessory: "#2a3a1a",
  consumable: "#3a2a4a",
} as const;

/** Border / accent color per item rarity. */
export const ITEM_RARITY_COLORS = {
  common: "#333333",
  uncommon: "#8e44ad",
} as const;

// ---------------------------------------------
// Interact highlight (entities)
// ---------------------------------------------

/** Extra radius added to the highlight ring around interactable entities. */
export const ENTITY_INTERACT_HALO_BASE = 6;

/** Additional pulsing amplitude added to the halo radius. */
export const ENTITY_INTERACT_HALO_PULSE = 3;

/** Stroke width of the interact halo ring. */
export const ENTITY_INTERACT_HALO_WIDTH = 1.5;

/** Base RGB for interact halo (white). */
export const ENTITY_INTERACT_HALO_RGB = "255,255,255";

/** Base alpha of the interact halo color. */
export const ENTITY_INTERACT_HALO_ALPHA = 0.3;

/** Additional alpha added by the pulse. */
export const ENTITY_INTERACT_HALO_PULSE_ALPHA = 0.25;

// ---------------------------------------------
// Interact prompt ("[E]")
// ---------------------------------------------

/** Color of the "[E]" interact prompt. */
export const COLOR_INTERACT_PROMPT = "#eee";

/** Vertical offset above the entity circle for the "[E]" prompt. */
export const INTERACT_PROMPT_OFFSET = 4;
