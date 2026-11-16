import { describe, test, expect, beforeEach, afterEach } from "vitest";
import { render, cleanup } from "@testing-library/svelte";
import StatsPanel from "./StatsPanel.svelte";
import { gameState } from "../engine/StateStore";
import type { StateUpdate, StatsView } from "../engine/protocol";

const stats: StatsView = {
    attack: 65,
    defense: 25,
    critChance: 15,
    critMultiplier: 1.5,
    abilityCost: 26,
    attackTerms: [
        { source: "level", value: 30 },
        { source: "weapon", value: 16, affinity: true },
        { source: "set", value: 2 },
    ],
    defenseTerms: [{ source: "level", value: 12 }, { source: "armor", value: 8, affinity: true }],
    critTerms: [{ source: "accessories", value: 10, affinity: true }, { source: "set", value: 5 }],
};

function makeState(player: Partial<StateUpdate["player"]> = {}, overrides: Partial<StateUpdate> = {}): StateUpdate {
    return {
        phase: "EXPLORATION",
        player: { classId: "mage", hp: 62, maxHp: 83, resourceCurrent: 54, resourceMax: 80, level: 6, xp: 0, metaCurrency: 0, affinityTags: ["magic"], stats, ...player },
        equipment: { weapon: null, armor: null, accessories: [null, null], potionBelt: [null, null], keys: [] },
        abilities: [{ classId: "mage", id: "arcane_blast", name: "Arcane Blast", cost: 30, resourceName: "Mana", description: "45 damage" }],
        achievements: [],
        sets: [],
        victory: false,
        log: [],
        newlyUnlocked: [],
        damageEvents: [],
        soundEvents: [],
        debugRooms: [],
        ...overrides,
    };
}

const text = (el: Element | null) => el?.textContent?.replace(/\s+/g, " ").trim();
/** A breakdown line reads as its label then its value, the two sit in separate columns. */
const termText = (el: Element) => Array.from(el.querySelectorAll("span")).map((s) => text(s)).join(" ");

describe("StatsPanel", () => {
    beforeEach(() => gameState.set(null));
    afterEach(cleanup);

    test("renders nothing without a state or without stats", () => {
        const { container } = render(StatsPanel);
        expect(container.querySelector(".stats-panel")).toBeNull();
        gameState.set(makeState({ stats: undefined }));
        expect(container.querySelector(".stats-panel")).toBeNull();
    });

    test("renders nothing when the server sent null stats (the Hub)", () => {
        gameState.set(makeState({ stats: null }));
        const { container } = render(StatsPanel);
        expect(container.querySelector(".stats-panel")).toBeNull();
    });

    test("shows attack, defense, crit with its multiplier, max HP", () => {
        gameState.set(makeState());
        const { container } = render(StatsPanel);
        const rows = Array.from(container.querySelectorAll(".stat-row")).map((r) => text(r.querySelector(".stat-name")) + "=" + text(r.querySelector(".stat-value")));
        expect(rows).toEqual(["Attack=65", "Defense=25", "Crit chance=15%", "Max HP=83", "Ability=Arcane Blast"]);
        expect(container.textContent).toContain("Crit damage x1.5");
    });

    test("shows a crit chance above 100 as 100", () => {
        gameState.set(makeState({ stats: { ...stats, critChance: 140 } }));
        const { container } = render(StatsPanel);
        expect(text(container.querySelectorAll(".stat-value")[2])).toBe("100%");
    });

    test("lists each attack term with its sign, the affinity doubling and the total", () => {
        gameState.set(makeState());
        const { container } = render(StatsPanel);
        const terms = Array.from(container.querySelectorAll(".stat-row")[0].querySelectorAll(".term")).map(termText);
        expect(terms).toEqual(["Level +30", "Weapon (affinity x2) +16", "Set bonus +2", "Total 48"]);
    });

    test("lists crit terms in percent", () => {
        gameState.set(makeState());
        const { container } = render(StatsPanel);
        const terms = Array.from(container.querySelectorAll(".stat-row")[2].querySelectorAll(".term")).map(termText);
        expect(terms).toEqual(["Accessories (affinity x2) +10%", "Set bonus +5%", "Total 15%"]);
    });

    test("has no breakdown for a crit chance made of nothing", () => {
        gameState.set(makeState({ stats: { ...stats, critChance: 0, critTerms: [] } }));
        const { container } = render(StatsPanel);
        expect(container.querySelectorAll(".stat-row")[2].querySelector(".stat-tooltip")).toBeNull();
    });

    test("shows the discounted ability cost with the old one struck through", () => {
        gameState.set(makeState());
        const { container } = render(StatsPanel);
        const row = container.querySelector(".ability-row");
        expect(text(row?.querySelector(".base-cost") ?? null)).toBe("30");
        expect(text(row?.querySelector(".stat-note") ?? null)).toBe("30 26 Mana");
    });

    test("shows no struck cost when nothing is discounted", () => {
        gameState.set(makeState({ stats: { ...stats, abilityCost: 30 } }));
        const { container } = render(StatsPanel);
        expect(container.querySelector(".base-cost")).toBeNull();
    });

    test("falls back to the catalog cost when the server sent none", () => {
        gameState.set(makeState({ stats: { ...stats, abilityCost: null } }));
        const { container } = render(StatsPanel);
        expect(text(container.querySelector(".ability-row .stat-note"))).toBe("30 Mana");
    });

    test("shows no ability row when the class has none in the catalog", () => {
        gameState.set(makeState({}, { abilities: [] }));
        const { container } = render(StatsPanel);
        expect(container.querySelector(".ability-row")).toBeNull();
    });
});
