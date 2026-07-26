// Merges class-correct, animated player sprites into public/atlas/entities.json, cropped from
// the two flat "platform sprite sheets 16px" sheets (public/sprites/entities/hero_classes/).
//
// Unlike the Pixel Crawler pack (one PNG per sprite), this source is 2 fixed 160x128 grids
// (10 cols x 8 rows of 16x16 cells), one per facing (left/right, pre-mirrored - no runtime
// flip needed). 2 rows per character; row pairs, in order: generic/unused (0-1), warrior
// (2-3), archer (4-5), mage (6-7). Within a character's row pair:
//   row A cols 0-3 = idle (4 frames), cols 4-7 = walk (4 frames), cols 8-9 = damaged (2 frames)
//   row B cols 0-3 = jump (unused, no jump mechanic), cols 4-7 = attack (4 frames),
//         col 8 = KO (1 static frame)
// See "ASSETS/platform sprite sheets 16px - frame layout.md" for the full reverse-engineered
// (then user-confirmed) breakdown this script encodes.
//
// Run manually after copying sprite_left.png/sprite_right.png into place:
//   node scripts/generate-player-atlas.mjs

import { readFileSync, writeFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import path from "node:path";

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const publicDir = path.join(__dirname, "..", "public");
const entitiesAtlasPath = path.join(publicDir, "atlas", "entities.json");

const CELL = 16;
const SHEET_W = 160;
const SHEET_H = 128;

/** rowA = idle/walk/damaged, rowB (rowA+1) = jump/attack/ko. */
const CLASS_ROWS = { warrior: 2, archer: 4, mage: 6 };
const FACINGS = ["left", "right"];
const sheetFor = (facing) => `/sprites/entities/hero_classes/sprite_${facing}.png`;

const FRAME_MS = { idle: 220, walk: 120, attack: 300, damaged: 250 };

/** Reads width/height straight from a PNG's IHDR chunk - no image library needed. */
function pngDimensions(absPath) {
    const buf = readFileSync(absPath);
    return { width: buf.readUInt32BE(16), height: buf.readUInt32BE(20) };
}

for (const facing of FACINGS) {
    const { width, height } = pngDimensions(path.join(publicDir, "sprites", "entities", "hero_classes", `sprite_${facing}.png`));
    if (width !== SHEET_W || height !== SHEET_H) {
        throw new Error(`sprite_${facing}.png is ${width}x${height}, expected ${SHEET_W}x${SHEET_H}.`);
    }
}

const existing = JSON.parse(readFileSync(entitiesAtlasPath, "utf-8"));
const sprites = existing.sprites ?? {};

let count = 0;
for (const [cls, rowA] of Object.entries(CLASS_ROWS)) {
    const rowB = rowA + 1;

    for (const facing of FACINGS) {
        const sheet = sheetFor(facing);
        sprites[`player_${cls}_idle_${facing}`] = {
            sheet, x: 0 * CELL, y: rowA * CELL, w: CELL, h: CELL,
            frameCount: 4, frameDuration: FRAME_MS.idle,
        };
        sprites[`player_${cls}_walk_${facing}`] = {
            sheet, x: 4 * CELL, y: rowA * CELL, w: CELL, h: CELL,
            frameCount: 4, frameDuration: FRAME_MS.walk,
        };
        count += 2;
    }

    // Portrait-only states (CombatScreen/GameOverScreen): no directionality, right-facing sheet.
    const portrait = sheetFor("right");
    sprites[`player_${cls}_attack`] = {
        sheet: portrait, x: 4 * CELL, y: rowB * CELL, w: CELL, h: CELL,
        frameCount: 4, frameDuration: FRAME_MS.attack,
    };
    sprites[`player_${cls}_damaged`] = {
        sheet: portrait, x: 8 * CELL, y: rowA * CELL, w: CELL, h: CELL,
        frameCount: 2, frameDuration: FRAME_MS.damaged,
    };
    sprites[`player_${cls}_ko`] = {
        sheet: portrait, x: 8 * CELL, y: rowB * CELL, w: CELL, h: CELL,
    };
    count += 3;
}

writeFileSync(entitiesAtlasPath, JSON.stringify({ sprites }, null, 2) + "\n");
console.log(`entities.json: added ${count} player sprites, ${Object.keys(sprites).length} total -> ${entitiesAtlasPath}`);
