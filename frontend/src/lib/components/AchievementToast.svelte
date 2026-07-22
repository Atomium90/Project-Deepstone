<script lang="ts">
    import { achievementToast } from "../engine/StateStore";
    import type { AchievementView } from "../engine/protocol";

    let visible: AchievementView[] = [];
    let timeoutId: ReturnType<typeof setTimeout> | undefined;

    $: if ($achievementToast.length > 0) {
        visible = $achievementToast;
        clearTimeout(timeoutId);
        timeoutId = setTimeout(() => {
            visible = [];
        }, 4000);
    }
</script>

{#if visible.length > 0}
    <div class="toast-stack">
        {#each visible as a (a.id)}
            <div class="toast">
                <span class="toast-icon">🏆</span>
                <div class="toast-body">
                    <span class="toast-title">Achievement Unlocked</span>
                    <span class="toast-label">{a.label}</span>
                </div>
            </div>
        {/each}
    </div>
{/if}

<style>
    .toast-stack {
        position: fixed;
        top: 1rem;
        right: 1rem;
        display: flex;
        flex-direction: column;
        gap: 0.5rem;
        z-index: 1000;
        pointer-events: none;
    }

    .toast {
        display: flex;
        align-items: center;
        gap: 0.6rem;
        padding: 0.6rem 1rem;
        min-width: 220px;
        background: #1e1e1e;
        border: 1px solid #c8a84b;
        border-radius: 4px;
        font-family: monospace;
        color: #ccc;
        box-shadow: 0 2px 12px rgba(0, 0, 0, 0.4);
    }

    .toast-icon {
        font-size: 1.2rem;
    }

    .toast-body {
        display: flex;
        flex-direction: column;
        gap: 0.1rem;
    }

    .toast-title {
        font-size: 0.6rem;
        text-transform: uppercase;
        letter-spacing: 0.1em;
        color: #d4ac0d;
    }

    .toast-label {
        font-size: 0.85rem;
        color: #eee;
    }
</style>
