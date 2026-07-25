import { writable } from "svelte/store";

/** Client-only preferences, persisted to localStorage. The first store in the codebase that
 * isn't derived from server state - everything else in StateStore.ts is a projection of
 * gameState. */
export interface Settings {
  reduceScreenShake: boolean;
}

const STORAGE_KEY = "deepstone-settings";

function loadSettings(): Settings {
  try {
    const raw = localStorage.getItem(STORAGE_KEY);
    if (raw) return { reduceScreenShake: false, ...JSON.parse(raw) };
  } catch {
    // Corrupt/inaccessible storage -> fall through to defaults.
  }
  return { reduceScreenShake: false };
}

export const settings = writable<Settings>(loadSettings());

settings.subscribe((s) => {
  try {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(s));
  } catch {
    // Storage full/unavailable (e.g. private browsing) -> setting still works for the session.
  }
});
