import { writable, derived } from "svelte/store";
import type { StateUpdate, GamePhase } from "./protocol";
import { GameClient } from "./GameClient";

// ---------------------------------------------
// Core store
// ---------------------------------------------

/** The latest StateUpdate received from the server. Null before first connection. */
export const gameState = writable<StateUpdate | null>(null);

/** Derived convenience: current game phase (or null if not yet connected). */
export const gamePhase = derived(gameState, ($s) => $s?.phase ?? null);

/** Derived convenience: combat log lines for the current state. */
export const combatLog = derived(gameState, ($s) => $s?.log ?? []);

/** Derived convenience: the current NPC dialogue line to show, or null. Only present on the
 * StateUpdate an NPC interaction produced - any other action clears it back to null. */
export const npcDialogue = derived(gameState, ($s) => $s?.dialogue ?? null);

/** Derived convenience: achievements newly earned by the most recent action, if any - cleared back
 * to [] on the next update that doesn't earn a new one. Drives AchievementToast.svelte. */
export const achievementToast = derived(gameState, ($s) => $s?.newlyUnlocked ?? []);

// ---------------------------------------------
// Client singleton
// ---------------------------------------------

/** The singleton GameClient used throughout the app. */
export const client = new GameClient();

/** Connect to the server and wire incoming updates into the Svelte store.
 *
 * Call this once from the root App component on mount.
 */
export function connectToServer(): void {
  client.onStateUpdate((update) => {
    gameState.set(update);
  });

  client.onError(() => {
    console.error("[StateStore] Connection error — retrying in 3s...");
    setTimeout(connectToServer, 3000);
  });

  client.connect();
}