<script lang="ts">
    import { gameState } from "../engine/StateStore";

    $: achievements = $gameState?.achievements ?? [];
</script>

<div class="achievements-panel">
    <p class="section-label">Achievements</p>

    {#if achievements.length === 0}
        <p class="muted">No achievements loaded.</p>
    {:else}
        <div class="achievement-list">
            {#each achievements as a (a.id)}
                <div class="achievement-row" class:owned={a.unlocked}>
                    <div class="achievement-info">
                        <span class="achievement-label">
                            {a.unlocked ? "🏆" : "🔒"} {a.label}
                        </span>
                        <span class="achievement-desc">{a.description}</span>
                    </div>
                    {#if a.unlocked}
                        <span class="owned-badge">✓ Unlocked</span>
                    {/if}
                </div>
            {/each}
        </div>
    {/if}
</div>

<style>
    .achievements-panel {
        display: flex;
        flex-direction: column;
        margin-top: 1.5rem;
    }

    .section-label {
        font-size: 0.65rem;
        text-transform: uppercase;
        letter-spacing: 0.15em;
        color: #444;
        margin-bottom: 1rem;
    }

    .achievement-list {
        display: flex;
        flex-direction: column;
        gap: 0.4rem;
    }

    .achievement-row {
        display: flex;
        align-items: center;
        justify-content: space-between;
        gap: 1rem;
        padding: 0.65rem 0.85rem;
        background: #161616;
        border: 1px solid #222;
        transition: border-color 0.1s;
    }

    .achievement-row:hover:not(.owned) { border-color: #333; }
    .achievement-row.owned { opacity: 0.85; border-color: #2a4a2a; }

    .achievement-info {
        display: flex;
        flex-direction: column;
        gap: 0.15rem;
        min-width: 0;
    }

    .achievement-label {
        font-size: 0.85rem;
        color: #ccc;
    }

    .achievement-desc {
        font-size: 0.7rem;
        color: #555;
    }

    .owned-badge {
        font-size: 0.72rem;
        color: #3a7a4a;
        letter-spacing: 0.05em;
        flex-shrink: 0;
    }

    .muted { color: #444; font-size: 0.85rem; }
</style>
