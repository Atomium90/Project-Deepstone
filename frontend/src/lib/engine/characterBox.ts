import { TILE_SIZE } from "./constants";

/** A character sprite (an NPC) lives on the Pixel Crawler grid, where one tile is a 32x32 cell, so every
 * sprite pixel is TILE_SIZE / CHARACTER_CELL screen pixels. */
export const CHARACTER_CELL = 32;

export interface Box {
    x: number;
    y: number;
    w: number;
    h: number;
}

/**
 * Where a character sprite is drawn, in screen pixels, for the tile (`tileX`, `tileY`) it stands on and
 * the size of its cell in the sheet.
 *
 * It is centred on the tile and stands on the tile's bottom edge, so a cell bigger than one tile lets a
 * big character rise above its tile and spill over its sides, like the Sanctuary's halo, with its feet
 * where they would be anyway. A 32x32 cell comes out as exactly the tile.
 */
export function characterBox(tileX: number, tileY: number, cellW: number, cellH: number, tileSize: number = TILE_SIZE): Box {
    const scale = tileSize / CHARACTER_CELL;
    const w = cellW * scale;
    const h = cellH * scale;
    return { x: tileX * tileSize + tileSize / 2 - w / 2, y: (tileY + 1) * tileSize - h, w, h };
}

/**
 * How many rows of a strip's pixels are empty above its highest opaque pixel, over every frame of the
 * strip: the room a character leaves above its head in its cell, which no tile arithmetic can tell
 * because it depends on how the art fills the cell. `data` is RGBA, row after row, `width` pixels wide.
 * A strip with nothing opaque in it is all empty, `height` rows.
 */
export function emptyRowsAbove(data: ArrayLike<number>, width: number, height: number): number {
    for (let row = 0; row < height; row++) {
        for (let col = 0; col < width; col++) {
            if (data[(row * width + col) * 4 + 3] > 0) return row;
        }
    }
    return height;
}
