import { describe, test, expect, beforeEach, vi } from "vitest";
import { render, fireEvent } from "@testing-library/svelte";
import { get } from "svelte/store";
import { tick } from "svelte";
import HubScreen from "./HubScreen.svelte";
import { gameState, client } from "../engine/StateStore";
import { lastStartedDifficulty } from "../engine/RunStore";
import type { StateUpdate, UpgradeView, PerkView } from "../engine/protocol";

function makeUpgrade(overrides: Partial<UpgradeView> = {}): UpgradeView {
    return { id: "u1", label: "Upgrade", description: "d", cost: 10, icon: "*", category: "stat", unlocked: false, ...overrides };
}

function makePerk(overrides: Partial<PerkView> = {}): PerkView {
    return { id: "p1", label: "Perk", description: "d", icon: "*", ...overrides };
}

function makeState(overrides: Partial<StateUpdate> = {}): StateUpdate {
    return {
        phase: "HUB",
        player: { classId: "warrior", hp: 100, maxHp: 100, resourceCurrent: 0, resourceMax: 100, level: 1, xp: 0, metaCurrency: 100, affinityTags: [] },
        equipment: { weapon: null, armor: null, accessories: [null, null], potionBelt: [null, null], keys: [] },
        hub: { upgrades: [], perks: [] },
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

// Card order matches HubScreen.svelte's own `classes`/`difficulties` arrays.
const CLASS_CARD = { warrior: 0, archer: 1, mage: 2 };
const DIFFICULTY_CARD = { easy: 0, normal: 1, hard: 2 };

describe("HubScreen", () => {
    beforeEach(() => {
        gameState.set(makeState());
        lastStartedDifficulty.set("normal");
        vi.restoreAllMocks();
    });

    test("Warrior has no gating upgrade and is never locked", () => {
        const { container } = render(HubScreen);
        const warriorCard = container.querySelectorAll(".class-card")[CLASS_CARD.warrior];
        expect(warriorCard.classList.contains("locked")).toBe(false);
    });

    test("a gated class is locked and unselectable until its unlock upgrade is owned", async () => {
        gameState.set(makeState({ hub: { upgrades: [makeUpgrade({ id: "archer_unlock", unlocked: false })], perks: [] } }));
        const { container } = render(HubScreen);
        const archerCard = container.querySelectorAll(".class-card")[CLASS_CARD.archer] as HTMLButtonElement;
        expect(archerCard.classList.contains("locked")).toBe(true);
        expect(archerCard.disabled).toBe(true);

        await fireEvent.click(archerCard);
        expect(archerCard.classList.contains("selected")).toBe(false);
        expect(container.querySelectorAll(".class-card")[CLASS_CARD.warrior].classList.contains("selected")).toBe(true);
    });

    test("a gated class becomes selectable once its unlock upgrade is owned", async () => {
        gameState.set(makeState({ hub: { upgrades: [makeUpgrade({ id: "archer_unlock", unlocked: true })], perks: [] } }));
        const { container } = render(HubScreen);
        const archerCard = container.querySelectorAll(".class-card")[CLASS_CARD.archer] as HTMLButtonElement;
        expect(archerCard.classList.contains("locked")).toBe(false);

        await fireEvent.click(archerCard);
        expect(archerCard.classList.contains("selected")).toBe(true);
    });

    test("Start Run sends the selected class/difficulty and remembers the difficulty", async () => {
        const { container } = render(HubScreen);
        const sendSpy = vi.spyOn(client, "send");

        await fireEvent.click(container.querySelectorAll(".difficulty-card")[DIFFICULTY_CARD.hard]);
        await fireEvent.click(container.querySelector("button.start-btn")!);

        expect(sendSpy).toHaveBeenCalledWith({ type: "HUB_ACTION", action: "STARTRUN", classId: "warrior", difficulty: "hard" });
        expect(get(lastStartedDifficulty)).toBe("hard");
    });

    test("selecting then re-clicking a perk toggles its id in and out of the STARTRUN payload", async () => {
        gameState.set(makeState({ hub: { upgrades: [], perks: [makePerk({ id: "heavy_hand" })] } }));
        const { container } = render(HubScreen);
        const sendSpy = vi.spyOn(client, "send");
        const perkCard = container.querySelector(".perk-card")!;
        const startBtn = container.querySelector("button.start-btn")!;

        await fireEvent.click(perkCard);
        await fireEvent.click(startBtn);
        expect(sendSpy).toHaveBeenLastCalledWith(expect.objectContaining({ perkId: "heavy_hand" }));

        await fireEvent.click(perkCard); // deselect
        await fireEvent.click(startBtn);
        const lastCall = sendSpy.mock.calls.at(-1)![0];
        expect(lastCall).not.toHaveProperty("perkId");
    });

    test("Buy is disabled below cost and sends BUYUPGRADE once affordable", async () => {
        gameState.set(
            makeState({
                player: { classId: "warrior", hp: 100, maxHp: 100, resourceCurrent: 0, resourceMax: 100, level: 1, xp: 0, metaCurrency: 5, affinityTags: [] },
                hub: { upgrades: [makeUpgrade({ id: "hp_boost_1", cost: 30 })], perks: [] },
            })
        );
        const { container } = render(HubScreen);
        expect((container.querySelector("button.buy-btn") as HTMLButtonElement).disabled).toBe(true);

        gameState.update((s) => ({ ...s!, player: { ...s!.player, metaCurrency: 50 } }));
        await tick();
        const sendSpy = vi.spyOn(client, "send");
        await fireEvent.click(container.querySelector("button.buy-btn")!);
        expect(sendSpy).toHaveBeenCalledWith({ type: "HUB_ACTION", action: "BUYUPGRADE", upgradeId: "hp_boost_1" });
    });

    test("the ALL/STATS/META tabs filter the upgrade list by category", async () => {
        gameState.set(
            makeState({
                hub: {
                    upgrades: [
                        makeUpgrade({ id: "s1", label: "Stat Upgrade", category: "stat" }),
                        makeUpgrade({ id: "m1", label: "Meta Upgrade", category: "meta" }),
                    ],
                    perks: [],
                },
            })
        );
        const { container } = render(HubScreen);
        const labels = () => Array.from(container.querySelectorAll(".upgrade-label")).map((el) => el.textContent);
        expect(labels()).toEqual(["Stat Upgrade", "Meta Upgrade"]);

        await fireEvent.click(container.querySelectorAll(".upgrade-tab")[1]); // "Stats"
        expect(labels()).toEqual(["Stat Upgrade"]);
    });
});
