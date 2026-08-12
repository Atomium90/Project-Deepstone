<script lang="ts">
    import { onMount, onDestroy } from "svelte";
    import { fly } from "svelte/transition";
    import { gameState, client, combatLog, npcDialogue } from "../engine/StateStore";
    import { Renderer } from "../engine/Renderer";
    import {
        RESOURCE_BAR_COLORS,
        HP_BAR_COLOR,
        ITEM_KIND_COLORS,
        ITEM_RARITY_COLORS,
        COLOR_ENTITY_FALLBACK,
    } from "../engine/constants";
    import type { Direction, ItemView } from "../engine/protocol";
    import StatBar from "./StatBar.svelte";
    import CombatLog from "./CombatLog.svelte";
    import NpcDialogue from "./NpcDialogue.svelte";

    let canvasEl: HTMLCanvasElement;
    let hudMainEl: HTMLDivElement;
    let renderer: Renderer | null = null;

    type WipeDirection = "up" | "down" | "left" | "right";

    let previousRoomId: string | undefined;
    let wipeDirection: WipeDirection | null = null;
    let wipeKey = 0;

    /** InteractionResolver.findSpawnPoint always places the player on the wall opposite their
     * direction of travel, so the new room's spawn position alone tells us which edge to wipe
     * in from - no extra signal beyond the room id changing is needed. */
    function travelDirection(room: { playerX: number; playerY: number; height: number }): WipeDirection {
        if (room.playerY <= 1) return "down";
        if (room.playerY >= room.height - 2) return "up";
        if (room.playerX <= 1) return "right";
        return "left";
    }

    /** The wipe panel enters from one edge and continues sweeping straight through to the
     * opposite edge, matching the direction of travel. */
    function wipeParams(direction: WipeDirection): { inX: number; inY: number; outX: number; outY: number } {
        const w = hudMainEl?.clientWidth ?? 800;
        const h = hudMainEl?.clientHeight ?? 600;
        switch (direction) {
            case "down":  return { inX: 0, inY: h, outX: 0, outY: -h };
            case "up":    return { inX: 0, inY: -h, outX: 0, outY: h };
            case "right": return { inX: w, inY: 0, outX: -w, outY: 0 };
            case "left":  return { inX: -w, inY: 0, outX: w, outY: 0 };
        }
    }

    // Map keyboard keys to game directions
    const KEY_MAP: Record<string, Direction> = {
        ArrowUp: "UP", ArrowDown: "DOWN", ArrowLeft: "LEFT", ArrowRight: "RIGHT",
        z: "UP", s: "DOWN", q: "LEFT", d: "RIGHT",
    };

    // Track which keys are currently held to avoid key repeat spam
    const heldKeys = new Set<string>();

    function handleKeyDown(e: KeyboardEvent): void {
        // Movement
        const direction = KEY_MAP[e.key];
        if (direction && !heldKeys.has(e.key)) {
            // Prevent arrow keys from scrolling the page
            e.preventDefault();
            heldKeys.add(e.key);
            client.send({ type: "MOVE", direction });
            return;
        }
        
        // Interact (E key) - guarded against native key-repeat the same way Move already is
        if ((e.key === "e" || e.key === "E") && !heldKeys.has(e.key)) {
            e.preventDefault();
            heldKeys.add(e.key);
            const entity = renderer?.nearestInteractable();
            if (entity) client.send({ type: "INTERACT", targetId: entity.id });
        }
    }

    function handleKeyUp(e: KeyboardEvent): void {
        heldKeys.delete(e.key);
    }

    onMount(() => {
        renderer = new Renderer(canvasEl);
        renderer.start();
        window.addEventListener("keydown", handleKeyDown);
        window.addEventListener("keyup", handleKeyUp);
    });

    onDestroy(() => {
        renderer?.stop();
        window.removeEventListener("keydown", handleKeyDown);
        window.removeEventListener("keyup", handleKeyUp);
    });

    // Reactively push new state to the renderer whenever the store updates
    $: if (renderer && $gameState?.room && $gameState?.player) {
        renderer.update($gameState.room, $gameState.player);
    }

    // Trigger the room-transition wipe when the room id changes. previousRoomId starts
    // undefined and gets set on the first room seen, so no wipe plays on initial spawn - and
    // since it resets on every remount (e.g. returning from combat), no wipe plays when the
    // room hasn't actually changed either.
    $: if ($gameState?.room) {
        const room = $gameState.room;
        if (previousRoomId !== undefined && room.roomId !== previousRoomId) {
            wipeDirection = travelDirection(room);
            wipeKey++;
            // Remove the overlay after the "in" leg finishes - this is what makes Svelte play
            // its out:fly transition (which only fires on removal from the DOM), sliding it the
            // rest of the way out instead of leaving it parked fully opaque forever.
            setTimeout(() => { wipeDirection = null; }, 200);
        }
        previousRoomId = room.roomId;
    }

    $: player          = $gameState?.player;
    $: equipment       = $gameState?.equipment;
    $: abilities       = $gameState?.abilities ?? [];
    $: resourceLabel   = player ? abilities.find((a) => a.classId === player.classId)?.resourceName ?? "Resource" : "Resource";
    $: resourceColor   = player ? RESOURCE_BAR_COLORS[player.classId] : COLOR_ENTITY_FALLBACK;

    /** Weapon + armor + 2 accessory slots + the potion belt, in one flat display grid. No
     * per-slot labels or icons yet - this is the simplest compiling swap from the old flat
     * inventory list; a proper per-slot Equipment tab lands in a follow-up. */
    $: slots = equipment
        ? ([equipment.weapon, equipment.armor, ...equipment.accessories, ...equipment.potionBelt] as (ItemView | null)[])
        : [];

    $: keyCount = equipment?.keys.reduce((sum, k) => sum + k.count, 0) ?? 0;

    /** First letter of each word, max 2 chars, used as the slot icon. */
    function abbrev(name: string): string {
        return name
            .split(" ")
            .map(w => w[0])
            .join("")
            .slice(0, 2)
            .toUpperCase();
    }
</script>

<!--
  Layout: the canvas fills all available space, the stats panel has a fixed
  width and sits alongside it. Both stretch to 100% height so the HUD always
  occupies the full viewport.
-->
<div class="hud-root">
    <div class="hud-main" bind:this={hudMainEl}>
        <canvas class="game-canvas" bind:this={canvasEl} />

        {#key wipeKey}
            {#if wipeDirection}
                {@const p = wipeParams(wipeDirection)}
                <div
                        class="room-wipe"
                        in:fly={{ x: p.inX, y: p.inY, duration: 200 }}
                        out:fly={{ x: p.outX, y: p.outY, duration: 200 }}
                ></div>
            {/if}
        {/key}

        <NpcDialogue dialogue={$npcDialogue} />

        {#if player}
            <aside class="stats-panel">
                <div class="class-badge">{player.classId.toUpperCase()}</div>

                <!-- HP -->
                <StatBar layout="column" label="HP" current={player.hp} max={player.maxHp} color={HP_BAR_COLOR} />

                <!-- Resource -->
                <StatBar layout="column" label={resourceLabel} current={player.resourceCurrent} max={player.resourceMax} color={resourceColor} />

                <!-- Level / XP -->
                <div class="stat-block inline">
                    <span class="stat-label">Level</span>
                    <span class="stat-value">{player.level}</span>
                </div>

                <div class="stat-block inline">
                    <span class="stat-label">XP</span>
                    <span class="stat-value">{player.xp}</span>
                </div>

                <!-- Equipment -->
                <div class="inv-section">
                    <span class="stat-label">Equipment</span>
                    <div class="inv-grid">
                        {#each slots as item}
                            <div
                                    class="inv-slot"
                                    class:occupied={!!item}
                                    style={item ? `border-color:${ITEM_RARITY_COLORS[item.rarity]}` : ""}
                                    title={item ? `${item.name}\n${item.statLine}` : "Empty"}
                            >
                                {#if item}
                                    <div
                                            class="slot-icon"
                                            style="background:{ITEM_KIND_COLORS[item.kind]}"
                                    >
                                        {abbrev(item.name)}
                                    </div>
                                    <span class="slot-stat">{item.statLine}</span>
                                {/if}
                            </div>
                        {/each}
                    </div>
                    {#if keyCount > 0}
                        <p class="key-count">🔑 {keyCount}</p>
                    {/if}
                </div>

                <p class="controls-hint">Move: ZQSD / Arrows<br />Interact: E</p>
            </aside>
        {/if}
    </div>

    <div class="exploration-log">
        <CombatLog log={$combatLog} />
    </div>
</div>

<style>
    .hud-root {
        display: flex;
        flex-direction: column;
        width: 100%;
        height: 100%;
        overflow: hidden;
    }

    .hud-main {
        position: relative;
        display: flex;
        flex: 1 1 0;
        min-height: 0;
    }

    /* Covers .hud-main entirely while sliding through, masking the instant tile-swap the
     * renderer already does underneath rather than animating the canvas content itself. */
    .room-wipe {
        position: absolute;
        inset: 0;
        z-index: 5;
        background: #0d0d0d;
        pointer-events: none;
    }

    .exploration-log {
        flex: 0 0 110px;
        padding: 0.6rem 1rem;
        background: #161616;
        border-top: 1px solid #2a2a2a;
        font-family: monospace;
    }

    /* Canvas fills all remaining space after the stats panel */
    .game-canvas {
        flex: 1 1 0;
        min-width: 0;
        height: 100%;
        display: block;
    }

    /* Stats panel: fixed width, full height, does not shrink */
    .stats-panel {
        flex: 0 0 180px;
        display: flex;
        flex-direction: column;
        gap: 1rem;
        padding: 1.25rem 1rem;
        background: #1a1a1a;
        border-left: 1px solid #2a2a2a;
        font-family: monospace;
        overflow-y: auto;
    }

    .class-badge {
        font-size: 0.65rem;
        letter-spacing: 0.18em;
        color: #666;
        border-bottom: 1px solid #2a2a2a;
        padding-bottom: 0.75rem;
    }

    .stat-block {
        display: flex;
        flex-direction: column;
        gap: 0.3rem;
    }

    .stat-block.inline {
        flex-direction: row;
        justify-content: space-between;
        align-items: center;
    }

    .stat-label {
        font-size: 0.65rem;
        color: #555;
        text-transform: uppercase;
        letter-spacing: 0.12em;
    }

    .stat-value {
        font-size: 0.78rem;
        color: #bbb;
    }

    /* ── Inventory ─────────────────────────────── */

    .inv-section {
        display: flex;
        flex-direction: column;
        gap: 0.5rem;
        border-top: 1px solid #2a2a2a;
        padding-top: 0.75rem;
    }

    .inv-grid {
        display: grid;
        grid-template-columns: repeat(3, 1fr);
        gap: 4px;
    }

    .inv-slot {
        aspect-ratio: 1;
        background: #111;
        border: 1px solid #222;
        border-radius: 2px;
        display: flex;
        flex-direction: column;
        align-items: center;
        justify-content: center;
        gap: 2px;
        cursor: default;
        transition: border-color 0.1s;
    }

    .inv-slot.occupied:hover {
        background: #1e1e1e;
    }

    .slot-icon {
        width: 22px;
        height: 22px;
        border-radius: 2px;
        display: flex;
        align-items: center;
        justify-content: center;
        font-size: 0.55rem;
        font-weight: bold;
        color: #ccc;
        letter-spacing: 0.02em;
    }

    .slot-stat {
        font-size: 0.48rem;
        color: #888;
        text-align: center;
        line-height: 1.1;
        max-width: 100%;
        overflow: hidden;
        text-overflow: ellipsis;
        white-space: nowrap;
        padding: 0 2px;
    }

    .key-count {
        font-size: 0.65rem;
        color: #999;
        margin: 0;
    }

    .controls-hint {
        margin-top: auto;
        font-size: 0.65rem;
        color: #3a3a3a;
        line-height: 1.6;
    }
</style>