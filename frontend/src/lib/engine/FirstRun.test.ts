import { describe, test, expect, beforeEach } from "vitest";
import { get } from "svelte/store";
import { isNewSave } from "./FirstRun";
import { gameState } from "./StateStore";
import { resetTutorial, skipTutorial } from "./HintStore";
import type { StateUpdate } from "./protocol";

function hubState(runsCompleted: number): StateUpdate {
    return {
        phase: "HUB",
        player: { classId: "warrior", hp: 100, maxHp: 100, resourceCurrent: 0, resourceMax: 100, level: 1, xp: 0, metaCurrency: 0, affinityTags: [] },
        equipment: { weapon: null, armor: null, accessories: [null, null], potionBelt: [null, null], keys: [] },
        hub: { upgrades: [], perks: [], runsCompleted },
        abilities: [],
        achievements: [],
        sets: [],
        victory: false,
        log: [],
        newlyUnlocked: [],
        damageEvents: [],
        soundEvents: [],
        debugRooms: [],
    };
}

describe("isNewSave", () => {
    beforeEach(() => {
        resetTutorial();
        gameState.set(null);
    });

    test("is true in the hub of a save with no finished run", () => {
        gameState.set(hubState(0));
        expect(get(isNewSave)).toBe(true);
    });

    test("is false once a run was finished", () => {
        gameState.set(hubState(1));
        expect(get(isNewSave)).toBe(false);
    });

    test("is false after Skip, even on a save with no finished run", () => {
        gameState.set(hubState(0));
        skipTutorial();
        expect(get(isNewSave)).toBe(false);
    });

    test("is true again after the tutorial is reset", () => {
        gameState.set(hubState(0));
        skipTutorial();
        resetTutorial();
        expect(get(isNewSave)).toBe(true);
    });

    test("is false before the server has sent anything", () => {
        expect(get(isNewSave)).toBe(false);
    });

    test("is false when the update carries no hub view", () => {
        gameState.set({ ...hubState(0), hub: undefined });
        expect(get(isNewSave)).toBe(false);
    });
});
