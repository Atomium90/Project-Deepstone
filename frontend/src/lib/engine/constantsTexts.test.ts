import { describe, test, expect } from "vitest";
import { dictionaries } from "./i18n";
import { CLASS_INFO, DIFFICULTY_INFO } from "./constants";
import type { ClassId, Difficulty, ItemKind, ItemRarity, RoomTypeName } from "./protocol";

/** The texts that used to live in constants.ts, and the ones built from an id at render time
 * (`class.${id}.name`, `rarity.${rarity}`...): a missing key would show on screen as the key. */
function expectKeys(keys: string[]): void {
    for (const [code, dictionary] of Object.entries(dictionaries)) {
        if (code !== "en") continue;
        const missing = keys.filter((key) => !(key in dictionary));
        expect(missing, `${code}.json lacks these keys`).toEqual([]);
    }
}

describe("texts built from an id", () => {
    test("each class has a name, a description and an affinity", () => {
        const classes = Object.keys(CLASS_INFO) as ClassId[];
        expectKeys(classes.flatMap((c) => [`class.${c}.name`, `class.${c}.desc`, `class.${c}.affinity`]));
    });

    test("each difficulty has a name and a description", () => {
        const tiers = Object.keys(DIFFICULTY_INFO) as Difficulty[];
        expectKeys(tiers.flatMap((d) => [`difficulty.${d}.name`, `difficulty.${d}.desc`]));
    });

    test("each room type, theme, rarity and item kind has a name", () => {
        const rooms: RoomTypeName[] = ["combat", "loot", "rest", "boss", "miniboss", "fork", "sanctuary", "vault"];
        const rarities: ItemRarity[] = ["common", "uncommon", "rare", "epic"];
        const kinds: ItemKind[] = ["weapon", "armor", "accessory", "consumable", "key"];
        expectKeys([
            ...rooms.map((r) => `room.${r}`),
            "theme.dungeon",
            "theme.darkDungeon",
            ...rarities.map((r) => `rarity.${r}`),
            ...kinds.map((k) => `itemKind.${k}`),
            ...(["UP", "DOWN", "LEFT", "RIGHT"] as const).map((d) => `minimap.exit.${d}`),
        ]);
    });
});
