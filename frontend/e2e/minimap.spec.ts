import { test, expect, type Page } from "@playwright/test";
import type { StateUpdate } from "../src/lib/engine/protocol";
import { currentState, waitForStateChange, DIRECTION_KEY, pathTo, interactTile, doorsFarthestFirst } from "./helpers";

/** Walks the player out of the room they are in, through its door. A fight is only taken when an
 * enemy stands in the way, and a trapped door's guardian is beaten the same way, so what is left
 * is a run that really did move on to the next room. */
async function leaveRoom(page: Page, from: StateUpdate): Promise<StateUpdate> {
    let state = from;
    const startRoomId = state.room!.roomId;
    const entry = { x: state.room!.playerX, y: state.room!.playerY };
    let previousPhase = state.phase;

    for (let iteration = 0; iteration < 150 && state.phase !== "GAMEOVER" && state.room?.roomId === startRoomId; iteration++) {
        if (state.phase !== previousPhase) {
            // App.svelte crossfades between phases, and the outgoing screen's listeners briefly live on.
            await page.waitForTimeout(300);
            previousPhase = state.phase;
        }

        if (state.phase === "COMBAT") {
            await page.locator("button.action-btn.attack").click();
            state = await waitForStateChange(page, state);
            continue;
        }
        if (state.pendingEquipChoice) {
            await page.locator("button.keep-btn").click();
            state = await waitForStateChange(page, state);
            continue;
        }
        if (state.pendingRewardChoice) {
            await page.locator("button.leave-btn").click();
            state = await waitForStateChange(page, state);
            continue;
        }

        await page.waitForFunction(() => window.__DEEPSTONE_RENDERER__ != null, { timeout: 5000 });

        const room = state.room!;
        const doors = room.entities.filter((e) => e.kind === "door");
        const enemies = room.entities.filter((e) => e.kind === "enemy");
        const choice = [...doorsFarthestFirst(doors, entry), ...enemies]
            .map((candidate) => {
                const goal = interactTile(candidate);
                return { candidate, path: pathTo(room, goal.x, goal.y) };
            })
            .find((c) => c.path !== null);
        if (!choice) throw new Error(`nothing to walk to in room ${room.roomId}: no door or enemy can be reached`);

        for (const dir of choice.path!) {
            await page.keyboard.press(DIRECTION_KEY[dir]);
            state = await waitForStateChange(page, state);
        }

        await page
            .waitForFunction(() => window.__DEEPSTONE_RENDERER__?.nearestInteractable() != null, { timeout: 2000 })
            .catch(() => {});

        const beforeInteract = state;
        for (let attempt = 0; attempt < 5 && state === beforeInteract; attempt++) {
            await page.keyboard.press("e");
            try {
                state = await waitForStateChange(page, beforeInteract, 500);
            } catch {
                // No response yet, press again.
            }
        }
    }
    return state;
}

test("the map shows the run, pauses it while open, and follows the player through a door", async ({ page }) => {
    page.on("pageerror", (err) => console.log(`[browser exception] ${err.message}\n${err.stack}`));

    await page.goto("/");
    await expect.poll(async () => (await currentState(page))?.phase).toBe("HUB");
    const inHub = await currentState(page);
    await page.locator("button.start-btn").click();
    let state = await waitForStateChange(page, inHub);
    expect(state.phase).toBe("EXPLORATION");
    await page.locator("canvas.game-canvas").click(); // ensure the page has keyboard focus

    // What the server sent: every room is on the map, the one the player is in is the only one
    // visited, and a room the player has not seen shows its type only when it is a boss, a
    // mini-boss or the Sanctuary.
    const first = state.minimap!;
    expect(first.nodes.length).toBeGreaterThan(1);
    expect(first.nodes.filter((n) => n.current)).toHaveLength(1);
    expect(first.nodes.filter((n) => n.visited)).toHaveLength(1);
    expect(first.nodes.filter((n) => n.roomType === "sanctuary")).toHaveLength(1);
    expect(first.nodes.filter((n) => n.roomType === "boss").length).toBeGreaterThan(0);
    for (const n of first.nodes.filter((node) => !node.visited && node.roomType !== null)) {
        expect(["boss", "miniboss", "sanctuary"], `an unvisited ${n.roomType} room is shown`).toContain(n.roomType);
    }
    for (const n of first.nodes) expect(n.id, "a node id is not opaque").toMatch(/^n\d+$/);

    // M opens the Map tab with one node per room, the current one marked.
    await page.keyboard.press("m");
    await expect(page.locator("[data-node]")).toHaveCount(first.nodes.length);
    await expect(page.locator('[data-kind="current"]')).toHaveCount(1);
    await expect(page.locator(".tab-btn.active")).toHaveText("Map");

    // The run is paused behind the open screen: the player does not walk.
    const paused = (await currentState(page))!.room!;
    for (const key of ["ArrowDown", "ArrowRight", "ArrowUp", "ArrowLeft", "z", "q", "s", "d"]) {
        await page.keyboard.press(key);
    }
    await page.waitForTimeout(400);
    const stillPaused = (await currentState(page))!.room!;
    expect([stillPaused.playerX, stillPaused.playerY]).toEqual([paused.playerX, paused.playerY]);

    // M closes it again.
    await page.keyboard.press("m");
    await expect(page.locator(".character-overlay")).toHaveCount(0);

    // Going through a door makes the new room current and the old one visited.
    state = await leaveRoom(page, (await currentState(page))!);
    expect(state.phase, "the run ended before leaving the first room").not.toBe("GAMEOVER");
    const second = state.minimap!;
    expect(second.nodes.filter((n) => n.visited)).toHaveLength(2);
    expect(second.nodes.find((n) => n.current)!.id).not.toBe(first.nodes.find((n) => n.current)!.id);

    await page.keyboard.press("m");
    await expect(page.locator('[data-kind="visited"]')).toHaveCount(1);
    await expect(page.locator('[data-kind="current"]')).toHaveCount(1);
});
