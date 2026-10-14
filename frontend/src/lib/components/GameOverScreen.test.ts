import { describe, test, expect, beforeEach, afterEach, vi } from "vitest";
import { render, fireEvent } from "@testing-library/svelte";
import { tick } from "svelte";
import GameOverScreen from "./GameOverScreen.svelte";
import { gameState, client } from "../engine/StateStore";
import { dictionaries } from "../engine/i18n";
import type { StateUpdate } from "../engine/protocol";

function makeState(overrides: Partial<StateUpdate> = {}): StateUpdate {
    return {
        phase: "GAMEOVER",
        player: { classId: "warrior", hp: 0, maxHp: 120, resourceCurrent: 0, resourceMax: 100, level: 3, xp: 250, metaCurrency: 42, affinityTags: [] },
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

describe("GameOverScreen", () => {
    beforeEach(() => {
        vi.restoreAllMocks();
    });

    test("shows DEFEATED and the KO portrait on a loss", () => {
        gameState.set(makeState({ victory: false }));
        const { container } = render(GameOverScreen);
        expect(container.querySelector(".title")?.textContent).toBe("DEFEATED");
        expect(container.querySelector(".over-root")?.classList.contains("victory")).toBe(false);
        expect(container.querySelector(".ko-portrait")).not.toBeNull();
    });

    test("shows VICTORY and no KO portrait on a win", () => {
        gameState.set(makeState({ victory: true }));
        const { container } = render(GameOverScreen);
        expect(container.querySelector(".title")?.textContent).toBe("VICTORY");
        expect(container.querySelector(".over-root")?.classList.contains("victory")).toBe(true);
        expect(container.querySelector(".ko-portrait")).toBeNull();
    });

    test("keeps saying VICTORY and showing its stats while it fades out after the state is the hub's", async () => {
        gameState.set(makeState({ victory: true }));
        const { container } = render(GameOverScreen);

        // "Return to Hub" brings the hub's state at once, and the screen is still there for the fade-out.
        gameState.set(makeState({ phase: "HUB", victory: false, player: { classId: "warrior", hp: 100, maxHp: 100, resourceCurrent: 0, resourceMax: 100, level: 1, xp: 0, metaCurrency: 7, affinityTags: [] } }));
        await tick();

        expect(container.querySelector(".title")?.textContent).toBe("VICTORY");
        expect(container.querySelector(".over-root")?.classList.contains("victory")).toBe(true);
        expect(container.querySelector(".ko-portrait")).toBeNull();
        expect(Array.from(container.querySelectorAll(".stat-value")).map((el) => el.textContent)).toEqual(["3", "250 XP", "◈ 42"]);
    });

    test("keeps saying DEFEATED while it fades out after a loss too", async () => {
        gameState.set(makeState({ victory: false }));
        const { container } = render(GameOverScreen);

        gameState.set(makeState({ phase: "HUB", victory: false }));
        await tick();

        expect(container.querySelector(".title")?.textContent).toBe("DEFEATED");
    });

    test("follows the state while it is still the end of a run", async () => {
        gameState.set(makeState({ victory: true }));
        const { container } = render(GameOverScreen);

        gameState.set(makeState({ victory: true, player: { classId: "warrior", hp: 0, maxHp: 120, resourceCurrent: 0, resourceMax: 100, level: 3, xp: 250, metaCurrency: 99, affinityTags: [] } }));
        await tick();

        expect(Array.from(container.querySelectorAll(".stat-value")).map((el) => el.textContent)).toEqual(["3", "250 XP", "◈ 99"]);
    });

    test("shows the run's level/xp/shards stats", () => {
        gameState.set(
            makeState({
                player: { classId: "mage", hp: 0, maxHp: 70, resourceCurrent: 0, resourceMax: 80, level: 5, xp: 999, metaCurrency: 123, affinityTags: [] },
            })
        );
        const { container } = render(GameOverScreen);
        const values = Array.from(container.querySelectorAll(".stat-value")).map((el) => el.textContent);
        expect(values).toEqual(["5", "999 XP", "◈ 123"]);
    });

    describe("the god's voice", () => {
        /** A line of its own, added to the real dictionary for the length of a test. */
        const fixture: Record<string, string> = { "story.god.death.test.z.1": "Rest now, said the test." };
        const death = { moment: "death.test", phase: "z" };

        beforeEach(() => void Object.assign(dictionaries.en, fixture));
        afterEach(() => {
            for (const key of Object.keys(fixture)) delete dictionaries.en[key];
        });

        test("says a line about the death under the title", () => {
            gameState.set(makeState({ godLine: death }));
            const { container } = render(GameOverScreen);
            expect(container.querySelector(".god-line")?.textContent).toBe("Rest now, said the test.");
        });

        test("says nothing on a victory, which has its card instead", () => {
            gameState.set(makeState({ victory: true, godLine: death }));
            const { container } = render(GameOverScreen);
            expect(container.querySelector(".god-line")).toBeNull();
        });

        test("says nothing when the update carries no line, or the language file has none for it", () => {
            gameState.set(makeState());
            expect(render(GameOverScreen).container.querySelector(".god-line")).toBeNull();
            gameState.set(makeState({ godLine: { moment: "death.test", phase: "y" } }));
            expect(render(GameOverScreen).container.querySelector(".god-line")).toBeNull();
        });

        test("keeps the line it picked when a later update arrives", async () => {
            gameState.set(makeState({ godLine: death }));
            const { container } = render(GameOverScreen);
            gameState.set(makeState());
            await Promise.resolve();
            expect(container.querySelector(".god-line")?.textContent).toBe("Rest now, said the test.");
        });
    });

    test("Return to Hub sends RETURNTOHUB", async () => {
        gameState.set(makeState());
        const { container } = render(GameOverScreen);
        const sendSpy = vi.spyOn(client, "send");
        await fireEvent.click(container.querySelector("button.return-btn")!);
        expect(sendSpy).toHaveBeenCalledWith({ type: "HUB_ACTION", action: "RETURNTOHUB" });
    });
});
