import { test, expect } from "@playwright/test";
import { currentState, waitForStateChange, skipTutorial, pathTo, DIRECTION_KEY } from "./helpers";

test("a key set in Settings interacts, and the old one no longer does", async ({ page }) => {
    page.on("pageerror", (err) => console.log(`[browser exception] ${err.message}\n${err.stack}`));

    await skipTutorial(page);
    await page.goto("/");
    await expect.poll(async () => (await currentState(page))?.phase).toBe("HUB");
    const beforeStart = await currentState(page);
    await page.locator("button.start-btn").click();
    let state = await waitForStateChange(page, beforeStart);
    expect(state.phase).toBe("EXPLORATION");
    await page.locator("canvas.game-canvas").click(); // ensure the page has keyboard focus
    await page.waitForTimeout(500);

    // Set F as the interact key in Settings, opened over the run.
    await page.keyboard.press("i");
    await page.locator(".tab-btn", { hasText: /^Settings$/ }).click();
    await page.locator('[data-action="interact"]').click();
    await page.keyboard.press("f");
    await expect(page.locator('[data-action="interact"]')).toHaveText("F");
    await page.locator(".close-btn").click();
    await expect(page.locator(".character-overlay")).toHaveCount(0);

    // The choice is kept for the next visit.
    const stored = await page.evaluate(() => JSON.parse(localStorage.getItem("deepstone-settings") ?? "{}"));
    expect(stored.keys).toEqual({ interact: "KeyF" });

    // Walk next to the first enemy.
    state = (await currentState(page))!;
    const enemy = state.room!.entities.find((e) => e.kind === "enemy")!;
    for (const dir of pathTo(state.room!, enemy.x, enemy.y)!) {
        await page.keyboard.press(DIRECTION_KEY[dir]);
        state = await waitForStateChange(page, state);
    }
    await page.waitForFunction(() => window.__DEEPSTONE_RENDERER__?.nearestInteractable() != null, { timeout: 3000 });

    // E is no longer the key: nothing starts.
    await page.keyboard.press("e");
    await page.waitForTimeout(500);
    expect((await currentState(page))!.phase).toBe("EXPLORATION");

    // F is.
    await page.keyboard.press("f");
    await expect.poll(async () => (await currentState(page))?.phase).toBe("COMBAT");
});
