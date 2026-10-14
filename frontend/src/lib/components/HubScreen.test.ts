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
import type { StateUpdate, UpgradeView, PerkView, DifficultyView } from "../engine/protocol";

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
        hub: { upgrades: [], perks: [], runsCompleted: 1, difficulties: [] },
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
const DIFFICULTY_CARD = { easy: 0, normal: 1, hard: 2, hardcore: 3 };

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
        gameState.set(makeState({ hub: { upgrades: [makeUpgrade({ id: "archer_unlock", unlocked: false })], perks: [], runsCompleted: 1, difficulties: [] } }));
        const { container } = render(HubScreen);
        const archerCard = container.querySelectorAll(".class-card")[CLASS_CARD.archer] as HTMLButtonElement;
        expect(archerCard.classList.contains("locked")).toBe(true);
        expect(archerCard.disabled).toBe(true);

        await fireEvent.click(archerCard);
        expect(archerCard.classList.contains("selected")).toBe(false);
        expect(container.querySelectorAll(".class-card")[CLASS_CARD.warrior].classList.contains("selected")).toBe(true);
    });

    test("a gated class becomes selectable once its unlock upgrade is owned", async () => {
        gameState.set(makeState({ hub: { upgrades: [makeUpgrade({ id: "archer_unlock", unlocked: true })], perks: [], runsCompleted: 1, difficulties: [] } }));
        const { container } = render(HubScreen);
        const archerCard = container.querySelectorAll(".class-card")[CLASS_CARD.archer] as HTMLButtonElement;
        expect(archerCard.classList.contains("locked")).toBe(false);

        await fireEvent.click(archerCard);
        expect(archerCard.classList.contains("selected")).toBe(true);
    });

    test("a gated class opens as soon as its unlock upgrade is bought, with no reload", async () => {
        const hubWith = (unlocked: boolean) =>
            makeState({ hub: { upgrades: [makeUpgrade({ id: "archer_unlock", unlocked })], perks: [], runsCompleted: 1, difficulties: [] } });
        gameState.set(hubWith(false));
        const { container } = render(HubScreen);
        const archerCard = () => container.querySelectorAll(".class-card")[CLASS_CARD.archer] as HTMLButtonElement;
        expect(archerCard().disabled).toBe(true);

        gameState.set(hubWith(true));
        await tick();
        expect(archerCard().classList.contains("locked")).toBe(false);
        expect(archerCard().disabled).toBe(false);

        await fireEvent.click(archerCard());
        expect(archerCard().classList.contains("selected")).toBe(true);
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
        gameState.set(makeState({ hub: { upgrades: [], perks: [makePerk({ id: "heavy_hand" })], runsCompleted: 1, difficulties: [] } }));
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
                    runsCompleted: 1, difficulties: [],
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
                    runsCompleted: 1, difficulties: [],
                },
            })
        );
        const { container } = render(HubScreen);
        const labels = () => Array.from(container.querySelectorAll(".upgrade-label")).map((el) => el.textContent);
        expect(labels()).toEqual(["Stat Upgrade", "Meta Upgrade"]);

        await fireEvent.click(container.querySelectorAll(".upgrade-tab")[1]); // "Stats"
        expect(labels()).toEqual(["Stat Upgrade"]);
    });

    describe("difficulty tiers", () => {
        const NORMAL_NEEDS = { winsAt: "easy", wins: 1, winsDone: 0, shardsSpent: 0, shardsSpentDone: 0 } as const;
        const HARD_NEEDS = { winsAt: "normal", wins: 4, winsDone: 2, shardsSpent: 300, shardsSpentDone: 120 } as const;

        function tiers(normal: boolean, hard: boolean): DifficultyView[] {
            return [
                { id: "easy", unlocked: true, requirement: null },
                { id: "normal", unlocked: normal, requirement: normal ? { ...NORMAL_NEEDS, winsDone: 1 } : NORMAL_NEEDS },
                { id: "hard", unlocked: hard, requirement: hard ? { ...HARD_NEEDS, winsDone: 4, shardsSpentDone: 300 } : HARD_NEEDS },
            ];
        }

        function hubWithTiers(difficulties: DifficultyView[]): StateUpdate {
            return makeState({ hub: { upgrades: [], perks: [], runsCompleted: 1, difficulties } });
        }

        const cards = (container: HTMLElement) => container.querySelectorAll<HTMLButtonElement>(".difficulty-card");
        const hintLines = (container: HTMLElement) =>
            Array.from(container.querySelectorAll(".difficulty-need")).map((el) => el.textContent);

        test("a locked tier is greyed out and cannot be selected", async () => {
            gameState.set(hubWithTiers(tiers(false, false)));
            const { container } = render(HubScreen);

            for (const locked of [DIFFICULTY_CARD.normal, DIFFICULTY_CARD.hard]) {
                expect(cards(container)[locked].classList.contains("locked")).toBe(true);
                expect(cards(container)[locked].disabled).toBe(true);
            }
            expect(cards(container)[DIFFICULTY_CARD.easy].disabled).toBe(false);

            await fireEvent.click(cards(container)[DIFFICULTY_CARD.hard]);
            expect(cards(container)[DIFFICULTY_CARD.easy].classList.contains("selected")).toBe(true);
            expect(cards(container)[DIFFICULTY_CARD.hard].classList.contains("selected")).toBe(false);
        });

        test("while Normal is locked, a run starts on Easy", async () => {
            gameState.set(hubWithTiers(tiers(false, false)));
            const { container } = render(HubScreen);
            const sendSpy = vi.spyOn(client, "send");

            await fireEvent.click(container.querySelector("button.start-btn")!);

            expect(sendSpy).toHaveBeenCalledWith(expect.objectContaining({ action: "STARTRUN", difficulty: "easy" }));
            expect(get(lastStartedDifficulty)).toBe("easy");
        });

        test("Normal is the default tier once it is unlocked", () => {
            gameState.set(hubWithTiers(tiers(true, false)));
            const { container } = render(HubScreen);
            expect(cards(container)[DIFFICULTY_CARD.normal].classList.contains("selected")).toBe(true);
            expect(cards(container)[DIFFICULTY_CARD.hard].disabled).toBe(true);
        });

        test("an unlocked Hard can be picked", async () => {
            gameState.set(hubWithTiers(tiers(true, true)));
            const { container } = render(HubScreen);
            const sendSpy = vi.spyOn(client, "send");

            await fireEvent.click(cards(container)[DIFFICULTY_CARD.hard]);
            await fireEvent.click(container.querySelector("button.start-btn")!);

            expect(sendSpy).toHaveBeenCalledWith(expect.objectContaining({ difficulty: "hard" }));
        });

        test("the next locked tier says what is missing to open it", () => {
            gameState.set(hubWithTiers(tiers(false, false)));
            const { container } = render(HubScreen);
            expect(container.querySelector(".difficulty-hint")?.textContent).toContain("Next tier: Normal");
            expect(hintLines(container)).toEqual(["Wins on Easy: 0 of 1"]);
        });

        test("a tier lacking both wins and Shards lists both", () => {
            gameState.set(hubWithTiers(tiers(true, false)));
            const { container } = render(HubScreen);
            expect(container.querySelector(".difficulty-hint")?.textContent).toContain("Next tier: Hard");
            expect(hintLines(container)).toEqual(["Wins on Normal: 2 of 4", "Shards spent: 120 of 300"]);
        });

        test("a tier that only lacks Shards lists only the Shards", () => {
            const lacking = tiers(true, false);
            lacking[2] = { ...lacking[2], requirement: { ...HARD_NEEDS, winsDone: 4 } };
            gameState.set(hubWithTiers(lacking));
            const { container } = render(HubScreen);
            expect(hintLines(container)).toEqual(["Shards spent: 120 of 300"]);
        });

        test("a locked card names what it lacks in its tooltip", () => {
            gameState.set(hubWithTiers(tiers(true, false)));
            const { container } = render(HubScreen);
            expect(cards(container)[DIFFICULTY_CARD.hard].title).toBe("Wins on Normal: 2 of 4 · Shards spent: 120 of 300");
            expect(cards(container)[DIFFICULTY_CARD.normal].title).toBe("The baseline experience");
        });

        test("no hint is shown once every tier is unlocked", () => {
            gameState.set(hubWithTiers(tiers(true, true)));
            const { container } = render(HubScreen);
            expect(container.querySelector(".difficulty-hint")).toBeNull();
        });

        test("the selection moves to an unlocked tier when the tiers change under it", async () => {
            gameState.set(hubWithTiers(tiers(true, true)));
            const { container } = render(HubScreen);
            await fireEvent.click(cards(container)[DIFFICULTY_CARD.hard]);
            expect(cards(container)[DIFFICULTY_CARD.hard].classList.contains("selected")).toBe(true);

            gameState.set(hubWithTiers(tiers(true, false)));
            await tick();
            expect(cards(container)[DIFFICULTY_CARD.normal].classList.contains("selected")).toBe(true);
        });

        describe("the tier only the story opens", () => {
            const HARDCORE: DifficultyView = { id: "hardcore", unlocked: true, requirement: null };
            const rules = (container: HTMLElement) => container.querySelector(".difficulty-rules");

            test("is not drawn while the server does not list it", () => {
                gameState.set(hubWithTiers(tiers(true, true)));
                const { container } = render(HubScreen);
                expect(cards(container)).toHaveLength(3);
            });

            test("is drawn last once the server lists it", () => {
                gameState.set(hubWithTiers([...tiers(true, true), HARDCORE]));
                const { container } = render(HubScreen);
                expect(cards(container)).toHaveLength(4);
                expect(cards(container)[DIFFICULTY_CARD.hardcore].textContent).toContain("Hardcore");
                expect(cards(container)[DIFFICULTY_CARD.hardcore].disabled).toBe(false);
            });

            test("is never the default pick, even when it is the only tier above Easy that is open", () => {
                gameState.set(hubWithTiers([...tiers(false, false), HARDCORE]));
                const { container } = render(HubScreen);
                expect(cards(container)[DIFFICULTY_CARD.easy].classList.contains("selected")).toBe(true);
                expect(cards(container)[DIFFICULTY_CARD.hardcore].classList.contains("selected")).toBe(false);
                expect(rules(container)).toBeNull();
            });

            test("can be picked, says what it takes away, and starts a run on it", async () => {
                gameState.set(hubWithTiers([...tiers(true, true), HARDCORE]));
                const { container } = render(HubScreen);
                const sendSpy = vi.spyOn(client, "send");

                await fireEvent.click(cards(container)[DIFFICULTY_CARD.hardcore]);
                expect(rules(container)?.textContent).toContain("No hub upgrades");
                await fireEvent.click(container.querySelector("button.start-btn")!);

                expect(sendSpy).toHaveBeenCalledWith(expect.objectContaining({ action: "STARTRUN", difficulty: "hardcore" }));
                expect(get(lastStartedDifficulty)).toBe("hardcore");
            });

            test("leaves the chosen perk out of the run, and the line goes away with another tier", async () => {
                gameState.set(
                    makeState({
                        hub: {
                            upgrades: [],
                            perks: [makePerk({ id: "heavy_hand" })],
                            runsCompleted: 1,
                            difficulties: [...tiers(true, true), HARDCORE],
                        },
                    })
                );
                const { container } = render(HubScreen);
                const sendSpy = vi.spyOn(client, "send");

                await fireEvent.click(container.querySelector(".perk-card")!);
                await fireEvent.click(cards(container)[DIFFICULTY_CARD.hardcore]);
                expect(container.querySelector(".perk-list")?.classList.contains("off")).toBe(true);
                await fireEvent.click(container.querySelector("button.start-btn")!);
                expect(sendSpy.mock.calls.at(-1)![0]).not.toHaveProperty("perkId");

                await fireEvent.click(cards(container)[DIFFICULTY_CARD.hard]);
                expect(rules(container)).toBeNull();
                expect(container.querySelector(".perk-list")?.classList.contains("off")).toBe(false);
                await fireEvent.click(container.querySelector("button.start-btn")!);
                expect(sendSpy).toHaveBeenLastCalledWith(expect.objectContaining({ difficulty: "hard", perkId: "heavy_hand" }));
            });
        });
    });

    describe("the altar shut for good", () => {
        const owned = makeUpgrade({ id: "hp_boost_1", label: "Owned One", unlocked: true });
        const wanted = makeUpgrade({ id: "weapon_mastery", label: "Wanted One", cost: 90 });

        function shutHub(): StateUpdate {
            return makeState({ hub: { upgrades: [owned, wanted], perks: [], runsCompleted: 5, difficulties: [], shopClosed: true } });
        }

        const labels = (container: HTMLElement) => Array.from(container.querySelectorAll(".upgrade-label")).map((el) => el.textContent);

        test("shows what the player owns, with no cost and nothing to buy, and says the altar is shut", () => {
            gameState.set(shutHub());
            const { container } = render(HubScreen);
            expect(labels(container)).toEqual(["Owned One"]);
            expect(container.querySelector("button.buy-btn")).toBeNull();
            expect(container.querySelector(".upgrade-cost")).toBeNull();
            expect(container.querySelector(".shop-closed")?.textContent).toContain("shut for good");
        });

        test("does not say it while the altar is open, and then lists every upgrade", () => {
            gameState.set(makeState({ hub: { upgrades: [owned, wanted], perks: [], runsCompleted: 5, difficulties: [] } }));
            const { container } = render(HubScreen);
            expect(container.querySelector(".shop-closed")).toBeNull();
            expect(labels(container)).toEqual(["Owned One", "Wanted One"]);
        });

        test("the shop hint, which is about spending Shards, is not asked for", () => {
            settings.update((s) => ({ ...s, showHints: true }));
            resetTutorial();
            gameState.set(shutHub());
            render(HubScreen);
            expect(get(activeHint)).toBeNull();
        });

        test("the run perks stay on offer", () => {
            gameState.set(
                makeState({ hub: { upgrades: [owned], perks: [makePerk({ id: "heavy_hand" })], runsCompleted: 5, difficulties: [], shopClosed: true } }),
            );
            const { container } = render(HubScreen);
            expect(container.querySelectorAll(".perk-card")).toHaveLength(1);
        });
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
                hub: { upgrades, perks, runsCompleted: 1, difficulties: [] },
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
