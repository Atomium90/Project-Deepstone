// Merges the NPC sprites into public/atlas/entities.json. Each NPC is a 4-frame idle strip of 32x32
// cells, built from the "16x16 Fantasy RPG Characters" pack by build-npc-strips.py, and animated the
// way the enemies' idle sheets are (frameCount).
//
// The ids name the character of the pack, never the NPC of the story, so that giving an NPC another
// look only changes data/npcs.json (the backend catalog that says which sprite each NPC wears).
//
// Run manually. It merges into whatever entities.json already holds, so it also has to be re-run
// after generate-atlas.mjs, which rewrites that file from scratch:
//   node scripts/generate-npc-atlas.mjs

import { readFileSync, writeFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import path from "node:path";

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const publicDir = path.join(__dirname, "..", "public");
const entitiesAtlasPath = path.join(publicDir, "atlas", "entities.json");

const FRAME = 32;
const FRAME_COUNT = 4;

/** Atlas id -> strip file under /sprites/entities/npcs/. */
const MANIFEST = {
    npc_townsfolk_male_idle: "townsfolk_male_idle.png",
    npc_executioner_idle: "executioner_idle.png",
    npc_bishop_idle: "bishop_idle.png",
    npc_herald_idle: "herald_idle.png",
    npc_archer_idle: "archer_idle.png",
};

const existing = JSON.parse(readFileSync(entitiesAtlasPath, "utf-8"));
const sprites = existing.sprites ?? {};

for (const [id, file] of Object.entries(MANIFEST)) {
    sprites[id] = {
        sheet: `/sprites/entities/npcs/${file}`,
        x: 0,
        y: 0,
        w: FRAME,
        h: FRAME,
        frameCount: FRAME_COUNT,
    };
}

writeFileSync(entitiesAtlasPath, JSON.stringify({ sprites }, null, 2) + "\n");
console.log(`entities.json: set ${Object.keys(MANIFEST).length} NPC sprites, ${Object.keys(sprites).length} total -> ${entitiesAtlasPath}`);
