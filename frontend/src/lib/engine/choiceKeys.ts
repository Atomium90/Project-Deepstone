/** The part of a key press the shortcuts read. */
export type KeyPress = Pick<KeyboardEvent, "key" | "code">;

/**
 * The digit, 0 to 9, a key press stands for, or null.
 *
 * It reads the physical key first (`code`: "Digit1" on the top row, "Numpad1" on the pad), then the
 * character (`key`). The top row of an AZERTY keyboard types "&" or "é" without Shift, so reading
 * the character alone would make the digits work only with Shift held, or on the pad.
 */
export function digitOf(press: KeyPress): number | null {
    const physical = /^(?:Digit|Numpad)([0-9])$/.exec(press.code ?? "");
    if (physical) return Number(physical[1]);
    return /^[0-9]$/.test(press.key) ? Number(press.key) : null;
}

/** Which of `count` choices, listed in order and numbered from 1, the key picks: the digits 1 to 9 pick
 * the first nine, any other key or a digit past the end of the list picks nothing. */
export function pickedIndex(press: KeyPress, count: number): number | null {
    const digit = digitOf(press);
    if (digit === null || digit < 1 || digit > 9 || digit > count) return null;
    return digit - 1;
}

/** Whether the key means "leave" or "go back": 0 or Escape. */
export function isLeaveKey(press: KeyPress): boolean {
    return press.key === "Escape" || digitOf(press) === 0;
}

/** Whether the press can be a shortcut: not held down (auto-repeat would answer a choice twice) and
 * with no modifier (Ctrl+1 and Alt+1 belong to the browser). */
export function isPlainPress(press: Pick<KeyboardEvent, "repeat" | "ctrlKey" | "altKey" | "metaKey">): boolean {
    return !press.repeat && !press.ctrlKey && !press.altKey && !press.metaKey;
}
