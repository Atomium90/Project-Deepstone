import { describe, test, expect, beforeEach } from "vitest";
import { render, fireEvent } from "@testing-library/svelte";
import { get } from "svelte/store";
import CharacterScreen from "./CharacterScreen.svelte";
import { characterTab } from "../engine/CharacterStore";
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
        expect(container.querySelectorAll(".setting-row").length).toBe(3);
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
            expect(tabLabels(container)).toEqual(["Equipment", "Settings", "Achievements", "Map"]);
        });

        test("is not offered when there is no map, as in the Hub", () => {
            gameState.set(exploring(undefined));
            characterTab.set("equipment");
            const { container } = render(CharacterScreen);
            expect(tabLabels(container)).toEqual(["Equipment", "Settings", "Achievements"]);
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
});
