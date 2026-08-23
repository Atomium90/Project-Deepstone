import { describe, test, expect, beforeEach } from "vitest";
import { render } from "@testing-library/svelte";
import AchievementsPanel from "./AchievementsPanel.svelte";
import { gameState } from "../engine/StateStore";
import type { StateUpdate, AchievementView } from "../engine/protocol";

function makeAchievement(overrides: Partial<AchievementView> = {}): AchievementView {
    return { id: "a1", label: "First Blood", description: "Defeat your first enemy.", unlocked: false, ...overrides };
}

function makeState(overrides: Partial<StateUpdate> = {}): StateUpdate {
    return {
        phase: "HUB",
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

describe("AchievementsPanel", () => {
    beforeEach(() => gameState.set(null));

    test("shows a muted message and no progress bar when no achievements are loaded", () => {
        gameState.set(makeState({ achievements: [] }));
        const { container } = render(AchievementsPanel);
        expect(container.querySelector(".muted")?.textContent).toBe("No achievements loaded.");
        expect(container.querySelector(".progress-wrap")).toBeNull();
    });

    test("shows the unlocked count out of the total as the progress value", () => {
        gameState.set(
            makeState({
                achievements: [makeAchievement({ id: "a1", unlocked: true }), makeAchievement({ id: "a2", unlocked: false }), makeAchievement({ id: "a3", unlocked: false })],
            })
        );
        const { container } = render(AchievementsPanel);
        expect(container.querySelector(".bar-value")?.textContent).toBe("1 / 3");
    });

    test("marks each row's owned state and only shows the unlocked badge for owned achievements", () => {
        gameState.set(
            makeState({
                achievements: [makeAchievement({ id: "a1", label: "Owned", unlocked: true }), makeAchievement({ id: "a2", label: "Not Owned", unlocked: false })],
            })
        );
        const { container } = render(AchievementsPanel);
        const rows = container.querySelectorAll(".achievement-row");
        expect(rows[0].classList.contains("owned")).toBe(true);
        expect(rows[0].querySelector(".owned-badge")).not.toBeNull();
        expect(rows[1].classList.contains("owned")).toBe(false);
        expect(rows[1].querySelector(".owned-badge")).toBeNull();
    });
});
