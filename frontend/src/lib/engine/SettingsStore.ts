import { writable } from "svelte/store";
import { availableLanguages, detectLanguage, language } from "./i18n";

/** Client-only preferences, persisted to localStorage. The first store in the codebase that
 * isn't derived from server state - everything else in StateStore.ts is a projection of
 * gameState. */
export interface Settings {
  reduceScreenShake: boolean;
  /** 0-100. */
  sfxVolume: number;
  /** 0-100. */
  musicVolume: number;
  /** Whether the first-time hints (see HintStore.ts) are shown at all. */
  showHints: boolean;
  /** The language code shown (a file of src/lang). Detected from the browser the first time. */
  language: string;
}

const STORAGE_KEY = "deepstone-settings";

const DEFAULTS: Omit<Settings, "language"> = {
  reduceScreenShake: false,
  sfxVolume: 70,
  musicVolume: 50,
  showHints: true,
};

/** The browser's preferred languages, or none where there is no navigator (never in a browser). */
function browserLanguages(): readonly string[] {
  if (typeof navigator === "undefined") return [];
  return navigator.languages?.length ? navigator.languages : [navigator.language];
}

function loadSettings(): Settings {
  const available = availableLanguages();
  const detected = detectLanguage(available, browserLanguages());
  try {
    const raw = localStorage.getItem(STORAGE_KEY);
    if (raw) {
      const merged = { ...DEFAULTS, language: detected, ...JSON.parse(raw) } as Settings;
      // A stored language whose file is gone (or a hand-edited value) must not leave the game
      // showing keys.
      return available.includes(merged.language) ? merged : { ...merged, language: detected };
    }
  } catch {
    // Corrupt/inaccessible storage -> fall through to defaults.
  }
  return { ...DEFAULTS, language: detected };
}

export const settings = writable<Settings>(loadSettings());

settings.subscribe((s) => {
  language.set(s.language);
  if (typeof document !== "undefined") document.documentElement.lang = s.language;
  try {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(s));
  } catch {
    // Storage full/unavailable (e.g. private browsing) -> setting still works for the session.
  }
});
