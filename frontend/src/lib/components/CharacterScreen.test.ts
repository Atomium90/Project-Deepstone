import { describe, test, expect, beforeEach } from "vitest";
import { render, fireEvent } from "@testing-library/svelte";
import { get } from "svelte/store";
import CharacterScreen from "./CharacterScreen.svelte";
import { characterTab } from "../engine/CharacterStore";
import { settings } from "../engine/SettingsStore";
import { gameState } from "../engine/StateStore";

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

    test("the reduce-screen-shake checkbox is bound to the settings store", async () => {
        settings.set({ reduceScreenShake: false, sfxVolume: 70, musicVolume: 50 });
        characterTab.set("settings");
        const { container } = render(CharacterScreen);
        const checkbox = container.querySelector('input[type="checkbox"]') as HTMLInputElement;
        expect(checkbox.checked).toBe(false);

        await fireEvent.click(checkbox);
        expect(get(settings).reduceScreenShake).toBe(true);
    });
});
