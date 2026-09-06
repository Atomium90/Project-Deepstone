import { assets, type AssetManager, type SourceRect } from "./AssetManager";
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
    COLOR_ENTITY_SHRINE,
    COLOR_ENTITY_LABEL,
    COLOR_ENTITY_FALLBACK,
    PLAYER_CLASS_COLORS,
    COLOR_PLAYER_OUTLINE,
    COLOR_PLAYER_OUTLINE_WIDTH,
    COLOR_PLAYER_INITIAL,
    COLOR_LOADING_BG,
    COLOR_LOADING_TEXT,
    INTERACT_BADGE_SIZE,
    INTERACT_BADGE_RADIUS,
    INTERACT_BADGE_BG,
    INTERACT_BADGE_BORDER,
    INTERACT_BADGE_BORDER_WIDTH,
    INTERACT_BADGE_TEXT,
    INTERACT_BADGE_OFFSET,
    INTERACT_BADGE_BOUNCE_AMPLITUDE,
    INTERACT_BADGE_BOUNCE_PERIOD,
    ELITE_OUTLINE_COLOR,
    ELITE_OUTLINE_WIDTH,
    SHRINE_BASE_SCALE,
    SHRINE_ICON_SIZE_RATIO,
    SHRINE_ICON_OVERLAP,
    SHRINE_ICON_X_OFFSET,
    SHRINE_OUTLINE_FAR_WIDTH,
    SHRINE_OUTLINE_FAR_ALPHA,
    SHRINE_OUTLINE_NEAR_WIDTH,
    SHRINE_BASE_TRIM_WIDTH,
    SHRINE_BASE_TRIM_HEIGHT
} from "./constants";

/** The 8 offsets (in pixels) a silhouette-hugging outline pass draws its tinted copy at,
 * surrounding the real sprite/icon drawn on top - the visible fringe becomes the outline. Shared
 * by the Elite aura (drawEliteOutline) and the Shrine icon's two-ring glow (drawShrine). */
function outlineOffsets(width: number): ReadonlyArray<readonly [number, number]> {
    return [
        [-width, -width], [0, -width], [width, -width],
        [-width, 0],                    [width, 0],
        [-width, width],  [0, width],  [width, width],
    ];
}

const ENTITY_COLORS: Record<string, string> = {
    enemy: COLOR_ENTITY_ENEMY,
    chest: COLOR_ENTITY_CHEST,
    door:  COLOR_ENTITY_DOOR,
    locked_door: COLOR_ENTITY_LOCKED_DOOR,
    npc: COLOR_ENTITY_NPC,
    shrine: COLOR_ENTITY_SHRINE,
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

    /** Offscreen buffer reused to build one silhouette-recolor pass (draw the sprite/icon, then
     * source-in tint it solid gold) before compositing it onto the main canvas as an outline -
     * shared by the Elite aura and the Shrine icon's glow, resized to whatever each needs. Lazily
     * created on first use - most rooms contain neither. */
    private outlineCanvas: HTMLCanvasElement | null = null;
    private outlineCtx: CanvasRenderingContext2D | null = null;

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
     * Find the nearest entity within INTERACT_RANGE tiles of the player, directly north/south/
     * east/west - never diagonal. Returns null if no entity is reachable.
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
            if (!isCardinalNeighbor(px, py, entity.x, entity.y)) continue;
            const dist = chebyshevDist(px, py, entity.x, entity.y);
            if (dist < nearestDist) {
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
            const isNearby = isCardinalNeighbor(px, py, entity.x, entity.y);

            // Elite aura: always visible (not gated by proximity like the interact badge below) -
            // the whole point is anticipation before the player approaches. Drawn behind the
            // entity body (outline first, real sprite/circle drawn on top last).
            const isElite = entity.kind === "enemy" && !!entity.isElite;

            // Entity body: real sprite when one resolves, geometric circle otherwise
            const fallbackColor = ENTITY_COLORS[entity.kind] ?? COLOR_ENTITY_FALLBACK;
            const spriteKey = entity.kind === "enemy" ? entity.spriteId : ENTITY_SPRITES[entity.kind];
            const sprite = spriteKey ? this.assets.getSprite(spriteKey, fallbackColor, this.elapsed) : null;

            if (entity.kind === "shrine") {
                this.drawShrine(cx, cy);
            } else if (sprite?.image && sprite.sourceRect) {
                const flip = entity.kind === "enemy" && shouldFlip(entity.id);
                if (isElite) {
                    this.drawEliteOutline(sprite.image, sprite.sourceRect, flip, cx, cy);
                }
                const { x: sx, y: sy, w: sw, h: sh } = sprite.sourceRect;
                if (flip) {
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

            // Keycap badge above when nearby - the Shrine's composite (base + overlapping icon +
            // outline) is much taller than the generic per-entity circle radius every other kind
            // uses here, so the badge needs its own, taller anchor or it renders on top of the gem.
            if (isNearby) {
                const badgeTopOffset = entity.kind === "shrine" ? this.shrineTopOffset() : radius;
                this.drawInteractBadge(cx, cy - badgeTopOffset - INTERACT_BADGE_OFFSET);
            }
        }
    }

    /** Lazily creates (and resizes on demand) the reusable offscreen buffer a silhouette-recolor
     * pass tints and composites from - most rooms contain neither an Elite nor a Shrine, so this
     * never allocates in the common case. */
    private getOutlineBuffer(size: number): CanvasRenderingContext2D {
        if (!this.outlineCtx) {
            this.outlineCanvas = document.createElement("canvas");
            const ctx = this.outlineCanvas.getContext("2d");
            if (!ctx) throw new Error("Could not get 2D context for the outline buffer.");
            this.outlineCtx = ctx;
        }
        if (this.outlineCanvas!.width !== size || this.outlineCanvas!.height !== size) {
            this.outlineCanvas!.width = size;
            this.outlineCanvas!.height = size;
        }
        return this.outlineCtx;
    }

    /** Draws `image`'s `sourceRect` region (optionally flipped) into the shared outline buffer at
     * `size`x`size`, recolors every non-transparent pixel solid `color` (keeping the source's own
     * alpha shape), and returns the buffer's canvas - ready for the caller to composite as an
     * offset outline. Shared by the Elite aura and the Shrine icon's glow. */
    private tintedSilhouette(image: HTMLImageElement, sourceRect: SourceRect, flip: boolean, size: number, color: string): HTMLCanvasElement {
        const off = this.getOutlineBuffer(size);
        const { x: sx, y: sy, w: sw, h: sh } = sourceRect;

        off.clearRect(0, 0, size, size);
        off.save();
        if (flip) {
            off.translate(size, 0);
            off.scale(-1, 1);
        }
        off.drawImage(image, sx, sy, sw, sh, 0, 0, size, size);
        off.restore();

        off.globalCompositeOperation = "source-in";
        off.fillStyle = color;
        off.fillRect(0, 0, size, size);
        off.globalCompositeOperation = "source-over";

        return off.canvas;
    }

    /** Draws a silhouette-hugging outline around an Elite enemy's sprite: the sprite's own pixels,
     * offset by ELITE_OUTLINE_WIDTH in the 8 surrounding directions and tinted solid gold, so the
     * visible fringe follows the actual character silhouette (any pose, either facing) instead of
     * a generic shape laid on top. The real sprite is drawn afterward, on top, covering the center. */
    private drawEliteOutline(image: HTMLImageElement, sourceRect: SourceRect, flip: boolean, cx: number, cy: number): void {
        const silhouette = this.tintedSilhouette(image, sourceRect, flip, TILE_SIZE, ELITE_OUTLINE_COLOR);
        const { ctx } = this;
        const originX = cx - TILE_SIZE / 2;
        const originY = cy - TILE_SIZE / 2;
        for (const [dx, dy] of outlineOffsets(ELITE_OUTLINE_WIDTH)) {
            ctx.drawImage(silhouette, originX + dx, originY + dy);
        }
    }

    /** Draws a Shrine: a stone base ("torch_no_flame", sans its flame) topped by a floating item
     * icon, outlined with the same silhouette-recolor technique as the Elite aura (two rings this
     * time - see SHRINE_OUTLINE_FAR_WIDTH's doc for why the draw order matters). Falls back to a
     * plain circle if the base sprite hasn't resolved yet (atlas not loaded), same convention as
     * every other entity kind. */
    private drawShrine(cx: number, cy: number): void {
        const { ctx } = this;
        const base = this.assets.getSprite("torch_no_flame", COLOR_ENTITY_SHRINE, this.elapsed);
        if (!base.image || !base.sourceRect) {
            ctx.beginPath();
            ctx.arc(cx, cy, TILE_SIZE * ENTITY_RADIUS_RATIO, 0, Math.PI * 2);
            ctx.fillStyle = COLOR_ENTITY_SHRINE;
            ctx.fill();
            return;
        }

        // torch_no_flame's declared atlas box is mostly transparent padding shared with the lit-
        // torch animation frames - the actual visible holder is only this sub-region. Trimmed
        // here, locally, rather than editing the shared atlas entry (real wall torches elsewhere
        // still need the full box).
        const trim = { x: base.sourceRect.x + 3, y: base.sourceRect.y + 8, w: SHRINE_BASE_TRIM_WIDTH, h: SHRINE_BASE_TRIM_HEIGHT };
        const drawW = trim.w * SHRINE_BASE_SCALE;
        const drawH = trim.h * SHRINE_BASE_SCALE;
        const baseBottom = cy + TILE_SIZE / 2;
        const baseTop = baseBottom - drawH;
        ctx.drawImage(base.image, trim.x, trim.y, trim.w, trim.h, cx - drawW / 2, baseTop, drawW, drawH);

        const gem = this.assets.getSprite("dark_gem", COLOR_ENTITY_SHRINE, this.elapsed);
        if (!gem.image || !gem.sourceRect) return;

        const iconSize = TILE_SIZE * SHRINE_ICON_SIZE_RATIO;
        const iconX = cx - iconSize / 2 + SHRINE_ICON_X_OFFSET;
        const iconY = baseTop + SHRINE_ICON_OVERLAP - iconSize;

        const silhouette = this.tintedSilhouette(gem.image, gem.sourceRect, false, iconSize, ELITE_OUTLINE_COLOR);
        const rings = [
            { width: SHRINE_OUTLINE_FAR_WIDTH, alpha: SHRINE_OUTLINE_FAR_ALPHA }, // far, faded - drawn first
            { width: SHRINE_OUTLINE_NEAR_WIDTH, alpha: 1.0 },                     // near, normal - drawn second, on top
        ];
        for (const ring of rings) {
            ctx.globalAlpha = ring.alpha;
            for (const [dx, dy] of outlineOffsets(ring.width)) {
                ctx.drawImage(silhouette, iconX + dx, iconY + dy);
            }
        }
        ctx.globalAlpha = 1.0;

        const { x: gx, y: gy, w: gw, h: gh } = gem.sourceRect;
        ctx.drawImage(gem.image, gx, gy, gw, gh, iconX, iconY, iconSize, iconSize);
    }

    /** How far above the entity's center point (cy) the Shrine's tallest visible pixel sits - the
     * floating icon's top edge, plus its outline's far-ring extension. Mirrors drawShrine's own
     * baseTop/iconY math so the two can never drift apart. Used to position the interact badge:
     * the generic per-entity circle radius every other kind uses is far too small for this
     * taller composite, and using it here put the badge right on top of the gem. */
    private shrineTopOffset(): number {
        const drawH = SHRINE_BASE_TRIM_HEIGHT * SHRINE_BASE_SCALE;
        const iconSize = TILE_SIZE * SHRINE_ICON_SIZE_RATIO;
        return drawH - TILE_SIZE / 2 - SHRINE_ICON_OVERLAP + iconSize + SHRINE_OUTLINE_FAR_WIDTH;
    }

    /** Draws a small dark keycap-style badge (like a keyboard key) with "E" centered in it, its
     * bottom edge resting at `anchorY` and floating up periodically for a subtle "this is alive"
     * cue - replaces the old pulsing ring halo around the entity itself. */
    private drawInteractBadge(cx: number, anchorY: number): void {
        const { ctx } = this;
        const bounce = Math.sin(this.elapsed / INTERACT_BADGE_BOUNCE_PERIOD) * INTERACT_BADGE_BOUNCE_AMPLITUDE;
        const half = INTERACT_BADGE_SIZE / 2;
        const badgeBottom = anchorY + bounce;
        const badgeTop = badgeBottom - INTERACT_BADGE_SIZE;
        const badgeCenterY = badgeTop + half;

        ctx.beginPath();
        if (typeof ctx.roundRect === "function") {
            ctx.roundRect(cx - half, badgeTop, INTERACT_BADGE_SIZE, INTERACT_BADGE_SIZE, INTERACT_BADGE_RADIUS);
        } else {
            ctx.rect(cx - half, badgeTop, INTERACT_BADGE_SIZE, INTERACT_BADGE_SIZE);
        }
        ctx.fillStyle = INTERACT_BADGE_BG;
        ctx.fill();
        ctx.strokeStyle = INTERACT_BADGE_BORDER;
        ctx.lineWidth = INTERACT_BADGE_BORDER_WIDTH;
        ctx.stroke();

        ctx.fillStyle = INTERACT_BADGE_TEXT;
        ctx.font = "bold 10px monospace";
        ctx.textAlign = "center";
        ctx.textBaseline = "middle";
        ctx.fillText("E", cx, badgeCenterY);
        ctx.textBaseline = "alphabetic";
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

/**
 * True if (x2, y2) sits directly north/south/east/west of (x1, y1), within INTERACT_RANGE -
 * never diagonal, never the same tile. Chebyshev distance alone (used elsewhere for the Elite
 * aura, which has no directional meaning) treats a diagonal neighbor the same as a cardinal one,
 * which reads wrong for something as directional as "press E to interact".
 */
function isCardinalNeighbor(x1: number, y1: number, x2: number, y2: number): boolean {
    const sameRow = y1 === y2;
    const sameCol = x1 === x2;
    if (sameRow === sameCol) return false; // both true (same tile) or both false (diagonal)
    return chebyshevDist(x1, y1, x2, y2) <= INTERACT_RANGE;
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