import { describe, test, expect } from "vitest";
import { get } from "svelte/store";
import { lastStartedDifficulty } from "./RunStore";

describe("RunStore", () => {
    test("defaults to normal difficulty", () => {
        expect(get(lastStartedDifficulty)).toBe("normal");
    });

    test("remembers the last difficulty set", () => {
        lastStartedDifficulty.set("hard");
        expect(get(lastStartedDifficulty)).toBe("hard");
    });
});
