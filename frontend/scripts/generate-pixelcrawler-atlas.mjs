// Merges frame-0 sprites from the Pixel Crawler pack's per-entity idle sheets into
// public/atlas/entities.json, alongside whatever generate-atlas.mjs already put there.
//
// Unlike 0x72, Pixel Crawler ships no atlas metadata and every sprite is its own small PNG
// file rather than a shared sheet - each entry below just needs a name and the file copied
// into public/sprites/entities/ (see the V0.3 Track B plan for the copy mapping). Frame size
// isn't constant across the pack, but every sheet checked follows one rule: frames are
// square, side length = sheet height, frame count = width / height. That's enough to find
// frame 0 (the leftmost frame - the resting pose in every sheet checked) without hardcoding
// a size per sprite.
//
// Run manually after copying the 9 source PNGs into place:
//   node scripts/generate-pixelcrawler-atlas.mjs

import { readFileSync, writeFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import path from "node:path";

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const publicDir = path.join(__dirname, "..", "public");
const entitiesAtlasPath = path.join(publicDir, "atlas", "entities.json");

const MANIFEST = [
    { name: "hero_idle", file: "hero/Idle_Down-Sheet.png" },
    { name: "mob_orc_idle", file: "mob_orc/Idle-Sheet.png" },
    { name: "mob_orc_rogue_idle", file: "mob_orc_rogue/Idle-Sheet.png" },
    { name: "mob_orc_shaman_idle", file: "mob_orc_shaman/Idle-Sheet.png" },
    { name: "mob_orc_warrior_idle", file: "mob_orc_warrior/Idle-Sheet.png" },
    { name: "mob_skeleton_idle", file: "mob_skeleton/Idle-Sheet.png" },
    { name: "mob_skeleton_mage_idle", file: "mob_skeleton_mage/Idle-Sheet.png" },
    { name: "mob_skeleton_rogue_idle", file: "mob_skeleton_rogue/Idle-Sheet.png" },
    { name: "mob_skeleton_warrior_idle", file: "mob_skeleton_warrior/Idle-Sheet.png" },
];

/** Reads width/height straight from a PNG's IHDR chunk - no image library needed. */
function pngDimensions(absPath) {
    const buf = readFileSync(absPath);
    return { width: buf.readUInt32BE(16), height: buf.readUInt32BE(20) };
}

const existing = JSON.parse(readFileSync(entitiesAtlasPath, "utf-8"));
const sprites = existing.sprites ?? {};

for (const { name, file } of MANIFEST) {
    const absPath = path.join(publicDir, "sprites", "entities", file);
    const { width, height } = pngDimensions(absPath);

    if (width % height !== 0) {
        throw new Error(
            `"${file}" is ${width}x${height} - width isn't a multiple of height, ` +
            `the square-frame assumption doesn't hold for this sheet.`
        );
    }

    sprites[name] = { sheet: `/sprites/entities/${file}`, x: 0, y: 0, w: height, h: height };
}

writeFileSync(entitiesAtlasPath, JSON.stringify({ sprites }, null, 2) + "\n");
console.log(`entities.json: added ${MANIFEST.length} Pixel Crawler sprites, ${Object.keys(sprites).length} total -> ${entitiesAtlasPath}`);
