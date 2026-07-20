<script lang="ts">
    import { gameState, client } from "../engine/StateStore";

    $: player  = $gameState?.player;
    $: victory = $gameState?.victory ?? false;

    function returnToHub(): void {
        client.send({ type: "HUB_ACTION", action: "RETURNTOHUB" });
    }
</script>

<div class="over-root" class:victory>
    <div class="over-content">

        <div class="skull">{victory ? "★" : "†"}</div>
        <h1 class="title">{victory ? "VICTORY" : "DEFEATED"}</h1>
        <p class="subtitle">
            {victory ? "You have conquered the dungeon." : "The dungeon claims another soul."}
        </p>

        <!-- Run stats -->
        {#if player}
            <div class="stats">
                <div class="stat-row">
                    <span class="stat-label">Level reached</span>
                    <span class="stat-value">{player.level}</span>
                </div>
                <div class="stat-row">
                    <span class="stat-label">Experience</span>
                    <span class="stat-value">{player.xp} XP</span>
                </div>
                <div class="stat-row shards-row">
                    <span class="stat-label">Total Shards</span>
                    <span class="stat-value shards">◈ {player.metaCurrency}</span>
                </div>
            </div>
        {/if}

        <button class="return-btn" on:click={returnToHub}>
            ↩ Return to Hub
        </button>

    </div>
</div>

<style>
    .over-root {
        width: 100%;
        height: 100%;
        display: flex;
        align-items: center;
        justify-content: center;
        background: #0e0e0e;
        font-family: monospace;
    }

    .over-content {
        display: flex;
        flex-direction: column;
        align-items: center;
        gap: 1.25rem;
        max-width: 320px;
        text-align: center;
    }

    .skull {
        font-size: 2.5rem;
        color: #c0392b;
        opacity: 0.7;
    }

    .title {
        font-size: 2rem;
        letter-spacing: 0.3em;
        color: #c0392b;
    }

    .over-root.victory .skull,
    .over-root.victory .title {
        color: #5ce07a;
    }

    .subtitle {
        font-size: 0.8rem;
        color: #444;
        letter-spacing: 0.05em;
    }

    /* ── Stats ── */

    .stats {
        width: 100%;
        margin-top: 0.5rem;
        display: flex;
        flex-direction: column;
        gap: 0.35rem;
        border-top: 1px solid #1e1e1e;
        border-bottom: 1px solid #1e1e1e;
        padding: 0.85rem 0;
    }

    .stat-row {
        display: flex;
        justify-content: space-between;
        align-items: baseline;
        padding: 0 0.25rem;
    }

    .stat-label {
        font-size: 0.72rem;
        color: #555;
        text-transform: uppercase;
        letter-spacing: 0.1em;
    }

    .stat-value {
        font-size: 0.9rem;
        color: #aaa;
    }

    .shards-row { margin-top: 0.25rem; }

    .shards {
        color: #d4ac0d;
        font-size: 1rem;
    }

    /* ── Return button ── */

    .return-btn {
        margin-top: 0.5rem;
        padding: 0.7rem 2rem;
        background: #1a1a1a;
        border: 1px solid #333;
        color: #888;
        font-family: monospace;
        font-size: 0.9rem;
        letter-spacing: 0.08em;
        cursor: pointer;
        transition: background 0.12s, border-color 0.12s, color 0.12s;
    }

    .return-btn:hover {
        background: #222;
        border-color: #555;
        color: #ccc;
    }
</style>