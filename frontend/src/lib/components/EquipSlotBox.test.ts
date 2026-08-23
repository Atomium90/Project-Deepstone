import { describe, test, expect, beforeEach } from "vitest";
import { render, fireEvent } from "@testing-library/svelte";
import EquipSlotBox from "./EquipSlotBox.svelte";
import { gameState } from "../engine/StateStore";
import type { StateUpdate, ItemView } from "../engine/protocol";

function makeItem(overrides: Partial<ItemView> = {}): ItemView {
    return { id: "i1", typeId: "t1", name: "Iron Sword", kind: "weapon", rarity: "common", statLine: "+3 ATK", ...overrides };
}

function makeState(overrides: Partial<StateUpdate> = {}): StateUpdate {
    return {
        phase: "HUB",
        player: { classId: "warrior", hp: 100, maxHp: 100, resourceCurrent: 0, resourceMax: 100, level: 1, xp: 0, metaCurrency: 0, affinityTags: [] },
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

describe("EquipSlotBox", () => {
    beforeEach(() => gameState.set(makeState()));

    test("renders an empty, unoccupied box when there is no item", () => {
        const { container } = render(EquipSlotBox, { item: null });
        expect(container.querySelector(".equip-slot.occupied")).toBeNull();
        expect(container.querySelector(".equip-slot")).not.toBeNull();
    });

    test("shows an abbreviation fallback (first letter of each word) when the item has no iconId", () => {
        const { container } = render(EquipSlotBox, { item: makeItem({ name: "Iron Sword", iconId: undefined }) });
        expect(container.querySelector(".abbrev")?.textContent).toBe("IS");
    });

    test("caps the abbreviation at 2 characters for a longer name", () => {
        const { container } = render(EquipSlotBox, { item: makeItem({ name: "Practice Sword Of Doom", iconId: undefined }) });
        expect(container.querySelector(".abbrev")?.textContent).toBe("PS");
    });

    test("shows a stack count badge only when count > 1", () => {
        const { container: withOne } = render(EquipSlotBox, { item: makeItem({ count: 1 }) });
        expect(withOne.querySelector(".stack-count")).toBeNull();

        const { container: withFive } = render(EquipSlotBox, { item: makeItem({ count: 5 }) });
        expect(withFive.querySelector(".stack-count")?.textContent).toBe("x5");
    });

    test("shows the tooltip on hover and hides it on mouse leave", async () => {
        const { container } = render(EquipSlotBox, { item: makeItem() });
        const slot = container.querySelector(".equip-slot.occupied")!;
        expect(document.body.querySelector(".tooltip-portal")).toBeNull();

        await fireEvent.mouseEnter(slot);
        expect(document.body.querySelector(".tooltip-portal")).not.toBeNull();

        await fireEvent.mouseLeave(slot);
        expect(document.body.querySelector(".tooltip-portal")).toBeNull();
    });
});
