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

export const COLOR_TILE_FLOOR        = "#2a2a2a";
export const COLOR_TILE_WALL         = "#0f0f0f";
export const COLOR_TILE_FLOOR_BORDER = "#333333";
export const COLOR_TILE_GRID_WIDTH   = 0.5;

// ---------------------------------------------
// Colors — entities
// ---------------------------------------------

export const COLOR_ENTITY_ENEMY = "#c0392b";
export const COLOR_ENTITY_CHEST = "#d4ac0d";
export const COLOR_ENTITY_DOOR  = "#5d6d7e";
export const COLOR_ENTITY_LABEL = "#ccc";

/** Fallback color for entity kinds not explicitly mapped. */
export const COLOR_ENTITY_FALLBACK = "#888";

// ---------------------------------------------
// Colors — player
// ---------------------------------------------

export const PLAYER_CLASS_COLORS = {
    warrior: "#e05c5c",
    archer:  "#5ce07a",
    mage:    "#5c9be0",
} as const;

export const COLOR_PLAYER_OUTLINE       = "rgba(255,255,255,0.4)";
export const COLOR_PLAYER_OUTLINE_WIDTH = 2;
export const COLOR_PLAYER_INITIAL       = "#fff";

// ---------------------------------------------
// Colors — loading state
// ---------------------------------------------

export const COLOR_LOADING_BG   = "#1a1a1a";
export const COLOR_LOADING_TEXT = "#555";

// ---------------------------------------------
// Stats panel (ExplorationHUD)
// ---------------------------------------------

/** Width in pixels of the stats panel beside the canvas. */
export const STATS_PANEL_WIDTH = 160;

/** Resource bar color per class. */
export const RESOURCE_BAR_COLORS = {
    warrior: "#c0392b", // Rage — red
    archer:  "#27ae60", // Focus — green
    mage:    "#2980b9", // Mana — blue
} as const;

export const HP_BAR_COLOR = "#c0392b";

/** Human-readable resource name per class. */
export const RESOURCE_LABELS = {
    warrior: "Rage",
    archer:  "Focus",
    mage:    "Mana",
} as const;