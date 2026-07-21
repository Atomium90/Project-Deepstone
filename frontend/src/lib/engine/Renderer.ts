import { AssetManager } from "./AssetManager";
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
    COLOR_ENTITY_LABEL,
    COLOR_ENTITY_FALLBACK,
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
        this.assets = new AssetManager();

        // Keep the canvas pixel dimensions in sync with its CSS layout size
        this.resizeObserver = new ResizeObserver(() => this.fitToContainer());
        this.resizeObserver.observe(canvas);
    }

    /**
     * Push a new game state into the renderer.
     * Called every time a StateUpdate arrives from the server.
     */
    update(room: RoomView, player: PlayerView): void {
        this.room = room;
        this.player = player;
        this.targetPos = tileToPixelCenter(room.playerX, room.playerY);

        // On the very first update, snap directly to the position (no lerp)
        if (!this.posInitialized) {
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

        if (Math.abs(dx) < LERP_SNAP_THRESHOLD && Math.abs(dy) < LERP_SNAP_THRESHOLD) {
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

                // Tile background
                ctx.fillStyle = this.assets.getTileColor(tileType);
                ctx.fillRect(x, y, TILE_SIZE, TILE_SIZE);

                // Subtle grid line on floor tiles only
                if (tileType === "floor") {
                    ctx.strokeStyle = this.assets.getTileFloorBorderColor();
                    ctx.lineWidth = COLOR_TILE_GRID_WIDTH;
                    ctx.strokeRect(x, y, TILE_SIZE, TILE_SIZE);
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

            // Entity circle
            ctx.beginPath();
            ctx.arc(cx, cy, radius, 0, Math.PI * 2);
            ctx.fillStyle = ENTITY_COLORS[entity.kind] ?? COLOR_ENTITY_FALLBACK;
            ctx.fill();

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
        const asset = this.assets.getPlayer(player.classId);
        const radius = TILE_SIZE * PLAYER_RADIUS_RATIO;
        const { x, y } = this.visualPos;

        if (asset.image) {
            // Draw sprite centered on visual position
            ctx.drawImage(asset.image, x - TILE_SIZE / 2, y - TILE_SIZE / 2, TILE_SIZE, TILE_SIZE);
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
 * Chebyshev distance — the natural "within N tiles" metric on a grid,
 * since diagonal and orthogonal steps both cost 1.
 */
function chebyshevDist(x1: number, y1: number, x2: number, y2: number): number {
    return Math.max(Math.abs(x2 - x1), Math.abs(y2 - y1));
}