import { describe, test, expect, beforeEach } from "vitest";
import { get } from "svelte/store";
import {
    gameState,
    gamePhase,
    combatLog,
    explorationLog,
    LOG_HISTORY_SIZE,
    pendingScene,
    achievementToast,
    combatDamageEvents,
    soundEvents,
    pendingEquipChoice,
    equippedSetCounts,
    minimap,
} from "./StateStore";
import type { StateUpdate, ItemView, EquipmentView, MinimapView } from "./protocol";

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
        debugRooms: [],
        ...overrides,
    };
}

describe("StateStore derived stores", () => {
    beforeEach(() => gameState.set(null));

    test("every derived store defaults to its empty value before first connection", () => {
        expect(get(gamePhase)).toBeNull();
        expect(get(combatLog)).toEqual([]);
        expect(get(explorationLog)).toEqual([]);
        expect(get(achievementToast)).toEqual([]);
        expect(get(combatDamageEvents)).toEqual([]);
        expect(get(soundEvents)).toEqual([]);
        expect(get(pendingEquipChoice)).toBeNull();
        expect(get(pendingScene)).toBeNull();
        expect(get(equippedSetCounts)).toEqual({});
        expect(get(minimap)).toBeNull();
    });

    describe("the two logs", () => {
        const update = (phase: StateUpdate["phase"], log: string[] = [], fightLines?: number) =>
            gameState.set(makeState({ phase, log, fightLines }));

        test("the exploration log keeps the lines of earlier updates, so a pickup is still readable after the next step", () => {
            update("EXPLORATION", ["You take the Steel Sword."]);
            update("EXPLORATION");
            update("EXPLORATION", ["You open a door."]);
            expect(get(explorationLog)).toEqual(["You take the Steel Sword.", "You open a door."]);
        });

        test(`each log keeps only its last ${LOG_HISTORY_SIZE} lines`, () => {
            const lines = Array.from({ length: LOG_HISTORY_SIZE + 5 }, (_, n) => `line ${n + 1}`);
            update("EXPLORATION", lines.slice(0, 8));
            update("EXPLORATION", lines.slice(8));
            expect(get(explorationLog)).toEqual(lines.slice(-LOG_HISTORY_SIZE));

            update("COMBAT", ["You engage the Orc!"]);
            update("COMBAT", lines.slice(0, 8));
            update("COMBAT", lines.slice(8));
            expect(get(combatLog)).toEqual(lines.slice(-LOG_HISTORY_SIZE));
        });

        test("the log of a fight starts from scratch with the line that tells it began", () => {
            update("EXPLORATION", ["You walk in."]);
            update("COMBAT", ["You engage the Orc!"]);
            update("COMBAT", ["You strike the Orc for 27 damage.", "Orc attacks you for 1 damage."]);
            expect(get(combatLog)).toEqual(["You engage the Orc!", "You strike the Orc for 27 damage.", "Orc attacks you for 1 damage."]);

            update("EXPLORATION", ["Orc has been defeated!"], 0);
            update("COMBAT", ["You engage the Dire Wolf!"]);
            expect(get(combatLog)).toEqual(["You engage the Dire Wolf!"]);
        });

        test("the blows of a fight stay out of the exploration log, the fight that begins does not", () => {
            update("EXPLORATION", ["You open a door."]);
            update("COMBAT", ["You engage the Orc!"]);
            update("COMBAT", ["You strike the Orc for 27 damage.", "Orc attacks you for 1 damage."]);
            expect(get(explorationLog)).toEqual(["You open a door.", "You engage the Orc!"]);
        });

        test("what the end of a won fight brings reaches the exploration log, without the blow that ended it", () => {
            update("EXPLORATION", ["You open a door."]);
            update("COMBAT", ["You engage the Orc!"]);
            update("COMBAT", ["You strike the Orc for 27 damage."]);
            update(
                "EXPLORATION",
                ["You strike the Orc for 24 damage.", "Orc has been defeated!", "You gain 25 XP and 5 Shards.", "Orc dropped Silver Spear."],
                1,
            );
            expect(get(explorationLog)).toEqual([
                "You open a door.",
                "You engage the Orc!",
                "Orc has been defeated!",
                "You gain 25 XP and 5 Shards.",
                "Orc dropped Silver Spear.",
            ]);
        });

        test("the exploration log goes on from one fight to the next, with what the first one brought still in it", () => {
            update("COMBAT", ["You engage the Orc!"]);
            update("EXPLORATION", ["You strike the Orc.", "Orc has been defeated!", "You equip Silver Spear."], 1);
            update("COMBAT", ["You engage the Dire Wolf!"]);
            update("COMBAT", ["You strike the Dire Wolf."]);
            expect(get(explorationLog)).toEqual(["You engage the Orc!", "Orc has been defeated!", "You equip Silver Spear.", "You engage the Dire Wolf!"]);
        });

        test("a run begins with a clean exploration log", () => {
            update("EXPLORATION", ["Old line of the last run."]);
            update("GAMEOVER", ["The run ends."]);
            update("HUB", ["You return to the hub."]);
            update("EXPLORATION", ["A new run begins."]);
            expect(get(explorationLog)).toEqual(["A new run begins."]);
        });

        test("both are empty again once the connection is lost", () => {
            update("EXPLORATION", ["Something happened."]);
            update("COMBAT", ["You engage the Orc!"]);
            gameState.set(null);
            expect(get(explorationLog)).toEqual([]);
            expect(get(combatLog)).toEqual([]);
        });
    });

    test("gamePhase mirrors the current StateUpdate's phase", () => {
        gameState.set(makeState({ phase: "COMBAT" }));
        expect(get(gamePhase)).toBe("COMBAT");
    });

    test("transient fields (newlyUnlocked/damageEvents/soundEvents) clear on the next update that omits them", () => {
        gameState.set(
            makeState({
                newlyUnlocked: [{ id: "a1", label: "L", description: "D", unlocked: true }],
                damageEvents: [{ targetIsPlayer: false, amount: 5, kind: "damage", crit: false }],
                soundEvents: ["pickup"],
            })
        );
        expect(get(achievementToast)).toHaveLength(1);
        expect(get(combatDamageEvents)).toHaveLength(1);
        expect(get(soundEvents)).toEqual(["pickup"]);

        gameState.set(makeState());
        expect(get(achievementToast)).toEqual([]);
        expect(get(combatDamageEvents)).toEqual([]);
        expect(get(soundEvents)).toEqual([]);
    });

    test("pendingScene is a durable reflection of server state, and null once the server omits it", () => {
        const scene = { npc: "npc.1", kind: "scene" as const, sceneId: "npc.1.s1" };
        gameState.set(makeState({ pendingScene: scene }));
        expect(get(pendingScene)).toEqual(scene);
        gameState.set(makeState({ pendingScene: scene }));
        expect(get(pendingScene)).toEqual(scene);
        gameState.set(makeState({ pendingScene: null }));
        expect(get(pendingScene)).toBeNull();
        gameState.set(makeState());
        expect(get(pendingScene)).toBeNull();
    });

    test("pendingEquipChoice is a durable reflection of server state, not a transient one-shot", () => {
        const choice = { newItem: makeItem(), options: [] };
        gameState.set(makeState({ pendingEquipChoice: choice }));
        expect(get(pendingEquipChoice)).toEqual(choice);
        // Unlike the transient fields above, a follow-up update that still carries it must not clear it.
        gameState.set(makeState({ pendingEquipChoice: choice }));
        expect(get(pendingEquipChoice)).toEqual(choice);
    });

    test("minimap reflects the map the server sent and is null once an update has none", () => {
        const map: MinimapView = {
            sections: [{ index: 0, theme: "dungeon" }],
            nodes: [{ id: "n0", roomType: "combat", visited: true, current: true, section: 0, column: 0, lane: 0 }],
            edges: [],
        };
        gameState.set(makeState({ phase: "EXPLORATION", minimap: map }));
        expect(get(minimap)).toEqual(map);

        gameState.set(makeState({ phase: "COMBAT" }));
        expect(get(minimap)).toBeNull();
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
