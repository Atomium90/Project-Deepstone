import { describe, test, expect } from "vitest";
import { pickGodLine } from "./godVoice";
import type { TextLookup } from "./sceneText";

const texts: Record<string, string> = {
    "story.god.start.a.1": "Start one.",
    "story.god.start.a.2": "Start two.",
    "story.god.death.boss.a.1": "Boss one.",
    "story.god.death.boss.a.2": "Boss two.",
    "story.god.death.depth.b.1": "Deep.",
};
const lookup: TextLookup = (key) => texts[key] ?? null;
const first = () => 0;
const last = () => 0.99;

describe("pickGodLine", () => {
    test("picks a line of the pool for the moment and the tone", () => {
        expect(pickGodLine({ moment: "death.boss", phase: "a" }, lookup, first, new Map())).toBe("Boss one.");
        expect(pickGodLine({ moment: "death.boss", phase: "a" }, lookup, last, new Map())).toBe("Boss two.");
    });

    test("never says the line it said last time while another exists", () => {
        const memory = new Map<string, string>();
        const line = { moment: "death.boss", phase: "a" };
        expect(pickGodLine(line, lookup, first, memory)).toBe("Boss one.");
        expect(pickGodLine(line, lookup, first, memory)).toBe("Boss two.");
        expect(pickGodLine(line, lookup, first, memory)).toBe("Boss one.");
    });

    test("says its only line again when it has no other", () => {
        const memory = new Map<string, string>();
        const line = { moment: "death.depth", phase: "b" };
        expect(pickGodLine(line, lookup, first, memory)).toBe("Deep.");
        expect(pickGodLine(line, lookup, first, memory)).toBe("Deep.");
    });

    test("the last line of a death and the last line of a run start are remembered apart", () => {
        const memory = new Map<string, string>();
        expect(pickGodLine({ moment: "start", phase: "a" }, lookup, first, memory)).toBe("Start one.");
        // The run start said its first line, which must not make the death skip its own first one.
        expect(pickGodLine({ moment: "death.boss", phase: "a" }, lookup, first, memory)).toBe("Boss one.");
        expect(pickGodLine({ moment: "death.boss", phase: "a" }, lookup, first, memory)).toBe("Boss two.");
        expect(pickGodLine({ moment: "start", phase: "a" }, lookup, first, memory)).toBe("Start two.");
    });

    test("every kind of death follows the last death line, whichever case it was", () => {
        const memory = new Map<string, string>();
        const crowded: TextLookup = (key) =>
            ({ "story.god.death.boss.a.1": "Shared.", "story.god.death.depth.a.1": "Shared.", "story.god.death.depth.a.2": "Other." })[key] ?? null;
        expect(pickGodLine({ moment: "death.boss", phase: "a" }, crowded, first, memory)).toBe("Shared.");
        expect(pickGodLine({ moment: "death.depth", phase: "a" }, crowded, first, memory)).toBe("Other.");
    });

    test("says nothing when the language file has no line for it", () => {
        expect(pickGodLine({ moment: "death.streak", phase: "c" }, lookup, first, new Map())).toBeNull();
    });
});
