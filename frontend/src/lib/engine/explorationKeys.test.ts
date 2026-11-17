import { describe, test, expect } from "vitest";
import { keyCommand } from "./explorationKeys";
import { DEFAULT_BINDINGS, type KeyPress } from "./keybindings";

const press = (key: string, code = ""): KeyPress => ({ key, code });
const run = (p: KeyPress, openTab: Parameters<typeof keyCommand>[2], mapAvailable: boolean, conversationPending = false) =>
    keyCommand(p, DEFAULT_BINDINGS, openTab, mapAvailable, conversationPending);

// The key blocks under the left hand: the keys that type W A S D on QWERTY and Z Q S D on AZERTY.
const W = press("z", "KeyW");
const A = press("q", "KeyA");
const S = press("s", "KeyS");
const D = press("d", "KeyD");

describe("keyCommand while a conversation is pending", () => {
    test("walking, interacting and the debug keys do nothing", () => {
        for (const p of [W, press("ArrowUp", "ArrowUp"), press("e", "KeyE"), press("E", "KeyE"), press("à"), press("ç")]) {
            expect(run(p, null, true, true)).toBeNull();
        }
    });

    test("the Character screen keys still work", () => {
        expect(run(press("i", "KeyI"), null, true, true)).toEqual({ kind: "openTab", tab: "equipment" });
        expect(run(press("m"), null, true, true)).toEqual({ kind: "openTab", tab: "map" });
        expect(run(press("i", "KeyI"), "equipment", true, true)).toEqual({ kind: "closeOverlay" });
    });

    test("without a conversation the keys act as usual", () => {
        expect(run(press("e", "KeyE"), null, true, false)).toEqual({ kind: "interact" });
    });
});

describe("keyCommand while the Character screen is closed", () => {
    test("the key block under the left hand and the arrow keys move, on any layout", () => {
        const keys = [W, S, A, D, press("ArrowUp", "ArrowUp"), press("ArrowDown", "ArrowDown"), press("ArrowLeft", "ArrowLeft"), press("ArrowRight", "ArrowRight")];
        expect(keys.map((p) => run(p, null, true))).toEqual([
            { kind: "move", direction: "UP" },
            { kind: "move", direction: "DOWN" },
            { kind: "move", direction: "LEFT" },
            { kind: "move", direction: "RIGHT" },
            { kind: "move", direction: "UP" },
            { kind: "move", direction: "DOWN" },
            { kind: "move", direction: "LEFT" },
            { kind: "move", direction: "RIGHT" },
        ]);
    });

    test("W and A move on QWERTY, where they are the same keys by place", () => {
        expect(run(press("w", "KeyW"), null, true)).toEqual({ kind: "move", direction: "UP" });
        expect(run(press("a", "KeyA"), null, true)).toEqual({ kind: "move", direction: "LEFT" });
    });

    test("the letter of a movement key alone does nothing: it is the place that counts", () => {
        // On AZERTY the key that types W is in another place, which is not a movement key.
        expect(run(press("w", "KeyZ"), null, true)).toBeNull();
    });

    test("E interacts, in either case", () => {
        expect(run(press("e", "KeyE"), null, true)).toEqual({ kind: "interact" });
        expect(run(press("E", "KeyE"), null, true)).toEqual({ kind: "interact" });
    });

    test("the debug room keys step to the next and previous room", () => {
        expect(run(press("à"), null, true)).toEqual({ kind: "debugRoom", step: 1 });
        expect(run(press("ç"), null, true)).toEqual({ kind: "debugRoom", step: -1 });
    });

    test("I opens Equipment and M opens the map, in either case and whatever the layout", () => {
        for (const key of ["i", "I"]) expect(run(press(key, "KeyI"), null, true)).toEqual({ kind: "openTab", tab: "equipment" });
        // M is in another place on AZERTY: the letter is what counts.
        for (const p of [press("m", "KeyM"), press("M", "Semicolon")]) expect(run(p, null, true)).toEqual({ kind: "openTab", tab: "map" });
    });

    test("M does nothing when there is no map, and I still opens Equipment", () => {
        expect(run(press("m"), null, false)).toBeNull();
        expect(run(press("i", "KeyI"), null, false)).toEqual({ kind: "openTab", tab: "equipment" });
    });

    test("any other key does nothing", () => {
        expect(run(press("x", "KeyX"), null, true)).toBeNull();
        expect(run(press(" ", "Space"), null, true)).toBeNull();
        expect(run(press("Escape", "Escape"), null, true)).toBeNull();
    });
});

describe("keyCommand while the Character screen is open", () => {
    test("the run is paused: movement, interact and the debug room keys do nothing", () => {
        for (const tab of ["equipment", "settings", "achievements", "map"] as const) {
            for (const p of [W, A, press("ArrowUp", "ArrowUp"), press("ArrowRight", "ArrowRight"), press("e", "KeyE"), press("E", "KeyE"), press("à"), press("ç")]) {
                expect(run(p, tab, true), `${p.key} on ${tab}`).toBeNull();
            }
        }
    });

    test("the key of the tab that is showing closes the screen", () => {
        expect(run(press("m"), "map", true)).toEqual({ kind: "closeOverlay" });
        expect(run(press("i", "KeyI"), "equipment", true)).toEqual({ kind: "closeOverlay" });
    });

    test("the key of another tab switches to it", () => {
        expect(run(press("m"), "equipment", true)).toEqual({ kind: "openTab", tab: "map" });
        expect(run(press("i", "KeyI"), "map", true)).toEqual({ kind: "openTab", tab: "equipment" });
        expect(run(press("m"), "settings", true)).toEqual({ kind: "openTab", tab: "map" });
    });
});
