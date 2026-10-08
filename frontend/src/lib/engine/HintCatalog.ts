/** Where the hint card sits on screen. */
export type HintPosition = "bottom-left" | "bottom-right" | "top-left" | "top-right";

/** One hint. Its texts live in the lang files, under `hint.<id>.title` and `hint.<id>.body`. */
export interface HintDef {
    id: string;
    /** The corner this hint's card shows in, when it should not use the default one. No hint does
     * today, the field is there for a screen where the default corner would hide something the
     * player needs while reading. */
    position?: HintPosition;
}

/** The top right is where the eye goes first on every screen, so the card is read before it is
 * dismissed. It can cover information (the Shards counter, the stats panel), which does not
 * matter: a hint is meant to be read, then closed with "Got it". */
export const DEFAULT_HINT_POSITION: HintPosition = "top-right";

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
    { id: "shop" },
    { id: "perks" },
];

export function hintPosition(id: string, catalog: readonly HintDef[] = HINTS): HintPosition {
    return catalog.find((hint) => hint.id === id)?.position ?? DEFAULT_HINT_POSITION;
}
