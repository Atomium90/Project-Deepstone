import { describe, test, expect } from "vitest";
import {
    actionOf,
    bindingLabel,
    DEFAULT_BINDINGS,
    directionOf,
    isAdvanceKey,
    keyTexts,
    moveLabel,
    pressMatches,
    type Bindings,
    type LayoutMap,
} from "./keybindings";

/** What an AZERTY keyboard types at the places that are W A S D on QWERTY, and the place of M. */
const AZERTY: LayoutMap = new Map([
    ["KeyW", "z"],
    ["KeyA", "q"],
    ["KeyS", "s"],
    ["KeyD", "d"],
]);

describe("pressMatches", () => {
    test("a code binding matches the place of the key, not the letter it types", () => {
        expect(pressMatches({ code: "KeyW" }, { key: "z", code: "KeyW" })).toBe(true);
        expect(pressMatches({ code: "KeyW" }, { key: "w", code: "KeyZ" })).toBe(false);
    });

    test("a code binding never matches a press that carries no code", () => {
        expect(pressMatches({ code: "KeyW" }, { key: "w", code: "" })).toBe(false);
    });

    test("a key binding matches the letter, in either case, wherever the key is", () => {
        expect(pressMatches({ key: "m" }, { key: "M", code: "Semicolon" })).toBe(true);
        expect(pressMatches({ key: "m" }, { key: "m", code: "KeyM" })).toBe(true);
        expect(pressMatches({ key: "m" }, { key: "n", code: "KeyN" })).toBe(false);
    });
});

describe("actionOf", () => {
    test("finds the action of each default key", () => {
        expect(actionOf({ key: "z", code: "KeyW" }, DEFAULT_BINDINGS)).toBe("moveUp");
        expect(actionOf({ key: "q", code: "KeyA" }, DEFAULT_BINDINGS)).toBe("moveLeft");
        expect(actionOf({ key: "s", code: "KeyS" }, DEFAULT_BINDINGS)).toBe("moveDown");
        expect(actionOf({ key: "d", code: "KeyD" }, DEFAULT_BINDINGS)).toBe("moveRight");
        expect(actionOf({ key: "e", code: "KeyE" }, DEFAULT_BINDINGS)).toBe("interact");
        expect(actionOf({ key: "i", code: "KeyI" }, DEFAULT_BINDINGS)).toBe("equipment");
        expect(actionOf({ key: "m", code: "KeyM" }, DEFAULT_BINDINGS)).toBe("map");
    });

    test("the arrow keys always move, whatever the bindings say", () => {
        const odd: Bindings = { ...DEFAULT_BINDINGS, moveUp: { code: "KeyI" } };
        expect(actionOf({ key: "ArrowUp", code: "ArrowUp" }, odd)).toBe("moveUp");
        expect(actionOf({ key: "ArrowLeft", code: "ArrowLeft" }, odd)).toBe("moveLeft");
    });

    test("a key bound to nothing has no action", () => {
        expect(actionOf({ key: "x", code: "KeyX" }, DEFAULT_BINDINGS)).toBeNull();
    });

    test("a rebound key replaces the default one", () => {
        const rebound: Bindings = { ...DEFAULT_BINDINGS, interact: { code: "KeyF" } };
        expect(actionOf({ key: "f", code: "KeyF" }, rebound)).toBe("interact");
        expect(actionOf({ key: "e", code: "KeyE" }, rebound)).toBeNull();
    });
});

describe("directionOf", () => {
    test("names the direction of a movement action and no other", () => {
        expect(directionOf("moveUp")).toBe("UP");
        expect(directionOf("moveRight")).toBe("RIGHT");
        expect(directionOf("interact")).toBeNull();
    });
});

describe("isAdvanceKey", () => {
    test("the interact key, Space and Enter go on with a conversation", () => {
        expect(isAdvanceKey({ key: "e", code: "KeyE" }, DEFAULT_BINDINGS)).toBe(true);
        expect(isAdvanceKey({ key: " ", code: "Space" }, DEFAULT_BINDINGS)).toBe(true);
        expect(isAdvanceKey({ key: "Enter", code: "Enter" }, DEFAULT_BINDINGS)).toBe(true);
        expect(isAdvanceKey({ key: "x", code: "KeyX" }, DEFAULT_BINDINGS)).toBe(false);
    });

    test("a rebound interact key goes on, and the old letter no longer does", () => {
        const rebound: Bindings = { ...DEFAULT_BINDINGS, interact: { code: "KeyF" } };
        expect(isAdvanceKey({ key: "f", code: "KeyF" }, rebound)).toBe(true);
        expect(isAdvanceKey({ key: "e", code: "KeyE" }, rebound)).toBe(false);
    });
});

describe("the names of the keys", () => {
    test("a letter binding names its letter, in capitals", () => {
        expect(bindingLabel({ key: "e" }, null)).toBe("E");
    });

    test("a place binding names what the key types on this layout, else its QWERTY letter", () => {
        expect(bindingLabel({ code: "KeyW" }, AZERTY)).toBe("Z");
        expect(bindingLabel({ code: "KeyW" }, null)).toBe("W");
        expect(bindingLabel({ code: "Digit3" }, null)).toBe("3");
        expect(bindingLabel({ code: "ArrowUp" }, null)).toBe("↑");
        expect(bindingLabel({ code: "Space" }, null)).toBe("SPACE");
    });

    test("the movement keys read in the order up, left, down, right", () => {
        expect(moveLabel(DEFAULT_BINDINGS, null)).toBe("WASD");
        expect(moveLabel(DEFAULT_BINDINGS, AZERTY)).toBe("ZQSD");
    });

    test("a movement key with a longer name is spaced from the others", () => {
        const odd: Bindings = { ...DEFAULT_BINDINGS, moveUp: { code: "Space" } };
        expect(moveLabel(odd, null)).toBe("SPACE A S D");
    });

    test("the texts get one name per thing they speak of", () => {
        expect(keyTexts(DEFAULT_BINDINGS, AZERTY)).toEqual({ move: "ZQSD", interact: "E", equipment: "I", map: "M" });
        expect(keyTexts(DEFAULT_BINDINGS, null)).toEqual({ move: "WASD", interact: "E", equipment: "I", map: "M" });
    });
});
