import type { Direction } from "./protocol";
import type { CharacterTab } from "./CharacterStore";
import { actionOf, directionOf, type Bindings, type KeyPress } from "./keybindings";

/** What a key press asks the exploration screen to do. */
export type KeyCommand =
  | { kind: "move"; direction: Direction }
  | { kind: "interact" }
  | { kind: "debugRoom"; step: 1 | -1 }
  | { kind: "openTab"; tab: CharacterTab }
  | { kind: "closeOverlay" };

/** Turns a key press into a command, or null when the key does nothing right now.
 *
 * The Character screen's own keys work whether or not the screen is open: pressing one opens its
 * tab, and pressing the key of the tab that is already showing closes the screen. Every other key
 * is ignored while the screen is open, so the run is paused behind it: the player neither walks nor
 * interacts while looking at their equipment or the map.
 *
 * @param press The key pressed: the character it types and its place on the keyboard.
 * @param bindings Which key does what (see keybindings.ts); the arrow keys always move.
 * @param openTab The Character screen tab that is open, or null when it is closed.
 * @param mapAvailable Whether the server sent a map, which is what the map key needs.
 * @param conversationPending Whether a conversation with a Rest NPC is waiting to be read to its end.
 * Walking and interacting do nothing meanwhile (the server refuses them too), and the keys that
 * advance the conversation belong to the conversation itself.
 */
export function keyCommand(
  press: KeyPress,
  bindings: Bindings,
  openTab: CharacterTab | null,
  mapAvailable: boolean,
  conversationPending = false,
): KeyCommand | null {
  const action = actionOf(press, bindings);

  // The map key does nothing when there is no map, and then is no more than any other unbound key.
  const tab: CharacterTab | null = action === "equipment" ? "equipment" : action === "map" && mapAvailable ? "map" : null;
  if (tab !== null) return openTab === tab ? { kind: "closeOverlay" } : { kind: "openTab", tab };

  if (openTab !== null || conversationPending) return null;

  // Dev tooling, tied to the characters of the keys it was written on.
  if (press.key === "ç" || press.key === "à") return { kind: "debugRoom", step: press.key === "à" ? 1 : -1 };
  if (action === "interact") return { kind: "interact" };

  const direction = action === null ? null : directionOf(action);
  return direction ? { kind: "move", direction } : null;
}
