import { describe, test, expect, beforeEach, afterEach, vi } from "vitest";
import { cleanup, render, fireEvent } from "@testing-library/svelte";
import { tick } from "svelte";
import SceneDialogue from "./SceneDialogue.svelte";
import { client } from "../engine/StateStore";
import { characterTab } from "../engine/CharacterStore";
import { dictionaries } from "../engine/i18n";
import type { PendingSceneView } from "../engine/protocol";

/** Texts of a conversation of their own, added to the real dictionary for the length of a test, so
 * these tests never depend on what the game's scenes say. */
const fixture: Record<string, string> = {
    "story.npc.t.name": "Tester",
    "story.npc.t.s1.1": "Line one.",
    "story.npc.t.s1.2": "Line two.",
    "story.npc.t.s2.1": "A deal?",
    "story.npc.t.s2.choice.accept": "Yes please.",
    "story.npc.t.s2.choice.decline": "No thanks.",
    "story.npc.t.s2.reply.accept": "Great.",
    "story.npc.t.s2.reply.decline": "Shame.",
};

const plain: PendingSceneView = { npc: "npc.t", kind: "scene", sceneId: "npc.t.s1" };
const offering: PendingSceneView = { npc: "npc.t", kind: "scene", sceneId: "npc.t.s2", offerScene: "npc.t.s2" };

const text = (container: HTMLElement, selector: string) => container.querySelector(selector)?.textContent ?? null;
const pressKey = (key: string, init: KeyboardEventInit = {}) => fireEvent.keyDown(window, { key, ...init });

describe("SceneDialogue", () => {
    beforeEach(() => {
        vi.useFakeTimers();
        vi.setSystemTime(0);
        vi.restoreAllMocks();
        characterTab.set(null);
        Object.assign(dictionaries.en, { "story.npc.t.name": "Tester", ...fixture });
    });

    afterEach(() => {
        // The component listens to the window, so the ones of earlier tests must not stay mounted.
        cleanup();
        vi.useRealTimers();
        for (const key of Object.keys(fixture)) delete dictionaries.en[key];
    });

    test("renders nothing when there is no conversation", () => {
        const { container } = render(SceneDialogue, { scene: null });
        expect(container.querySelector(".dialogue-box")).toBeNull();
    });

    test("shows the speaker and the first line", () => {
        const { container } = render(SceneDialogue, { scene: plain });
        expect(text(container, ".dialogue-name")).toBe("Tester");
        expect(text(container, ".dialogue-line")).toBe("Line one.");
    });

    test("E goes to the next line once the delay has passed", async () => {
        const { container } = render(SceneDialogue, { scene: plain });
        vi.advanceTimersByTime(400);
        await pressKey("e");
        expect(text(container, ".dialogue-line")).toBe("Line two.");
    });

    test("a key pressed too soon after a line appeared is ignored", async () => {
        const { container } = render(SceneDialogue, { scene: plain });
        vi.advanceTimersByTime(399);
        await pressKey("e");
        expect(text(container, ".dialogue-line")).toBe("Line one.");
    });

    test("the delay starts again with every line", async () => {
        const send = vi.spyOn(client, "send");
        const { container } = render(SceneDialogue, { scene: plain });
        vi.advanceTimersByTime(400);
        await pressKey("e");
        // Line two has just appeared: this press is too soon, it neither goes on nor ends it.
        await pressKey("e");
        expect(text(container, ".dialogue-line")).toBe("Line two.");
        expect(send).not.toHaveBeenCalled();
    });

    test("Space and Enter go on too, a held key does not", async () => {
        const { container } = render(SceneDialogue, { scene: plain });
        vi.advanceTimersByTime(400);
        await pressKey("e", { repeat: true });
        expect(text(container, ".dialogue-line")).toBe("Line one.");
        await pressKey(" ");
        expect(text(container, ".dialogue-line")).toBe("Line two.");
    });

    test("on the last line E ends the conversation, once", async () => {
        const send = vi.spyOn(client, "send");
        render(SceneDialogue, { scene: plain });
        vi.advanceTimersByTime(400);
        await pressKey("e");
        vi.advanceTimersByTime(400);
        await pressKey("e");
        await pressKey("e");
        expect(send).toHaveBeenCalledTimes(1);
        expect(send).toHaveBeenCalledWith({ type: "SCENE_DONE" });
    });

    test("a conversation that offers a vow ends with two choices instead of a hint", async () => {
        const { container } = render(SceneDialogue, { scene: offering });
        const buttons = Array.from(container.querySelectorAll(".choice-btn")).map((b) => b.textContent);
        expect(buttons).toEqual(["1Yes please.", "2No thanks."]);
        expect(container.querySelector(".dialogue-hint")).toBeNull();
    });

    test("E does not end a conversation while a vow is waiting for its answer", async () => {
        const send = vi.spyOn(client, "send");
        render(SceneDialogue, { scene: offering });
        vi.advanceTimersByTime(400);
        await pressKey("e");
        expect(send).not.toHaveBeenCalled();
    });

    test("accepting shows the NPC's reply, then ends the conversation with the answer", async () => {
        const send = vi.spyOn(client, "send");
        const { container } = render(SceneDialogue, { scene: offering });
        await fireEvent.click(container.querySelector(".choice-btn.accept")!);
        expect(text(container, ".dialogue-line")).toBe("Great.");
        expect(container.querySelector(".choice-btn")).toBeNull();

        vi.advanceTimersByTime(400);
        await pressKey("e");
        expect(send).toHaveBeenCalledWith({ type: "SCENE_DONE", accept: true });
    });

    test("declining shows its own reply and ends the conversation with the refusal", async () => {
        const send = vi.spyOn(client, "send");
        const { container } = render(SceneDialogue, { scene: offering });
        await fireEvent.click(container.querySelector(".choice-btn.decline")!);
        expect(text(container, ".dialogue-line")).toBe("Shame.");

        vi.advanceTimersByTime(400);
        await pressKey("e");
        expect(send).toHaveBeenCalledWith({ type: "SCENE_DONE", accept: false });
    });

    test("1 and 2 answer the vow from the keyboard", async () => {
        const { container } = render(SceneDialogue, { scene: offering });
        await pressKey("2");
        expect(text(container, ".dialogue-line")).toBe("Shame.");
    });

    test("keys do nothing while the Character screen is open", async () => {
        const { container } = render(SceneDialogue, { scene: plain });
        characterTab.set("equipment");
        vi.advanceTimersByTime(400);
        await pressKey("e");
        expect(text(container, ".dialogue-line")).toBe("Line one.");
    });

    test("a different conversation starts from its first line", async () => {
        const { container, component } = render(SceneDialogue, { scene: plain });
        vi.advanceTimersByTime(400);
        await pressKey("e");
        expect(text(container, ".dialogue-line")).toBe("Line two.");

        component.$set({ scene: offering });
        await tick();
        expect(text(container, ".dialogue-line")).toBe("A deal?");
    });

    test("the same conversation sent again does not start over", async () => {
        const { container, component } = render(SceneDialogue, { scene: plain });
        vi.advanceTimersByTime(400);
        await pressKey("e");
        component.$set({ scene: { ...plain } });
        await tick();
        expect(text(container, ".dialogue-line")).toBe("Line two.");
    });
});
