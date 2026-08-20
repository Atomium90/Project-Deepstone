// Merges item icons into public/atlas/items.json, alongside whatever generate-atlas.mjs already
// put there (0x72's weapon_/flask_-prefixed slices).
//
// Unlike 0x72, neither the Case RPG packs (weapons/armour/mage, by CaseIRL) nor Kyrise's icon
// pack ship any coordinate/mapping file - just raw sheet PNGs. Same problem
// generate-pixelcrawler-atlas.mjs solves for Pixel Crawler: a hardcoded manifest here, derived
// from claude/items.csv's sourcePack/sheetFile/sheetRow/sheetCol columns. All four sheets sit on
// a confirmed clean 16px grid (verified by direct crop-and-inspect, see the project plan history),
// so cropping is just row*16/col*16 - no PNG-dimension reads needed, unlike the Pixel Crawler
// script which has to handle sprites of different native sizes.
//
// Every entry's atlas key is the item's own typeId (e.g. "steel_plate"), matching
// items.json's "iconId" field 1:1 - there's no pre-existing naming scheme to reconcile with here.
//
// Run manually after copying the 4 source PNGs into public/sprites/items/:
//   node scripts/generate-items-atlas.mjs

import { readFileSync, writeFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import path from "node:path";

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const publicDir = path.join(__dirname, "..", "public");
const itemsAtlasPath = path.join(publicDir, "atlas", "items.json");

const TILE_SIZE = 16;

/** One entry per catalog item: typeId, source sheet file (under public/sprites/items/), and its
 * row/col on that sheet's own 16px grid. Coordinates transcribed directly from claude/items.csv. */
const MANIFEST = [
    // -- Warrior: Light Soldier set --
    { typeId: "steel_plate", sheet: "rpg_armour_16.png", row: 4, col: 3 },
    { typeId: "steel_sword", sheet: "spritesheet_16x16.png", row: 21, col: 5 },
    { typeId: "ring_of_strength", sheet: "spritesheet_16x16.png", row: 16, col: 9 },
    { typeId: "iron_shield", sheet: "rpg_armour_16.png", row: 19, col: 4 },
    // -- Warrior: Enraged Berserker set --
    { typeId: "berserker_armor", sheet: "spritesheet_16x16.png", row: 0, col: 4 },
    { typeId: "obsidian_hammer", sheet: "rpg_weapons_16.png", row: 17, col: 6 },
    { typeId: "mask_of_terror", sheet: "spritesheet_16x16.png", row: 11, col: 15 },
    { typeId: "obsidian_hatchet", sheet: "rpg_weapons_16.png", row: 2, col: 6 },
    // -- Archer: Silent Archer set --
    { typeId: "leather_cape", sheet: "rpg_armour_16.png", row: 3, col: 0 },
    { typeId: "wooden_emerald_bow", sheet: "spritesheet_16x16.png", row: 4, col: 9 },
    { typeId: "accuracy_necklace", sheet: "spritesheet_16x16.png", row: 13, col: 0 },
    { typeId: "piercing_arrow", sheet: "spritesheet_16x16.png", row: 1, col: 0 },
    // -- Archer: Iron Bolter set --
    { typeId: "copper_cape", sheet: "rpg_armour_16.png", row: 3, col: 1 },
    { typeId: "bronze_crossbow", sheet: "rpg_weapons_16.png", row: 9, col: 2 },
    { typeId: "iron_bolt", sheet: "spritesheet_16x16.png", row: 0, col: 15 },
    { typeId: "rage_stone", sheet: "spritesheet_16x16.png", row: 9, col: 8 },
    // -- Mage: Fire Mage (pyromancer) set --
    { typeId: "pyromancer_cape", sheet: "rpg_mage_16.png", row: 3, col: 3 },
    { typeId: "pyromancer_staff", sheet: "rpg_mage_16.png", row: 14, col: 3 },
    { typeId: "pyromancer_hat", sheet: "spritesheet_16x16.png", row: 11, col: 4 },
    { typeId: "arcana_scroll", sheet: "spritesheet_16x16.png", row: 16, col: 12 },
    // -- Mage: Necromancer set --
    { typeId: "dark_mantle", sheet: "rpg_mage_16.png", row: 3, col: 6 },
    { typeId: "occult_grimoire", sheet: "spritesheet_16x16.png", row: 3, col: 13 },
    { typeId: "skull_talisman", sheet: "spritesheet_16x16.png", row: 18, col: 13 },
    { typeId: "dark_gem", sheet: "spritesheet_16x16.png", row: 8, col: 6 },
    // -- Generic weapons --
    { typeId: "silver_spear", sheet: "rpg_weapons_16.png", row: 22, col: 4 },
    { typeId: "bronze_battleaxe", sheet: "rpg_weapons_16.png", row: 6, col: 2 },
    { typeId: "practice_sword", sheet: "spritesheet_16x16.png", row: 21, col: 3 },
    { typeId: "flame_infused_bow", sheet: "spritesheet_16x16.png", row: 4, col: 12 },
    { typeId: "gold_dagger", sheet: "rpg_weapons_16.png", row: 11, col: 5 },
    { typeId: "leather_bound_grimoire", sheet: "spritesheet_16x16.png", row: 1, col: 8 },
    // -- Generic accessories --
    { typeId: "red_shield", sheet: "spritesheet_16x16.png", row: 18, col: 2 },
    { typeId: "wooden_shield", sheet: "rpg_armour_16.png", row: 15, col: 0 },
    { typeId: "plain_ring", sheet: "spritesheet_16x16.png", row: 16, col: 11 },
    { typeId: "worn_boots", sheet: "spritesheet_16x16.png", row: 3, col: 15 },
    { typeId: "hunting_gloves", sheet: "spritesheet_16x16.png", row: 10, col: 13 },
    { typeId: "travelers_necklace", sheet: "spritesheet_16x16.png", row: 13, col: 7 },
    { typeId: "open_grimoire", sheet: "spritesheet_16x16.png", row: 3, col: 7 },
    { typeId: "arcane_scroll", sheet: "spritesheet_16x16.png", row: 17, col: 1 },
    { typeId: "storm_potion", sheet: "spritesheet_16x16.png", row: 15, col: 12 },
    { typeId: "soulstone", sheet: "spritesheet_16x16.png", row: 9, col: 11 },
    { typeId: "speed_potion", sheet: "spritesheet_16x16.png", row: 14, col: 14 },
    { typeId: "luck_clover", sheet: "spritesheet_16x16.png", row: 12, col: 15 },
    // -- Consumables --
    { typeId: "health_potion", sheet: "spritesheet_16x16.png", row: 14, col: 13 },
    { typeId: "second_wind", sheet: "spritesheet_16x16.png", row: 15, col: 12 },
    { typeId: "battle_brew", sheet: "spritesheet_16x16.png", row: 15, col: 0 },
    { typeId: "volatile_flask", sheet: "spritesheet_16x16.png", row: 14, col: 9 },
    { typeId: "focus_tonic", sheet: "spritesheet_16x16.png", row: 14, col: 7 },
];

const existing = JSON.parse(readFileSync(itemsAtlasPath, "utf-8"));
const sprites = existing.sprites ?? {};

for (const { typeId, sheet, row, col } of MANIFEST) {
    sprites[typeId] = {
        sheet: `/sprites/items/${sheet}`,
        x: col * TILE_SIZE,
        y: row * TILE_SIZE,
        w: TILE_SIZE,
        h: TILE_SIZE,
    };
}

writeFileSync(itemsAtlasPath, JSON.stringify({ sprites }, null, 2) + "\n");
console.log(`items.json: added ${MANIFEST.length} item icons, ${Object.keys(sprites).length} total -> ${itemsAtlasPath}`);
