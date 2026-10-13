import { test, expect, type Page } from "@playwright/test";
import {
    currentState,
    waitForStateChange,
    skipTutorial,
    playRunToGameOver,
    dismissPendingCards,
} from "./helpers";

/** What the cheapest hub upgrade costs, in Shards (the Warrior's basic kit). */
const CHEAPEST_UPGRADE = 25;

/** Plays one run from the Hub to its end and goes back to the Hub, reading any card on the way. */
async function playOneRunAndReturn(page: Page): Promise<void> {
    const inHub = await currentState(page);
    await page.locator("button.start-btn").click();
    const started = await waitForStateChange(page, inHub);
    expect(started.phase).toBe("EXPLORATION");
    await page.locator("canvas.game-canvas").click(); // ensure the page has keyboard focus

    const { state } = await playRunToGameOver(page, started);
    expect(state.phase, "expected the run to reach GAMEOVER within the iteration budget").toBe("GAMEOVER");

    await dismissPendingCards(page); // a win has the card of the chapter it opens
    await page.locator("button.return-btn").click();
    await expect.poll(async () => (await currentState(page))?.phase).toBe("HUB");
}

test("the first purchase at the hub brings the prologue card, which is read once", async ({ page }) => {
    page.on("pageerror", (err) => console.log(`[browser exception] ${err.message}\n${err.stack}`));

    await skipTutorial(page);
    await page.goto("/");
    await expect.poll(async () => (await currentState(page))?.phase).toBe("HUB");

    // The cheapest upgrade has to be affordable. Runs earn Shards, and a save that has finished three
    // runs without buying a starting kit is given what it lacks, so a few runs always get there.
    for (let runs = 0; runs < 4; runs++) {
        if ((await currentState(page))!.player.metaCurrency >= CHEAPEST_UPGRADE) break;
        await playOneRunAndReturn(page);
    }
    const inHub = (await currentState(page))!;
    expect(inHub.player.metaCurrency).toBeGreaterThanOrEqual(CHEAPEST_UPGRADE);
    expect(inHub.pendingCard ?? null).toBeNull();

    await page.locator("button.buy-btn:not([disabled])").first().click();
    const bought = await waitForStateChange(page, inHub);

    // The card covers the page, its first line is there and the rest follows.
    expect(bought.pendingCard).toBe("prologue");
    await expect(page.locator(".card")).toBeVisible();
    await expect(page.locator(".card-line.visible").first()).toHaveText("Thank you.");

    await dismissPendingCards(page);
    await expect(page.locator(".card")).toHaveCount(0);
    expect((await currentState(page))!.pendingCard ?? null).toBeNull();

    // Seen once, it does not come back with the next purchase or the next visit.
    await page.reload();
    await expect.poll(async () => (await currentState(page))?.phase).toBe("HUB");
    expect((await currentState(page))!.pendingCard ?? null).toBeNull();
    await expect(page.locator(".card")).toHaveCount(0);
});
