// Generates a full-grid sprite atlas from a Tiled tileset (.tsx) - one atlas entry per tile in
// the sheet, keyed "{tilesetName}_{localId}" where localId is Tiled's own row-major tile
// numbering (id = row * columns + col). That's exactly the same number
// convert-tiled-room.mjs already resolves for every painted cell (gid - firstgid), and it's what
// makes that script's `sprite` tile property optional: as long as this atlas was generated from
// the same .tsx (so the tileset `name` matches), every painted tile resolves automatically to its
// exact sub-rect - no manual per-tile naming step in the Tileset editor needed at all. Tile size,
// column count and sprite keys are all read straight from the .tsx, nothing hardcoded here, so
// there's nothing to keep in sync by hand if the sheet ever grows.
//
// Only a single-image tileset is supported (one <image>, not a "collection of images" tileset) -
// that's the only kind used in this project so far.
//
// Usage:
//   node scripts/generate-tiled-tileset-atlas.mjs path/to/tileset.tsx
//
// Copies the tileset's own referenced PNG into frontend/public/sprites/tiles/ (creating it, or
// overwriting if the source changed) and writes frontend/public/atlas/{tilesetName}_tiles.json.
// Re-run this whenever a tileset's source PNG changes (new tiles added, etc) - safe to run
// repeatedly, it always regenerates the whole atlas file from scratch rather than merging.

import { readFileSync, writeFileSync, copyFileSync, mkdirSync } from "node:fs";
import { dirname, resolve, join, basename } from "node:path";
import { fileURLToPath } from "node:url";
import { parseXmlDoc } from "./lib/tiled-xml.mjs";

const __dirname = dirname(fileURLToPath(import.meta.url));
const publicDir = join(__dirname, "..", "public");

function fail(message) {
    console.error(`generate-tiled-tileset-atlas: ${message}`);
    process.exit(1);
}

function main() {
    const tsxPath = process.argv[2];
    if (!tsxPath) fail("usage: node scripts/generate-tiled-tileset-atlas.mjs path/to/tileset.tsx");

    const dir = dirname(tsxPath);
    const doc = parseXmlDoc(readFileSync(tsxPath, "utf-8"), tsxPath);
    const tileset = doc.querySelector("tileset");
    if (!tileset) fail(`"${tsxPath}" has no root <tileset> element - is this a valid Tiled .tsx file?`);

    const name = tileset.getAttribute("name");
    const tileWidth = Number(tileset.getAttribute("tilewidth"));
    const tileHeight = Number(tileset.getAttribute("tileheight"));
    const columns = Number(tileset.getAttribute("columns"));
    const tileCount = Number(tileset.getAttribute("tilecount"));
    if (!name) fail(`"${tsxPath}"'s <tileset> has no 'name' attribute - set one (Tileset editor > Properties) before generating an atlas from it.`);

    const imageEl = tileset.querySelector(":scope > image");
    if (!imageEl) fail(`"${tsxPath}" has no <image> - only a single-image tileset is supported, not a "collection of images" tileset.`);
    const imageSource = imageEl.getAttribute("source");

    const srcImagePath = resolve(dir, imageSource);
    const destImageName = basename(imageSource);
    const destDir = join(publicDir, "sprites", "tiles");
    const destImagePath = join(destDir, destImageName);
    mkdirSync(destDir, { recursive: true });
    copyFileSync(srcImagePath, destImagePath);

    const sheet = `/sprites/tiles/${destImageName}`;
    const sprites = {};
    for (let id = 0; id < tileCount; id++) {
        const col = id % columns;
        const row = Math.floor(id / columns);
        sprites[`${name}_${id}`] = { sheet, x: col * tileWidth, y: row * tileHeight, w: tileWidth, h: tileHeight };
    }

    const outPath = join(publicDir, "atlas", `${name}_tiles.json`);
    writeFileSync(outPath, JSON.stringify({ sprites }, null, 2) + "\n");
    console.log(`${name}_tiles.json: ${Object.keys(sprites).length} sprites (${tileWidth}x${tileHeight}, ${columns} cols) -> ${outPath}`);
    console.log(`Copied ${imageSource} -> ${destImagePath}`);
}

main();
