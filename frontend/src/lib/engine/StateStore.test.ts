import { describe, test, expect, beforeEach } from "vitest";
import { get } from "svelte/store";
import {
    gameState,
    gamePhase,
    combatLog,
    npcDialogue,
    achievementToast,
    combatDamageEvents,
    soundEvents,
    pendingEquipChoice,
    equippedSetCounts,
} from "./StateStore";
import type { StateUpdate, ItemView, EquipmentView } from "./protocol";

function makeItem(overrides: Partial<ItemView> = {}): ItemView {
    return { id: "i1", typeId: "t1", name: "Item", kind: "weapon", rarity: "common", statLine: "+1 ATK", ...overrides };
}

function makeEquipment(overrides: Partial<EquipmentView> = {}): EquipmentView {
    return { weapon: null, armor: null, accessories: [null, null], potionBelt: [null, null], keys: [], ...overrides };
}

function makeState(overrides: Partial<StateUpdate> = {}): StateUpdate {
    return {
        phase: "HUB",
        player: {
            classId: "warrior",
            hp: 100,
            maxHp: 100,
            resourceCurrent: 0,
            resourceMax: 100,
            level: 1,
            xp: 0,
            metaCurrency: 0,
            affinityTags: [],
        },
        equipment: makeEquipment(),
        abilities: [],
        achievements: [],
        sets: [],
        victory: false,
        log: [],
        newlyUnlocked: [],
        damageEvents: [],
        soundEvents: [],
        ...overrides,
    };
}

describe("StateStore derived stores", () => {
    beforeEach(() => gameState.set(null));

    test("every derived store defaults to its empty value before first connection", () => {
        expect(get(gamePhase)).toBeNull();
        expect(get(combatLog)).toEqual([]);
        expect(get(npcDialogue)).toBeNull();
        expect(get(achievementToast)).toEqual([]);
        expect(get(combatDamageEvents)).toEqual([]);
        expect(get(soundEvents)).toEqual([]);
        expect(get(pendingEquipChoice)).toBeNull();
        expect(get(equippedSetCounts)).toEqual({});
    });

    test("gamePhase mirrors the current StateUpdate's phase", () => {
        gameState.set(makeState({ phase: "COMBAT" }));
        expect(get(gamePhase)).toBe("COMBAT");
    });

    test("transient fields (dialogue/newlyUnlocked/damageEvents/soundEvents) clear on the next update that omits them", () => {
        gameState.set(
            makeState({
                dialogue: { npcName: "Wren", line: "Hi." },
                newlyUnlocked: [{ id: "a1", label: "L", description: "D", unlocked: true }],
                damageEvents: [{ targetIsPlayer: false, amount: 5, kind: "damage", crit: false }],
                soundEvents: ["pickup"],
            })
        );
        expect(get(npcDialogue)).toEqual({ npcName: "Wren", line: "Hi." });
        expect(get(achievementToast)).toHaveLength(1);
        expect(get(combatDamageEvents)).toHaveLength(1);
        expect(get(soundEvents)).toEqual(["pickup"]);

        gameState.set(makeState());
        expect(get(npcDialogue)).toBeNull();
        expect(get(achievementToast)).toEqual([]);
        expect(get(combatDamageEvents)).toEqual([]);
        expect(get(soundEvents)).toEqual([]);
    });

    test("pendingEquipChoice is a durable reflection of server state, not a transient one-shot", () => {
        const choice = { newItem: makeItem(), options: [] };
        gameState.set(makeState({ pendingEquipChoice: choice }));
        expect(get(pendingEquipChoice)).toEqual(choice);
        // Unlike the transient fields above, a follow-up update that still carries it must not clear it.
        gameState.set(makeState({ pendingEquipChoice: choice }));
        expect(get(pendingEquipChoice)).toEqual(choice);
    });

    test("equippedSetCounts counts weapon/armor/accessories sharing a setId", () => {
        gameState.set(
            makeState({
                equipment: makeEquipment({
                    weapon: makeItem({ setId: "light_soldier" }),
                    armor: makeItem({ setId: "light_soldier" }),
                    accessories: [makeItem({ setId: "light_soldier" }), makeItem()], // second has no setId
                }),
            })
        );
        expect(get(equippedSetCounts)).toEqual({ light_soldier: 3 });
    });

    test("equippedSetCounts ignores the potion belt entirely", () => {
        gameState.set(
            makeState({
                equipment: makeEquipment({ potionBelt: [makeItem({ setId: "should_not_count" }), null] }),
            })
        );
        expect(get(equippedSetCounts)).toEqual({});
    });
});
