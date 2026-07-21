<script lang="ts">
    import { onMount, onDestroy } from "svelte";
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
    let renderer: Renderer | null = null;

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

    $: player          = $gameState?.player;
    $: inventory       = $gameState?.inventory ?? [];
    $: abilities       = $gameState?.abilities ?? [];
    $: resourceLabel   = player ? abilities.find((a) => a.classId === player.classId)?.resourceName ?? "Resource" : "Resource";
    $: resourceColor   = player ? RESOURCE_BAR_COLORS[player.classId] : COLOR_ENTITY_FALLBACK;

    /** Pad the inventory array to always show 6 slots (some may be null). */
    $: slots = Array.from({ length: 6 }, (_, i) => inventory[i] ?? null) as (ItemView | null)[];

    /** First letter of each word, max 2 chars — used as the slot icon. */
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
    <div class="hud-main">
        <canvas class="game-canvas" bind:this={canvasEl} />

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

                <!-- Inventory -->
                <div class="inv-section">
                    <span class="stat-label">Inventory</span>
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

    .controls-hint {
        margin-top: auto;
        font-size: 0.65rem;
        color: #3a3a3a;
        line-height: 1.6;
    }
</style>