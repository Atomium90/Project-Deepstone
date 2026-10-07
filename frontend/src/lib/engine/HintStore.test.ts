import { describe, test, expect, beforeEach, vi } from "vitest";
import { get } from "svelte/store";

const STORAGE_KEY = "deepstone-tutorial";

function stored(): unknown {
    return JSON.parse(localStorage.getItem(STORAGE_KEY)!);
}

/** The state is read from localStorage once, at module import time, so each scenario seeds
 * storage first and then loads fresh module instances (vi.resetModules). HintStore reads the
 * Settings store, so both are imported after the reset to share one registry. */
async function load() {
    const hints = await import("./HintStore");
    const { settings } = await import("./SettingsStore");
    return { ...hints, settings };
}

describe("HintStore", () => {
    beforeEach(() => {
        localStorage.clear();
        vi.resetModules();
    });

    describe("loading", () => {
        test("starts empty when localStorage is empty", async () => {
            const { tutorial, activeHint } = await load();
            expect(get(tutorial)).toEqual({ skipped: false, seen: [] });
            expect(get(activeHint)).toBeNull();
        });

        test("restores what was stored", async () => {
            localStorage.setItem(STORAGE_KEY, JSON.stringify({ skipped: true, seen: ["a", "b"] }));
            const { tutorial } = await load();
            expect(get(tutorial)).toEqual({ skipped: true, seen: ["a", "b"] });
        });

        test("ignores stored values of the wrong shape", async () => {
            localStorage.setItem(STORAGE_KEY, JSON.stringify({ skipped: "yes", seen: ["a", 7, null] }));
            const { tutorial } = await load();
            expect(get(tutorial)).toEqual({ skipped: false, seen: ["a"] });
        });

        test("falls back to empty when the stored value is not a list", async () => {
            localStorage.setItem(STORAGE_KEY, JSON.stringify({ seen: "a" }));
            const { tutorial } = await load();
            expect(get(tutorial)).toEqual({ skipped: false, seen: [] });
        });

        test("falls back to empty when localStorage holds corrupt JSON", async () => {
            localStorage.setItem(STORAGE_KEY, "{not valid json");
            const { tutorial } = await load();
            expect(get(tutorial)).toEqual({ skipped: false, seen: [] });
        });
    });

    describe("showing and dismissing", () => {
        test("a requested hint becomes the active one", async () => {
            const { showHint, activeHint } = await load();
            showHint("affinity");
            expect(get(activeHint)).toBe("affinity");
        });

        test("a second hint waits until the first is dismissed", async () => {
            const { showHint, dismissHint, activeHint } = await load();
            showHint("affinity");
            showHint("rarity");
            expect(get(activeHint)).toBe("affinity");

            dismissHint();
            expect(get(activeHint)).toBe("rarity");

            dismissHint();
            expect(get(activeHint)).toBeNull();
        });

        test("asking twice for the same hint queues it once", async () => {
            const { showHint, dismissHint, activeHint } = await load();
            showHint("affinity");
            showHint("affinity");
            dismissHint();
            expect(get(activeHint)).toBeNull();
        });

        test("dismissing records the hint as seen, in storage too", async () => {
            const { showHint, dismissHint, tutorial } = await load();
            showHint("affinity");
            dismissHint();
            expect(get(tutorial).seen).toEqual(["affinity"]);
            expect(stored()).toEqual({ skipped: false, seen: ["affinity"] });
        });

        test("a hint is not recorded as seen before it is dismissed", async () => {
            const { showHint, tutorial } = await load();
            showHint("affinity");
            expect(get(tutorial).seen).toEqual([]);
        });

        test("a seen hint is never shown again", async () => {
            const { showHint, dismissHint, activeHint } = await load();
            showHint("affinity");
            dismissHint();
            showHint("affinity");
            expect(get(activeHint)).toBeNull();
        });

        test("a hint seen in an earlier session is not shown", async () => {
            localStorage.setItem(STORAGE_KEY, JSON.stringify({ skipped: false, seen: ["affinity"] }));
            const { showHint, activeHint } = await load();
            showHint("affinity");
            expect(get(activeHint)).toBeNull();
        });

        test("dismissing with nothing on screen does nothing", async () => {
            const { dismissHint, tutorial } = await load();
            dismissHint();
            expect(get(tutorial)).toEqual({ skipped: false, seen: [] });
        });

        test("isHintSeen reads the seen list and the skipped flag", async () => {
            const { isHintSeen } = await load();
            expect(isHintSeen({ skipped: false, seen: ["a"] }, "a")).toBe(true);
            expect(isHintSeen({ skipped: false, seen: ["a"] }, "b")).toBe(false);
            expect(isHintSeen({ skipped: true, seen: [] }, "b")).toBe(true);
        });

        test("keeps working for the session when localStorage can't be written to", async () => {
            const { showHint, dismissHint, tutorial } = await load();
            const setItemSpy = vi.spyOn(Storage.prototype, "setItem").mockImplementation(() => {
                throw new Error("QuotaExceededError");
            });
            showHint("affinity");
            expect(() => dismissHint()).not.toThrow();
            expect(get(tutorial).seen).toEqual(["affinity"]);
            setItemSpy.mockRestore();
        });
    });

    describe("the Show hints setting", () => {
        test("nothing is shown while hints are off", async () => {
            const { showHint, activeHint, settings } = await load();
            settings.update((s) => ({ ...s, showHints: false }));
            showHint("affinity");
            expect(get(activeHint)).toBeNull();
        });

        test("a hint asked for while hints are off is not recorded as seen", async () => {
            const { showHint, tutorial, settings } = await load();
            settings.update((s) => ({ ...s, showHints: false }));
            showHint("affinity");
            expect(get(tutorial).seen).toEqual([]);
        });

        test("turning hints off takes down the card on screen and the ones waiting", async () => {
            const { showHint, activeHint, tutorial, settings } = await load();
            showHint("affinity");
            showHint("rarity");
            settings.update((s) => ({ ...s, showHints: false }));
            expect(get(activeHint)).toBeNull();
            expect(get(tutorial).seen).toEqual([]);
        });

        test("hints show again once switched back on", async () => {
            const { showHint, activeHint, settings } = await load();
            settings.update((s) => ({ ...s, showHints: false }));
            settings.update((s) => ({ ...s, showHints: true }));
            showHint("affinity");
            expect(get(activeHint)).toBe("affinity");
        });
    });

    describe("skip and reset", () => {
        test("skipping takes down the card on screen and records the skip", async () => {
            const { showHint, skipTutorial, activeHint } = await load();
            showHint("affinity");
            skipTutorial();
            expect(get(activeHint)).toBeNull();
            expect(stored()).toEqual({ skipped: true, seen: [] });
        });

        test("after skipping, no hint is shown, including ones never seen before", async () => {
            const { showHint, skipTutorial, activeHint } = await load();
            skipTutorial();
            showHint("a-hint-added-in-a-later-version");
            expect(get(activeHint)).toBeNull();
        });

        test("resetting forgets what was seen and the skip", async () => {
            const { showHint, dismissHint, skipTutorial, resetTutorial, tutorial } = await load();
            showHint("affinity");
            dismissHint();
            skipTutorial();
            resetTutorial();
            expect(get(tutorial)).toEqual({ skipped: false, seen: [] });
            expect(stored()).toEqual({ skipped: false, seen: [] });
        });

        test("after a reset the hints show again", async () => {
            const { showHint, dismissHint, resetTutorial, activeHint } = await load();
            showHint("affinity");
            dismissHint();
            resetTutorial();
            showHint("affinity");
            expect(get(activeHint)).toBe("affinity");
        });

        test("resetting takes down the card on screen", async () => {
            const { showHint, resetTutorial, activeHint } = await load();
            showHint("affinity");
            resetTutorial();
            expect(get(activeHint)).toBeNull();
        });
    });
});
