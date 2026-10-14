import { describe, test, expect, beforeEach, vi } from "vitest";
import { render, fireEvent } from "@testing-library/svelte";
import { tick } from "svelte";
import EndingChoiceModal from "./EndingChoiceModal.svelte";
import { gameState, client } from "../engine/StateStore";
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
const labels = (container: HTMLElement) => Array.from(answers(container)).map((button) => button.textContent?.trim());

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
