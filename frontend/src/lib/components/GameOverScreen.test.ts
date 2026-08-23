import { describe, test, expect, beforeEach, vi } from "vitest";
import { render, fireEvent } from "@testing-library/svelte";
import GameOverScreen from "./GameOverScreen.svelte";
import { gameState, client } from "../engine/StateStore";
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

    test("Return to Hub sends RETURNTOHUB", async () => {
        gameState.set(makeState());
        const { container } = render(GameOverScreen);
        const sendSpy = vi.spyOn(client, "send");
        await fireEvent.click(container.querySelector("button.return-btn")!);
        expect(sendSpy).toHaveBeenCalledWith({ type: "HUB_ACTION", action: "RETURNTOHUB" });
    });
});
