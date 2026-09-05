import { test, expect, type Page } from "@playwright/test";
import type { StateUpdate, EntityView, Direction } from "../src/lib/engine/protocol";

declare global {
    interface Window {
        __DEEPSTONE_STATE__?: StateUpdate | null;
        __DEEPSTONE_RENDERER__?: { nearestInteractable(): EntityView | null };
    }
}

async function currentState(page: Page): Promise<StateUpdate | null> {
    return page.evaluate(() => window.__DEEPSTONE_STATE__ ?? null);
}

/** Waits until the state exposed on `window` (StateStore.ts's __DEEPSTONE_STATE__ mirror) differs
 * from `previous`, returning the new value. Reads the same store every component reacts to,
 * instead of independently observing WebSocket traffic at the network level - that was the first
 * approach tried here, and it proved unreliable: no guarantee of observing frames in the same
 * order or timing as the page's own reactivity, which made both movement and interact flaky. */
async function waitForStateChange(page: Page, previous: StateUpdate | null, timeoutMs = 10_000): Promise<StateUpdate> {
    const previousJson = JSON.stringify(previous);
    await expect
        .poll(async () => JSON.stringify(await currentState(page)), { timeout: timeoutMs, intervals: [50] })
        .not.toBe(previousJson);
    return (await currentState(page))!;
}

const DIRECTION_KEY: Record<Direction, string> = {
    UP: "ArrowUp",
    DOWN: "ArrowDown",
    LEFT: "ArrowLeft",
    RIGHT: "ArrowRight",
};

function chebyshev(x1: number, y1: number, x2: number, y2: number): number {
    return Math.max(Math.abs(x1 - x2), Math.abs(y1 - y2));
}

/** 4-directional BFS from the player's tile to a walkable tile within Chebyshev distance 1 of
 * (targetX, targetY) - a door embedded in a wall isn't itself walkable, only adjacent to it is. */
function pathTo(room: NonNullable<StateUpdate["room"]>, targetX: number, targetY: number): Direction[] {
    const tiles = room.tiles;
    const isFloor = (x: number, y: number) =>
        y >= 0 && y < tiles.length && x >= 0 && x < tiles[y].length && tiles[y][x] === "floor";
    const start = { x: room.playerX, y: room.playerY };
    if (chebyshev(start.x, start.y, targetX, targetY) <= 1) return [];

    const steps: { dir: Direction; dx: number; dy: number }[] = [
        { dir: "UP", dx: 0, dy: -1 },
        { dir: "DOWN", dx: 0, dy: 1 },
        { dir: "LEFT", dx: -1, dy: 0 },
        { dir: "RIGHT", dx: 1, dy: 0 },
    ];
    const visited = new Set<string>([`${start.x},${start.y}`]);
    const queue: { x: number; y: number; path: Direction[] }[] = [{ ...start, path: [] }];
    while (queue.length > 0) {
        const cur = queue.shift()!;
        if (chebyshev(cur.x, cur.y, targetX, targetY) <= 1) return cur.path;
        for (const s of steps) {
            const nx = cur.x + s.dx;
            const ny = cur.y + s.dy;
            const key = `${nx},${ny}`;
            if (visited.has(key) || !isFloor(nx, ny)) continue;
            visited.add(key);
            queue.push({ x: nx, y: ny, path: [...cur.path, s.dir] });
        }
    }
    return [];
}

/** Prefers the door farthest from the player's current position. InteractionResolver.findSpawnPoint
 * always spawns the player on the wall opposite their direction of travel, so the door they just
 * came through is always the nearest one - the farthest door is the way forward, not back. */
function pickDoor(room: NonNullable<StateUpdate["room"]>, doors: EntityView[]): EntityView {
    return doors.reduce((farthest, d) =>
        chebyshev(room.playerX, room.playerY, d.x, d.y) > chebyshev(room.playerX, room.playerY, farthest.x, farthest.y)
            ? d
            : farthest
    );
}

test("a full run: hub -> exploration -> combat -> loot -> game over", async ({ page }) => {
    // Surfaces a client-side crash directly in the test output instead of leaving it as an
    // unexplained hang further down (e.g. a stuck waitForStateChange with no clue why).
    page.on("pageerror", (err) => console.log(`[browser exception] ${err.message}\n${err.stack}`));
    page.on("console", (msg) => {
        if (msg.type() === "error") console.log(`[browser console.error] ${msg.text()}`);
    });

    await page.goto("/");
    await expect.poll(async () => (await currentState(page))?.phase).toBe("HUB");
    const beforeStart = await currentState(page);
    await page.locator("button.start-btn").click();
    let state = await waitForStateChange(page, beforeStart);
    expect(state.phase).toBe("EXPLORATION");

    await page.locator("canvas.game-canvas").click(); // ensure the page has keyboard focus

    let combatsResolved = 0;
    let lootPickedUp = false;
    let previousPhase = state.phase;

    // Normal difficulty's dungeon grew substantially once biomes landed (entrance + 2 biomes of 4
    // rooms each + a MiniBoss checkpoint + boss, vs. the old flat 4-room dungeon) - the old budget
    // of 80 was sized for that smaller shape and no longer covers a full run reliably.
    for (let iteration = 0; iteration < 300 && state.phase !== "GAMEOVER"; iteration++) {
        if (state.phase !== previousPhase) {
            // App.svelte re-keys the whole phase component on every transition ({#key $gamePhase},
            // a 220ms crossfade) - the outgoing instance (old listeners, old DOM elements) can
            // briefly coexist with the incoming one until the fade finishes. Settling here, once
            // per actual phase change rather than per action, avoided several distinct symptoms of
            // the same race: a duplicate "Attack" button (Playwright strict-mode violation), and
            // movement/interact presses handled twice (once by each coexisting listener).
            await page.waitForTimeout(300);
            previousPhase = state.phase;
        }

        if (state.phase === "COMBAT") {
            const attackButton = page.locator("button.action-btn.attack");
            await expect(attackButton).toHaveCount(1);
            await attackButton.click();
            const next = await waitForStateChange(page, state);
            if (next.phase !== "COMBAT") combatsResolved++;
            state = next;
            continue;
        }

        await page.waitForFunction(() => window.__DEEPSTONE_RENDERER__ != null, { timeout: 5000 });

        const room = state.room!;
        const chest = room.entities.find((e) => e.kind === "chest");
        const enemy = room.entities.find((e) => e.kind === "enemy");
        const doors = room.entities.filter((e) => e.kind === "door" || e.kind === "locked_door");
        const target: EntityView | undefined = chest ?? enemy ?? (doors.length > 0 ? pickDoor(room, doors) : undefined);

        if (!target) {
            // Nothing visible to interact with (e.g. an unrevealed secret door) - take a step in
            // any walkable direction to trigger InteractionResolver's proximity reveal.
            const stepDirs: Direction[] = ["UP", "RIGHT", "DOWN", "LEFT"];
            const tiles = room.tiles;
            const isFloor = (x: number, y: number) => y >= 0 && y < tiles.length && x >= 0 && x < tiles[y].length && tiles[y][x] === "floor";
            const deltas: Record<Direction, [number, number]> = { UP: [0, -1], DOWN: [0, 1], LEFT: [-1, 0], RIGHT: [1, 0] };
            const dir = stepDirs.find((d) => isFloor(room.playerX + deltas[d][0], room.playerY + deltas[d][1]));
            if (!dir) break; // truly stuck - let the assertions below report it clearly
            await page.keyboard.press(DIRECTION_KEY[dir]);
            state = await waitForStateChange(page, state);
            continue;
        }

        for (const dir of pathTo(room, target.x, target.y)) {
            await page.keyboard.press(DIRECTION_KEY[dir]);
            state = await waitForStateChange(page, state);
        }

        // nearestInteractable() (what the 'e' key handler actually checks) reads the renderer's
        // own room state, updated by a Svelte reactive statement a tick after gameState changes -
        // wait for the real condition instead of guessing a settle delay.
        await page
            .waitForFunction(() => window.__DEEPSTONE_RENDERER__?.nearestInteractable() != null, { timeout: 2000 })
            .catch(() => {}); // best-effort; the retry loop below still catches a genuine miss

        const beforeInteract = state;
        let interacted = false;
        for (let attempt = 0; attempt < 5 && !interacted; attempt++) {
            await page.keyboard.press("e");
            try {
                state = await waitForStateChange(page, beforeInteract, 500);
                interacted = true;
            } catch {
                // No response yet - retry.
            }
        }
        if (!interacted) {
            throw new Error(`'e' near (${target.x},${target.y}) [${target.kind}] produced no server response after retries`);
        }

        // Matches the server's actual log wording: "You open the chest and find X!" and
        // "<enemy> dropped X!" (the chest and enemy-drop pickup paths in
        // InteractionResolver/CombatResolver) - `log` reflects only the action that produced this
        // StateUpdate, not a cumulative history.
        if (state.log.some((l) => /find|dropped|equip/i.test(l))) lootPickedUp = true;
    }

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
