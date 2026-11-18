import { describe, test, expect } from "vitest";
import { logLineText } from "./logText";
import { dictionaries } from "./i18n";
import type { LogLine } from "./protocol";

const line = (key: string, params: LogLine["params"] = {}): LogLine => ({ key, params });

describe("logLineText", () => {
    test("writes a line from its key and params", () => {
        expect(logLineText(line("log.combat.defend"), "en")).toBe(
            "You brace for impact. Incoming damage is halved this round.",
        );
        expect(logLineText(line("log.combat.engage", { enemy: "goblin" }), "en")).toBe("You engage the Goblin!");
    });

    test("an id param is replaced by the name of what it stands for", () => {
        expect(logLineText(line("log.combat.strike", { enemy: "goblin", damage: 12 }), "en")).toBe(
            "You strike the Goblin for 12 damage.",
        );
        expect(logLineText(line("log.equip.equip", { item: "steel_plate" }), "en")).toBe("You equip Steel Plate.");
        expect(logLineText(line("log.combat.abilityEmpower", { ability: "berserker_slash" }), "en")).toBe(
            "Berserker Slash! Your next attack is empowered.",
        );
        expect(logLineText(line("log.run.begin", { class: "mage" }), "en")).toBe("A new run begins. Good luck, Mage.");
        expect(logLineText(line("log.run.tierLocked", { difficulty: "hard" }), "en")).toBe("Hard is not unlocked yet.");
        expect(logLineText(line("log.door.pass", { direction: "UP" }), "en")).toBe("You pass through the door heading Up.");
    });

    test("the resource of a line is the one the ability spends", () => {
        const text = logLineText(
            line("log.combat.notEnoughResource", { resource: "berserker_slash", ability: "berserker_slash", cost: 40 }),
            "en",
        );
        expect(text).toBe("Not enough Rage — Berserker Slash requires 40 Rage.");
    });

    test("a count picks the singular or the plural text", () => {
        expect(logLineText(line("log.combat.xpShards", { xp: 3, count: 1 }), "en")).toBe("You gain 3 XP and 1 Shard.");
        expect(logLineText(line("log.combat.xpShards", { xp: 15, count: 3 }), "en")).toBe("You gain 15 XP and 3 Shards.");
        expect(logLineText(line("log.combat.useAttackBuff", { item: "battle_brew", percent: 20, count: 3 }), "en")).toContain(
            "for 3 turns",
        );
    });

    test("an id that has no text is shown as it is", () => {
        expect(logLineText(line("log.equip.equip", { item: "no_such_item" }), "en")).toBe("You equip no_such_item.");
    });

    test("a raw line shows its own text", () => {
        expect(logLineText(line("log.raw", { text: "Debug room loaded." }), "en")).toBe("Debug room loaded.");
    });

    test("an unknown key shows the key, so a missing text is noticed", () => {
        expect(logLineText(line("log.no.such.line"), "en")).toBe("log.no.such.line");
    });
});

describe("the log texts", () => {
    const sources = import.meta.glob<string>("../../../../deepstone-backend/src/main/scala/**/*.scala", {
        eager: true,
        query: "?raw",
        import: "default",
    });

    /** Every log key the server writes as a plain string in its code. */
    const serverKeys = new Set<string>();
    for (const source of Object.values(sources)) {
        for (const match of source.matchAll(/"(log\.[A-Za-z0-9]+(?:\.[A-Za-z0-9]+)+)"/g)) serverKeys.add(match[1]);
    }

    /** A key counts as written when its text exists, or its plural forms do. */
    const written = (key: string): boolean =>
        key in dictionaries.en || `${key}.other` in dictionaries.en || `${key}.one` in dictionaries.en;

    test("the server's code was found", () => {
        expect(serverKeys.size).toBeGreaterThan(60);
    });

    test("every key the server writes has its text", () => {
        const missing = [...serverKeys].filter((key) => key !== "log.raw" && !written(key));
        expect(missing).toEqual([]);
    });

    test("the keys the server builds from a prefix have their texts too", () => {
        // `log.levelUp.<kind>`, and `log.chest.found` / `log.chest.foundBack` with Choice or Worse.
        const built = [
            ...["maxHp", "attack", "defense", "resource"].map((k) => `log.levelUp.${k}`),
            ...["found", "foundBack"].flatMap((k) => [`log.chest.${k}`, `log.chest.${k}Choice`, `log.chest.${k}Worse`]),
        ];
        expect(built.filter((key) => !written(key))).toEqual([]);
    });

    test("no log text is left that the server no longer writes", () => {
        const stems = new Set([...serverKeys].map((key) => key.replace(/\.(one|other)$/, "")));
        const built = new Set([
            ...["maxHp", "attack", "defense", "resource"].map((k) => `log.levelUp.${k}`),
            ...["found", "foundBack"].flatMap((k) => [`log.chest.${k}`, `log.chest.${k}Choice`, `log.chest.${k}Worse`]),
        ]);
        const orphans = Object.keys(dictionaries.en)
            .filter((key) => key.startsWith("log.") && key.split(".").length >= 3)
            .map((key) => key.replace(/\.(one|other)$/, ""))
            .filter((key) => !stems.has(key) && !built.has(key));
        expect(orphans).toEqual([]);
    });
});
