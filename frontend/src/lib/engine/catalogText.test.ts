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
import type { ItemStats } from "./protocol";

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

/** The effect id of a set bonus as written in sets.json. */
function effectOf(bonus: unknown): string {
    return (bonus as { type: string }).type;
}

/** Every number found anywhere in an entry, as text. */
function numbersIn(value: unknown): Set<string> {
    const found = new Set<string>();
    const walk = (v: unknown): void => {
        if (typeof v === "number") {
            found.add(String(v));
            // A malus is stored negative and written as its size ("40% less").
            found.add(String(Math.abs(v)));
        }
        else if (Array.isArray(v)) v.forEach(walk);
        else if (v !== null && typeof v === "object") Object.values(v).forEach(walk);
    };
    walk(value);
    return found;
}

/** The numbers a text states, leaving out the ones that are words ("4-piece", "2x"). */
function numbersOf(text: string): string[] {
    return (
        text
            .replace(/\d+-piece/g, "")
            .replace(/\d+\s*[×x](?![a-z])/g, "")
            // "4 pièces" in French is the size of a set, not a figure of the data.
            .replace(/\d+ pièces/g, "")
            // "x2" is a multiplier written the other way round.
            .replace(/[×x]\d+/g, "")
            .match(/\d+/g) ?? []
    );
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
            ...sets.map((s) => `set.${s.id}.name`),
            ...sets.flatMap((s) => [`set.effect.${effectOf(s.twoPiece)}`, `set.effect.${effectOf(s.fourPiece)}`]),
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
        // A text of an entry is `<kind>.<id>.<field>` (or `enemy.<id>`); the other keys under the same
        // prefixes (set.progress, item.stat.attack, set.effect.FlatAttack...) are the UI's own.
        const fields = ["name", "label", "desc", "resource"];
        const orphans = Object.keys(english).filter((key) => {
            const [kind, id, field, ...rest] = key.split(".");
            if (!(kind in ids)) return false;
            const known = ids[kind as keyof typeof ids];
            if (kind === "enemy") return rest.length === 0 && field === undefined && !known.has(id);
            return field !== undefined && rest.length === 0 && fields.includes(field) && !known.has(id);
        });
        expect(orphans).toEqual([]);
    });

    test.each(Object.keys(dictionaries))("a number written in a %s description is one of the entry's own numbers", (code) => {
        // A balance change in the data that is not echoed in the text would leave the player
        // reading the old figure.
        const texts = dictionaries[code];
        const stale: string[] = [];
        const check = (key: string, entry: Entry): void => {
            const allowed = numbersIn(entry);
            for (const n of numbersOf(texts[key])) {
                if (!allowed.has(n)) stale.push(`${key} says ${n}`);
            }
        };
        for (const p of perks) check(`perk.${p.id}.desc`, p);
        // Two figures live in the code, not in the data: the third belt slot and the 5-charge stack.
        const codeSideNumbers: Record<string, number[]> = { extra_slot: [3], extra_potion_capacity: [5] };
        for (const u of upgrades) check(`upgrade.${u.id}.desc`, { ...u, codeSide: codeSideNumbers[u.id as string] ?? [] });
        for (const a of achievements) check(`achievement.${a.id}.desc`, a);
        for (const a of abilities) check(`ability.${a.id}.desc`, a);
        expect(stale).toEqual([]);
    });
});

describe("catalogTextFor", () => {
    test("reads the text of an id, in the language asked", () => {
        expect(catalogTextFor("en").itemName({ typeId: "steel_plate" })).toBe("Steel Plate");
    });

    test("shows the id itself when it has no text, so a missing text is noticed", () => {
        expect(catalogTextFor("en").itemName({ typeId: "no_such_item" })).toBe("no_such_item");
        expect(catalogTextFor("en").enemyName("no_such_enemy")).toBe("no_such_enemy");
        expect(catalogTextFor("en").perkLabel({ id: "no_such_perk" })).toBe("no_such_perk");
    });

    test("writes a set bonus from its effect and figure", () => {
        const set = {
            bonus2pc: { effect: "MaxHpPercent", value: 5 },
            bonus4pc: { effect: "FlatDefense", value: 2 },
        };
        expect(catalogTextFor("en").setBonus(set, 2)).toBe("+5% max HP");
        expect(catalogTextFor("en").setBonus(set, 4)).toBe("+2 flat DEF in combat");
    });

});

describe("item stat lines", () => {
    const line = (stats: ItemStats, typeTag?: string): string => catalogTextFor("en").itemStatLine({ stats, typeTag });

    test("a weapon or armor shows its figure with its affinity tag", () => {
        expect(line({ attack: 8 }, "heavy")).toBe("+8 ATK [heavy]");
        expect(line({ defense: 4 })).toBe("+4 DEF");
    });

    test("a field the server sends as null (what Circe writes for a None) is left out, like a missing one", () => {
        // JSON from the server: every empty field is present and null, not absent.
        const wire = JSON.parse('{"maxHp":null,"attack":20,"defense":null,"critChance":null,"effect":null}') as ItemStats;
        expect(line(wire, "heavy")).toBe("+20 ATK [heavy]");
        const potion = JSON.parse('{"maxHp":null,"effect":"attackBuff","amount":null,"percent":20,"turns":3}') as ItemStats;
        expect(line(potion)).toBe("+20% ATK for 3 turns");
    });

    test("an accessory lists every figure it has, in a fixed order, then the tag", () => {
        expect(line({ critChance: 6, maxHp: 10, attack: 4, defense: 2 }, "magic")).toBe(
            "+10 MAX HP, +4 ATK, +2 DEF, +6% CRIT [magic]",
        );
    });

    test("a consumable writes its effect, with the singular and plural of a turn count", () => {
        expect(line({ effect: "healFixed", amount: 30 })).toBe("Heal 30 HP");
        expect(line({ effect: "healPercent", percent: 25 })).toBe("Heal 25% HP");
        expect(line({ effect: "restoreResource", amount: 20 })).toBe("Restore 20 Resource");
        expect(line({ effect: "attackBuff", percent: 20, turns: 3 })).toBe("+20% ATK for 3 turns");
        expect(line({ effect: "attackBuff", percent: 20, turns: 1 })).toBe("+20% ATK for 1 turn");
        expect(line({ effect: "flatDamage", amount: 40 })).toBe("Deal 40 damage");
        expect(line({ effect: "critBuff", percent: 15, turns: 2 })).toBe("+15% crit chance for 2 turns");
        expect(line({ effect: "opensDoor" })).toBe("Opens a locked door");
    });


    test("every consumable effect and affinity tag the server can send has a text", () => {
        const effects = ["healFixed", "healPercent", "restoreResource", "flatDamage", "opensDoor"];
        const withTurns = ["attackBuff", "critBuff"];
        const keys = [
            ...effects.map((e) => `item.effect.${e}`),
            ...withTurns.flatMap((e) => [`item.effect.${e}.one`, `item.effect.${e}.other`]),
            ...["heavy", "ranged", "magic", "light"].map((t) => `tag.${t}`),
        ];
        expect(keys.filter((key) => !(key in english))).toEqual([]);
    });
});
