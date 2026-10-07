import { derived } from "svelte/store";
import { gameState } from "./StateStore";
import { tutorial } from "./HintStore";

/** True while the player is on a brand-new save and has not skipped the tutorial: the Hub gives
 * way to the start screen, and the first run is launched straight from it. The server decides
 * what a new save is (no run finished yet, `HubView.runsCompleted`), so a returning player on a
 * fresh browser is never mistaken for one. Skip is the only client-side part. */
export const isNewSave = derived(
    [gameState, tutorial],
    ([$gameState, $tutorial]) => $gameState?.hub?.runsCompleted === 0 && !$tutorial.skipped,
);
