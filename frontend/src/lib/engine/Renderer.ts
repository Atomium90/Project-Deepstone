import { AssetManager } from "./AssetManager";
import type { RoomView, PlayerView } from "./protocol"

/** Size of one tile in pixels. */
export const TILE_SIZE = 64;

/** How fast the player sprite moves toward its target position (0–1 per frame). */
const LERP_SPEED = 0.18;

/** Threshold in pixels below which we snap to the target (avoids infinite micro-lerp). */
const LERP_SNAP_THRESHOLD = 0.5;

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

    private animFramId: number | null = null;
    private room: RoomView | null = null;
    private player: PlayerView | null = null;

    /** Current visual position of the player in pixel space (interpolated). */
    private visualPos: Vec2 = { x: 0, y: 0 };

    /** Target position in pixel space (set from server state). */
    private targetPos: Vec2 = { x: 0, y: 0 };

    /** Whether the visual position has been initialized (skip lerp on first frame). */
    private posInitialized: false;

    constructor(canvas: HTMLCanvasElement) {
        const ctx = canvas.getContext("2d");
        if (!ctx) throw new Error("Could not get 2D rendering context from canvas.");

        this.canvas = canvas;
        this.ctx = ctx;
        this.assets = new AssetManager();
    }

    /**
     * Push a new game state into the renderer.
     * Called every time a StateUpdate arrives from the server.
     */
    update(room: RoomView, player: PlayerView): void {
        this.room = room;
        this.player = player;

        // Resize canvas to fit the room
        const expectedWidth = room.width * TILE_SIZE;
        const expectedHeight = room.height * TILE_SIZE;
        if (this.canvas.width !== expectedWidth || this.canvas.height !== expectedHeight) {
            this.canvas.width = expectedWidth;
            this.canvas.height = expectedHeight;
        }

        // Update the target pixel position
        this.targetPos = tileToPixelCenter(room.playerX, room.playerY);

        // On the very first update, snap directly to the position (no lerp)
        if (!this.posInitialized) {
            this.visualPos = { ...this.targetPos };
            this.posInitialized = true;
        }
    }

    /** Start the render loop. Call once after mounting the canvas. */
    start(): void {
        if (this.animFramId !== null) return;
        this.loop()
    }

    stop(): void {
        if (this.animFramId !== null) {
            cancelAnimationFrame(this.animFramId);
            this.animFramId = null;
        }
    }

    // -------------------------------------------------------------------------
    // Private rendering pipeline
    // -------------------------------------------------------------------------

    private loop = (): void => {
        this.animFramId = requestAnimationFrame(this.loop);
        this.tick();
    }

    private tick(): void {
        this.interpolate();
        this.draw();
    }

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
        const {ctx, canvas } = this;

        // Clear
        ctx.clearRect(0, 0, canvas.width, canvas.height);

        if (!this.room || !this.player) {
            this.drawLoadingState();
            return;
        }

        this.drawTiles(this.room);
        this.drawEntities(this.room);
        this.drawPlayer(this.player);
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
                    ctx.lineWidth = 0.5;
                    ctx.strokeRect(x, y, TILE_SIZE, TILE_SIZE);
                }
            }
        }
    }

    private drawEntities(room: RoomView): void {
        const { ctx } = this;

        for (const entity of room.entities) {
            const cx = entity.x * TILE_SIZE + TILE_SIZE / 2;
            const cy = entity.y * TILE_SIZE + TILE_SIZE / 2;
            const radius = TILE_SIZE * 0.3;

            const color = ENTITY_COLORS[entity.kind] ?? "#888";

            ctx.beginPath();
            ctx.arc(cx, cy, radius, 0, Math.PI * 2);
            ctx.fillStyle = color;
            ctx.fill();

            // Small label below
            ctx.fillStyle = "#ccc";
            ctx.font = "10px monospace";
            ctx.textAlign = "center";
            ctx.fillText(entity.label, cx, cy + radius + 12);
        }
    }

    private drawPlayer(player: PlayerView): void {
        const { ctx } = this;
        const asset = this.assets.getPlayer(player.classId);
        const radius = TILE_SIZE * 0.32;
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
            ctx.strokeStyle = "rgba(255,255,255,0.4)";
            ctx.lineWidth = 2;
            ctx.stroke();

            // Class initial
            ctx.fillStyle = "#fff";
            ctx.font = `bold ${Math.floor(radius)}px monospace`;
            ctx.textAlign = "center";
            ctx.textBaseline = "middle";
            ctx.fillText(player.classId[0].toUpperCase(), x, y);
            ctx.textBaseline = "alphabetic";
        }
    }

    private drawLoadingState(): void {
        const { ctx, canvas } = this;
        ctx.fillStyle = "#1a1a1a";
        ctx.fillRect(0, 0, canvas.width, canvas.height);
        ctx.fillStyle = "#555";
        ctx.font = "14px monospace";
        ctx.textAlign = "center";
        ctx.fillText("Loading room…", canvas.width / 2, canvas.height / 2);
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

const ENTITY_COLORS: Record<string, string> = {
    enemy: "#c0392b",
    chest: "#d4ac0d",
    door: "#5d6d7e",
}
















