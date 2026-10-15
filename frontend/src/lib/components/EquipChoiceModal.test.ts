import { describe, test, expect, beforeEach, afterEach, vi } from "vitest";
import { render, fireEvent, cleanup } from "@testing-library/svelte";
import EquipChoiceModal from "./EquipChoiceModal.svelte";
import { gameState, client } from "../engine/StateStore";
import { CHOICE_GRACE_MS } from "../engine/choiceKeys";
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
        debugRooms: [],
        ...overrides,
    };
}

afterEach(() => {
    vi.useRealTimers();
    cleanup();
});

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

/** A key press as the browser makes it. `code` is the physical key, `key` what it types: the top row of
 * an AZERTY keyboard types "&" for 1, which the shortcuts must not care about. */
const press = (key: string, code: string, init: KeyboardEventInit = {}) => fireEvent.keyDown(window, { key, code, ...init });
const digit = (n: number, init: KeyboardEventInit = {}) => press(String(n), `Digit${n}`, init);
const sent = (spy: ReturnType<typeof vi.spyOn>) => spy.mock.calls.map((call) => call[0]);

describe("EquipChoiceModal keyboard shortcuts", () => {
    const choosing = () =>
        gameState.set(
            makeState({
                pendingEquipChoice: {
                    newItem: makeItem(),
                    options: [makeOption({ slot: "ACCESSORY_0" }), makeOption({ slot: "ACCESSORY_1", current: makeItem({ id: "cur2", name: "Old Ring" }) })],
                },
            })
        );

    /** Opens the window and waits out the grace period, as a player who reads it would. */
    const open = () => {
        const view = render(EquipChoiceModal);
        vi.advanceTimersByTime(CHOICE_GRACE_MS);
        return view;
    };

    beforeEach(() => {
        vi.useFakeTimers();
        gameState.set(makeState());
        vi.restoreAllMocks();
    });

    test("a number replaces the option of that number", async () => {
        choosing();
        open();
        const sendSpy = vi.spyOn(client, "send");

        await digit(2);

        expect(sent(sendSpy)).toEqual([{ type: "EQUIP_CHOICE", targetSlot: "ACCESSORY_1" }]);
    });

    test("reads the physical key, so the top row of an AZERTY keyboard works too", async () => {
        choosing();
        open();
        const sendSpy = vi.spyOn(client, "send");

        await press("&", "Digit1");

        expect(sent(sendSpy)).toEqual([{ type: "EQUIP_CHOICE", targetSlot: "ACCESSORY_0" }]);
    });

    test("0 and Escape keep what the player has", async () => {
        choosing();
        open();
        const sendSpy = vi.spyOn(client, "send");

        await digit(0);
        await press("Escape", "Escape");

        expect(sent(sendSpy)).toEqual([{ type: "EQUIP_CHOICE" }, { type: "EQUIP_CHOICE" }]);
    });

    test("a number past the last option does nothing", async () => {
        choosing();
        open();
        const sendSpy = vi.spyOn(client, "send");

        await digit(3);

        expect(sent(sendSpy)).toEqual([]);
    });

    test("a key held down, or pressed with a modifier, does nothing", async () => {
        choosing();
        open();
        const sendSpy = vi.spyOn(client, "send");

        await digit(1, { repeat: true });
        await digit(1, { ctrlKey: true });

        expect(sent(sendSpy)).toEqual([]);
    });

    test("waits for the grace period, so the press that was meant for the fight does not answer it", async () => {
        choosing();
        render(EquipChoiceModal);
        const sendSpy = vi.spyOn(client, "send");

        await digit(1);
        expect(sent(sendSpy)).toEqual([]);

        vi.advanceTimersByTime(CHOICE_GRACE_MS);
        await digit(1);
        expect(sent(sendSpy)).toEqual([{ type: "EQUIP_CHOICE", targetSlot: "ACCESSORY_0" }]);
    });

    test("does nothing when no choice is pending", async () => {
        open();
        const sendSpy = vi.spyOn(client, "send");

        await digit(1);
        await digit(0);

        expect(sent(sendSpy)).toEqual([]);
    });

    test("each row shows its number, and keeping what the player has shows 0", () => {
        choosing();
        const { container } = open();
        const hints = (selector: string) => Array.from(container.querySelectorAll(`${selector} .key-hint`)).map((el) => el.textContent);
        expect(hints(".option-row")).toEqual(["1", "2"]);
        expect(hints(".keep-btn")).toEqual(["0"]);
    });
});
