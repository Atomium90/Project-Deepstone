import { describe, test, expect, beforeEach, afterEach, vi } from "vitest";
import { cleanup, render, fireEvent } from "@testing-library/svelte";
import { tick } from "svelte";
import CardScreen from "./CardScreen.svelte";
import { client } from "../engine/StateStore";
import { dictionaries } from "../engine/i18n";

/** Texts of cards of their own, added to the real dictionary for the length of a test, so these
 * tests never depend on what the game's cards say. */
const fixture: Record<string, string> = {
    "story.card.t1.1": "First.",
    "story.card.t1.2": "Second.",
    "story.card.t1.3": "Third.",
    "story.card.t2.1": "Alone.",
};

/** How long a line takes to follow the one before it: the delay of CardScreen.svelte. */
const LINE_DELAY_MS = 2000;

const visible = (container: HTMLElement) =>
    Array.from(container.querySelectorAll(".card-line.visible")).map((line) => line.textContent);
const pressKey = (key: string, init: KeyboardEventInit = {}) => fireEvent.keyDown(window, { key, ...init });

describe("CardScreen", () => {
    beforeEach(() => {
        vi.useFakeTimers();
        vi.setSystemTime(0);
        vi.restoreAllMocks();
        Object.assign(dictionaries.en, fixture);
    });

    afterEach(() => {
        // The component listens to the window, so the ones of earlier tests must not stay mounted.
        cleanup();
        vi.useRealTimers();
        for (const key of Object.keys(fixture)) delete dictionaries.en[key];
    });

    test("renders nothing when there is no card", () => {
        const { container } = render(CardScreen, { cardId: null });
        expect(container.querySelector(".card")).toBeNull();
    });

    test("shows the first line at once and the others one after another", async () => {
        const { container } = render(CardScreen, { cardId: "t1" });
        expect(visible(container)).toEqual(["First."]);
        await vi.advanceTimersByTimeAsync(LINE_DELAY_MS);
        expect(visible(container)).toEqual(["First.", "Second."]);
        await vi.advanceTimersByTimeAsync(LINE_DELAY_MS);
        expect(visible(container)).toEqual(["First.", "Second.", "Third."]);
    });

    test("every line takes its place from the start, so the card does not move as it appears", () => {
        const { container } = render(CardScreen, { cardId: "t1" });
        expect(container.querySelectorAll(".card-line")).toHaveLength(3);
    });

    test("the hint to go on appears with the last line", async () => {
        const { container } = render(CardScreen, { cardId: "t1" });
        expect(container.querySelector(".card-hint.visible")).toBeNull();
        await vi.advanceTimersByTimeAsync(2 * LINE_DELAY_MS);
        expect(container.querySelector(".card-hint.visible")).not.toBeNull();
    });

    test("a first key press shows the rest of the card and sends nothing", async () => {
        const send = vi.spyOn(client, "send");
        const { container } = render(CardScreen, { cardId: "t1" });
        await pressKey("e");
        expect(visible(container)).toEqual(["First.", "Second.", "Third."]);
        expect(send).not.toHaveBeenCalled();
    });

    test("once the card is complete a key press closes it, once", async () => {
        const send = vi.spyOn(client, "send");
        render(CardScreen, { cardId: "t1" });
        await vi.advanceTimersByTimeAsync(2 * LINE_DELAY_MS);
        vi.advanceTimersByTime(400);
        await pressKey("e");
        await pressKey("e");
        expect(send).toHaveBeenCalledTimes(1);
        expect(send).toHaveBeenCalledWith({ type: "CARD_SEEN", cardId: "t1" });
    });

    test("the press that showed the rest of the card cannot close it right away", async () => {
        const send = vi.spyOn(client, "send");
        render(CardScreen, { cardId: "t1" });
        await pressKey("e");
        await pressKey("e");
        expect(send).not.toHaveBeenCalled();
        vi.advanceTimersByTime(400);
        await pressKey("e");
        expect(send).toHaveBeenCalledTimes(1);
    });

    test("a line that has just appeared cannot be skipped by a key press", async () => {
        const send = vi.spyOn(client, "send");
        render(CardScreen, { cardId: "t1" });
        await vi.advanceTimersByTimeAsync(2 * LINE_DELAY_MS);
        vi.advanceTimersByTime(399);
        await pressKey("e");
        expect(send).not.toHaveBeenCalled();
    });

    test("Space and Enter go on too, a held key does not", async () => {
        const send = vi.spyOn(client, "send");
        const { container } = render(CardScreen, { cardId: "t1" });
        await pressKey("e", { repeat: true });
        expect(visible(container)).toEqual(["First."]);
        await pressKey(" ");
        expect(visible(container)).toHaveLength(3);
        vi.advanceTimersByTime(400);
        await pressKey("Enter");
        expect(send).toHaveBeenCalledTimes(1);
    });

    test("a click goes on like a key", async () => {
        const send = vi.spyOn(client, "send");
        const { container } = render(CardScreen, { cardId: "t2" });
        vi.advanceTimersByTime(400);
        await fireEvent.click(container.querySelector(".card")!);
        expect(send).toHaveBeenCalledWith({ type: "CARD_SEEN", cardId: "t2" });
    });

    test("a card of one line is complete from the start", async () => {
        const { container } = render(CardScreen, { cardId: "t2" });
        expect(visible(container)).toEqual(["Alone."]);
        expect(container.querySelector(".card-hint.visible")).not.toBeNull();
    });

    test("a different card starts again from its first line", async () => {
        const { container, component } = render(CardScreen, { cardId: "t1" });
        await vi.advanceTimersByTimeAsync(2 * LINE_DELAY_MS);
        component.$set({ cardId: "t2" });
        await tick();
        expect(visible(container)).toEqual(["Alone."]);
    });

    test("a card whose texts are missing shows its key, and can still be closed", async () => {
        const send = vi.spyOn(client, "send");
        const { container } = render(CardScreen, { cardId: "nowhere" });
        expect(visible(container)).toEqual(["story.card.nowhere"]);
        vi.advanceTimersByTime(400);
        await pressKey("e");
        expect(send).toHaveBeenCalledWith({ type: "CARD_SEEN", cardId: "nowhere" });
    });

    test("keys do nothing once there is no card", async () => {
        const send = vi.spyOn(client, "send");
        const { component } = render(CardScreen, { cardId: "t2" });
        component.$set({ cardId: null });
        await tick();
        vi.advanceTimersByTime(400);
        await pressKey("e");
        expect(send).not.toHaveBeenCalled();
    });
});
