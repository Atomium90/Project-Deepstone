import { describe, test, expect, beforeEach, afterEach, vi } from "vitest";
import { render, fireEvent, cleanup } from "@testing-library/svelte";
import { tick } from "svelte";
import CombatScreen from "./CombatScreen.svelte";
import { gameState, client } from "../engine/StateStore";
import type { ItemView, StateUpdate } from "../engine/protocol";

function potion(overrides: Partial<ItemView> = {}): ItemView {
    return { id: "p1", typeId: "health_potion", name: "Health Potion", kind: "consumable", rarity: "common", statLine: "Heal 30 HP", count: 1, ...overrides };
}

function combatState(overrides: Partial<StateUpdate> = {}, player: Partial<StateUpdate["player"]> = {}, combat: Partial<NonNullable<StateUpdate["combat"]>> = {}): StateUpdate {
    return {
        phase: "COMBAT",
        player: { classId: "warrior", hp: 100, maxHp: 120, resourceCurrent: 50, resourceMax: 100, level: 1, xp: 0, metaCurrency: 0, affinityTags: [], ...player },
        combat: { enemyId: "goblin", enemyLabel: "Goblin", enemyHp: 20, enemyMaxHp: 20, isPlayerTurn: true, isBoss: false, isMiniBoss: false, isElite: false, abilityCost: 40, ...combat },
        equipment: { weapon: null, armor: null, accessories: [null, null], potionBelt: [potion(), potion({ id: "p2", name: "Battle Brew", typeId: "battle_brew" })], keys: [] },
        abilities: [{ classId: "warrior", id: "slash", name: "Berserker Slash", cost: 40, resourceName: "Rage", description: "Hits twice as hard." }],
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

/** A key press as the browser makes it: the physical key (`code`) and what it types (`key`). On an
 * AZERTY keyboard the top row types & é " ' for 1 to 4, which the game must not care about. */
const press = (key: string, code: string, init: KeyboardEventInit = {}) => fireEvent.keyDown(window, { key, code, ...init });

const sent = (spy: ReturnType<typeof vi.spyOn>) => spy.mock.calls.map((call) => call[0]);

describe("CombatScreen keyboard shortcuts", () => {
    beforeEach(() => {
        gameState.set(combatState());
        vi.restoreAllMocks();
    });

    afterEach(cleanup);

    test("1 attacks and 2 defends, on any layout", async () => {
        render(CombatScreen);
        const sendSpy = vi.spyOn(client, "send");

        await press("&", "Digit1");
        await press("2", "Numpad2");

        expect(sent(sendSpy)).toEqual([
            { type: "COMBAT_ACTION", action: "ATTACK" },
            { type: "COMBAT_ACTION", action: "DEFEND" },
        ]);
    });

    test("3 uses the class ability when there is enough of its resource", async () => {
        render(CombatScreen);
        const sendSpy = vi.spyOn(client, "send");

        await press("\"", "Digit3");

        expect(sent(sendSpy)).toEqual([{ type: "COMBAT_ACTION", action: "ABILITY" }]);
    });

    test("3 does nothing when the resource is short, like the button that is greyed out", async () => {
        gameState.set(combatState({}, { resourceCurrent: 10 }));
        render(CombatScreen);
        const sendSpy = vi.spyOn(client, "send");

        await press("\"", "Digit3");

        expect(sent(sendSpy)).toEqual([]);
    });

    test("nothing is sent while it is the enemy's turn", async () => {
        gameState.set(combatState({}, {}, { isPlayerTurn: false }));
        render(CombatScreen);
        const sendSpy = vi.spyOn(client, "send");

        for (const [key, code] of [["&", "Digit1"], ["\u00e9", "Digit2"], ["\"", "Digit3"], ["'", "Digit4"]]) await press(key, code);

        expect(sent(sendSpy)).toEqual([]);
    });

    test("4 opens the item picker, then a digit uses that consumable and closes it", async () => {
        const { container } = render(CombatScreen);
        const sendSpy = vi.spyOn(client, "send");

        await press("'", "Digit4");
        expect(container.querySelector(".item-picker")).not.toBeNull();
        expect(sent(sendSpy)).toEqual([]);

        await press("\u00e9", "Digit2");
        expect(sent(sendSpy)).toEqual([{ type: "COMBAT_ACTION", action: "ITEM", itemId: "p2" }]);
        await tick();
        expect(container.querySelector(".item-picker")).toBeNull();
    });

    test("0 or Escape closes the picker without using anything", async () => {
        const { container } = render(CombatScreen);
        const sendSpy = vi.spyOn(client, "send");

        await press("'", "Digit4");
        await press("Escape", "Escape");
        expect(container.querySelector(".item-picker")).toBeNull();

        await press("'", "Digit4");
        await press("\u00e0", "Digit0");
        expect(container.querySelector(".item-picker")).toBeNull();
        expect(sent(sendSpy)).toEqual([]);
    });

    test("with the picker open, the digits of the actions pick consumables and nothing else", async () => {
        render(CombatScreen);
        const sendSpy = vi.spyOn(client, "send");

        await press("'", "Digit4");
        await press("&", "Digit1");

        expect(sent(sendSpy)).toEqual([{ type: "COMBAT_ACTION", action: "ITEM", itemId: "p1" }]);
    });

    test("a digit past the end of the picker uses nothing", async () => {
        render(CombatScreen);
        const sendSpy = vi.spyOn(client, "send");

        await press("'", "Digit4");
        await press("5", "Digit5");

        expect(sent(sendSpy)).toEqual([]);
    });

    test("4 does nothing without a consumable", async () => {
        gameState.set(combatState({ equipment: { weapon: null, armor: null, accessories: [null, null], potionBelt: [null, null], keys: [] } }));
        const { container } = render(CombatScreen);

        await press("'", "Digit4");

        expect(container.querySelector(".item-picker")).toBeNull();
    });

    test("a key held down, or pressed with a modifier, does nothing", async () => {
        render(CombatScreen);
        const sendSpy = vi.spyOn(client, "send");

        await press("&", "Digit1", { repeat: true });
        await press("&", "Digit1", { ctrlKey: true });
        await press("&", "Digit1", { altKey: true });

        expect(sent(sendSpy)).toEqual([]);
    });

    test("each action shows the key that does it", () => {
        const { container } = render(CombatScreen);
        const hints = Array.from(container.querySelectorAll(".actions .key-hint")).map((el) => el.textContent);
        expect(hints).toEqual(["1", "2", "3", "4"]);
    });

    test("each consumable of the picker shows its number", async () => {
        const { container } = render(CombatScreen);
        await press("'", "Digit4");
        const hints = Array.from(container.querySelectorAll(".picker-row .key-hint")).map((el) => el.textContent);
        expect(hints).toEqual(["1", "2"]);
    });
});
