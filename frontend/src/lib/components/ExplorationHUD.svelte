<script lang="ts">
    import { onMount, onDestroy } from "svelte";
    import { gameState, client } from "../engine/StateStore";
    import { Renderer } from "../engine/Renderer";
    import { RESOURCE_LABELS, RESOURCE_BAR_COLORS, HP_BAR_COLOR } from "../engine/constants";
    import type { Direction } from "../engine/protocol";

    let canvasEl: HTMLCanvasElement;
    let renderer: Renderer | null = null;

    // Map keyboard keys to game directions
    const KEY_MAP: Record<string, Direction> = {
        ArrowUp: "UP",
        ArrowDown: "DOWN",
        ArrowLeft: "LEFT",
        ArrowRight: "RIGHT",
        z: "UP",
        s: "DOWN",
        q: "LEFT",
        d: "RIGHT",
    };

    // Track which keys are currently held to avoid key repeat spam
    const heldKeys = new Set<string>();

    function handleKeyDown(e: KeyboardEvent): void {
        // Movement
        const direction = KEY_MAP[e.key];
        if (direction && heldKeys.has(e.key)) {
            // Prevent arrow keys from scrolling the page
            e.preventDefault();
            heldKeys.add(e.key);
            client.send({ type: "MOVE", direction });
            return;
        }
        
        // Interact (E key)
        if (e.key == "e" || e.key == "E") {
            e.preventDefault();
            const entity = renderer?.nearestInteractable();
            if (entity) {
                client.send({ type: "INTERACT", targetId: entity.id });
            }
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

    // Derived values for the stats overlay
    $: player = $gameState?.player;
    $: hpPercent = player ? (player.hp / player.maxHp) * 100 : 100;
    $: resourcePercent = player ? (player.resourceCurrent / player.resourceMax) * 100 : 100;
    $: resourceLabel   = player ? RESOURCE_LABELS[player.classId] : "Resource";
    $: resourceColor   = player ? RESOURCE_BAR_COLORS[player.classId] : "#888";
</script>

<!--
  Layout: the canvas fills all available space, the stats panel has a fixed
  width and sits alongside it. Both stretch to 100% height so the HUD always
  occupies the full viewport.
-->
<div class="hud-root">
    <canvas class="game-canvas" bind:this={canvasEl} />

    {#if player}
        <aside class="stats-panel">
            <div class="class-badge">{player.classId.toUpperCase()}</div>

            <div class="stat-block">
                <span class="stat-label">HP</span>
                <div class="bar-track">
                    <div class="bar" style="width:{hpPercent}%; background:{HP_BAR_COLOR}" />
                </div>
                <span class="stat-value">{player.hp} / {player.maxHp}</span>
            </div>

            <div class="stat-block">
                <span class="stat-label">{resourceLabel}</span>
                <div class="bar-track">
                    <div class="bar" style="width:{resourcePercent}%; background:{resourceColor}" />
                </div>
                <span class="stat-value">{player.resourceCurrent} / {player.resourceMax}</span>
            </div>

            <div class="stat-block inline">
                <span class="stat-label">Level</span>
                <span class="stat-value">{player.level}</span>
            </div>

            <div class="stat-block inline">
                <span class="stat-label">XP</span>
                <span class="stat-value">{player.xp}</span>
            </div>

            <p class="controls-hint">Move: ZQSD / Arrows<br />Interact: E</p>
        </aside>
    {/if}
</div>

<style>
    .hud-root {
        display: flex;
        width: 100%;
        height: 100%;
        overflow: hidden;
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

    .bar-track {
        height: 5px;
        background: #252525;
        border-radius: 2px;
        overflow: hidden;
    }

    .bar {
        height: 100%;
        border-radius: 2px;
        transition: width 0.15s ease;
    }

    .controls-hint {
        margin-top: auto;
        font-size: 0.65rem;
        color: #3a3a3a;
        line-height: 1.6;
    }
</style>