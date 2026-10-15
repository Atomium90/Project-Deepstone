import { describe, test, expect } from "vitest";
import { digitOf, isLeaveKey, isPlainPress, pickedIndex } from "./choiceKeys";

const press = (key: string, code = "") => ({ key, code });

describe("digitOf", () => {
    test("reads a digit from the character", () => {
        expect(digitOf(press("1"))).toBe(1);
        expect(digitOf(press("0"))).toBe(0);
    });

    test("reads the physical key of the top row, whatever it types: AZERTY gives & without Shift", () => {
        expect(digitOf(press("&", "Digit1"))).toBe(1);
        expect(digitOf(press("é", "Digit2"))).toBe(2);
        expect(digitOf(press('"', "Digit3"))).toBe(3);
        expect(digitOf(press("à", "Digit0"))).toBe(0);
    });

    test("reads the pad, with or without Num Lock", () => {
        expect(digitOf(press("4", "Numpad4"))).toBe(4);
        expect(digitOf(press("End", "Numpad1"))).toBe(1);
    });

    test("is null for any other key", () => {
        expect(digitOf(press("a", "KeyA"))).toBeNull();
        expect(digitOf(press("Enter", "Enter"))).toBeNull();
        expect(digitOf(press("&"))).toBeNull();
    });
});

describe("pickedIndex", () => {
    test("picks the choice of that number, counted from 1", () => {
        expect(pickedIndex(press("1", "Digit1"), 3)).toBe(0);
        expect(pickedIndex(press("3", "Digit3"), 3)).toBe(2);
    });

    test("picks nothing past the end of the list, nor with 0 or a letter", () => {
        expect(pickedIndex(press("4", "Digit4"), 3)).toBeNull();
        expect(pickedIndex(press("0", "Digit0"), 3)).toBeNull();
        expect(pickedIndex(press("a", "KeyA"), 3)).toBeNull();
    });

    test("reaches the ninth choice and no farther", () => {
        expect(pickedIndex(press("9", "Digit9"), 12)).toBe(8);
    });

    test("picks nothing from an empty list", () => {
        expect(pickedIndex(press("1", "Digit1"), 0)).toBeNull();
    });
});

describe("isLeaveKey", () => {
    test("is 0 or Escape", () => {
        expect(isLeaveKey(press("Escape", "Escape"))).toBe(true);
        expect(isLeaveKey(press("0", "Digit0"))).toBe(true);
        expect(isLeaveKey(press("à", "Digit0"))).toBe(true);
    });

    test("is no other key", () => {
        expect(isLeaveKey(press("1", "Digit1"))).toBe(false);
        expect(isLeaveKey(press("e", "KeyE"))).toBe(false);
    });
});

describe("isPlainPress", () => {
    const plain = { repeat: false, ctrlKey: false, altKey: false, metaKey: false };

    test("is a press that is neither held down nor made with a modifier", () => {
        expect(isPlainPress(plain)).toBe(true);
    });

    test("is not a press held down, or made with Ctrl, Alt or Meta", () => {
        expect(isPlainPress({ ...plain, repeat: true })).toBe(false);
        expect(isPlainPress({ ...plain, ctrlKey: true })).toBe(false);
        expect(isPlainPress({ ...plain, altKey: true })).toBe(false);
        expect(isPlainPress({ ...plain, metaKey: true })).toBe(false);
    });
});
