import { writable } from "svelte/store";

/** Which tab of the Character screen is open, or null when the screen is closed. Ephemeral -
 * unlike SettingsStore, this is never persisted, always starts closed on load. */
export type CharacterTab = "settings" | "achievements";

export const characterTab = writable<CharacterTab | null>(null);
