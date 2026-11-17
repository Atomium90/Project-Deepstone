import { test, expect } from "@playwright/test";
import { currentState, waitForStateChange, skipTutorial } from "./helpers";

test("the movement keys are the key places W A S D, and the letters Z and Q of another layout's places are not", async ({ page }) => {
    page.on("pageerror", (err) => console.log(`[browser exception] ${err.message}\n${err.stack}`));

    await skipTutorial(page);
    await page.goto("/");
    await expect.poll(async () => (await currentState(page))?.phase).toBe("HUB");
    const beforeStart = await currentState(page);
    await page.locator("button.start-btn").click();
    const started = await waitForStateChange(page, beforeStart);
    expect(started.phase).toBe("EXPLORATION");
    await page.locator("canvas.game-canvas").click(); // ensure the page has keyboard focus
    await page.waitForTimeout(500);

    const at = async () => {
        const room = (await currentState(page))!.room!;
        return [room.playerX, room.playerY];
    };
    const origin = await at();

    // Z and Q are the places of the QWERTY keys that sit next to W: not movement keys here.
    for (const code of ["KeyZ", "KeyQ"]) await page.keyboard.press(code);
    await page.waitForTimeout(400);
    expect(await at()).toEqual(origin);

    // The key block under the left hand moves the player, whatever the layout prints on it.
    let moved = false;
    for (const code of ["KeyW", "KeyS", "KeyA", "KeyD"]) {
        await page.keyboard.press(code);
        await page.waitForTimeout(250);
        if (JSON.stringify(await at()) !== JSON.stringify(origin)) {
            moved = true;
            break;
        }
    }
    expect(moved, "none of W, S, A, D moved the player").toBe(true);
});
