import { describe, test, expect } from "vitest";
import { buildScript, numberedTexts, type TextLookup } from "./sceneText";
import type { PendingSceneView } from "./protocol";

/** A lookup over a small fixture, so these tests never depend on the real language file. */
const texts: Record<string, string> = {
    "story.npc.1.name": "Wren",
    "story.npc.1.s1.1": "First line.",
    "story.npc.1.s1.2": "Second line.",
    "story.npc.1.s2.1": "Take the deal?",
    "story.npc.1.s2.choice.accept": "Yes.",
    "story.npc.1.s2.choice.decline": "No.",
    "story.npc.1.s2.reply.accept": "Good.",
    "story.npc.1.s2.reply.decline": "Suit yourself.",
    "story.npc.1.vow.kept.1": "Kept it.",
    "story.npc.1.vow.kept.2": "Well done.",
    "story.npc.1.vow.failed.1": "You failed.",
    "story.npc.1.idle.general.1": "General one.",
    "story.npc.1.idle.general.2": "General two.",
    "story.npc.1.idle.death.1": "Death one.",
    "story.npc.1.idle.victory.1": "Victory one.",
};
const lookup: TextLookup = (key) => texts[key] ?? null;

function scene(overrides: Partial<PendingSceneView>): PendingSceneView {
    return { npc: "npc.1", kind: "scene", ...overrides };
}

describe("numberedTexts", () => {
    test("reads the numbered texts in order until one is missing", () => {
        expect(numberedTexts("story.npc.1.s1", lookup)).toEqual(["First line.", "Second line."]);
    });

    test("is empty when there is no text one", () => {
        expect(numberedTexts("story.npc.9.s1", lookup)).toEqual([]);
    });

    test("stops at a gap in the numbering", () => {
        const gap: TextLookup = (key) => ({ "a.1": "one", "a.3": "three" })[key] ?? null;
        expect(numberedTexts("a", gap)).toEqual(["one"]);
    });
});

describe("buildScript", () => {
    test("a scene reads the lines under its id and names the NPC", () => {
        const script = buildScript(scene({ sceneId: "npc.1.s1" }), lookup);
        expect(script.speaker).toBe("Wren");
        expect(script.lines).toEqual(["First line.", "Second line."]);
        expect(script.offer).toBeNull();
    });

    test("a scene that offers a vow brings the choice and its replies", () => {
        const script = buildScript(scene({ sceneId: "npc.1.s2", offerScene: "npc.1.s2" }), lookup);
        expect(script.offer).toEqual({ accept: "Yes.", decline: "No.", replyAccept: "Good.", replyDecline: "Suit yourself." });
    });

    test("a comment on a kept vow reads the kept lines", () => {
        const script = buildScript({ npc: "npc.1", kind: "vow_report", kept: true }, lookup);
        expect(script.lines).toEqual(["Kept it.", "Well done."]);
        expect(script.offer).toBeNull();
    });

    test("a comment on a failed vow reads the failed lines, and offers the vow again with the scene's texts", () => {
        const script = buildScript({ npc: "npc.1", kind: "vow_report", kept: false, offerScene: "npc.1.s2" }, lookup);
        expect(script.lines).toEqual(["You failed."]);
        expect(script.offer?.accept).toBe("Yes.");
    });

    test("a short line comes from the general pool", () => {
        const script = buildScript({ npc: "npc.1", kind: "idle", context: "general" }, lookup, () => 0, new Map());
        expect(script.lines).toEqual(["General one."]);
    });

    test("after a death, a short line is drawn from the general pool and the death pool together", () => {
        const drawn = [0, 0.4, 0.7, 0.99].map(
            (value) => buildScript({ npc: "npc.1", kind: "idle", context: "death" }, lookup, () => value, new Map()).lines[0],
        );
        expect(new Set(drawn)).toEqual(new Set(["General one.", "General two.", "Death one."]));
    });

    test("the short line said last time is not said again while another exists", () => {
        const last = new Map<string, string>();
        const idle: PendingSceneView = { npc: "npc.1", kind: "idle", context: "general" };
        const first = buildScript(idle, lookup, () => 0, last).lines[0];
        const second = buildScript(idle, lookup, () => 0, last).lines[0];
        expect(first).toBe("General one.");
        expect(second).toBe("General two.");
    });

    test("with a single short line available it is said again rather than nothing", () => {
        const last = new Map<string, string>();
        const only: TextLookup = (key) => ({ "story.npc.1.idle.general.1": "Only one." })[key] ?? null;
        const idle: PendingSceneView = { npc: "npc.1", kind: "idle", context: "general" };
        buildScript(idle, only, () => 0, last);
        expect(buildScript(idle, only, () => 0, last).lines).toEqual(["Only one."]);
    });

    test("a conversation whose texts are missing shows its key, so the gap is visible", () => {
        expect(buildScript(scene({ sceneId: "npc.9.s1" }), lookup).lines).toEqual(["story.npc.9.s1"]);
        expect(buildScript({ npc: "npc.9", kind: "idle", context: "general" }, lookup).lines).toEqual(["story.npc.9.idle"]);
    });

    test("an NPC without a name text is shown by its id", () => {
        expect(buildScript({ npc: "npc.9", kind: "idle" }, lookup).speaker).toBe("npc.9");
    });

    test("an offer whose choice texts are missing still gets buttons, labelled by their keys", () => {
        const script = buildScript(scene({ sceneId: "npc.9.s2", offerScene: "npc.9.s2" }), lookup);
        expect(script.offer?.accept).toBe("story.npc.9.s2.choice.accept");
    });
});
