import type { Direction } from "./protocol";

/** What a player can ask the game to do with a key while exploring. */
export type KeyAction = "moveUp" | "moveDown" | "moveLeft" | "moveRight" | "interact" | "equipment" | "map";

export const KEY_ACTIONS: readonly KeyAction[] = ["moveUp", "moveDown", "moveLeft", "moveRight", "interact", "equipment", "map"];

/** What a key press carries that a binding can match: the character it types (`key`) and the key's
 * place on the keyboard (`code`). */
export interface KeyPress {
  key: string;
  code: string;
}

/**
 * The key an action is on. A `code` is a place on the keyboard, the same whatever the layout: W, A, S
 * and D are the key block under the left hand on AZERTY (where they type Z, Q, S, D) as on QWERTY. A
 * `key` is a character, for the keys whose letter means something (E, I, M), since the place of M
 * differs between layouts.
 */
export type Binding = { code: string } | { key: string };

export type Bindings = Record<KeyAction, Binding>;

export const DEFAULT_BINDINGS: Bindings = {
  moveUp: { code: "KeyW" },
  moveDown: { code: "KeyS" },
  moveLeft: { code: "KeyA" },
  moveRight: { code: "KeyD" },
  interact: { key: "e" },
  equipment: { key: "i" },
  map: { key: "m" },
};

/** The keys a player has set, by action: the place of the key (`KeyboardEvent.code`). An action with
 * no entry is on its default key. */
export type KeyOverrides = Partial<Record<KeyAction, string>>;

/** Punctuation keys a player can bind, besides the letters. Digits stay for the choices of fights and
 * windows, and Space, Enter, Escape, Tab, the arrows and the modifiers belong to the game. */
const BINDABLE_PUNCTUATION = new Set([
  "Backquote",
  "Minus",
  "Equal",
  "BracketLeft",
  "BracketRight",
  "Backslash",
  "Semicolon",
  "Quote",
  "Comma",
  "Period",
  "Slash",
]);

/** Whether a key place can be given to an action. */
export function isBindableCode(code: string): boolean {
  return /^Key[A-Z]$/.test(code) || BINDABLE_PUNCTUATION.has(code);
}

/** The action, other than `except`, that a key press is already on, or null when it is free. */
export function takenBy(press: KeyPress, except: KeyAction, bindings: Bindings): KeyAction | null {
  return KEY_ACTIONS.find((action) => action !== except && pressMatches(bindings[action], press)) ?? null;
}

/** The bindings in use: the defaults, with the keys the player has set in place of theirs. */
export function resolveBindings(overrides: KeyOverrides): Bindings {
  const resolved: Bindings = { ...DEFAULT_BINDINGS };
  for (const action of KEY_ACTIONS) {
    const code = overrides[action];
    if (code !== undefined) resolved[action] = { code };
  }
  return resolved;
}

/**
 * The keys a stored value really sets: an unknown action, a key that cannot be bound, or a key an
 * action before it already has is left out (a hand-edited or older save must never leave an unknown
 * key behind or two actions on one place).
 */
export function sanitizeOverrides(raw: unknown): KeyOverrides {
  if (typeof raw !== "object" || raw === null) return {};
  const stored = raw as Record<string, unknown>;
  const kept: KeyOverrides = {};
  for (const action of KEY_ACTIONS) {
    const code = stored[action];
    if (typeof code !== "string" || !isBindableCode(code)) continue;
    const bindings = resolveBindings(kept);
    // Only a key place held by another action is checked: a letter binding (E, I, M) is told apart by the
    // character, which a stored place does not carry. The window that sets keys checks the real press.
    if (takenBy({ key: "", code }, action, bindings) !== null) continue;
    kept[action] = code;
  }
  return kept;
}

/** The arrow keys always move, whatever the bindings are. */
const ARROWS: Record<string, KeyAction> = {
  ArrowUp: "moveUp",
  ArrowDown: "moveDown",
  ArrowLeft: "moveLeft",
  ArrowRight: "moveRight",
};

const DIRECTIONS: Partial<Record<KeyAction, Direction>> = {
  moveUp: "UP",
  moveDown: "DOWN",
  moveLeft: "LEFT",
  moveRight: "RIGHT",
};

/** The direction a movement action goes in, null for any other action. */
export function directionOf(action: KeyAction): Direction | null {
  return DIRECTIONS[action] ?? null;
}

export function pressMatches(binding: Binding, press: KeyPress): boolean {
  if ("code" in binding) return press.code !== "" && press.code === binding.code;
  return press.key.toLowerCase() === binding.key;
}

/** The action a key press asks for, or null when the key is bound to nothing. */
export function actionOf(press: KeyPress, bindings: Bindings): KeyAction | null {
  const arrow = ARROWS[press.code] ?? ARROWS[press.key];
  if (arrow !== undefined) return arrow;
  return KEY_ACTIONS.find((action) => pressMatches(bindings[action], press)) ?? null;
}

/** Whether a key moves on a conversation or a card: the interact key, Space or Enter. */
export function isAdvanceKey(press: KeyPress, bindings: Bindings): boolean {
  return press.key === " " || press.key === "Enter" || actionOf(press, bindings) === "interact";
}

/** The characters the browser says the keys type, by key place (`KeyboardLayoutMap`), where it can. */
export type LayoutMap = ReadonlyMap<string, string>;

function fallbackLabel(code: string): string {
  if (code.startsWith("Key")) return code.slice(3);
  if (code.startsWith("Digit")) return code.slice(5);
  const arrows: Record<string, string> = { ArrowUp: "↑", ArrowDown: "↓", ArrowLeft: "←", ArrowRight: "→" };
  return arrows[code] ?? code;
}

/** What to call a binding on screen: "E", or the character the key types on this layout ("Z" for the
 * key that is W on QWERTY, on an AZERTY keyboard). */
export function bindingLabel(binding: Binding, layout: LayoutMap | null): string {
  if ("key" in binding) return binding.key.toUpperCase();
  return (layout?.get(binding.code) ?? fallbackLabel(binding.code)).toUpperCase();
}

/** The four movement keys in the order a player reads them (up, left, down, right: WASD, ZQSD). */
export function moveLabel(bindings: Bindings, layout: LayoutMap | null): string {
  const labels = (["moveUp", "moveLeft", "moveDown", "moveRight"] as const).map((action) =>
    bindingLabel(bindings[action], layout),
  );
  return labels.every((label) => label.length === 1) ? labels.join("") : labels.join(" ");
}

/** The key names the texts speak of, under the parameters they use: `{move}`, `{interact}`,
 * `{equipment}` and `{map}`. */
export type KeyTexts = {
  move: string;
  interact: string;
  equipment: string;
  map: string;
};

export function keyTexts(bindings: Bindings, layout: LayoutMap | null): KeyTexts {
  return {
    move: moveLabel(bindings, layout),
    interact: bindingLabel(bindings.interact, layout),
    equipment: bindingLabel(bindings.equipment, layout),
    map: bindingLabel(bindings.map, layout),
  };
}
