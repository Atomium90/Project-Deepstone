import { describe, test, expect, beforeEach } from "vitest";
import { render } from "@testing-library/svelte";
import EquipmentPanel from "./EquipmentPanel.svelte";
import { gameState } from "../engine/StateStore";
import type { StateUpdate, ItemView, SetView, EquipmentView } from "../engine/protocol";

function makeItem(overrides: Partial<ItemView> = {}): ItemView {
    return { id: "i1", typeId: "t1", name: "Item", kind: "weapon", rarity: "common", statLine: "+3 ATK", setId: "light_soldier", ...overrides };
}

function makeSet(overrides: Partial<SetView> = {}): SetView {
    return { id: "light_soldier", name: "Light Soldier", classId: "warrior", bonus2pcLabel: "+5% max HP", bonus4pcLabel: "+2 flat DEF", ...overrides };
}

function makeEquipment(overrides: Partial<EquipmentView> = {}): EquipmentView {
    return { weapon: null, armor: null, accessories: [null, null], potionBelt: [null, null], keys: [], ...overrides };
}

function makeState(overrides: Partial<StateUpdate> = {}): StateUpdate {
    return {
        phase: "HUB",
        player: { classId: "warrior", hp: 100, maxHp: 100, resourceCurrent: 0, resourceMax: 100, level: 1, xp: 0, metaCurrency: 0, affinityTags: [] },
        equipment: makeEquipment(),
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

describe("EquipmentPanel", () => {
    beforeEach(() => gameState.set(null));

    test("shows a muted message when there's no state yet", () => {
        const { container } = render(EquipmentPanel);
        expect(container.querySelector(".muted")?.textContent).toBe("No equipment loaded.");
    });

    test("renders one slot per weapon/armor/accessory/potion slot", () => {
        gameState.set(makeState());
        const { container } = render(EquipmentPanel);
        // Weapon + Armor + 2 accessories + 2 potion slots = 6 slot-groups total.
        expect(container.querySelectorAll(".slot-group").length).toBe(6);
    });

    test("hides the key count line when the player has no keys", () => {
        gameState.set(makeState({ equipment: makeEquipment({ keys: [] }) }));
        const { container } = render(EquipmentPanel);
        expect(container.querySelector(".key-count")).toBeNull();
    });

    test("sums key counts across kinds and pluralizes correctly", () => {
        gameState.set(
            makeState({
                equipment: makeEquipment({ keys: [{ keyKind: "generic", count: 2 }, { keyKind: "universal", count: 1 }] }),
            })
        );
        const { container } = render(EquipmentPanel);
        expect(container.querySelector(".key-count")?.textContent).toContain("3 keys");
    });

    test("singular 'key' when the total count is exactly 1", () => {
        gameState.set(makeState({ equipment: makeEquipment({ keys: [{ keyKind: "generic", count: 1 }] }) }));
        const { container } = render(EquipmentPanel);
        expect(container.querySelector(".key-count")?.textContent).toContain("1 key");
        expect(container.querySelector(".key-count")?.textContent).not.toContain("1 keys");
    });

    test("shows no set badge below a 2-piece count", () => {
        gameState.set(
            makeState({
                sets: [makeSet()],
                equipment: makeEquipment({ weapon: makeItem() }), // only 1 piece equipped
            })
        );
        const { container } = render(EquipmentPanel);
        expect(container.querySelector(".set-badge")).toBeNull();
    });

    test("shows a 2/4 set badge once 2 pieces are equipped", () => {
        gameState.set(
            makeState({
                sets: [makeSet()],
                equipment: makeEquipment({ weapon: makeItem(), armor: makeItem({ id: "i2" }) }),
            })
        );
        const { container } = render(EquipmentPanel);
        expect(container.querySelector(".set-badge")?.textContent?.trim()).toBe("Light Soldier 2/4");
    });

    test("caps the badge at 4/4 even with more matching pieces than the set has", () => {
        gameState.set(
            makeState({
                sets: [makeSet()],
                equipment: makeEquipment({
                    weapon: makeItem(),
                    armor: makeItem({ id: "i2" }),
                    accessories: [makeItem({ id: "i3" }), makeItem({ id: "i4" })],
                }),
            })
        );
        const { container } = render(EquipmentPanel);
        expect(container.querySelector(".set-badge")?.textContent?.trim()).toBe("Light Soldier 4/4");
    });

    test("does not show a badge for a set belonging to a different class", () => {
        gameState.set(
            makeState({
                sets: [makeSet({ classId: "mage" })],
                equipment: makeEquipment({ weapon: makeItem(), armor: makeItem({ id: "i2" }) }),
            })
        );
        const { container } = render(EquipmentPanel);
        expect(container.querySelector(".set-badge")).toBeNull();
    });
});
