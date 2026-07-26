import {
    COLOR_TILE_FLOOR,
    COLOR_TILE_WALL,
    COLOR_TILE_FLOOR_BORDER,
    DEFAULT_FRAME_DURATION_MS,
} from "./constants";

/** A sub-rectangle within a sheet image, in source pixels. */
export interface SourceRect {
    x: number;
    y: number;
    w: number;
    h: number;
}

/** Describes how to draw a single sprite (or its geometric fallback). */
export interface DrawableAsset {
    /** Whole-image sprite, or the sheet a sourceRect is cropped from. Null = no image loaded. */
    image: HTMLImageElement | null;
    /** If set, only this sub-rect of `image` should be drawn. Unset = draw the whole image. */
    sourceRect?: SourceRect;
    /** Fallback color used when no sprite is available yet. */
    fallbackColor: string;
}

/** Where an atlas sprite lives: which sheet URL, and which sub-rect of it. */
export interface AtlasSprite extends SourceRect {
    sheet: string;
    /** Frame count for a horizontal frame-strip animation: frames are `w` px wide each, laid
     * out left-to-right starting at (x,y), same sheet, same y. Omitted or 1 = static single-frame
     * sprite - existing atlas entries need no changes to stay static. */
    frameCount?: number;
    /** Ms per frame. Only meaningful when frameCount > 1. Omitted -> DEFAULT_FRAME_DURATION_MS. */
    frameDuration?: number;
}

/** Current frame index (0-based) for an animated atlas entry at a given elapsed time. Returns 0
 * for static entries (frameCount absent or 1), regardless of elapsedMs. The single place frame-
 * index math lives, so canvas (Renderer) and DOM (Sprite.svelte) rendering stay consistent. */
export function frameIndexFor(
    entry: Pick<AtlasSprite, "frameCount" | "frameDuration">,
    elapsedMs: number
): number {
    const frameCount = entry.frameCount ?? 1;
    if (frameCount <= 1) return 0;
    const frameDuration = entry.frameDuration ?? DEFAULT_FRAME_DURATION_MS;
    return Math.floor(elapsedMs / frameDuration) % frameCount;
}

/** Shape of the atlas JSON files under public/atlas/, produced by scripts/generate-atlas*.mjs. */
interface AtlasFile {
    sprites: Record<string, AtlasSprite>;
}

/** The atlas JSON files to preload at startup. Each sprite carries its own sheet path, since
 * some packs (0x72) share one big sheet while others (Pixel Crawler) are one small PNG per sprite. */
const ATLAS_URLS = ["/atlas/tiles.json", "/atlas/entities.json", "/atlas/items.json"];

/**
 * Centralizes all asset access for the app. Used as a shared singleton (see `assets` below) by
 * both the canvas renderer and any Svelte component that needs a sprite (e.g. Sprite.svelte).
 *
 * Sprites are resolved through getSprite(), which looks up a named sub-rect within one of the
 * sheets described by the JSON files in public/atlas/. A null `image` (nothing loaded yet, or a
 * typeId absent from every atlas) always falls back to fallbackColor, so callers don't need to
 * branch on why a sprite didn't resolve. load() is the lower-level primitive that actually
 * fetches an image - used internally by the atlas loader, but also available directly for any
 * future whole-image sprite that doesn't come from an atlas.
 */
export class AssetManager {
    private readonly sprites: Map<string, HTMLImageElement> = new Map();
    private readonly requestedSheets: Set<string> = new Set();
    private readonly atlas: Map<string, AtlasSprite> = new Map();

    /** Kicks off loading every atlas file. Fire-and-forget by design (see load()'s doc comment)
     * - called explicitly by the root App component on mount rather than from the constructor,
     * since constructors shouldn't launch unawaited async work. */
    preloadAtlases(): void {
        for (const atlasUrl of ATLAS_URLS) {
            this.loadAtlas(atlasUrl);
        }
    }

    /**
     * Pre-load a sprite from a URL and store it under the given key.
     * Safe to call even if the path does not exist yet -> the load will
     * silently fail and the geometric fallback will be used instead.
     */
    load(key: string, src: string): void {
        const img = new Image();
        img.onload = () => this.sprites.set(key, img);
        img.onerror = () => {
            // Sprite not found -> fallback will be used automatically.
        };
        img.src = src;
    }

    /**
     * Fetches an atlas JSON file (see scripts/generate-atlas*.mjs) and registers every sprite
     * it defines, loading each distinct sheet URL it references exactly once. Safe to call
     * before the file exists -> its sprites simply stay unresolved.
     */
    private async loadAtlas(atlasUrl: string): Promise<void> {
        try {
            const res = await fetch(atlasUrl);
            if (!res.ok) return;
            const data: AtlasFile = await res.json();

            for (const [name, sprite] of Object.entries(data.sprites)) {
                if (!this.requestedSheets.has(sprite.sheet)) {
                    this.requestedSheets.add(sprite.sheet);
                    this.load(sprite.sheet, sprite.sheet);
                }
                this.atlas.set(name, sprite);
            }
        } catch {
            // Atlas not reachable -> callers keep getting fallbacks, nothing to recover.
        }
    }

    /**
     * Looks up `typeId` (e.g. an enemy typeId, item typeId, or tile name) across every
     * loaded atlas. Falls back to `fallbackColor` if the atlas isn't loaded yet or has no
     * sprite under that name. `elapsedMs` selects the current frame for animated entries
     * (see frameIndexFor) - callers that never animate can omit it.
     */
    getSprite(typeId: string, fallbackColor: string, elapsedMs = 0): DrawableAsset {
        const entry = this.atlas.get(typeId);
        if (!entry) return { image: null, fallbackColor };

        const frameIndex = frameIndexFor(entry, elapsedMs);
        return {
            image: this.sprites.get(entry.sheet) ?? null,
            sourceRect: { x: entry.x + frameIndex * entry.w, y: entry.y, w: entry.w, h: entry.h },
            fallbackColor,
        };
    }

    /** Returns the color to use for a tile of the given type. */
    getTileColor(tileType: string): string {
        return tileType === "wall" ? COLOR_TILE_WALL : COLOR_TILE_FLOOR;
    }

    getTileFloorBorderColor(): string {
        return COLOR_TILE_FLOOR_BORDER;
    }
}

/** The singleton AssetManager used throughout the app - one atlas cache shared by the canvas
 * renderer and any Svelte component that needs a sprite (e.g. Sprite.svelte). Call
 * `assets.preloadAtlases()` once from the root App component on mount, same convention as
 * `client`/`connectToServer()` in StateStore.ts. */
export const assets = new AssetManager();