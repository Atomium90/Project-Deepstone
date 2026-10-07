import { test, expect } from "@playwright/test";
import type { EntityView, Direction } from "../src/lib/engine/protocol";
import {
    currentState,
    waitForStateChange,
    DIRECTION_KEY,
    pathTo,
    directionBetween,
    interactTile,
    doorsFarthestFirst,
    skipTutorial,
} from "./helpers";

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
    let state = await waitForStateChange(page, beforeStart);
    expect(state.phase).toBe("EXPLORATION");

    await page.locator("canvas.game-canvas").click(); // ensure the page has keyboard focus

    let combatsResolved = 0;
    let lootPickedUp = false;
    let previousPhase = state.phase;
    let entryRoomId = "";
    let entry = { x: 0, y: 0 }; // where the player stood when they entered the current room

    // A full Normal run (two sections, each with fights, an optional branch, a boss and a rest stop)
    // takes a few hundred iterations, one per keypress, click or interaction. 800 leaves a wide margin
    // without letting a run that is genuinely stuck go on for long.
    for (let iteration = 0; iteration < 800 && state.phase !== "GAMEOVER"; iteration++) {
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

        // Movement is now blocked server-side while either modal is up (see StateMachine's Move
        // case), so these have to be resolved before anything else - the simplest deterministic
        // choice ("decline") always keeps the loop moving regardless of current loadout/gear.
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
        if (room.roomId !== entryRoomId) {
            entryRoomId = room.roomId;
            entry = { x: room.playerX, y: room.playerY };
        }
        // Only a closed chest is worth walking to: an opened one stays on the map (empty, sprung, or
        // still holding an item that was declined), so targeting it again would loop forever.
        // Targets in priority order: chests, then enemies, then the Sanctuary (the only thing that
        // ends the run, it has to outrank the doors or the loop would walk back out through the
        // entrance forever once it reaches the Sanctuary room), then doors, forward ones first.
        const doors = room.entities.filter((e) => e.kind === "door" || e.kind === "locked_door");
        const candidates: EntityView[] = [
            ...room.entities.filter((e) => e.kind === "chest" && e.state === "closed"),
            ...room.entities.filter((e) => e.kind === "enemy"),
            ...room.entities.filter((e) => e.kind === "sanctuary"),
            ...doorsFarthestFirst(doors, entry),
        ];
        // The first target that can actually be reached wins: a chest walled in by the enemies a trap
        // spawned in a one-tile corridor would otherwise make every E do nothing.
        const choice = candidates
            .map((candidate) => {
                const goal = interactTile(candidate);
                return { candidate, path: pathTo(room, goal.x, goal.y) };
            })
            .find((c) => c.path !== null);
        const target = choice?.candidate;

        if (!target) {
            // Nothing visible to interact with (e.g. an unrevealed secret door) - take a step in
            // any walkable direction to trigger InteractionResolver's proximity reveal.
            const stepDirs: Direction[] = ["UP", "RIGHT", "DOWN", "LEFT"];
            const tiles = room.tiles;
            const occupied = new Set(room.entities.map((e) => `${e.x},${e.y}`));
            const isWalkable = (x: number, y: number) =>
                y >= 0 && y < tiles.length && x >= 0 && x < tiles[y].length && tiles[y][x] === "floor" && !occupied.has(`${x},${y}`);
            const deltas: Record<Direction, [number, number]> = { UP: [0, -1], DOWN: [0, 1], LEFT: [-1, 0], RIGHT: [1, 0] };
            const dir = stepDirs.find((d) => isWalkable(room.playerX + deltas[d][0], room.playerY + deltas[d][1]));
            if (!dir) break; // truly stuck - let the assertions below report it clearly
            await page.keyboard.press(DIRECTION_KEY[dir]);
            state = await waitForStateChange(page, state);
            continue;
        }

        for (const dir of choice!.path!) {
            await page.keyboard.press(DIRECTION_KEY[dir]);
            state = await waitForStateChange(page, state);
        }

        if (target.kind === "sanctuary") {
            // No E-key path to the Sanctuary at all (see nearestInteractable's own exclusion) -
            // walking directly into its tile is what triggers it server-side (StateMachine's Move
            // handling), so this sends one more Move instead of falling into the 'e' block below.
            const dir = directionBetween(state.room!.playerX, state.room!.playerY, target.x, target.y);
            await page.keyboard.press(DIRECTION_KEY[dir]);
            state = await waitForStateChange(page, state);
            continue;
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
            // Everything needed to tell a spec problem from a game problem: where the player really
            // is, which door/entity the client itself would act on, and what the server last said.
            const clientTarget = await page.evaluate(() => window.__DEEPSTONE_RENDERER__?.nearestInteractable()?.id ?? null);
            const r = state.room!;
            throw new Error(
                `'e' near (${target.x},${target.y}) [${target.kind}:${target.id}${target.direction ? " " + target.direction : ""}] ` +
                    `produced no server response after retries - room ${r.roomId} (${r.theme}), player at ` +
                    `${r.playerX},${r.playerY}, client's nearest interactable: ${clientTarget}, last log: ${JSON.stringify(state.log.slice(-2))}`
            );
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
