import { writable, derived, type Readable } from "svelte/store";
import type { StateUpdate } from "./protocol";
import { GameClient } from "./GameClient";

/** How many equipped weapon/armor/accessory pieces carry each setId - the same counting rule as
 * the server's `Player.equippedSetIds`/`SetDef.activeBonuses` (potion belt and keys don't count).
 * Purely a display concern: the client never decides whether a set bonus is mechanically active,
 * it only mirrors the count so tooltips/badges can show 2pc/4pc progress locally. */
function countEquippedSets(equipment: StateUpdate["equipment"] | undefined): Record<string, number> {
  const counts: Record<string, number> = {};
  if (!equipment) return counts;
  const pieces = [equipment.weapon, equipment.armor, ...equipment.accessories];
  for (const piece of pieces) {
    if (piece?.setId) counts[piece.setId] = (counts[piece.setId] ?? 0) + 1;
  }
  return counts;
}

// ---------------------------------------------
// Core store
// ---------------------------------------------

/** The latest StateUpdate received from the server. Null before first connection. */
export const gameState = writable<StateUpdate | null>(null);

/** Dev tooling only: `StateUpdate.debugRooms` is only populated while in the hub (not worth a
 * directory read on every mid-run action - see GameSession.withDebugRooms), so it's cached here
 * on the way through rather than re-read live - lets ExplorationHUD's next/prev debug-room
 * shortcut still know the full list after leaving the hub. */
export const debugRoomsCache = writable<string[]>([]);
gameState.subscribe(($s) => {
    if ($s && $s.debugRooms.length > 0) debugRoomsCache.set($s.debugRooms);
});

/** Exposes the live state on `window` for e2e tests (Playwright) to poll directly - the same
 * value every derived store and component reacts to, read the same way the UI itself does.
 * Deliberately not gated behind an env flag: it's a read-only mirror nothing in production code
 * consumes, and relying on WebSocket frame interception at the network level instead (the first
 * approach tried) proved unreliable - it doesn't guarantee observing state in the same order or
 * timing as the page's own reactivity. */
if (typeof window !== "undefined") {
    gameState.subscribe((s) => {
        (window as unknown as { __DEEPSTONE_STATE__?: StateUpdate | null }).__DEEPSTONE_STATE__ = s;
    });
}

/** Derived convenience: current game phase (or null if not yet connected). */
export const gamePhase = derived(gameState, ($s) => $s?.phase ?? null);

/** How many lines each log keeps: enough to still read what a pickup said after the next step, not a
 * history of the run. */
export const LOG_HISTORY_SIZE = 10;

const fightLog = writable<string[]>([]);
const walkLog = writable<string[]>([]);
let phaseBefore: StateUpdate["phase"] | null = null;

/** The latest `LOG_HISTORY_SIZE` lines once `added` follow `lines`. */
const keepLatest = (lines: string[], added: string[]): string[] => [...lines, ...added].slice(-LOG_HISTORY_SIZE);

// Every update carries only the lines of its own action, so the two logs are built here from the
// updates that go by. The log of a fight tells that fight only. The log of the exploration is kept
// from fight to fight and tells everything but the blows: the fight that starts, the enemy fallen and
// what the victory brought.
gameState.subscribe(($s) => {
    if ($s === null) {
        phaseBefore = null;
        fightLog.set([]);
        walkLog.set([]);
        return;
    }
    const before = phaseBefore;
    phaseBefore = $s.phase;

    if ($s.phase === "COMBAT") {
        const fightBegins = before !== "COMBAT";
        // A fight starts from scratch, with the line that tells it began, which the exploration keeps too.
        fightLog.update((lines) => keepLatest(fightBegins ? [] : lines, $s.log));
        if (fightBegins) walkLog.update((lines) => keepLatest(lines, $s.log));
    } else if ($s.phase === "EXPLORATION") {
        // The update that ends a won fight starts with the blows, which belong to the fight alone.
        const outcome = before === "COMBAT" ? $s.log.slice($s.fightLines ?? 0) : $s.log;
        // A run that begins starts with a clean log, whatever the last one ended on.
        walkLog.update((lines) => keepLatest(before === "HUB" ? [] : lines, outcome));
    }
});

/** The log of the fight going on, `LOG_HISTORY_SIZE` lines at most, from its first line. Drives the
 * log of CombatScreen. */
export const combatLog: Readable<string[]> = { subscribe: fightLog.subscribe };

/** The log of the exploration, `LOG_HISTORY_SIZE` lines at most, kept from one fight to the next and
 * fed by what happens outside the fights, plus the fight that begins and what its victory brings.
 * Cleared when a run begins and when the connection is lost. Drives the log of ExplorationHUD. */
export const explorationLog: Readable<string[]> = { subscribe: walkLog.subscribe };

/** Derived convenience: achievements newly earned by the most recent action, if any - cleared back
 * to [] on the next update that doesn't earn a new one. Drives AchievementToast.svelte. */
export const achievementToast = derived(gameState, ($s) => $s?.newlyUnlocked ?? []);

/** Derived convenience: damage/heal events produced by the most recent action, if any - same
 * transient convention as `achievementToast`. Drives the floating numbers/hit flash/screen shake
 * in CombatScreen.svelte. */
export const combatDamageEvents = derived(gameState, ($s) => $s?.damageEvents ?? []);

/** Derived convenience: sound cue tags produced by the most recent action, if any - same
 * transient convention as `combatDamageEvents`. Consumed by AudioManager.ts. */
export const soundEvents = derived(gameState, ($s) => $s?.soundEvents ?? []);

/** Derived convenience: a pickup awaiting a keep/replace decision, or null. Unlike
 * the transient fields above, this one is durable server-side (still present on the
 * next update until actually resolved), so it needs no extra latch/dedup logic here - it's a
 * direct reflection of server state, not a one-shot signal. Drives EquipChoiceModal.svelte. */
export const pendingEquipChoice = derived(gameState, ($s) => $s?.pendingEquipChoice ?? null);

/** Derived convenience: a Shrine's 3 rolled reward candidates awaiting a pick, or null. Same
 * durable-state discipline as `pendingEquipChoice` above. Drives RewardChoiceModal.svelte. */
export const pendingRewardChoice = derived(gameState, ($s) => $s?.pendingRewardChoice ?? null);

/** Derived convenience: the conversation with a Rest NPC awaiting its end, or null. Durable
 * server-side like `pendingEquipChoice`, so a direct reflection of server state. Drives
 * SceneDialogue.svelte. */
export const pendingScene = derived(gameState, ($s) => $s?.pendingScene ?? null);

/** Derived convenience: the question of the light awaiting an answer, or null. Durable server-side
 * like `pendingScene`, so a direct reflection of server state. Drives EndingChoiceModal.svelte. */
export const pendingEndingChoice = derived(gameState, ($s) => $s?.pendingEndingChoice ?? null);

/** Derived convenience: the story card waiting to be shown, or null. A direct reflection of server
 * state, durable like `pendingScene`. Drives CardScreen.svelte. */
export const pendingCard = derived(gameState, ($s) => $s?.pendingCard ?? null);

/** Derived convenience: the line the god's voice says on the latest update, or null. Transient like
 * `soundEvents`: it is only there on the update of the moment it belongs to. Drives GodLineBanner
 * (the start of a run) and GameOverScreen (a death). */
export const godLine = derived(gameState, ($s) => $s?.godLine ?? null);

/** Derived convenience: the dungeon map while exploring a run, or null. A direct reflection of
 * server state like `pendingEquipChoice`, resent on every update. Drives MinimapPanel.svelte. */
export const minimap = derived(gameState, ($s) => $s?.minimap ?? null);

/** Derived convenience: how many equipped pieces carry each setId, keyed by setId. Drives the
 * set-bonus progress shown in ItemTooltip.svelte and the active-set badges in
 * EquipmentPanel.svelte. See `countEquippedSets` above for the counting rule. */
export const equippedSetCounts = derived(gameState, ($s) => countEquippedSets($s?.equipment));

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