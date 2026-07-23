// Generates frontend/public/atlas/{tiles,entities,items}.json from the 0x72 tileset's own
// Aseprite slice export. Run manually after copying the pack into public/sprites/tiles/:
//   node scripts/generate-atlas.mjs

import { readFileSync, writeFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import path from "node:path";

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const publicDir = path.join(__dirname, "..", "public");

const SHEET_PATH = "/sprites/tiles/0x72_16x16DungeonTileset.v5.png";
const SOURCE_JSON = path.join(publicDir, "sprites", "tiles", "0x72_16x16DungeonTileset.v5.json");

/** Buckets a slice name into one of our atlas domains, or null to skip it. */
function categorize(name) {
    const n = name.toLowerCase();

    if (n.startsWith("weapon_") || n.startsWith("waepon_") || n.startsWith("flask_")) {
        return "items";
    }
    if (
        n.startsWith("monster_") || n.startsWith("npc_") ||
        n.startsWith("chest_") || n.startsWith("door_") || n.startsWith("hero")
    ) {
        return "entities";
    }
    if (
        n.startsWith("floor_") || n.startsWith("wall_") || n.startsWith("edge_") ||
        n.startsWith("pit_") || n.startsWith("stairs_") || n.startsWith("darkness_") ||
        n.startsWith("gargoyle_") || n.startsWith("torch_") || n.startsWith("column") ||
        n.startsWith("box") || n === "skull" || n === "black"
    ) {
        return "tiles";
    }

    return null;
}

const source = JSON.parse(readFileSync(SOURCE_JSON, "utf-8"));
const { size, slices } = source.meta;

const buckets = { tiles: {}, entities: {}, items: {} };
const skipped = [];

for (const slice of slices) {
    const category = categorize(slice.name);
    if (!category) {
        skipped.push(slice.name);
        continue;
    }

    const { x, y, w, h } = slice.keys[0].bounds;
    if (x + w > size.w || y + h > size.h) {
        throw new Error(`Slice "${slice.name}" bounds exceed sheet size ${size.w}x${size.h}`);
    }

    // The source JSON has a couple of duplicate names (e.g. two "Wall_outline_2" slices at
    // different coordinates) - disambiguate rather than silently dropping one.
    let key = slice.name;
    let suffix = 2;
    while (key in buckets[category]) {
        key = `${slice.name}_alt${suffix}`;
        suffix++;
    }
    if (key !== slice.name) {
        console.warn(`Duplicate slice name "${slice.name}" - second occurrence renamed to "${key}"`);
    }

    buckets[category][key] = { x, y, w, h };
}

for (const [category, sprites] of Object.entries(buckets)) {
    const out = { sheet: SHEET_PATH, sprites };
    const outPath = path.join(publicDir, "atlas", `${category}.json`);
    writeFileSync(outPath, JSON.stringify(out, null, 2) + "\n");
    console.log(`${category}.json: ${Object.keys(sprites).length} sprites -> ${outPath}`);
}

if (skipped.length > 0) {
    console.log(`Skipped ${skipped.length} slice(s) not mapped to any atlas: ${skipped.join(", ")}`);
}
