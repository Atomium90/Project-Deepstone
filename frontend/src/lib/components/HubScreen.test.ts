import { describe, test, expect, beforeEach, vi } from "vitest";
import { render, fireEvent } from "@testing-library/svelte";
import { get } from "svelte/store";
import { tick } from "svelte";
import HubScreen from "./HubScreen.svelte";
import { gameState, client } from "../engine/StateStore";
import { lastStartedDifficulty } from "../engine/RunStore";
import { characterTab } from "../engine/CharacterStore";
import { activeHint, resetTutorial, skipTutorial } from "../engine/HintStore";
import { settings } from "../engine/SettingsStore";
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
        hub: { upgrades: [], perks: [], runsCompleted: 1 },
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
        gameState.set(makeState({ hub: { upgrades: [makeUpgrade({ id: "archer_unlock", unlocked: false })], perks: [], runsCompleted: 1 } }));
        const { container } = render(HubScreen);
        const archerCard = container.querySelectorAll(".class-card")[CLASS_CARD.archer] as HTMLButtonElement;
        expect(archerCard.classList.contains("locked")).toBe(true);
        expect(archerCard.disabled).toBe(true);

        await fireEvent.click(archerCard);
        expect(archerCard.classList.contains("selected")).toBe(false);
        expect(container.querySelectorAll(".class-card")[CLASS_CARD.warrior].classList.contains("selected")).toBe(true);
    });

    test("a gated class becomes selectable once its unlock upgrade is owned", async () => {
        gameState.set(makeState({ hub: { upgrades: [makeUpgrade({ id: "archer_unlock", unlocked: true })], perks: [], runsCompleted: 1 } }));
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
        gameState.set(makeState({ hub: { upgrades: [], perks: [makePerk({ id: "heavy_hand" })], runsCompleted: 1 } }));
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
                // The cheap second upgrade is what keeps the shop on screen at 5 Shards.
                hub: {
                    upgrades: [makeUpgrade({ id: "hp_boost_1", cost: 30 }), makeUpgrade({ id: "cheap", cost: 5 })],
                    perks: [],
                    runsCompleted: 1,
                },
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
                    runsCompleted: 1,
                },
            })
        );
        const { container } = render(HubScreen);
        const labels = () => Array.from(container.querySelectorAll(".upgrade-label")).map((el) => el.textContent);
        expect(labels()).toEqual(["Stat Upgrade", "Meta Upgrade"]);

        await fireEvent.click(container.querySelectorAll(".upgrade-tab")[1]); // "Stats"
        expect(labels()).toEqual(["Stat Upgrade"]);
    });

    test("the footer's Help button opens the Help tab of the Character screen", async () => {
        characterTab.set(null);
        const { container } = render(HubScreen);
        await fireEvent.click(container.querySelector(".help-nav-btn")!);
        expect(get(characterTab)).toBe("help");
    });

    describe("the Hub shown step by step", () => {
        function hubWith(shards: number, upgrades: UpgradeView[], perks: PerkView[] = []): StateUpdate {
            return makeState({
                player: { classId: "warrior", hp: 100, maxHp: 100, resourceCurrent: 0, resourceMax: 100, level: 1, xp: 0, metaCurrency: shards, affinityTags: [] },
                hub: { upgrades, perks, runsCompleted: 1 },
            });
        }

        const shopShown = (container: HTMLElement) => container.querySelector(".right-panel .upgrade-tabs") !== null;
        const classCards = (container: HTMLElement) => container.querySelectorAll(".class-card").length;

        beforeEach(() => {
            settings.update((s) => ({ ...s, showHints: true }));
            resetTutorial();
        });

        test("with too few Shards for any upgrade, the shop and the locked classes are hidden", () => {
            gameState.set(hubWith(10, [makeUpgrade({ cost: 30 })]));
            const { container } = render(HubScreen);
            expect(shopShown(container)).toBe(false);
            expect(classCards(container)).toBe(1);
            expect(container.querySelector(".class-card .class-name")?.textContent).toBe("Warrior");
        });

        test("the left panel then stands alone, with no empty right panel", () => {
            gameState.set(hubWith(10, [makeUpgrade({ cost: 30 })]));
            const { container } = render(HubScreen);
            expect(container.querySelector(".right-panel")).toBeNull();
            expect(container.querySelector(".hub-body")?.classList.contains("single")).toBe(true);
        });

        test("the shop and every class appear once the balance covers the cheapest upgrade", () => {
            gameState.set(hubWith(30, [makeUpgrade({ cost: 30 }), makeUpgrade({ id: "dear", cost: 90 })]));
            const { container } = render(HubScreen);
            expect(shopShown(container)).toBe(true);
            expect(classCards(container)).toBe(3);
            expect(container.querySelector(".hub-body")?.classList.contains("single")).toBe(false);
        });

        test("an owned upgrade keeps the shop on screen even with no Shards left", () => {
            gameState.set(hubWith(0, [makeUpgrade({ cost: 30, unlocked: true })]));
            const { container } = render(HubScreen);
            expect(shopShown(container)).toBe(true);
        });

        test("skipping the tutorial shows the shop whatever the balance", () => {
            skipTutorial();
            gameState.set(hubWith(0, [makeUpgrade({ cost: 30 })]));
            const { container } = render(HubScreen);
            expect(shopShown(container)).toBe(true);
            expect(classCards(container)).toBe(3);
        });

        test("the shop shows up when the balance grows, and stays if it drops back", async () => {
            gameState.set(hubWith(10, [makeUpgrade({ cost: 30 })]));
            const { container } = render(HubScreen);
            expect(shopShown(container)).toBe(false);

            gameState.set(hubWith(30, [makeUpgrade({ cost: 30 })]));
            await tick();
            expect(shopShown(container)).toBe(true);

            // Buying the upgrade spends the Shards: the shop must not disappear with them.
            gameState.set(hubWith(0, [makeUpgrade({ cost: 30, unlocked: true })]));
            await tick();
            expect(shopShown(container)).toBe(true);
        });

        test("run perks alone bring the right panel back, without the shop", () => {
            gameState.set(hubWith(0, [makeUpgrade({ cost: 30 })], [makePerk({ id: "heavy_hand", label: "Heavy Hand" })]));
            const { container } = render(HubScreen);
            expect(container.querySelector(".right-panel")).not.toBeNull();
            expect(shopShown(container)).toBe(false);
            expect(container.querySelectorAll(".perk-card")).toHaveLength(1);
            expect(classCards(container)).toBe(1);
        });

        test("the first time the shop appears, the shop hint is asked for", () => {
            gameState.set(hubWith(30, [makeUpgrade({ cost: 30 })]));
            render(HubScreen);
            expect(get(activeHint)).toBe("shop");
        });

        test("no hint is asked for while the shop is hidden", () => {
            gameState.set(hubWith(10, [makeUpgrade({ cost: 30 })]));
            render(HubScreen);
            expect(get(activeHint)).toBeNull();
        });
    });
});
