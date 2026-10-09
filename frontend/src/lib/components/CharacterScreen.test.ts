import { describe, test, expect, beforeEach } from "vitest";
import { render, fireEvent } from "@testing-library/svelte";
import { get } from "svelte/store";
import { tick } from "svelte";
import CharacterScreen from "./CharacterScreen.svelte";
import { characterTab } from "../engine/CharacterStore";
import { dismissHint, resetTutorial, showHint, skipTutorial, tutorial } from "../engine/HintStore";
import { settings } from "../engine/SettingsStore";
import { gameState } from "../engine/StateStore";
import type { MinimapView, StateUpdate } from "../engine/protocol";

describe("CharacterScreen", () => {
    beforeEach(() => {
        characterTab.set(null);
        gameState.set(null);
    });

    test("renders nothing when no tab is open", () => {
        const { container } = render(CharacterScreen);
        expect(container.querySelector(".character-overlay")).toBeNull();
    });

    test("opens on the requested tab and marks it active", () => {
        characterTab.set("achievements");
        const { container } = render(CharacterScreen);
        expect(container.querySelector(".character-overlay")).not.toBeNull();
        const active = container.querySelector(".tab-btn.active");
        expect(active?.textContent?.trim()).toBe("Achievements");
    });

    test("clicking a tab switches the visible panel", async () => {
        characterTab.set("equipment");
        const { container } = render(CharacterScreen);
        expect(container.querySelector(".equipment-panel")).not.toBeNull();

        const tabs = Array.from(container.querySelectorAll(".tab-btn"));
        const settingsTab = tabs.find((t) => t.textContent?.trim() === "Settings")!;
        await fireEvent.click(settingsTab);

        expect(container.querySelector(".equipment-panel")).toBeNull();
        expect(container.querySelectorAll(".setting-row")).toHaveLength(6);
    });

    test("the Credits tab is there with no run going, and shows the credits panel", async () => {
        characterTab.set("equipment");
        const { container } = render(CharacterScreen);
        const tabs = Array.from(container.querySelectorAll(".tab-btn"));
        const creditsTab = tabs.find((t) => t.textContent?.trim() === "Credits")!;
        expect(creditsTab).toBeDefined();

        await fireEvent.click(creditsTab);

        expect(get(characterTab)).toBe("credits");
        expect(container.querySelector(".credits-panel")).not.toBeNull();
        expect(container.querySelector(".equipment-panel")).toBeNull();
        expect(container.querySelector(".tab-btn.active")?.textContent?.trim()).toBe("Credits");
    });

    test("the close button clears characterTab", async () => {
        characterTab.set("settings");
        const { container } = render(CharacterScreen);
        await fireEvent.click(container.querySelector(".close-btn")!);
        expect(get(characterTab)).toBeNull();
    });

    describe("Map tab", () => {
        const map: MinimapView = {
            sections: [{ index: 0, theme: "dungeon" }],
            nodes: [{ id: "n0", roomType: "combat", visited: true, current: true, section: 0, column: 0, lane: 0 }],
            edges: [],
        };

        function exploring(minimap: MinimapView | undefined): StateUpdate {
            return {
                phase: "EXPLORATION",
                player: {
                    classId: "warrior",
                    hp: 100,
                    maxHp: 100,
                    resourceCurrent: 0,
                    resourceMax: 100,
                    level: 1,
                    xp: 0,
                    metaCurrency: 0,
                    affinityTags: [],
                },
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
                minimap,
            };
        }

        function tabLabels(container: HTMLElement): (string | undefined)[] {
            return Array.from(container.querySelectorAll(".tab-btn")).map((t) => t.textContent?.trim());
        }

        test("is offered while the server sends a map", () => {
            gameState.set(exploring(map));
            characterTab.set("equipment");
            const { container } = render(CharacterScreen);
            expect(tabLabels(container)).toEqual(["Equipment", "Settings", "Achievements", "Map", "Help", "Credits"]);
        });

        test("is not offered when there is no map, as in the Hub", () => {
            gameState.set(exploring(undefined));
            characterTab.set("equipment");
            const { container } = render(CharacterScreen);
            expect(tabLabels(container)).toEqual(["Equipment", "Settings", "Achievements", "Help", "Credits"]);
        });

        test("clicking it shows the map and marks it active", async () => {
            gameState.set(exploring(map));
            characterTab.set("equipment");
            const { container } = render(CharacterScreen);
            const mapTab = Array.from(container.querySelectorAll(".tab-btn")).find((t) => t.textContent?.trim() === "Map")!;
            await fireEvent.click(mapTab);

            expect(get(characterTab)).toBe("map");
            expect(container.querySelector(".tab-btn.active")?.textContent?.trim()).toBe("Map");
            expect(container.querySelectorAll("[data-node]")).toHaveLength(1);
            expect(container.querySelector(".equipment-panel")).toBeNull();
        });
    });

    test("the reduce-screen-shake checkbox is bound to the settings store", async () => {
        settings.set({ reduceScreenShake: false, sfxVolume: 70, musicVolume: 50, showHints: true });
        characterTab.set("settings");
        const { container } = render(CharacterScreen);
        const checkbox = container.querySelector('input[type="checkbox"]') as HTMLInputElement;
        expect(checkbox.checked).toBe(false);

        await fireEvent.click(checkbox);
        expect(get(settings).reduceScreenShake).toBe(true);
    });

    describe("hints", () => {
        beforeEach(() => {
            settings.update((s) => ({ ...s, showHints: true }));
            resetTutorial();
            characterTab.set("settings");
        });

        test("the Show hints checkbox is bound to the settings store", async () => {
            const { container } = render(CharacterScreen);
            const checkbox = container.querySelectorAll('input[type="checkbox"]')[1] as HTMLInputElement;
            expect(checkbox.checked).toBe(true);

            await fireEvent.click(checkbox);
            expect(get(settings).showHints).toBe(false);
        });

        test("the reset button makes the hints show again and says it is done", async () => {
            showHint("affinity");
            dismissHint();
            expect(get(tutorial).seen).toEqual(["affinity"]);

            const { container } = render(CharacterScreen);
            const button = container.querySelector(".reset-tutorial-btn")!;
            expect(button.textContent?.trim()).toBe("Reset");

            await fireEvent.click(button);
            expect(get(tutorial)).toEqual({ skipped: false, seen: [] });
            expect(button.textContent?.trim()).toBe("Done");
        });

        test("the button reads Reset again after leaving the Settings tab", async () => {
            const { container } = render(CharacterScreen);
            await fireEvent.click(container.querySelector(".reset-tutorial-btn")!);

            characterTab.set("equipment");
            await tick();
            characterTab.set("settings");
            await tick();
            expect(container.querySelector(".reset-tutorial-btn")?.textContent?.trim()).toBe("Reset");
        });

        test("the skip button marks the tutorial as skipped and then reads Skipped, disabled", async () => {
            const { container } = render(CharacterScreen);
            const button = container.querySelector(".skip-tutorial-btn") as HTMLButtonElement;
            expect(button.textContent?.trim()).toBe("Skip");
            expect(button.disabled).toBe(false);

            await fireEvent.click(button);

            expect(get(tutorial).skipped).toBe(true);
            expect(button.textContent?.trim()).toBe("Skipped");
            expect(button.disabled).toBe(true);
        });

        test("the skip button comes back after a reset", async () => {
            skipTutorial();
            const { container } = render(CharacterScreen);
            const button = container.querySelector(".skip-tutorial-btn") as HTMLButtonElement;
            expect(button.disabled).toBe(true);

            await fireEvent.click(container.querySelector(".reset-tutorial-btn")!);

            expect(button.disabled).toBe(false);
            expect(button.textContent?.trim()).toBe("Skip");
        });

        test("the Help tab shows the journal of the hints discovered", async () => {
            showHint("controls");
            dismissHint();
            characterTab.set("equipment");
            const { container } = render(CharacterScreen);
            const helpTab = Array.from(container.querySelectorAll(".tab-btn")).find((t) => t.textContent?.trim() === "Help")!;

            await fireEvent.click(helpTab);

            expect(get(characterTab)).toBe("help");
            expect(container.querySelector(".tab-btn.active")?.textContent?.trim()).toBe("Help");
            expect(container.querySelector(".entry-title")?.textContent).toBe("Moving around");
        });
    });
});
