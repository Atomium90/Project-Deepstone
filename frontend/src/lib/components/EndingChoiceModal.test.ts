import { describe, test, expect, beforeEach, afterEach, vi } from "vitest";
import { render, fireEvent, cleanup } from "@testing-library/svelte";
import { tick } from "svelte";
import EndingChoiceModal from "./EndingChoiceModal.svelte";
import { gameState, client } from "../engine/StateStore";
import { CHOICE_GRACE_MS } from "../engine/choiceKeys";
import type { StateUpdate } from "../engine/protocol";

function makeState(overrides: Partial<StateUpdate> = {}): StateUpdate {
    return {
        phase: "EXPLORATION",
        player: { classId: "warrior", hp: 100, maxHp: 100, resourceCurrent: 0, resourceMax: 100, level: 1, xp: 0, metaCurrency: 0, affinityTags: [] },
        equipment: { weapon: null, armor: null, accessories: [null, null], potionBelt: [null, null], keys: [] },
        abilities: [],
        achievements: [],
        sets: [],
        victory: false,
        log: [],
        newlyUnlocked: [],
        damageEvents: [],
        soundEvents: [],
        debugRooms: [],
        ...overrides,
    };
}

const asking = (candidates: string[]) => makeState({ pendingEndingChoice: { candidates } });

const answers = (container: HTMLElement) => container.querySelectorAll<HTMLButtonElement>(".answer");
/** What a button says, without the little key that sits in front of it. */
const textOf = (button: HTMLElement) => {
    const copy = button.cloneNode(true) as HTMLElement;
    copy.querySelectorAll(".key-hint").forEach((hint) => hint.remove());
    return copy.textContent?.trim();
};
const labels = (container: HTMLElement) => Array.from(answers(container)).map(textOf);

afterEach(() => {
    vi.useRealTimers();
    cleanup();
});

describe("EndingChoiceModal", () => {
    beforeEach(() => {
        gameState.set(makeState());
        vi.restoreAllMocks();
    });

    test("renders nothing when the light asks no question", () => {
        const { container } = render(EndingChoiceModal);
        expect(container.querySelector(".backdrop")).toBeNull();
    });

    test("offers the three answers", () => {
        gameState.set(asking(["npc.2"]));
        const { container } = render(EndingChoiceModal);
        expect(labels(container)).toEqual(["Enter the light.", "Let someone take my place.", "Refuse the light."]);
    });

    test("entering the light is answered at once", async () => {
        gameState.set(asking([]));
        const { container } = render(EndingChoiceModal);
        const sendSpy = vi.spyOn(client, "send");

        await fireEvent.click(answers(container)[0]);

        expect(sendSpy).toHaveBeenCalledWith({ type: "ENDING_CHOICE", answer: "ENTER" });
    });

    test("letting someone take the place is closed, and says why, while nobody has finished their story", () => {
        gameState.set(asking([]));
        const { container } = render(EndingChoiceModal);
        expect(answers(container)[1].disabled).toBe(true);
        expect(container.querySelector(".closed")?.textContent).toContain("Nobody here is ready");
    });

    test("when someone is ready the second answer is open and says nothing is missing", () => {
        gameState.set(asking(["npc.2"]));
        const { container } = render(EndingChoiceModal);
        expect(answers(container)[1].disabled).toBe(false);
        expect(container.querySelector(".closed")).toBeNull();
    });

    test("the second answer lists who can take the place by name, and sends the one picked", async () => {
        gameState.set(asking(["npc.2", "npc.3"]));
        const { container } = render(EndingChoiceModal);
        const sendSpy = vi.spyOn(client, "send");

        await fireEvent.click(answers(container)[1]);
        expect(container.querySelector(".question")?.textContent).toContain("Who takes your place?");
        const names = container.querySelectorAll<HTMLButtonElement>(".candidate");
        expect(names).toHaveLength(2);
        expect(sendSpy).not.toHaveBeenCalled();

        await fireEvent.click(names[1]);
        expect(sendSpy).toHaveBeenCalledWith({ type: "ENDING_CHOICE", answer: "REPLACE", npc: "npc.3" });
    });

    test("going back from the list returns to the three answers without sending anything", async () => {
        gameState.set(asking(["npc.2"]));
        const { container } = render(EndingChoiceModal);
        const sendSpy = vi.spyOn(client, "send");

        await fireEvent.click(answers(container)[1]);
        await fireEvent.click(container.querySelector(".back")!);

        expect(answers(container)).toHaveLength(3);
        expect(sendSpy).not.toHaveBeenCalled();
    });

    test("refusing the light warns first, and is only answered by the second click", async () => {
        gameState.set(asking([]));
        const { container } = render(EndingChoiceModal);
        const sendSpy = vi.spyOn(client, "send");

        await fireEvent.click(answers(container)[2]);
        expect(container.querySelector(".warning")?.textContent).toContain("cannot be undone");
        expect(sendSpy).not.toHaveBeenCalled();

        await fireEvent.click(container.querySelector(".answer.refuse")!);
        expect(sendSpy).toHaveBeenCalledWith({ type: "ENDING_CHOICE", answer: "REFUSE" });
    });

    test("backing out of the warning leaves the question as it was", async () => {
        gameState.set(asking([]));
        const { container } = render(EndingChoiceModal);
        const sendSpy = vi.spyOn(client, "send");

        await fireEvent.click(answers(container)[2]);
        await fireEvent.click(container.querySelector(".back")!);

        expect(labels(container)).toEqual(["Enter the light.", "Let someone take my place.", "Refuse the light."]);
        expect(sendSpy).not.toHaveBeenCalled();
    });

    test("the question starts again from the three answers once it has been answered", async () => {
        gameState.set(asking(["npc.2"]));
        const { container } = render(EndingChoiceModal);
        await fireEvent.click(answers(container)[1]);

        gameState.set(makeState());
        await tick();
        gameState.set(asking(["npc.2"]));
        await tick();

        expect(labels(container)).toEqual(["Enter the light.", "Let someone take my place.", "Refuse the light."]);
    });
});

/** A key press as the browser makes it. `code` is the physical key, `key` what it types: the top row of
 * an AZERTY keyboard types "&" for 1, which the shortcuts must not care about. */
const press = (key: string, code: string, init: KeyboardEventInit = {}) => fireEvent.keyDown(window, { key, code, ...init });
const digit = (n: number, init: KeyboardEventInit = {}) => press(String(n), `Digit${n}`, init);
const sent = (spy: ReturnType<typeof vi.spyOn>) => spy.mock.calls.map((call) => call[0]);

describe("EndingChoiceModal keyboard shortcuts", () => {
    const open = (candidates: string[]) => {
        gameState.set(asking(candidates));
        const view = render(EndingChoiceModal);
        vi.advanceTimersByTime(CHOICE_GRACE_MS);
        return view;
    };

    beforeEach(() => {
        vi.useFakeTimers();
        gameState.set(makeState());
        vi.restoreAllMocks();
    });

    test("1 enters the light", async () => {
        open([]);
        const sendSpy = vi.spyOn(client, "send");

        await digit(1);

        expect(sent(sendSpy)).toEqual([{ type: "ENDING_CHOICE", answer: "ENTER" }]);
    });

    test("reads the physical key, so the top row of an AZERTY keyboard works too", async () => {
        open([]);
        const sendSpy = vi.spyOn(client, "send");

        await press("&", "Digit1");

        expect(sent(sendSpy)).toEqual([{ type: "ENDING_CHOICE", answer: "ENTER" }]);
    });

    test("2 lists who can take the place, and a number then picks one", async () => {
        const { container } = open(["npc.2", "npc.3"]);
        const sendSpy = vi.spyOn(client, "send");

        await digit(2);
        expect(container.querySelectorAll(".candidate")).toHaveLength(2);
        expect(sent(sendSpy)).toEqual([]);

        await digit(2);
        expect(sent(sendSpy)).toEqual([{ type: "ENDING_CHOICE", answer: "REPLACE", npc: "npc.3" }]);
    });

    test("2 does nothing while nobody can take the place", async () => {
        const { container } = open([]);

        await digit(2);

        expect(container.querySelectorAll(".candidate")).toHaveLength(0);
        expect(labels(container)).toHaveLength(3);
    });

    test("0 and Escape go back from the list without sending anything", async () => {
        const { container } = open(["npc.2"]);
        const sendSpy = vi.spyOn(client, "send");

        await digit(2);
        await digit(0);
        expect(labels(container)).toEqual(["Enter the light.", "Let someone take my place.", "Refuse the light."]);

        await digit(2);
        await press("Escape", "Escape");
        expect(labels(container)).toHaveLength(3);
        expect(sent(sendSpy)).toEqual([]);
    });

    test("3 asks for a confirmation and no digit gives it", async () => {
        const { container } = open([]);
        const sendSpy = vi.spyOn(client, "send");

        await digit(3);
        expect(container.querySelector(".warning")).not.toBeNull();

        await digit(1);
        await digit(2);
        await digit(3);
        expect(container.querySelector(".warning")).not.toBeNull();
        expect(sent(sendSpy)).toEqual([]);
    });

    test("0 backs out of the warning", async () => {
        const { container } = open([]);

        await digit(3);
        await digit(0);

        expect(container.querySelector(".warning")).toBeNull();
        expect(labels(container)).toHaveLength(3);
    });

    test("Escape does nothing on the three answers, there is nothing to go back to", async () => {
        const { container } = open([]);
        const sendSpy = vi.spyOn(client, "send");

        await press("Escape", "Escape");

        expect(labels(container)).toHaveLength(3);
        expect(sent(sendSpy)).toEqual([]);
    });

    test("waits for the grace period", async () => {
        gameState.set(asking([]));
        render(EndingChoiceModal);
        const sendSpy = vi.spyOn(client, "send");

        await digit(1);
        expect(sent(sendSpy)).toEqual([]);

        vi.advanceTimersByTime(CHOICE_GRACE_MS);
        await digit(1);
        expect(sent(sendSpy)).toEqual([{ type: "ENDING_CHOICE", answer: "ENTER" }]);
    });

    test("does nothing when the light asks no question", async () => {
        gameState.set(makeState());
        render(EndingChoiceModal);
        vi.advanceTimersByTime(CHOICE_GRACE_MS);
        const sendSpy = vi.spyOn(client, "send");

        await digit(1);

        expect(sent(sendSpy)).toEqual([]);
    });

    test("each answer shows its number, the list too, and going back shows 0", async () => {
        const { container } = open(["npc.2", "npc.3"]);
        const hints = (selector: string) => Array.from(container.querySelectorAll(`${selector} .key-hint`)).map((el) => el.textContent);
        expect(hints(".answer")).toEqual(["1", "2", "3"]);

        await digit(2);
        expect(hints(".candidate")).toEqual(["1", "2"]);
        expect(hints(".back")).toEqual(["0"]);
    });
});
