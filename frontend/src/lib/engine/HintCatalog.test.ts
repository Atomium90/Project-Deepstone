import { describe, test, expect } from "vitest";
import { DEFAULT_HINT_POSITION, HINTS, hintPosition } from "./HintCatalog";
import { dictionaries } from "./i18n";

const POSITIONS = ["bottom-left", "bottom-right", "top-left", "top-right"];

describe("HintCatalog", () => {
    test("every hint has a distinct id", () => {
        const ids = HINTS.map((hint) => hint.id);
        expect(new Set(ids).size).toBe(ids.length);
    });

    test("every hint has a title and a body in the English file", () => {
        for (const { id } of HINTS) {
            expect(dictionaries.en[`hint.${id}.title`], `hint.${id}.title`).toBeTruthy();
            expect(dictionaries.en[`hint.${id}.body`], `hint.${id}.body`).toBeTruthy();
        }
    });

    test("no hint text in the English file belongs to a hint missing from the catalog", () => {
        const catalogIds = new Set(HINTS.map((hint) => hint.id));
        const textIds = Object.keys(dictionaries.en)
            .map((key) => key.match(/^hint\.(.+)\.(title|body)$/)?.[1])
            .filter((id): id is string => id !== undefined);
        expect(textIds.filter((id) => !catalogIds.has(id))).toEqual([]);
    });

    test("every hint position is one of the four corners", () => {
        for (const hint of HINTS) {
            if (hint.position !== undefined) expect(POSITIONS).toContain(hint.position);
        }
    });

    test("the default corner is the top right", () => {
        expect(DEFAULT_HINT_POSITION).toBe("top-right");
    });

    test("a hint with no position of its own uses the default corner", () => {
        expect(hintPosition("controls")).toBe(DEFAULT_HINT_POSITION);
    });

    test("no hint of the game asks for a corner of its own today", () => {
        expect(HINTS.filter((hint) => hint.position !== undefined)).toEqual([]);
    });

    test("a hint with a position of its own uses it", () => {
        const catalog = [{ id: "a", position: "bottom-left" as const }, { id: "b" }];
        expect(hintPosition("a", catalog)).toBe("bottom-left");
        expect(hintPosition("b", catalog)).toBe(DEFAULT_HINT_POSITION);
    });

    test("an id missing from the catalog uses the default corner", () => {
        expect(hintPosition("not-a-hint")).toBe(DEFAULT_HINT_POSITION);
    });
});
