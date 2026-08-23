import { describe, test, expect, beforeEach, vi } from "vitest";
import { render, fireEvent } from "@testing-library/svelte";
import EquipChoiceModal from "./EquipChoiceModal.svelte";
import { gameState, client } from "../engine/StateStore";
import type { StateUpdate, ItemView, EquipChoiceOptionView } from "../engine/protocol";

function makeItem(overrides: Partial<ItemView> = {}): ItemView {
    return { id: "i1", typeId: "t1", name: "Steel Sword", kind: "weapon", rarity: "uncommon", statLine: "+7 ATK", ...overrides };
}

function makeOption(overrides: Partial<EquipChoiceOptionView> = {}): EquipChoiceOptionView {
    return { slot: "WEAPON", current: makeItem({ id: "cur", name: "Iron Sword", statLine: "+3 ATK" }), ...overrides };
}

function makeState(overrides: Partial<StateUpdate> = {}): StateUpdate {
    return {
        phase: "EXPLORATION",
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

describe("EquipChoiceModal", () => {
    beforeEach(() => {
        gameState.set(makeState());
        vi.restoreAllMocks();
    });

    test("renders nothing when there is no pending equip choice", () => {
        const { container } = render(EquipChoiceModal);
        expect(container.querySelector(".backdrop")).toBeNull();
    });

    test("shows the new item and one row per candidate option", () => {
        gameState.set(makeState({ pendingEquipChoice: { newItem: makeItem({ name: "Steel Sword" }), options: [makeOption()] } }));
        const { container } = render(EquipChoiceModal);
        expect(container.querySelector(".item-name")?.textContent).toBe("Steel Sword");
        expect(container.querySelectorAll(".option-row").length).toBe(1);
        expect(container.querySelector(".option-name")?.textContent).toContain("Iron Sword");
    });

    test("clicking an option row sends EQUIP_CHOICE with that option's slot", async () => {
        gameState.set(makeState({ pendingEquipChoice: { newItem: makeItem(), options: [makeOption({ slot: "WEAPON" })] } }));
        const { container } = render(EquipChoiceModal);
        const sendSpy = vi.spyOn(client, "send");

        await fireEvent.click(container.querySelector(".option-row")!);
        expect(sendSpy).toHaveBeenCalledWith({ type: "EQUIP_CHOICE", targetSlot: "WEAPON" });
    });

    test("clicking 'Keep what I have' sends EQUIP_CHOICE with no targetSlot", async () => {
        gameState.set(makeState({ pendingEquipChoice: { newItem: makeItem(), options: [makeOption()] } }));
        const { container } = render(EquipChoiceModal);
        const sendSpy = vi.spyOn(client, "send");

        await fireEvent.click(container.querySelector(".keep-btn")!);
        expect(sendSpy).toHaveBeenCalledWith({ type: "EQUIP_CHOICE" });
    });

    test("dims an option's stat line when its typeTag isn't in the player's affinity", () => {
        gameState.set(
            makeState({
                pendingEquipChoice: { newItem: makeItem(), options: [makeOption({ current: makeItem({ typeTag: "ranged" }) })] },
            })
        );
        const { container } = render(EquipChoiceModal);
        expect(container.querySelector(".option-stat")?.classList.contains("off-affinity")).toBe(true);
    });

    test("appends the current count to an option's name only when count > 1", () => {
        gameState.set(
            makeState({
                pendingEquipChoice: { newItem: makeItem(), options: [makeOption({ current: makeItem({ name: "Health Potion", count: 2 }) })] },
            })
        );
        const { container } = render(EquipChoiceModal);
        expect(container.querySelector(".option-name")?.textContent).toContain("Health Potion x2");
    });
});
