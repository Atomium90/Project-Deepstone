// Merges the chest sprites into public/atlas/entities.json, taken from the DungeonTileset II
// (v1.7) sheet rather than from the 16x16 v5 tileset generate-atlas.mjs reads: only v1.7 has a
// mimic, and a chest set looks right when every state comes from one sheet.
//
// The v1.7 sheet ships no slice metadata, so the coordinates are listed by hand. Each chest is a
// 3-frame opening animation laid out left to right in 16x16 cells (closed, opening, open), one
// strip per kind of contents. The atlas only needs a still per chest state: the closed frame
// (identical in every strip) and the fully open last frame of each strip.
//
// Run manually. It merges into whatever entities.json already holds, so it also has to be re-run
// after generate-atlas.mjs, which rewrites that file from scratch:
//   node scripts/generate-chest-atlas.mjs

import { readFileSync, writeFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import path from "node:path";

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const publicDir = path.join(__dirname, "..", "public");
const entitiesAtlasPath = path.join(publicDir, "atlas", "entities.json");

const SHEET_PATH = "/sprites/tiles/0x72_DungeonTilesetII_v1.7.png";
const CELL = 16;
const CLOSED_FRAME_X = 304;
const OPEN_FRAME_X = 336;

// Strip rows: y=400 is the empty chest's, y=416 the full chest's, y=432 the mimic's.
const MANIFEST = [
    { name: "chest_closed", x: CLOSED_FRAME_X, y: 400 },
    { name: "chest_open_empty", x: OPEN_FRAME_X, y: 400 },
    { name: "chest_open_full", x: OPEN_FRAME_X, y: 416 },
    { name: "chest_mimic", x: OPEN_FRAME_X, y: 432 },
];

const existing = JSON.parse(readFileSync(entitiesAtlasPath, "utf-8"));
const sprites = existing.sprites ?? {};

for (const { name, x, y } of MANIFEST) {
    sprites[name] = { sheet: SHEET_PATH, x, y, w: CELL, h: CELL };
}

writeFileSync(entitiesAtlasPath, JSON.stringify({ sprites }, null, 2) + "\n");
console.log(`entities.json: set ${MANIFEST.length} chest sprites, ${Object.keys(sprites).length} total -> ${entitiesAtlasPath}`);
