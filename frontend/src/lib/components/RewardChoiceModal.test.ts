import { describe, test, expect, beforeEach, vi } from "vitest";
import { render, fireEvent } from "@testing-library/svelte";
import RewardChoiceModal from "./RewardChoiceModal.svelte";
import { gameState, client } from "../engine/StateStore";
import type { StateUpdate, ItemView } from "../engine/protocol";

function makeItem(overrides: Partial<ItemView> = {}): ItemView {
    return { id: "i1", typeId: "t1", name: "Steel Sword", kind: "weapon", rarity: "uncommon", statLine: "+7 ATK", ...overrides };
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

describe("RewardChoiceModal", () => {
    beforeEach(() => {
        gameState.set(makeState());
        vi.restoreAllMocks();
    });

    test("renders nothing when there is no pending reward choice", () => {
        const { container } = render(RewardChoiceModal);
        expect(container.querySelector(".backdrop")).toBeNull();
    });

    test("shows one row per rolled option", () => {
        gameState.set(makeState({
            pendingRewardChoice: { options: [makeItem({ name: "Steel Sword" }), makeItem({ id: "i2", name: "Chain Mail" })] },
        }));
        const { container } = render(RewardChoiceModal);
        expect(container.querySelectorAll(".option-row")).toHaveLength(2);
        expect(container.querySelector(".option-name")?.textContent).toContain("Steel Sword");
    });

    test("clicking an option row sends REWARD_CHOICE with that option's id", async () => {
        gameState.set(makeState({ pendingRewardChoice: { options: [makeItem({ id: "i7" })] } }));
        const { container } = render(RewardChoiceModal);
        const sendSpy = vi.spyOn(client, "send");

        await fireEvent.click(container.querySelector(".option-row")!);
        expect(sendSpy).toHaveBeenCalledWith({ type: "REWARD_CHOICE", itemId: "i7" });
    });

    test("clicking 'Leave empty-handed' sends REWARD_CHOICE with no itemId", async () => {
        gameState.set(makeState({ pendingRewardChoice: { options: [makeItem()] } }));
        const { container } = render(RewardChoiceModal);
        const sendSpy = vi.spyOn(client, "send");

        await fireEvent.click(container.querySelector(".leave-btn")!);
        expect(sendSpy).toHaveBeenCalledWith({ type: "REWARD_CHOICE" });
    });

    test("dims an option's stat line when its typeTag isn't in the player's affinity", () => {
        gameState.set(makeState({ pendingRewardChoice: { options: [makeItem({ typeTag: "ranged" })] } }));
        const { container } = render(RewardChoiceModal);
        expect(container.querySelector(".option-stat")?.classList.contains("off-affinity")).toBe(true);
    });
});
