import { derived, readable, writable } from "svelte/store";
import { DEFAULT_BINDINGS, keyTexts, type Bindings, type LayoutMap } from "./keybindings";

/** The keys the game reads while exploring. */
export const bindings = readable<Bindings>(DEFAULT_BINDINGS);

/** What the keys type on this keyboard, once the browser has said (Chromium does, Firefox and Safari
 * do not): lets the texts say "Z" for the key that is W on QWERTY on an AZERTY keyboard. */
export const layoutMap = writable<LayoutMap | null>(null);

/** The key names texts speak of (`{move}`, `{interact}`, `{equipment}`, `{map}`), for any text that
 * names a key: `$t("hint.controls.body", $keyNames)`. */
export const keyNames = derived([bindings, layoutMap], ([$bindings, $layoutMap]) => keyTexts($bindings, $layoutMap));

/** Asks the browser which character each key place types. Nothing happens where it cannot. */
export async function loadLayoutMap(): Promise<void> {
  try {
    const keyboard = (navigator as unknown as { keyboard?: { getLayoutMap?: () => Promise<LayoutMap> } }).keyboard;
    const map = await keyboard?.getLayoutMap?.();
    if (map) layoutMap.set(map);
  } catch {
    // Not offered, or refused: the texts name the keys by their QWERTY places.
  }
}

if (typeof navigator !== "undefined") void loadLayoutMap();
