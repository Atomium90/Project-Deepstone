import { describe, test, expect, beforeEach, vi } from "vitest";
import { get } from "svelte/store";

const STORAGE_KEY = "deepstone-settings";
const DEFAULTS = { reduceScreenShake: false, sfxVolume: 70, musicVolume: 50 };

/** loadSettings() runs once at module import time, reading whatever is in localStorage at that
 * moment - so each scenario needs a fresh module instance (vi.resetModules) after seeding storage,
 * not just a fresh store value. */
describe("SettingsStore", () => {
    beforeEach(() => {
        localStorage.clear();
        vi.resetModules();
    });

    test("defaults when localStorage is empty", async () => {
        const { settings } = await import("./SettingsStore");
        expect(get(settings)).toEqual(DEFAULTS);
    });

    test("merges a partially-stored settings object onto the defaults", async () => {
        localStorage.setItem(STORAGE_KEY, JSON.stringify({ sfxVolume: 20 }));
        const { settings } = await import("./SettingsStore");
        expect(get(settings)).toEqual({ ...DEFAULTS, sfxVolume: 20 });
    });

    test("falls back to defaults when localStorage holds corrupt JSON", async () => {
        localStorage.setItem(STORAGE_KEY, "{not valid json");
        const { settings } = await import("./SettingsStore");
        expect(get(settings)).toEqual(DEFAULTS);
    });

    test("persists updates back to localStorage", async () => {
        const { settings } = await import("./SettingsStore");
        const updated = { reduceScreenShake: true, sfxVolume: 10, musicVolume: 90 };
        settings.set(updated);
        expect(JSON.parse(localStorage.getItem(STORAGE_KEY)!)).toEqual(updated);
    });
});
