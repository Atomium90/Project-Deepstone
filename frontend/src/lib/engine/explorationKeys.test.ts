import { describe, test, expect } from "vitest";
import { keyCommand } from "./explorationKeys";

describe("keyCommand while the Character screen is closed", () => {
    test("ZQSD and the arrow keys move", () => {
        const moves = ["z", "s", "q", "d", "ArrowUp", "ArrowDown", "ArrowLeft", "ArrowRight"].map((key) => keyCommand(key, null, true));
        expect(moves).toEqual([
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

    test("E interacts, in either case", () => {
        expect(keyCommand("e", null, true)).toEqual({ kind: "interact" });
        expect(keyCommand("E", null, true)).toEqual({ kind: "interact" });
    });

    test("the debug room keys step to the next and previous room", () => {
        expect(keyCommand("à", null, true)).toEqual({ kind: "debugRoom", step: 1 });
        expect(keyCommand("ç", null, true)).toEqual({ kind: "debugRoom", step: -1 });
    });

    test("I opens Equipment and M opens the map, in either case", () => {
        for (const key of ["i", "I"]) expect(keyCommand(key, null, true)).toEqual({ kind: "openTab", tab: "equipment" });
        for (const key of ["m", "M"]) expect(keyCommand(key, null, true)).toEqual({ kind: "openTab", tab: "map" });
    });

    test("M does nothing when there is no map, and I still opens Equipment", () => {
        expect(keyCommand("m", null, false)).toBeNull();
        expect(keyCommand("i", null, false)).toEqual({ kind: "openTab", tab: "equipment" });
    });

    test("any other key does nothing", () => {
        expect(keyCommand("x", null, true)).toBeNull();
        expect(keyCommand(" ", null, true)).toBeNull();
        expect(keyCommand("Escape", null, true)).toBeNull();
    });
});

describe("keyCommand while the Character screen is open", () => {
    test("the run is paused: movement, interact and the debug room keys do nothing", () => {
        for (const tab of ["equipment", "settings", "achievements", "map"] as const) {
            for (const key of ["z", "q", "ArrowUp", "ArrowRight", "e", "E", "à", "ç"]) {
                expect(keyCommand(key, tab, true), `${key} on ${tab}`).toBeNull();
            }
        }
    });

    test("the key of the tab that is showing closes the screen", () => {
        expect(keyCommand("m", "map", true)).toEqual({ kind: "closeOverlay" });
        expect(keyCommand("i", "equipment", true)).toEqual({ kind: "closeOverlay" });
    });

    test("the key of another tab switches to it", () => {
        expect(keyCommand("m", "equipment", true)).toEqual({ kind: "openTab", tab: "map" });
        expect(keyCommand("i", "map", true)).toEqual({ kind: "openTab", tab: "equipment" });
        expect(keyCommand("m", "settings", true)).toEqual({ kind: "openTab", tab: "map" });
    });
});
