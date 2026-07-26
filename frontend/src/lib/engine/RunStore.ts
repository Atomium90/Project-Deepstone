import { writable } from "svelte/store";
import type { Difficulty } from "./protocol";

/** The difficulty last sent in a STARTRUN action. The server never echoes difficulty back on
 * StateUpdate, so this is the only way the client knows which exploration music pool to use -
 * set by HubScreen.startRun(), read by AudioManager. Stays at its last value across the run
 * (not reset on ReturnToHub) since it remains correct until the next StartRun overwrites it. */
export const lastStartedDifficulty = writable<Difficulty>("normal");
