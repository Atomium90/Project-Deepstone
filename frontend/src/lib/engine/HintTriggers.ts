import { get } from "svelte/store";
import { gameState } from "./StateStore";
import { activeHint, isHintSeen, showHint, tutorial } from "./HintStore";
import type { ItemView, StateUpdate } from "./protocol";

/** Every item the player can currently look at: worn, on the potion belt, offered in a keep/replace
 * choice (the new one and the ones it would replace), or offered by a Shrine. */
function visibleItems(state: StateUpdate): ItemView[] {
    const { equipment, pendingEquipChoice, pendingRewardChoice } = state;
    const items: (ItemView | null | undefined)[] = [
        equipment.weapon,
        equipment.armor,
        ...equipment.accessories,
        ...equipment.potionBelt,
        pendingEquipChoice?.newItem,
        ...(pendingEquipChoice?.options.map((option) => option.current) ?? []),
        ...(pendingRewardChoice?.options ?? []),
    ];
    return items.filter((item): item is ItemView => item != null);
}

/** What the items on screen teach, in the order they are worth showing. */
function itemHints(state: StateUpdate): string[] {
    const items = visibleItems(state);
    const hints: string[] = [];
    if (items.some((item) => item.typeTag !== undefined)) hints.push("affinity");
    if (items.some((item) => item.rarity !== "common")) hints.push("rarity");
    if (items.some((item) => item.setId !== undefined)) hints.push("set");
    if (state.equipment.potionBelt.some((slot) => slot !== null)) hints.push("potions");
    return hints;
}

/**
 * The hints whose condition holds in this state, most pressing first. Pure: it says nothing about
 * what was already seen, `watchHints` deals with that. "shop" is not here, HubScreen asks for it
 * itself since it depends on what that screen shows.
 */
export function candidateHints(state: StateUpdate): string[] {
    switch (state.phase) {
        case "EXPLORATION": {
            const hints: string[] = [];
            // A modal waiting for an answer is the most pressing thing on screen.
            if (state.pendingEquipChoice) hints.push("equip_choice");
            if (state.pendingRewardChoice) hints.push("reward_choice");
            hints.push("controls");
            if (state.room?.entities.some((entity) => entity.kind === "enemy" && entity.isElite)) hints.push("elite");
            hints.push(...itemHints(state));
            if (visibleItems(state).length > 0) hints.push("menus");
            return hints;
        }
        case "COMBAT": {
            const hints = ["combat", "resource"];
            if (state.equipment.potionBelt.some((slot) => slot !== null)) hints.push("potions");
            return hints;
        }
        case "HUB": {
            const hints: string[] = [];
            // With no run finished yet the start screen is up instead of the Hub.
            if ((state.hub?.runsCompleted ?? 0) > 0) hints.push("difficulty");
            if ((state.hub?.perks.length ?? 0) > 0) hints.push("perks");
            return hints;
        }
        default:
            return [];
    }
}

/**
 * Asks for the first unseen hint of every game state, but only while no hint is on screen, so
 * hints never pile up: a hint dismissed now leaves the next one for the next state update, a move,
 * a combat action, and its condition still holds by then. Returns the function that stops watching.
 */
export function watchHints(): () => void {
    return gameState.subscribe((state) => {
        if (state === null || get(activeHint) !== null) return;
        const next = candidateHints(state).find((id) => !isHintSeen(get(tutorial), id));
        if (next !== undefined) showHint(next);
    });
}
