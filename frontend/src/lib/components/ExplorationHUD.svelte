<script lang="ts">
    import { onMount, onDestroy } from "svelte";
    import { gameState, client } from "../engine/StateStore";
    import { Renderer } from "../engine/Renderer";
    import type { Direction } from "../engine/protocol";

    let canvasEl: HTMLCanvasElement;
    let renderer: Renderer | null = null;

    // Map keyboard keys to game directions
    const KEY_MAP: Record<string, Direction> = {
        ArrowUp: "UP",
        ArrowDown: "DOWN",
        ArrowLeft: "LEFT",
        ArrowRight: "RIGHT",
        w: "UP",
        s: "DOWN",
        a: "LEFT",
        d: "RIGHT",
    };

    // Track which keys are currently held to avoid key repeat spam
    const heldKeys = new Set<string>();

    function handleKeyDown(e: KeyboardEvent): void {
        const direction = KEY_MAP[e.key];
        if (!direction || heldKeys.has(e.key)) return;

        // Prevent arrow keys from scrolling the page
        e.preventDefault();
        heldKeys.add(e.key);

        client.send({ type: "MOVE", direction });
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
    $: resourceLabel = player?.classId === "warrior"
        ? "Rage"
        : player?.classId === "archer"
            ? "Focus"
            : "Mana";
</script>

<div class="exploration-root">
    <!-- Canvas — the dungeon room -->
    <div class="canvas-wrapper">
        <canvas bind:this={canvasEl} width={640} height={512} />
        <p class="controls-hint">Move: Arrow keys or WASD</p>
    </div>

    <!-- Stats overlay -->
    {#if player}
        <aside class="stats-panel">
            <div class="class-badge">{player.classId.toUpperCase()}</div>

            <div class="stat-block">
                <span class="stat-label">HP</span>
                <div class="bar-track">
                    <div class="bar hp-bar" style="width: {hpPercent}%" />
                </div>
                <span class="stat-value">{player.hp} / {player.maxHp}</span>
            </div>

            <div class="stat-block">
                <span class="stat-label">{resourceLabel}</span>
                <div class="bar-track">
                    <div class="bar resource-bar" style="width: {resourcePercent}%" />
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
        </aside>
    {/if}
</div>

<style>
    .exploration-root {
        display: flex;
        gap: 1.5rem;
        align-items: flex-start;
        padding: 1.5rem;
        height: 100%;
    }

    .canvas-wrapper {
        display: flex;
        flex-direction: column;
        align-items: flex-start;
        gap: 0.5rem;
    }

    canvas {
        display: block;
        border: 1px solid #333;
        image-rendering: pixelated; /* keeps sprites crisp if we add pixel art later */
    }

    .controls-hint {
        font-size: 0.75rem;
        color: #555;
        font-family: monospace;
    }

    /* -- Stats panel ------------------------------------------- */

    .stats-panel {
        display: flex;
        flex-direction: column;
        gap: 1rem;
        min-width: 160px;
        padding: 1rem;
        background: #1a1a1a;
        border: 1px solid #333;
        font-family: monospace;
    }

    .class-badge {
        font-size: 0.7rem;
        letter-spacing: 0.15em;
        color: #888;
        border-bottom: 1px solid #333;
        padding-bottom: 0.5rem;
    }

    .stat-block {
        display: flex;
        flex-direction: column;
        gap: 0.25rem;
    }

    .stat-block.inline {
        flex-direction: row;
        justify-content: space-between;
        align-items: center;
    }

    .stat-label {
        font-size: 0.7rem;
        color: #666;
        text-transform: uppercase;
        letter-spacing: 0.1em;
    }

    .stat-value {
        font-size: 0.8rem;
        color: #ccc;
    }

    .bar-track {
        height: 6px;
        background: #2a2a2a;
        border-radius: 2px;
        overflow: hidden;
    }

    .bar {
        height: 100%;
        border-radius: 2px;
        transition: width 0.2s ease;
    }

    .hp-bar {
        background: #c0392b;
    }

    .resource-bar {
        background: #2980b9;
    }
</style>