import { test, expect } from "@playwright/test";
import { currentState, waitForStateChange, playRunToGameOver } from "./helpers";

// The tutorial is NOT skipped here: this is what a player meets on a brand-new save. It needs a
// database with no finished run, which the config guarantees by running this spec in a project of
// its own, before the others, on a database deleted at the start of the run.
test("the first run of a new save: start screen -> bare-handed run -> game over -> hub", async ({ page }) => {
    page.on("pageerror", (err) => console.log(`[browser exception] ${err.message}\n${err.stack}`));

    await page.goto("/");
    await expect.poll(async () => (await currentState(page))?.phase).toBe("HUB");
    const inHub = (await currentState(page))!;

    // A new save: nothing finished, no perk on offer, and the start screen instead of the Hub.
    expect(inHub.hub?.runsCompleted).toBe(0);
    expect(inHub.hub?.perks).toEqual([]);
    await expect(page.locator("button.begin-btn")).toBeVisible();
    await expect(page.locator("div.hub-root")).toHaveCount(0);

    await page.locator("button.begin-btn").click();
    const started = await waitForStateChange(page, inHub);

    // The first run is a Warrior with nothing equipped, on Easy (a single section on the map).
    expect(started.phase).toBe("EXPLORATION");
    expect(started.player.classId).toBe("warrior");
    expect(started.equipment.weapon).toBeNull();
    expect(started.equipment.armor).toBeNull();
    expect(started.minimap?.sections).toHaveLength(1);

    // The first hint comes with the first exploration update, and does not block the game.
    await expect(page.locator(".hint-card .hint-title")).toHaveText("Moving around");
    await page.locator(".hint-dismiss").click();
    await expect(page.locator(".hint-card")).toHaveCount(0);

    await page.locator("canvas.game-canvas").click(); // ensure the page has keyboard focus
    const { state: ended } = await playRunToGameOver(page, started);
    expect(ended.phase, "expected the run to reach GAMEOVER within the iteration budget").toBe("GAMEOVER");

    // Back in the Hub: the run counts, so the start screen is gone for good.
    await page.locator("button.return-btn").click();
    await expect.poll(async () => (await currentState(page))?.phase).toBe("HUB");
    const backInHub = (await currentState(page))!;
    expect(backInHub.hub?.runsCompleted).toBe(1);
    await expect(page.locator("div.hub-root")).toBeVisible();
    await expect(page.locator("button.begin-btn")).toHaveCount(0);
});
