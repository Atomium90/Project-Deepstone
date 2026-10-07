import { describe, test, expect, beforeEach, vi } from "vitest";
import { render, fireEvent } from "@testing-library/svelte";
import { get } from "svelte/store";
import StartScreen from "./StartScreen.svelte";
import { client } from "../engine/StateStore";
import { lastStartedDifficulty } from "../engine/RunStore";
import { resetTutorial, tutorial } from "../engine/HintStore";

describe("StartScreen", () => {
    beforeEach(() => {
        resetTutorial();
        lastStartedDifficulty.set("normal");
        vi.restoreAllMocks();
    });

    test("shows the title, the goal and both buttons, texts from the lang file", () => {
        const { container } = render(StartScreen);
        expect(container.querySelector(".title")?.textContent).toBe("DEEPSTONE");
        expect(container.querySelector(".goal")?.textContent).toBe("Fight your way through the dungeon and reach the Sanctuary at its end.");
        expect(container.querySelector(".begin-btn")?.textContent).toBe("Start");
        expect(container.querySelector(".skip-btn")?.textContent).toBe("Skip tutorial");
    });

    test("Start launches the first run: Warrior on Easy, no perk", async () => {
        const { container } = render(StartScreen);
        const sendSpy = vi.spyOn(client, "send");

        await fireEvent.click(container.querySelector(".begin-btn")!);

        expect(sendSpy).toHaveBeenCalledTimes(1);
        expect(sendSpy).toHaveBeenCalledWith({ type: "HUB_ACTION", action: "STARTRUN", classId: "warrior", difficulty: "easy" });
    });

    test("Start remembers Easy, which picks the exploration music", async () => {
        const { container } = render(StartScreen);
        vi.spyOn(client, "send");

        await fireEvent.click(container.querySelector(".begin-btn")!);

        expect(get(lastStartedDifficulty)).toBe("easy");
    });

    test("Skip marks the tutorial as skipped and starts no run", async () => {
        const { container } = render(StartScreen);
        const sendSpy = vi.spyOn(client, "send");

        await fireEvent.click(container.querySelector(".skip-btn")!);

        expect(get(tutorial).skipped).toBe(true);
        expect(sendSpy).not.toHaveBeenCalled();
    });
});
