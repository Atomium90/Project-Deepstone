import type { Direction } from "./protocol";
import type { CharacterTab } from "./CharacterStore";

/** What a key press asks the exploration screen to do. */
export type KeyCommand =
  | { kind: "move"; direction: Direction }
  | { kind: "interact" }
  | { kind: "debugRoom"; step: 1 | -1 }
  | { kind: "openTab"; tab: CharacterTab }
  | { kind: "closeOverlay" };

const MOVE_KEYS: Record<string, Direction> = {
  ArrowUp: "UP",
  ArrowDown: "DOWN",
  ArrowLeft: "LEFT",
  ArrowRight: "RIGHT",
  z: "UP",
  s: "DOWN",
  q: "LEFT",
  d: "RIGHT",
};

/** The Character screen tab a key opens, if any. The map key does nothing when there is no map. */
function tabForKey(key: string, mapAvailable: boolean): CharacterTab | null {
  if (key === "i" || key === "I") return "equipment";
  if ((key === "m" || key === "M") && mapAvailable) return "map";
  return null;
}

/** Turns a key press into a command, or null when the key does nothing right now.
 *
 * The Character screen's own keys work whether or not the screen is open: pressing one opens its
 * tab, and pressing the key of the tab that is already showing closes the screen. Every other key
 * is ignored while the screen is open, so the run is paused behind it: the player neither walks nor
 * interacts while looking at their equipment or the map.
 *
 * @param openTab The Character screen tab that is open, or null when it is closed.
 * @param mapAvailable Whether the server sent a map, which is what the map key needs.
 */
export function keyCommand(key: string, openTab: CharacterTab | null, mapAvailable: boolean): KeyCommand | null {
  const tab = tabForKey(key, mapAvailable);
  if (tab !== null) return openTab === tab ? { kind: "closeOverlay" } : { kind: "openTab", tab };

  if (openTab !== null) return null;

  if (key === "ç" || key === "à") return { kind: "debugRoom", step: key === "à" ? 1 : -1 };
  if (key === "e" || key === "E") return { kind: "interact" };

  const direction = MOVE_KEYS[key];
  return direction ? { kind: "move", direction } : null;
}
