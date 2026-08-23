import { describe, test, expect, beforeEach } from "vitest";
import { render } from "@testing-library/svelte";
import ItemTooltip from "./ItemTooltip.svelte";
import { gameState } from "../engine/StateStore";
import type { StateUpdate, ItemView, SetView } from "../engine/protocol";

function makeItem(overrides: Partial<ItemView> = {}): ItemView {
    return { id: "i1", typeId: "t1", name: "Item", kind: "weapon", rarity: "common", statLine: "+3 ATK", ...overrides };
}

function makeSet(overrides: Partial<SetView> = {}): SetView {
    return { id: "light_soldier", name: "Light Soldier", classId: "warrior", bonus2pcLabel: "+5% max HP", bonus4pcLabel: "+2 flat DEF", ...overrides };
}

function makeState(overrides: Partial<StateUpdate> = {}): StateUpdate {
    return {
        phase: "HUB",
        player: { classId: "warrior", hp: 100, maxHp: 100, resourceCurrent: 0, resourceMax: 100, level: 1, xp: 0, metaCurrency: 0, affinityTags: ["heavy"] },
        equipment: { weapon: null, armor: null, accessories: [null, null], potionBelt: [null, null], keys: [] },
        abilities: [],
        achievements: [],
        sets: [],
        victory: false,
        log: [],
        newlyUnlocked: [],
        damageEvents: [],
        soundEvents: [],
        ...overrides,
    };
}

describe("ItemTooltip", () => {
    beforeEach(() => gameState.set(makeState()));

    test("shows name, kind, and stat line for a plain item with no set", () => {
        gameState.set(makeState());
        const { container } = render(ItemTooltip, { item: makeItem({ name: "Iron Sword", kind: "weapon", statLine: "+3 ATK" }) });
        expect(container.querySelector(".tooltip-name")?.textContent).toBe("Iron Sword");
        expect(container.querySelector(".tooltip-kind")?.textContent).toBe("weapon");
        expect(container.querySelector(".tooltip-stat")?.textContent).toBe("+3 ATK");
        expect(container.querySelector(".tooltip-set")).toBeNull();
    });

    test("appends the stack count to the kind line only when count > 1", () => {
        const { container: withOne } = render(ItemTooltip, { item: makeItem({ count: 1 }) });
        expect(withOne.querySelector(".tooltip-kind")?.textContent).toBe("weapon");

        const { container: withThree } = render(ItemTooltip, { item: makeItem({ count: 3 }) });
        expect(withThree.querySelector(".tooltip-kind")?.textContent).toBe("weapon · x3");
    });

    test("dims the stat line when the item's typeTag isn't in the player's affinityTags", () => {
        const { container } = render(ItemTooltip, { item: makeItem({ typeTag: "ranged" }) });
        expect(container.querySelector(".tooltip-stat")?.classList.contains("off-affinity")).toBe(true);
    });

    test("does not dim the stat line when the item's typeTag matches the player's affinity", () => {
        const { container } = render(ItemTooltip, { item: makeItem({ typeTag: "heavy" }) });
        expect(container.querySelector(".tooltip-stat")?.classList.contains("off-affinity")).toBe(false);
    });

    test("shows set progress and marks a bonus active once enough pieces are equipped", () => {
        gameState.set(makeState({ sets: [makeSet()] }));
        const { container } = render(ItemTooltip, { item: makeItem({ setId: "light_soldier" }) });
        expect(container.querySelector(".set-name")?.textContent).toContain("Light Soldier (0/4)");
        const bonuses = container.querySelectorAll(".set-bonus");
        expect(bonuses[0].classList.contains("active")).toBe(false);
        expect(bonuses[1].classList.contains("active")).toBe(false);
    });

    test("falls back to the raw setId when the set isn't in the catalog yet", () => {
        gameState.set(makeState({ sets: [] }));
        const { container } = render(ItemTooltip, { item: makeItem({ setId: "unknown_set" }) });
        expect(container.querySelector(".set-name")?.textContent).toContain("unknown_set");
    });
});
