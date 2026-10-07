import { describe, test, expect, beforeEach } from "vitest";
import { render } from "@testing-library/svelte";
import MinimapPanel from "./MinimapPanel.svelte";
import { gameState } from "../engine/StateStore";
import type { MinimapNodeView, MinimapView, StateUpdate } from "../engine/protocol";

function node(id: string, section: number, column: number, lane: number, overrides: Partial<MinimapNodeView> = {}): MinimapNodeView {
    return { id, roomType: null, visited: false, current: false, section, column, lane, ...overrides };
}

/** Two sections: the first starts with the room the player is in and has a fork, the second is
 * still all unknown except its boss. */
const MAP: MinimapView = {
    sections: [
        { index: 0, theme: "dungeon" },
        { index: 1, theme: "darkDungeon" },
    ],
    nodes: [
        node("n0", 0, 0, 0, { roomType: "combat", visited: true, current: true }),
        node("n1", 0, 1, 0),
        node("n2", 0, 2, 0),
        node("n3", 0, 2, 1),
        node("n4", 0, 3, 0, { roomType: "boss" }),
        node("n5", 1, 0, 0),
    ],
    edges: [
        { from: "n0", to: "n1", exit: null },
        { from: "n1", to: "n2", exit: "RIGHT" },
        { from: "n1", to: "n3", exit: "DOWN" },
        { from: "n2", to: "n4", exit: null },
        { from: "n3", to: "n4", exit: null },
        { from: "n4", to: "n5", exit: null },
    ],
};

function stateWith(minimap: MinimapView | undefined): StateUpdate {
    return {
        phase: "EXPLORATION",
        player: {
            classId: "warrior",
            hp: 100,
            maxHp: 100,
            resourceCurrent: 0,
            resourceMax: 100,
            level: 1,
            xp: 0,
            metaCurrency: 0,
            affinityTags: [],
        },
        equipment: { weapon: null, armor: null, accessories: [null, null], potionBelt: [null, null], keys: [] },
        abilities: [],
        achievements: [],
        sets: [],
        victory: false,
        log: [],
        newlyUnlocked: [],
        damageEvents: [],
        soundEvents: [],
        debugRooms: [],
        minimap,
    };
}

describe("MinimapPanel", () => {
    beforeEach(() => gameState.set(null));

    test("says the map is only available during a run when the server sent none", () => {
        gameState.set(stateWith(undefined));
        const { container } = render(MinimapPanel);
        expect(container.querySelector("[data-node]")).toBeNull();
        expect(container.textContent).toContain("only available during a run");
    });

    test("draws one node per room of the map", () => {
        gameState.set(stateWith(MAP));
        const { container } = render(MinimapPanel);
        expect(container.querySelectorAll("[data-node]")).toHaveLength(6);
    });

    test("marks the room the player is in, with its type", () => {
        gameState.set(stateWith(MAP));
        const { container } = render(MinimapPanel);
        const current = container.querySelectorAll('[data-kind="current"]');
        expect(current).toHaveLength(1);
        expect(current[0].getAttribute("data-type")).toBe("combat");
    });

    test("shows an unvisited room with a hidden type as unexplored, and gives nothing away on hover", () => {
        gameState.set(stateWith(MAP));
        const { container } = render(MinimapPanel);
        const unknown = container.querySelectorAll('[data-kind="unknown"]');
        expect(unknown).toHaveLength(4);
        unknown.forEach((n) => {
            expect(n.getAttribute("data-type")).toBe("");
            expect(n.querySelector("title")?.textContent).toBe("Unexplored");
        });
    });

    test("shows a boss that has not been reached as known", () => {
        gameState.set(stateWith(MAP));
        const { container } = render(MinimapPanel);
        const known = container.querySelectorAll('[data-kind="known"]');
        expect(known).toHaveLength(1);
        expect(known[0].getAttribute("data-type")).toBe("boss");
        expect(known[0].querySelector("title")?.textContent).toBe("Boss (not visited)");
    });

    test("puts an arrow on each exit of the fork, naming the wall", () => {
        gameState.set(stateWith(MAP));
        const { container } = render(MinimapPanel);
        const chips = Array.from(container.querySelectorAll("[data-chip]"));
        expect(chips.map((c) => c.getAttribute("data-direction"))).toEqual(["RIGHT", "DOWN"]);
    });

    test("titles each section with its theme", () => {
        gameState.set(stateWith(MAP));
        const { container } = render(MinimapPanel);
        const titles = Array.from(container.querySelectorAll("[data-section]")).map((t) => t.textContent?.replace(/\s+/g, " ").trim());
        expect(titles).toEqual(["Section 1Dungeon", "Section 2Dark dungeon"]);
    });

    test("lists every room type and every state in the legend", () => {
        gameState.set(stateWith(MAP));
        const { container } = render(MinimapPanel);
        const items = Array.from(container.querySelectorAll(".legend li")).map((li) => li.textContent?.trim());
        expect(items).toEqual([
            "Combat",
            "Loot",
            "Rest",
            "Fork",
            "Mini-boss",
            "Boss",
            "Sanctuary",
            "You are here",
            "Known, not visited",
            "Unexplored",
        ]);
    });
});
