import { assets, type AssetManager } from "./AssetManager";
import type { RoomView, PlayerView, EntityView } from "./protocol";
import {
    TILE_SIZE,
    LERP_SPEED,
    LERP_SNAP_THRESHOLD,
    PLAYER_RADIUS_RATIO,
    ENTITY_RADIUS_RATIO,
    ENTITY_LABEL_OFFSET,
    COLOR_TILE_GRID_WIDTH,
    COLOR_ENTITY_ENEMY,
    COLOR_ENTITY_CHEST,
    COLOR_ENTITY_DOOR,
    COLOR_ENTITY_LOCKED_DOOR,
    COLOR_ENTITY_NPC,
    COLOR_ENTITY_LABEL,
    COLOR_ENTITY_FALLBACK,
    PLAYER_CLASS_COLORS,
    COLOR_PLAYER_OUTLINE,
    COLOR_PLAYER_OUTLINE_WIDTH,
    COLOR_PLAYER_INITIAL,
    COLOR_LOADING_BG,
    COLOR_LOADING_TEXT,
    ENTITY_INTERACT_HALO_BASE,
    ENTITY_INTERACT_HALO_PULSE,
    ENTITY_INTERACT_HALO_WIDTH,
    ENTITY_INTERACT_HALO_ALPHA,
    ENTITY_INTERACT_HALO_PULSE_ALPHA,
    COLOR_INTERACT_PROMPT,
    INTERACT_PROMPT_OFFSET,
    ENTITY_INTERACT_HALO_RGB
} from "./constants";

const ENTITY_COLORS: Record<string, string> = {
    enemy: COLOR_ENTITY_ENEMY,
    chest: COLOR_ENTITY_CHEST,
    door:  COLOR_ENTITY_DOOR,
    locked_door: COLOR_ENTITY_LOCKED_DOOR,
    npc: COLOR_ENTITY_NPC,
};

/** Atlas sprite per tile type. One fixed sprite per type, no autotiling in this pass. */
const TILE_SPRITES: Record<string, string> = {
    floor: "floor_plain",
    wall: "wall_center",
};

/** Atlas sprite per entity kind, for the kinds that don't vary by instance (everything except
 * "enemy", which uses EntityView.spriteId - server-resolved per typeId, see AssetManager.getSprite). */
const ENTITY_SPRITES: Record<string, string> = {
    chest: "chest_closed",
    door: "door_closed",
    locked_door: "door_closed",
    npc: "npc_sage",
};

/** Distance in tiles within which an entity is considered reachable (E key). */
const INTERACT_RANGE = 1;

/** Represents a 2D position in pixel space. */
interface Vec2 {
    x: number;
    y: number;
}

/**
 * Handles all canvas rendering for the exploration view.
 *
 * The renderer runs a requestAnimationFrame loop independently of the WebSocket.
 * The server sends discrete tile-grid positions; the renderer smoothly interpolates
 * the player sprite between its current visual position and the server-authoritative
 * target position, giving the illusion of fluid movement without any client-side
 * physics or prediction.
 *
 * The canvas is resized dynamically to fill whatever container it is placed in.
 * The tile size (TILE_SIZE) stays fixed; the room is centered inside the canvas.
 *
 * Usage:
 *   const renderer = new Renderer(canvasElement);
 *   renderer.start();
 *   renderer.update(roomView, playerView); // call whenever a StateUpdate arrives
 *   renderer.stop(); // call on component destroy
 */
export class Renderer {
    private readonly canvas: HTMLCanvasElement;
    private readonly ctx: CanvasRenderingContext2D;
    private readonly assets: AssetManager;

    private animFrameId: number | null = null;
    private room: RoomView | null = null;
    private player: PlayerView | null = null;

    /** Current visual position of the player in pixel space (interpolated). */
    private visualPos: Vec2 = { x: 0, y: 0 };

    /** Target position in pixel space (set from server state). */
    private targetPos: Vec2 = { x: 0, y: 0 };

    /** Tracks whether visualPos has been seeded (skip lerp on first frame). */
    private posInitialized = false;

    /** Id of the room last passed to update(), used to detect a room change so the lerp doesn't
     * slide the player sprite across an unrelated room. */
    private currentRoomId: string | null = null;

    /** Which way the player sprite is currently facing - only updated by a horizontal move, so
     * a vertical-only move keeps whatever facing was already set. */
    private playerFacing: "left" | "right" = "right";

    /** True while visualPos hasn't caught up to targetPos yet - drives idle vs. walk. */
    private isPlayerMoving = false;

    /** Elapsed time in ms, used for the pulsing interact indicator. */
    private elapsed = 0;
    private lastTimestamp = 0;

    /** ResizeObserver to react when the container changes size. */
    private resizeObserver: ResizeObserver;

    constructor(canvas: HTMLCanvasElement) {
        const ctx = canvas.getContext("2d");
        if (!ctx) throw new Error("Could not get 2D rendering context from canvas.");

        this.canvas = canvas;
        this.ctx = ctx;
        this.assets = assets;

        // Keep the canvas pixel dimensions in sync with its CSS layout size
        this.resizeObserver = new ResizeObserver(() => this.fitToContainer());
        this.resizeObserver.observe(canvas);
    }

    /**
     * Push a new game state into the renderer.
     * Called every time a StateUpdate arrives from the server.
     */
    update(room: RoomView, player: PlayerView): void {
        const roomChanged = this.currentRoomId !== null && this.currentRoomId !== room.roomId;
        const prevTileX = this.room?.playerX;
        this.currentRoomId = room.roomId;

        // Only a horizontal move updates facing - skip across a room transition so entering a
        // new room's entrance never causes a spurious flip, and skip a vertical-only move so it
        // keeps whatever facing was already set.
        if (!roomChanged && prevTileX !== undefined && room.playerX !== prevTileX) {
            this.playerFacing = room.playerX > prevTileX ? "right" : "left";
        }

        this.room = room;
        this.player = player;
        this.targetPos = tileToPixelCenter(room.playerX, room.playerY);

        // On the very first update, or when the room itself just changed, snap directly to the
        // position instead of lerping - otherwise the sprite visibly slides in from wherever it
        // was in the previous (now irrelevant) room.
        if (!this.posInitialized || roomChanged) {
            this.visualPos = { ...this.targetPos };
            this.posInitialized = true;
        }
    }

    /** Start the render loop. Call once after mounting the canvas. */
    start(): void {
        if (this.animFrameId !== null) return;
        this.fitToContainer();
        this.lastTimestamp = performance.now();
        this.animFrameId = requestAnimationFrame(this.loop);
    }

    /** Stop the render loop and disconnect the resize observer. */
    stop(): void {
        if (this.animFrameId !== null) {
            cancelAnimationFrame(this.animFrameId);
            this.animFrameId = null;
        }
        this.resizeObserver.disconnect();
    }

    // -------------------------------------------------------------------------
    // Interact range query
    // -------------------------------------------------------------------------

    /**
     * Find the nearest entity within INTERACT_RANGE tiles of the player.
     * Returns null if no entity is reachable.
     *
     * Called by ExplorationHUD when the player presses E.
     */
    nearestInteractable(): EntityView | null {
        if (!this.room) return null;

        const px = this.room.playerX;
        const py = this.room.playerY;

        let nearest: EntityView | null = null;
        let nearestDist = Infinity;

        for (const entity of this.room.entities) {
            const dist = chebyshevDist(px, py, entity.x, entity.y)
            if (dist <= INTERACT_RANGE && dist < nearestDist) {
                nearest = entity;
                nearestDist = dist;
            }
        }

        return nearest;
    }

    // -------------------------------------------------------------------------
    // Private rendering pipeline
    // -------------------------------------------------------------------------

    /** Resize the canvas pixel buffer to match its current CSS display size. */
    private fitToContainer(): void {
        const rect = this.canvas.getBoundingClientRect();
        if (rect.width === 0 || rect.height === 0) return;

        const dpr = window.devicePixelRatio || 1;
        this.canvas.width  = Math.round(rect.width  * dpr);
        this.canvas.height = Math.round(rect.height * dpr);
        this.ctx.scale(dpr, dpr);

        // Resizing canvas.width/height resets all 2D context state, including this flag - must
        // be re-set every time, not just once at startup, or pixel art sprites come out blurred.
        this.ctx.imageSmoothingEnabled = false;
    }

    private loop = (timesStamp: number): void => {
        this.elapsed += timesStamp - this.lastTimestamp;
        this.lastTimestamp = timesStamp;
        this.animFrameId = requestAnimationFrame(this.loop);
        this.interpolate();
        this.draw();
    };

    /** Move visualPos toward targetPos using linear interpolation. */
    private interpolate(): void {
        const dx = this.targetPos.x - this.visualPos.x;
        const dy = this.targetPos.y - this.visualPos.y;

        this.isPlayerMoving = Math.abs(dx) >= LERP_SNAP_THRESHOLD || Math.abs(dy) >= LERP_SNAP_THRESHOLD;

        if (!this.isPlayerMoving) {
            this.visualPos = { ...this.targetPos };
        } else {
            this.visualPos = {
                x: this.visualPos.x + dx * LERP_SPEED,
                y: this.visualPos.y + dy * LERP_SPEED,
            };
        }
    }

    private draw(): void {
        const { ctx } = this;
        const dpr = window.devicePixelRatio || 1;
        const cssWidth  = this.canvas.width  / dpr;
        const cssHeight = this.canvas.height / dpr;

        // Clear
        ctx.clearRect(0, 0, cssWidth, cssHeight);

        if (!this.room || !this.player) {
            this.drawLoadingState(cssWidth, cssHeight);
            return;
        }

        // Center the room inside the canvas
        const roomPixelW = this.room.width  * TILE_SIZE;
        const roomPixelH = this.room.height * TILE_SIZE;
        const offsetX = Math.max(0, (cssWidth  - roomPixelW) / 2);
        const offsetY = Math.max(0, (cssHeight - roomPixelH) / 2);

        ctx.save();
        ctx.translate(offsetX, offsetY);

        this.drawTiles(this.room);
        this.drawEntities(this.room);
        this.drawPlayer(this.player);

        ctx.restore();
    }

    private drawTiles(room: RoomView): void {
        const { ctx } = this;

        for (let row = 0; row < room.height; row++) {
            for (let col = 0; col < room.width; col++) {
                const tileType = room.tiles[row][col];
                const x = col * TILE_SIZE;
                const y = row * TILE_SIZE;

                const spriteKey = TILE_SPRITES[tileType];
                const sprite = spriteKey
                    ? this.assets.getSprite(spriteKey, this.assets.getTileColor(tileType))
                    : { image: null, fallbackColor: this.assets.getTileColor(tileType) };

                if (sprite.image && sprite.sourceRect) {
                    const { x: sx, y: sy, w: sw, h: sh } = sprite.sourceRect;
                    ctx.drawImage(sprite.image, sx, sy, sw, sh, x, y, TILE_SIZE, TILE_SIZE);
                } else {
                    ctx.fillStyle = sprite.fallbackColor;
                    ctx.fillRect(x, y, TILE_SIZE, TILE_SIZE);

                    // Grid line only on the flat-color fallback - pixel art tiles don't need it
                    if (tileType === "floor") {
                        ctx.strokeStyle = this.assets.getTileFloorBorderColor();
                        ctx.lineWidth = COLOR_TILE_GRID_WIDTH;
                        ctx.strokeRect(x, y, TILE_SIZE, TILE_SIZE);
                    }
                }
            }
        }
    }

    private drawEntities(room: RoomView): void {
        const { ctx } = this;
        const radius = TILE_SIZE * ENTITY_RADIUS_RATIO;
        const px = room.playerX;
        const py = room.playerY;

        for (const entity of room.entities) {
            const cx = entity.x * TILE_SIZE + TILE_SIZE / 2;
            const cy = entity.y * TILE_SIZE + TILE_SIZE / 2;
            const isNearby = chebyshevDist(px, py, entity.x, entity.y) <= INTERACT_RANGE;

            // Pulsing highlight ring when entity is within interact range
            if (isNearby) {
                const pulse = 0.5 + 0.5 * Math.sin(this.elapsed / 300);
                ctx.beginPath();
                ctx.arc(cx, cy,
                    radius + ENTITY_INTERACT_HALO_BASE + pulse * ENTITY_INTERACT_HALO_PULSE,
                    0, Math.PI * 2
                );
                ctx.strokeStyle = `rgba(${ENTITY_INTERACT_HALO_RGB}, ${
                    ENTITY_INTERACT_HALO_ALPHA + pulse * ENTITY_INTERACT_HALO_PULSE_ALPHA
                })`;
                ctx.lineWidth = ENTITY_INTERACT_HALO_WIDTH;
                ctx.stroke();
            }

            // Entity body: real sprite when one resolves, geometric circle otherwise
            const fallbackColor = ENTITY_COLORS[entity.kind] ?? COLOR_ENTITY_FALLBACK;
            const spriteKey = entity.kind === "enemy" ? entity.spriteId : ENTITY_SPRITES[entity.kind];
            const sprite = spriteKey ? this.assets.getSprite(spriteKey, fallbackColor, this.elapsed) : null;

            if (sprite?.image && sprite.sourceRect) {
                const { x: sx, y: sy, w: sw, h: sh } = sprite.sourceRect;
                if (entity.kind === "enemy" && shouldFlip(entity.id)) {
                    // Stable per-entity mirror (not random per frame) so enemies vary in
                    // orientation without ever flickering - purely cosmetic, no server/protocol
                    // involvement, unlike the player's separately pre-mirrored sheets.
                    ctx.save();
                    ctx.translate(cx, cy);
                    ctx.scale(-1, 1);
                    ctx.drawImage(sprite.image, sx, sy, sw, sh, -TILE_SIZE / 2, -TILE_SIZE / 2, TILE_SIZE, TILE_SIZE);
                    ctx.restore();
                } else {
                    ctx.drawImage(sprite.image, sx, sy, sw, sh,
                        cx - TILE_SIZE / 2, cy - TILE_SIZE / 2, TILE_SIZE, TILE_SIZE
                    );
                }
            } else {
                ctx.beginPath();
                ctx.arc(cx, cy, radius, 0, Math.PI * 2);
                ctx.fillStyle = fallbackColor;
                ctx.fill();
            }

            // Label below
            ctx.fillStyle = COLOR_ENTITY_LABEL;
            ctx.font = "10px monospace";
            ctx.textAlign = "center";
            ctx.fillText(entity.label, cx, cy + radius + ENTITY_LABEL_OFFSET);

            // "E" prompt above when nearby
            if (isNearby) {
                ctx.fillStyle = COLOR_INTERACT_PROMPT;
                ctx.font = "bold 11px monospace";
                ctx.textAlign = "center";
                ctx.fillText("[E]", cx, cy - radius - INTERACT_PROMPT_OFFSET);
            }
        }
    }

    private drawPlayer(player: PlayerView): void {
        const { ctx } = this;
        const state = this.isPlayerMoving ? "walk" : "idle";
        const typeId = `player_${player.classId}_${state}_${this.playerFacing}`;
        const asset = this.assets.getSprite(typeId, PLAYER_CLASS_COLORS[player.classId], this.elapsed);
        const radius = TILE_SIZE * PLAYER_RADIUS_RATIO;
        const { x, y } = this.visualPos;

        if (asset.image && asset.sourceRect) {
            // Draw sprite centered on visual position
            const { x: sx, y: sy, w: sw, h: sh } = asset.sourceRect;
            ctx.drawImage(asset.image, sx, sy, sw, sh,
                x - TILE_SIZE / 2, y - TILE_SIZE / 2, TILE_SIZE, TILE_SIZE
            );
        } else {
            // Geometric fallback: filled circle + white outline
            ctx.beginPath();
            ctx.arc(x, y, radius, 0, Math.PI * 2);
            ctx.fillStyle = asset.fallbackColor;
            ctx.fill();

            ctx.strokeStyle = COLOR_PLAYER_OUTLINE;
            ctx.lineWidth   = COLOR_PLAYER_OUTLINE_WIDTH;
            ctx.stroke();

            ctx.fillStyle = COLOR_PLAYER_INITIAL;
            ctx.font = `bold ${Math.floor(radius)}px monospace`;
            ctx.textAlign = "center";
            ctx.textBaseline = "middle";
            ctx.fillText(player.classId[0].toUpperCase(), x, y);
            ctx.textBaseline = "alphabetic";
        }
    }

    private drawLoadingState(cssWidth: number, cssHeight: number): void {
        const { ctx } = this;
        ctx.fillStyle = COLOR_LOADING_BG;
        ctx.fillRect(0, 0, cssWidth, cssHeight);
        ctx.fillStyle = COLOR_LOADING_TEXT;
        ctx.font = "14px monospace";
        ctx.textAlign = "center";
        ctx.fillText("Loading room…", cssWidth / 2, cssHeight / 2);
    }
}

// -------------------------
// Helpers
// -------------------------

/** Convert tile grid coordinates to the pixel center of that tile. */
function tileToPixelCenter(tileX: number, tileY: number): Vec2 {
    return {
        x: tileX * TILE_SIZE + TILE_SIZE / 2,
        y: tileY * TILE_SIZE + TILE_SIZE / 2,
    };
}

/**
 * Chebyshev distance: the natural "within N tiles" metric on a grid,
 * since diagonal and orthogonal steps both cost 1.
 */
function chebyshevDist(x1: number, y1: number, x2: number, y2: number): number {
    return Math.max(Math.abs(x2 - x1), Math.abs(y2 - y1));
}

/** Deterministic hash of an entity id, used to decide a stable left/right mirror per enemy.
 * entity.id is a fixed backend model field (never regenerated per view), so the same enemy
 * always flips the same way across re-renders instead of flickering. */
function shouldFlip(id: string): boolean {
    let hash = 0;
    for (let i = 0; i < id.length; i++) {
        hash = (hash * 31 + id.charCodeAt(i)) | 0;
    }
    return (hash & 1) === 1;
}