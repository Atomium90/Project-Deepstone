import { describe, test, expect, beforeEach, vi } from "vitest";
import { render } from "@testing-library/svelte";
import { tick } from "svelte";
import AchievementToast from "./AchievementToast.svelte";
import { gameState } from "../engine/StateStore";
import type { StateUpdate, AchievementView } from "../engine/protocol";

function makeAchievement(overrides: Partial<AchievementView> = {}): AchievementView {
    return { id: "a1", label: "First Blood", description: "d", unlocked: true, ...overrides };
}

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
        ...overrides,
    };
}

describe("AchievementToast", () => {
    beforeEach(() => {
        gameState.set(null);
        vi.useRealTimers();
    });

    test("shows nothing when there is no newly-unlocked achievement", () => {
        const { container } = render(AchievementToast);
        expect(container.querySelector(".toast-stack")).toBeNull();
    });

    test("shows a toast for each newly-unlocked achievement", async () => {
        const { container } = render(AchievementToast);
        gameState.set(makeState({ newlyUnlocked: [makeAchievement({ id: "a1", label: "First Blood" }), makeAchievement({ id: "a2", label: "Boss Slayer" })] }));
        await tick();
        const labels = Array.from(container.querySelectorAll(".toast-label")).map((el) => el.textContent);
        expect(labels).toEqual(["First Blood", "Boss Slayer"]);
    });

    test("auto-hides the toast after 4 seconds", async () => {
        vi.useFakeTimers();
        const { container } = render(AchievementToast);
        gameState.set(makeState({ newlyUnlocked: [makeAchievement()] }));
        await tick();
        expect(container.querySelector(".toast-stack")).not.toBeNull();

        vi.advanceTimersByTime(4000);
        await tick();
        expect(container.querySelector(".toast-stack")).toBeNull();
        vi.useRealTimers();
    });
});
