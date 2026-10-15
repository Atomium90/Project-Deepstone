import { describe, test, expect, beforeEach, afterEach, vi } from "vitest";
import { render, fireEvent, cleanup } from "@testing-library/svelte";
import RewardChoiceModal from "./RewardChoiceModal.svelte";
import { gameState, client } from "../engine/StateStore";
import { dictionaries } from "../engine/i18n";
import { CHOICE_GRACE_MS } from "../engine/choiceKeys";
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
        debugRooms: [],
        ...overrides,
    };
}

afterEach(() => {
    vi.useRealTimers();
    cleanup();
});

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

    describe("the relic of a Devoted", () => {
        beforeEach(() => void (dictionaries.en["story.boss.9.relic"] = "NINE. Kept the test. Stayed."));
        afterEach(() => void delete dictionaries.en["story.boss.9.relic"]);

        test("shows its inscription when it can be read", () => {
            gameState.set(makeState({ pendingRewardChoice: { options: [makeItem()], relic: { devoted: 9, readable: true } } }));
            const { container } = render(RewardChoiceModal);
            expect(container.querySelector(".relic")?.textContent?.trim()).toBe("NINE. Kept the test. Stayed.");
        });

        test("says the letters are worn smooth when it cannot be read yet", () => {
            gameState.set(makeState({ pendingRewardChoice: { options: [makeItem()], relic: { devoted: 9, readable: false } } }));
            const { container } = render(RewardChoiceModal);
            expect(container.querySelector(".relic")?.textContent?.trim()).toBe(dictionaries.en["story.boss.unreadable"]);
            expect(container.querySelector(".relic")?.textContent).not.toContain("NINE");
        });

        test("is not shown for a Shrine that holds none", () => {
            gameState.set(makeState({ pendingRewardChoice: { options: [makeItem()] } }));
            const { container } = render(RewardChoiceModal);
            expect(container.querySelector(".relic")).toBeNull();
        });
    });

    test("dims an option's stat line when its typeTag isn't in the player's affinity", () => {
        gameState.set(makeState({ pendingRewardChoice: { options: [makeItem({ typeTag: "ranged" })] } }));
        const { container } = render(RewardChoiceModal);
        expect(container.querySelector(".option-stat")?.classList.contains("off-affinity")).toBe(true);
    });
});

/** A key press as the browser makes it. `code` is the physical key, `key` what it types: the top row of
 * an AZERTY keyboard types "&" for 1, which the shortcuts must not care about. */
const press = (key: string, code: string, init: KeyboardEventInit = {}) => fireEvent.keyDown(window, { key, code, ...init });
const digit = (n: number, init: KeyboardEventInit = {}) => press(String(n), `Digit${n}`, init);
const sent = (spy: ReturnType<typeof vi.spyOn>) => spy.mock.calls.map((call) => call[0]);

describe("RewardChoiceModal keyboard shortcuts", () => {
    const offering = () =>
        gameState.set(
            makeState({
                pendingRewardChoice: { options: [makeItem({ id: "i1" }), makeItem({ id: "i2", name: "Chain Mail" }), makeItem({ id: "i3", name: "Ring" })] },
            })
        );

    const open = () => {
        const view = render(RewardChoiceModal);
        vi.advanceTimersByTime(CHOICE_GRACE_MS);
        return view;
    };

    beforeEach(() => {
        vi.useFakeTimers();
        gameState.set(makeState());
        vi.restoreAllMocks();
    });

    test("a number takes the offering of that number", async () => {
        offering();
        open();
        const sendSpy = vi.spyOn(client, "send");

        await digit(3);

        expect(sent(sendSpy)).toEqual([{ type: "REWARD_CHOICE", itemId: "i3" }]);
    });

    test("reads the physical key, so the top row of an AZERTY keyboard works too", async () => {
        offering();
        open();
        const sendSpy = vi.spyOn(client, "send");

        await press("&", "Digit1");

        expect(sent(sendSpy)).toEqual([{ type: "REWARD_CHOICE", itemId: "i1" }]);
    });

    test("0 and Escape leave empty-handed", async () => {
        offering();
        open();
        const sendSpy = vi.spyOn(client, "send");

        await digit(0);
        await press("Escape", "Escape");

        expect(sent(sendSpy)).toEqual([{ type: "REWARD_CHOICE" }, { type: "REWARD_CHOICE" }]);
    });

    test("a number past the last offering does nothing", async () => {
        offering();
        open();
        const sendSpy = vi.spyOn(client, "send");

        await digit(4);

        expect(sent(sendSpy)).toEqual([]);
    });

    test("waits for the grace period", async () => {
        offering();
        render(RewardChoiceModal);
        const sendSpy = vi.spyOn(client, "send");

        await digit(1);
        expect(sent(sendSpy)).toEqual([]);

        vi.advanceTimersByTime(CHOICE_GRACE_MS);
        await digit(1);
        expect(sent(sendSpy)).toEqual([{ type: "REWARD_CHOICE", itemId: "i1" }]);
    });

    test("does nothing when no choice is pending", async () => {
        open();
        const sendSpy = vi.spyOn(client, "send");

        await digit(1);
        await digit(0);

        expect(sent(sendSpy)).toEqual([]);
    });

    test("each offering shows its number, and leaving shows 0", () => {
        offering();
        const { container } = open();
        const hints = (selector: string) => Array.from(container.querySelectorAll(`${selector} .key-hint`)).map((el) => el.textContent);
        expect(hints(".option-row")).toEqual(["1", "2", "3"]);
        expect(hints(".leave-btn")).toEqual(["0"]);
    });
});
