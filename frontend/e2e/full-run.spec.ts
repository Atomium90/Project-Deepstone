import { test, expect } from "@playwright/test";
import { currentState, waitForStateChange, skipTutorial, playRunToGameOver } from "./helpers";

test("a full run: hub -> exploration -> combat -> loot -> game over", async ({ page }) => {
    // Surfaces a client-side crash directly in the test output instead of leaving it as an
    // unexplained hang further down (e.g. a stuck waitForStateChange with no clue why).
    page.on("pageerror", (err) => console.log(`[browser exception] ${err.message}\n${err.stack}`));
    page.on("console", (msg) => {
        if (msg.type() === "error") console.log(`[browser console.error] ${msg.text()}`);
    });

    await skipTutorial(page);
    await page.goto("/");
    await expect.poll(async () => (await currentState(page))?.phase).toBe("HUB");
    const beforeStart = await currentState(page);
    await page.locator("button.start-btn").click();
    const started = await waitForStateChange(page, beforeStart);
    expect(started.phase).toBe("EXPLORATION");

    await page.locator("canvas.game-canvas").click(); // ensure the page has keyboard focus

    const { state, combatsResolved, lootPickedUp } = await playRunToGameOver(page, started);

    expect(state.phase, "expected the run to reach GAMEOVER within the iteration budget").toBe("GAMEOVER");
    expect(combatsResolved, "expected at least one combat to resolve during the run").toBeGreaterThan(0);
    // Not a hard assertion: a chest can be trapped (no loot, spawns enemies instead) and enemy
    // drops are a 25-40% chance, not guaranteed, so a short/unlucky run can legitimately end
    // without ever logging a pickup. The chest-priority target selection above still actively
    // seeks loot when one is visible; this just doesn't fail the whole run over bad luck.
    if (!lootPickedUp) {
        test.info().annotations.push({ type: "warning", description: "no loot pickup logged this run - see comment above" });
    }

    await expect(page.locator("div.over-root")).toBeVisible();
});
