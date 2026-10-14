import { describe, test, expect, beforeEach, afterEach, vi } from "vitest";
import { cleanup, render } from "@testing-library/svelte";
import { tick } from "svelte";
import GodLineBanner from "./GodLineBanner.svelte";
import { gameState } from "../engine/StateStore";
import { dictionaries } from "../engine/i18n";
import type { StateUpdate } from "../engine/protocol";

/** A line of its own, added to the real dictionary for the length of a test. */
const fixture: Record<string, string> = { "story.god.start.t.1": "Mind the test." };

function makeState(overrides: Partial<StateUpdate> = {}): StateUpdate {
    return {
        phase: "EXPLORATION",
        player: { classId: "warrior", hp: 100, maxHp: 120, resourceCurrent: 0, resourceMax: 100, level: 1, xp: 0, metaCurrency: 0, affinityTags: [] },
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

const banner = (container: HTMLElement) => container.querySelector(".god-banner");

/** The store changes, then the component reacts, then it shows the line one frame later. */
async function settle(): Promise<void> {
    await tick();
    await tick();
}

describe("GodLineBanner", () => {
    beforeEach(() => {
        vi.useFakeTimers();
        gameState.set(makeState());
        Object.assign(dictionaries.en, fixture);
    });

    afterEach(() => {
        cleanup();
        vi.useRealTimers();
        for (const key of Object.keys(fixture)) delete dictionaries.en[key];
    });

    test("shows nothing while the voice has nothing to say", () => {
        const { container } = render(GodLineBanner);
        expect(banner(container)).toBeNull();
    });

    test("shows the line said at the start of a run, then fades it away", async () => {
        const { container } = render(GodLineBanner);
        gameState.set(makeState({ godLine: { moment: "start", phase: "t" } }));
        await settle();
        expect(banner(container)?.textContent).toBe("Mind the test.");
        expect(banner(container)?.classList.contains("visible")).toBe(true);

        vi.advanceTimersByTime(6000);
        await tick();
        expect(banner(container)?.classList.contains("visible")).toBe(false);
    });

    test("the next updates, which carry no line, do not take it away", async () => {
        const { container } = render(GodLineBanner);
        gameState.set(makeState({ godLine: { moment: "start", phase: "t" } }));
        await settle();
        gameState.set(makeState());
        await settle();
        expect(banner(container)?.classList.contains("visible")).toBe(true);
    });

    test("leaves the lines about a death to the Game Over screen", async () => {
        const { container } = render(GodLineBanner);
        gameState.set(makeState({ godLine: { moment: "death.boss", phase: "a" } }));
        await settle();
        expect(banner(container)).toBeNull();
    });

    test("says nothing when the language file has no line for the moment", async () => {
        const { container } = render(GodLineBanner);
        gameState.set(makeState({ godLine: { moment: "start", phase: "z" } }));
        await settle();
        expect(banner(container)).toBeNull();
    });
});
