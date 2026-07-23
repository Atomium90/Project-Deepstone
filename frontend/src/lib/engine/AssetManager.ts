import type { ClassId } from "./protocol";
import {
    COLOR_TILE_FLOOR,
    COLOR_TILE_WALL,
    COLOR_TILE_FLOOR_BORDER,
    PLAYER_CLASS_COLORS,
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

/** Shape of the atlas JSON files under public/atlas/, produced by scripts/generate-atlas.mjs. */
interface AtlasFile {
    sheet: string;
    sprites: Record<string, SourceRect>;
}

/** Where an atlas sprite lives: which loaded sheet, and which sub-rect of it. */
interface AtlasEntry extends SourceRect {
    sheetKey: string;
}

/** The atlas JSON files to preload at startup, keyed by the sheet key their sprites resolve to. */
const ATLAS_FILES: Record<string, string> = {
    tiles: "/atlas/tiles.json",
    entities: "/atlas/entities.json",
    items: "/atlas/items.json",
};

/**
 * Centralizes all asset access for the renderer.
 *
 * Two sprite sources coexist: whole-image sprites loaded ad hoc via load() (e.g. per-class
 * player sprites), and atlas sprites - named sub-rects within a shared sheet, described by
 * the JSON files in public/atlas/ and resolved through getSprite(). Both return the same
 * DrawableAsset shape; a null `image` (nothing loaded yet, or a typeId absent from the atlas)
 * always falls back to fallbackColor, so callers don't need to branch on which source it was.
 */
export class AssetManager {
    private sprites: Map<string, HTMLImageElement> = new Map();
    private atlas: Map<string, AtlasEntry> = new Map();

    constructor() {
        for (const [sheetKey, atlasUrl] of Object.entries(ATLAS_FILES)) {
            this.loadAtlas(sheetKey, atlasUrl);
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

    /** Loads one image and registers it as a sheet under `sheetKey` (alias of load()). */
    loadSheet(sheetKey: string, src: string): void {
        this.load(sheetKey, src);
    }

    /**
     * Fetches an atlas JSON file (see scripts/generate-atlas.mjs) and registers every sprite
     * it defines under `sheetKey`, loading the sheet image itself as a side effect. Safe to
     * call before the file exists -> sprites under `sheetKey` simply stay unresolved.
     */
    private async loadAtlas(sheetKey: string, atlasUrl: string): Promise<void> {
        try {
            const res = await fetch(atlasUrl);
            if (!res.ok) return;
            const data: AtlasFile = await res.json();

            this.loadSheet(sheetKey, data.sheet);
            for (const [name, rect] of Object.entries(data.sprites)) {
                this.atlas.set(name, { sheetKey, ...rect });
            }
        } catch {
            // Atlas not reachable -> callers keep getting fallbacks, nothing to recover.
        }
    }

    /** Returns the asset for a given player class. */
    getPlayer(classId: ClassId): DrawableAsset {
        return {
            image: this.sprites.get(`player_${classId}`) ?? null,
            fallbackColor: PLAYER_CLASS_COLORS[classId],
        };
    }

    /**
     * Looks up `typeId` (e.g. an enemy typeId, item typeId, or tile name) across every
     * loaded atlas. Falls back to `fallbackColor` if the atlas isn't loaded yet or has no
     * sprite under that name.
     */
    getSprite(typeId: string, fallbackColor: string): DrawableAsset {
        const entry = this.atlas.get(typeId);
        if (!entry) return { image: null, fallbackColor };

        return {
            image: this.sprites.get(entry.sheetKey) ?? null,
            sourceRect: { x: entry.x, y: entry.y, w: entry.w, h: entry.h },
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