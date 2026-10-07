import { derived, get, writable, type Readable } from "svelte/store";
import { settings } from "./SettingsStore";

/** What the player has already been told, persisted to localStorage. Client-only on purpose: it
 * only decides which hint cards show, never what the game allows (the staged Hub is derived from
 * the server save instead). Losing it just replays the hints, no progress is lost. */
export interface TutorialState {
    /** Skip was pressed: every hint, including ones added later, counts as seen. */
    skipped: boolean;
    /** Ids of the hints dismissed so far, in the order they were discovered. */
    seen: string[];
}

const STORAGE_KEY = "deepstone-tutorial";

const DEFAULTS: TutorialState = { skipped: false, seen: [] };

function loadTutorial(): TutorialState {
    try {
        const raw = localStorage.getItem(STORAGE_KEY);
        if (raw) {
            const stored = JSON.parse(raw);
            return {
                skipped: stored.skipped === true,
                seen: Array.isArray(stored.seen) ? stored.seen.filter((id: unknown) => typeof id === "string") : [],
            };
        }
    } catch {
        // Corrupt/inaccessible storage -> fall through to defaults.
    }
    return { ...DEFAULTS, seen: [] };
}

export const tutorial = writable<TutorialState>(loadTutorial());

tutorial.subscribe((state) => {
    try {
        localStorage.setItem(STORAGE_KEY, JSON.stringify(state));
    } catch {
        // Storage full/unavailable (e.g. private browsing) -> still works for the session.
    }
});

/** Hints waiting to be shown. The first one is on screen, the others wait for it to be dismissed
 * so several hints triggered by the same event never pile up on top of each other. */
const queue = writable<string[]>([]);

/** The id of the hint card to show right now, or null. */
export const activeHint: Readable<string | null> = derived(queue, ($queue) => $queue[0] ?? null);

/** Turning hints off in Settings also takes down the card that is on screen. */
settings.subscribe(($settings) => {
    if (!$settings.showHints) queue.set([]);
});

/** True once the hint was dismissed, or for every hint after Skip. */
export function isHintSeen(state: TutorialState, id: string): boolean {
    return state.skipped || state.seen.includes(id);
}

/** Asks for a hint to be shown. Does nothing if it was already seen, is already waiting, or
 * hints are switched off in Settings. A hint that is ignored while hints are off stays unseen. */
export function showHint(id: string): void {
    if (!get(settings).showHints || isHintSeen(get(tutorial), id)) return;
    queue.update(($queue) => ($queue.includes(id) ? $queue : [...$queue, id]));
}

/** Closes the hint card on screen and records it as seen, then the next waiting hint, if any,
 * takes its place. */
export function dismissHint(): void {
    const id = get(activeHint);
    if (id === null) return;
    queue.update(($queue) => $queue.slice(1));
    tutorial.update((state) => ({ ...state, seen: [...state.seen, id] }));
}

/** Marks the whole tutorial as done: nothing is shown again, including hints added later. */
export function skipTutorial(): void {
    queue.set([]);
    tutorial.update((state) => ({ ...state, skipped: true }));
}

/** Forgets everything, so the hints are shown again the next time each one comes up. */
export function resetTutorial(): void {
    queue.set([]);
    tutorial.set({ ...DEFAULTS, seen: [] });
}
