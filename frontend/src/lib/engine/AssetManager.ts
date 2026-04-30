import type { ClassId } from "./protocol"

/** Describes how to draw a single sprite (or its geometric fallback). */
export interface DrawableAsset {
    /** If an image is loaded, draw it. Otherwise use the fallback. */
    image: HTMLImageElement | null;
    /** Fallback color used when no sprite is available yet. */
    fallbackColor: String;
}

/** Maps a class ID to its player color for the geometric placeholder. */
const CLASS_COLORS: Record<ClassId, string> = {
    warrior: "#e05c5c", // warm red
    archer: "#5ce07a",  // green
    mage: "#5c9be0",    // blue
};

const TILE_COLORS = {
    floor: "#2a2a2a",
    wall: "#0f0f0f",
    floorBorder: "#333333",
};

/**
 * Centralizes all asset access for the renderer.
 *
 * Currently returns geometric fallbacks for everything. When real sprite sheets
 * are available, replace the `load*` methods to populate the `image` field and
 * the renderer will automatically use them. No other changes required.
 */
export class AssetManager {
    private sprites: Map<String, HTMLImageElement> = new Map();

    /**
     * Pre-load a sprite from a URL and store it under the given key.
     * Safe to call even if the path does not exist yet -> the load will
     * silently fail and the geometric fallback will be used instead.
     */
    load(key: string, src: string): void {
        const img = new Image();
        img.onload = () => this.sprites.set(key, img);
        img.onerror = () => {
            // Sprite not found -> fallback is used
        };
        img.src = src;
    }

    /** Returns the asset for a given player class. */
    getPlayer(classId: ClassId): DrawableAsset {
        return {
            image: this.sprites.get(`player_${classId}`) ?? null,
            fallbackColor: CLASS_COLORS[classId],
        };
    }

    /** Returns the color to use for a tile of the given type. */
    getTileColor(tileType: string): string {
        return tileType == "wall" ? TILE_COLORS.wall : TILE_COLORS.floor;
    }

    getTileFloorBorderColor(): string {
        return TILE_COLORS.floorBorder;
    }
}