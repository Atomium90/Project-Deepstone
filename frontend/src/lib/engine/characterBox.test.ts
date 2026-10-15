import { describe, test, expect } from "vitest";
import { CHARACTER_CELL, characterBox, emptyRowsAbove } from "./characterBox";

describe("characterBox", () => {
    test("a 32x32 cell is drawn exactly on its tile, as every NPC was before bigger cells existed", () => {
        expect(characterBox(3, 2, CHARACTER_CELL, CHARACTER_CELL, 64)).toEqual({ x: 192, y: 128, w: 64, h: 64 });
    });

    test("a bigger cell keeps its feet on the bottom edge of the tile and rises above it", () => {
        const box = characterBox(3, 2, 48, 48, 64);
        expect(box.y + box.h).toBe(3 * 64); // the bottom of tile row 2
        expect(box.h).toBe(96);
        expect(box.y).toBe(3 * 64 - 96);
    });

    test("a bigger cell stays centred on its tile, and spills over both sides alike", () => {
        const box = characterBox(3, 2, 48, 48, 64);
        expect(box.x + box.w / 2).toBe(3 * 64 + 32);
        expect(box.x).toBe(192 - 16);
    });

    test("the size follows the tile size", () => {
        expect(characterBox(0, 0, 48, 48, 32)).toEqual({ x: -8, y: -16, w: 48, h: 48 });
    });
});

describe("emptyRowsAbove", () => {
    /** An RGBA image `width` x `height` that is transparent except for the opaque pixels at `opaque`. */
    function image(width: number, height: number, opaque: Array<[number, number]>): Uint8ClampedArray {
        const data = new Uint8ClampedArray(width * height * 4);
        for (const [col, row] of opaque) data[(row * width + col) * 4 + 3] = 255;
        return data;
    }

    test("counts the rows above the highest opaque pixel", () => {
        expect(emptyRowsAbove(image(4, 6, [[1, 3], [2, 5]]), 4, 6)).toBe(3);
    });

    test("is the highest pixel of any column, so any frame of a strip", () => {
        expect(emptyRowsAbove(image(8, 6, [[1, 4], [6, 2]]), 8, 6)).toBe(2);
    });

    test("is 0 when the top row has something in it", () => {
        expect(emptyRowsAbove(image(2, 2, [[0, 0]]), 2, 2)).toBe(0);
    });

    test("is the whole height of an empty strip", () => {
        expect(emptyRowsAbove(image(3, 5, []), 3, 5)).toBe(5);
    });

    test("a nearly transparent pixel still counts, the art is judged on what is drawn", () => {
        const data = new Uint8ClampedArray(2 * 2 * 4);
        data[(1 * 2 + 0) * 4 + 3] = 1;
        expect(emptyRowsAbove(data, 2, 2)).toBe(1);
    });
});
