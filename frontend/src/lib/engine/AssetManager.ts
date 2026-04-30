import type { ClassId } from "./protocol";
import {
    COLOR_TILE_FLOOR,
    COLOR_TILE_WALL,
    COLOR_TILE_FLOOR_BORDER,
    PLAYER_CLASS_COLORS,
} from "./constants";

/** Describes how to draw a single sprite (or its geometric fallback). */
export interface DrawableAsset {
    /** If an image is loaded, draw it. Otherwise use the fallback. */
    image: HTMLImageElement | null;
    /** Fallback color used when no sprite is available yet. */
    fallbackColor: string;
}

/**
 * Centralizes all asset access for the renderer.
 *
 * Currently returns geometric fallbacks for everything. When real sprite sheets
 * are available, replace the load() calls to populate the image field —
 * the renderer will automatically use them with no other changes required.
 */
export class AssetManager {
    private sprites: Map<string, HTMLImageElement> = new Map();

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

    /** Returns the asset for a given player class. */
    getPlayer(classId: ClassId): DrawableAsset {
        return {
            image: this.sprites.get(`player_${classId}`) ?? null,
            fallbackColor: PLAYER_CLASS_COLORS[classId],
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