/** Where the hint card sits on screen. */
export type HintPosition = "bottom-left" | "bottom-right" | "top-left" | "top-right";

/** One hint. Its texts live in the lang files, under `hint.<id>.title` and `hint.<id>.body`. */
export interface HintDef {
    id: string;
    /** The corner this hint's card shows in. Each screen has its own free spots, so a hint that
     * would cover something important in the default corner picks another one. */
    position?: HintPosition;
}

export const DEFAULT_HINT_POSITION: HintPosition = "bottom-left";

/** Every hint of the game, in the order the Help tab lists them: what the dungeon teaches first,
 * then what the Hub does. What triggers each one is in HintTriggers.ts, except "shop", which
 * HubScreen asks for itself since it depends on what that screen shows. */
export const HINTS: readonly HintDef[] = [
    { id: "controls" },
    { id: "menus" },
    { id: "combat" },
    { id: "resource" },
    { id: "equip_choice" },
    { id: "rarity" },
    { id: "affinity" },
    { id: "set" },
    { id: "potions" },
    { id: "reward_choice" },
    { id: "elite" },
    { id: "difficulty" },
    { id: "shop", position: "top-right" },
    { id: "perks" },
];

export function hintPosition(id: string): HintPosition {
    return HINTS.find((hint) => hint.id === id)?.position ?? DEFAULT_HINT_POSITION;
}
