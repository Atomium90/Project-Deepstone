import { describe, test, expect, beforeEach, afterEach } from "vitest";
import { get } from "svelte/store";
import { candidateHints, watchHints } from "./HintTriggers";
import { activeHint, dismissHint, resetTutorial, showHint, skipTutorial } from "./HintStore";
import { gameState } from "./StateStore";
import { settings } from "./SettingsStore";
import type { EntityView, EquipmentView, ItemView, StateUpdate } from "./protocol";

function makeItem(overrides: Partial<ItemView> = {}): ItemView {
    return { id: "i1", typeId: "sword", name: "Sword", kind: "weapon", rarity: "common", statLine: "+3 ATK", ...overrides };
}

function makeEquipment(overrides: Partial<EquipmentView> = {}): EquipmentView {
    return { weapon: null, armor: null, accessories: [null, null], potionBelt: [null, null], keys: [], ...overrides };
}

function makeState(overrides: Partial<StateUpdate> = {}): StateUpdate {
    return {
        phase: "EXPLORATION",
        player: { classId: "warrior", hp: 100, maxHp: 100, resourceCurrent: 0, resourceMax: 100, level: 1, xp: 0, metaCurrency: 0, affinityTags: ["heavy"] },
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

function makeEnemy(overrides: Partial<EntityView> = {}): EntityView {
    return { id: "e1", kind: "enemy", x: 1, y: 1, label: "Goblin", ...overrides };
}

function roomWith(entities: EntityView[]): StateUpdate["room"] {
    return { roomId: "r1", width: 8, height: 6, tiles: [], theme: "dungeon", floorSprite: [], wallSprite: [], decoration: [], entities, playerX: 1, playerY: 1 };
}

describe("candidateHints", () => {
    describe("exploring", () => {
        test("always offers the controls", () => {
            expect(candidateHints(makeState())).toEqual(["controls"]);
        });

        test("puts a pending keep/replace choice first", () => {
            const choice = { newItem: makeItem({ typeTag: undefined }), options: [{ slot: "WEAPON" as const, current: makeItem({ id: "i2" }) }] };
            expect(candidateHints(makeState({ pendingEquipChoice: choice }))[0]).toBe("equip_choice");
        });

        test("puts a pending Shrine choice first", () => {
            const hints = candidateHints(makeState({ pendingRewardChoice: { options: [makeItem()] } }));
            expect(hints[0]).toBe("reward_choice");
            expect(hints).toContain("controls");
        });

        test("offers the Elite hint only for an Elite enemy in the room", () => {
            const plain = makeState({ room: roomWith([makeEnemy()]) });
            const elite = makeState({ room: roomWith([makeEnemy(), makeEnemy({ id: "e2", isElite: true })]) });
            expect(candidateHints(plain)).not.toContain("elite");
            expect(candidateHints(elite)).toContain("elite");
        });

        test("an Elite flag on something that is not an enemy does not count", () => {
            const state = makeState({ room: roomWith([makeEnemy({ kind: "chest", isElite: true })]) });
            expect(candidateHints(state)).not.toContain("elite");
        });

        test("a worn item with an affinity tag teaches affinity and the menus", () => {
            const state = makeState({ equipment: makeEquipment({ weapon: makeItem({ typeTag: "heavy" }) }) });
            expect(candidateHints(state)).toEqual(["controls", "affinity", "menus"]);
        });

        test("a common item with no tag only teaches the menus", () => {
            const state = makeState({ equipment: makeEquipment({ weapon: makeItem() }) });
            expect(candidateHints(state)).toEqual(["controls", "menus"]);
        });

        test("an item above Common teaches rarity", () => {
            const state = makeState({ equipment: makeEquipment({ armor: makeItem({ kind: "armor", rarity: "rare" }) }) });
            expect(candidateHints(state)).toContain("rarity");
        });

        test("an item of a set teaches sets", () => {
            const state = makeState({ equipment: makeEquipment({ weapon: makeItem({ setId: "fire_mage" }) }) });
            expect(candidateHints(state)).toContain("set");
        });

        test("a potion on the belt teaches potions", () => {
            const potion = makeItem({ kind: "consumable", count: 1 });
            const state = makeState({ equipment: makeEquipment({ potionBelt: [potion, null] }) });
            expect(candidateHints(state)).toContain("potions");
        });

        test("an item only on offer counts, as the new item of a choice", () => {
            const choice = { newItem: makeItem({ rarity: "epic" }), options: [{ slot: "WEAPON" as const, current: makeItem({ id: "i2" }) }] };
            expect(candidateHints(makeState({ pendingEquipChoice: choice }))).toContain("rarity");
        });

        test("an item only on offer counts, as one the new item would replace", () => {
            const choice = { newItem: makeItem(), options: [{ slot: "WEAPON" as const, current: makeItem({ id: "i2", setId: "fire_mage" }) }] };
            expect(candidateHints(makeState({ pendingEquipChoice: choice }))).toContain("set");
        });

        test("an item only on offer counts, as a Shrine reward", () => {
            const state = makeState({ pendingRewardChoice: { options: [makeItem({ typeTag: "magic" })] } });
            expect(candidateHints(state)).toContain("affinity");
        });

        test("with a lot on screen the pressing hints come before the item ones", () => {
            const choice = { newItem: makeItem({ rarity: "rare", typeTag: "heavy" }), options: [{ slot: "WEAPON" as const, current: makeItem({ id: "i2" }) }] };
            const hints = candidateHints(makeState({ pendingEquipChoice: choice, room: roomWith([makeEnemy({ isElite: true })]) }));
            expect(hints).toEqual(["equip_choice", "controls", "elite", "affinity", "rarity", "menus"]);
        });
    });

    describe("in combat", () => {
        test("teaches the actions, then the class resource", () => {
            expect(candidateHints(makeState({ phase: "COMBAT" }))).toEqual(["combat", "resource"]);
        });

        test("adds potions when the belt holds one", () => {
            const potion = makeItem({ kind: "consumable", count: 2 });
            const state = makeState({ phase: "COMBAT", equipment: makeEquipment({ potionBelt: [null, potion] }) });
            expect(candidateHints(state)).toEqual(["combat", "resource", "potions"]);
        });
    });

    describe("in the Hub", () => {
        const hub = (runsCompleted: number, perks = 0): StateUpdate["hub"] => ({
            upgrades: [],
            perks: Array.from({ length: perks }, (_, i) => ({ id: `p${i}`, label: "Perk", description: "d", icon: "*" })),
            runsCompleted,
        });

        test("offers nothing while the save has no finished run, the start screen is up", () => {
            expect(candidateHints(makeState({ phase: "HUB", hub: hub(0) }))).toEqual([]);
        });

        test("teaches the difficulty once a run is behind the player", () => {
            expect(candidateHints(makeState({ phase: "HUB", hub: hub(1) }))).toEqual(["difficulty"]);
        });

        test("teaches the perks when some are offered", () => {
            expect(candidateHints(makeState({ phase: "HUB", hub: hub(2, 3) }))).toEqual(["difficulty", "perks"]);
        });

        test("offers nothing when the update carries no hub view", () => {
            expect(candidateHints(makeState({ phase: "HUB" }))).toEqual([]);
        });
    });

    test("offers nothing on the Game Over screen", () => {
        const state = makeState({ phase: "GAMEOVER", equipment: makeEquipment({ weapon: makeItem({ typeTag: "heavy", rarity: "epic" }) }) });
        expect(candidateHints(state)).toEqual([]);
    });
});

describe("watchHints", () => {
    let stop: () => void;

    beforeEach(() => {
        settings.update((s) => ({ ...s, showHints: true }));
        resetTutorial();
        gameState.set(null);
        stop = watchHints();
    });

    afterEach(() => stop());

    test("shows the first hint of a game state", () => {
        gameState.set(makeState());
        expect(get(activeHint)).toBe("controls");
    });

    test("does nothing before the server has sent anything", () => {
        expect(get(activeHint)).toBeNull();
    });

    test("skips what was already seen and shows the next hint", () => {
        const state = makeState({ equipment: makeEquipment({ weapon: makeItem({ typeTag: "heavy" }) }) });
        gameState.set(state);
        dismissHint(); // controls
        gameState.set({ ...state, log: ["moved"] });
        expect(get(activeHint)).toBe("affinity");
    });

    test("leaves the next hint for the next update instead of showing it at once", () => {
        const state = makeState({ equipment: makeEquipment({ weapon: makeItem({ typeTag: "heavy" }) }) });
        gameState.set(state);
        dismissHint();
        expect(get(activeHint)).toBeNull();
    });

    test("shows no second hint while one is on screen", () => {
        const state = makeState({ equipment: makeEquipment({ weapon: makeItem({ typeTag: "heavy" }) }) });
        gameState.set(state);
        gameState.set({ ...state, log: ["moved"] });
        expect(get(activeHint)).toBe("controls");
        dismissHint();
        expect(get(activeHint)).toBeNull();
    });

    test("never shows a hint twice", () => {
        gameState.set(makeState());
        dismissHint();
        gameState.set(makeState({ log: ["moved"] }));
        expect(get(activeHint)).toBeNull();
    });

    test("shows nothing after the tutorial was skipped", () => {
        skipTutorial();
        gameState.set(makeState());
        expect(get(activeHint)).toBeNull();
    });

    test("shows nothing while hints are off in Settings", () => {
        settings.update((s) => ({ ...s, showHints: false }));
        gameState.set(makeState());
        expect(get(activeHint)).toBeNull();
    });

    test("waits behind a hint another screen already asked for", () => {
        showHint("shop");
        gameState.set(makeState());
        expect(get(activeHint)).toBe("shop");
    });

    test("stops once the watcher is stopped", () => {
        stop();
        gameState.set(makeState());
        expect(get(activeHint)).toBeNull();
    });
});
