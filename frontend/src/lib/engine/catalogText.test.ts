import { describe, test, expect } from "vitest";
import itemsFile from "../../../../deepstone-backend/src/main/resources/data/items.json?raw";
import enemiesFile from "../../../../deepstone-backend/src/main/resources/data/enemies.json?raw";
import perksFile from "../../../../deepstone-backend/src/main/resources/data/perks.json?raw";
import upgradesFile from "../../../../deepstone-backend/src/main/resources/data/upgrades.json?raw";
import achievementsFile from "../../../../deepstone-backend/src/main/resources/data/achievements.json?raw";
import abilitiesFile from "../../../../deepstone-backend/src/main/resources/data/abilities.json?raw";
import setsFile from "../../../../deepstone-backend/src/main/resources/data/sets.json?raw";
import { catalogTextFor } from "./catalogText";
import { dictionaries } from "./i18n";

interface Entry {
    id?: string;
    typeId?: string;
    [field: string]: unknown;
}

const items = JSON.parse(itemsFile) as Entry[];
const enemies = JSON.parse(enemiesFile) as Entry[];
const perks = JSON.parse(perksFile) as Entry[];
const upgrades = JSON.parse(upgradesFile) as Entry[];
const achievements = JSON.parse(achievementsFile) as Entry[];
const abilities = JSON.parse(abilitiesFile) as Entry[];
const sets = JSON.parse(setsFile) as Entry[];

const english = dictionaries.en;

/** Every number found anywhere in an entry, as text. */
function numbersIn(value: unknown): Set<string> {
    const found = new Set<string>();
    const walk = (v: unknown): void => {
        if (typeof v === "number") found.add(String(v));
        else if (Array.isArray(v)) v.forEach(walk);
        else if (v !== null && typeof v === "object") Object.values(v).forEach(walk);
    };
    walk(value);
    return found;
}

/** The numbers a text states, leaving out the ones that are words ("4-piece", "2x"). */
function numbersOf(text: string): string[] {
    return (text.replace(/\d+-piece/g, "").replace(/\d+\s*[×x](?![a-z])/g, "").match(/\d+/g) ?? []);
}

describe("catalog texts", () => {
    test("every catalog entry has its texts in English", () => {
        const expected = [
            ...items.map((i) => `item.${i.typeId}.name`),
            ...enemies.map((e) => `enemy.${e.typeId}`),
            ...perks.flatMap((p) => [`perk.${p.id}.label`, `perk.${p.id}.desc`]),
            ...upgrades.flatMap((u) => [`upgrade.${u.id}.label`, `upgrade.${u.id}.desc`]),
            ...achievements.flatMap((a) => [`achievement.${a.id}.label`, `achievement.${a.id}.desc`]),
            ...abilities.flatMap((a) => [`ability.${a.id}.name`, `ability.${a.id}.resource`, `ability.${a.id}.desc`]),
            ...sets.flatMap((s) => [`set.${s.id}.name`, `set.${s.id}.bonus2`, `set.${s.id}.bonus4`]),
        ];
        expect(expected.filter((key) => !(key in english))).toEqual([]);
    });

    test("no text is left for an entry that no longer exists", () => {
        const ids = {
            item: new Set(items.map((i) => i.typeId)),
            enemy: new Set(enemies.map((e) => e.typeId)),
            perk: new Set(perks.map((p) => p.id)),
            upgrade: new Set(upgrades.map((u) => u.id)),
            achievement: new Set(achievements.map((a) => a.id)),
            ability: new Set(abilities.map((a) => a.id)),
            set: new Set(sets.map((s) => s.id)),
        };
        const orphans = Object.keys(english).filter((key) => {
            const [kind, id] = key.split(".");
            if (!(kind in ids)) return false;
            // Texts of the UI itself live under the same prefixes (set.progress, item.stack...).
            const known = ids[kind as keyof typeof ids];
            return !known.has(id) && !["progress", "partOf", "bonus2pc", "bonus4pc", "stack"].includes(id);
        });
        expect(orphans).toEqual([]);
    });

    test("a number written in a description is one of the entry's own numbers", () => {
        // A balance change in the data that is not echoed in the text would leave the player
        // reading the old figure.
        const stale: string[] = [];
        const check = (key: string, entry: Entry): void => {
            const allowed = numbersIn(entry);
            for (const n of numbersOf(english[key])) {
                if (!allowed.has(n)) stale.push(`${key} says ${n}`);
            }
        };
        for (const p of perks) check(`perk.${p.id}.desc`, p);
        // Two figures live in the code, not in the data: the third belt slot and the 5-charge stack.
        const codeSideNumbers: Record<string, number[]> = { extra_slot: [3], extra_potion_capacity: [5] };
        for (const u of upgrades) check(`upgrade.${u.id}.desc`, { ...u, codeSide: codeSideNumbers[u.id as string] ?? [] });
        for (const a of achievements) check(`achievement.${a.id}.desc`, a);
        for (const a of abilities) check(`ability.${a.id}.desc`, a);
        for (const s of sets) {
            check(`set.${s.id}.bonus2`, s);
            check(`set.${s.id}.bonus4`, s);
        }
        expect(stale).toEqual([]);
    });
});

describe("catalogTextFor", () => {
    test("reads the text of an id, in the language asked", () => {
        expect(catalogTextFor("en").itemName({ typeId: "steel_plate", name: "Server name" })).toBe("Steel Plate");
    });

    test("falls back to the server's text for an id with no key", () => {
        expect(catalogTextFor("en").itemName({ typeId: "no_such_item", name: "Server name" })).toBe("Server name");
        expect(catalogTextFor("en").enemyName("no_such_enemy", "Server label")).toBe("Server label");
    });

    test("picks the 2-piece or 4-piece bonus of a set", () => {
        const set = { id: "light_soldier", bonus2pcLabel: "x", bonus4pcLabel: "y" };
        expect(catalogTextFor("en").setBonus(set, 2)).toBe("+5% max HP");
        expect(catalogTextFor("en").setBonus(set, 4)).toBe("+2 flat DEF in combat");
    });
});
