<script lang="ts">
    import { characterTab } from "../engine/CharacterStore";
    import { settings } from "../engine/SettingsStore";
    import { COLOR_ACHIEVEMENT_GOLD } from "../engine/constants";
    import AchievementsPanel from "./AchievementsPanel.svelte";

    function close(): void {
        characterTab.set(null);
    }
</script>

{#if $characterTab}
    <div class="character-overlay">
        <header class="character-header">
            <nav class="tab-bar">
                <button
                        class="tab-btn"
                        class:active={$characterTab === "settings"}
                        on:click={() => characterTab.set("settings")}
                >
                    Settings
                </button>
                <button
                        class="tab-btn"
                        class:active={$characterTab === "achievements"}
                        on:click={() => characterTab.set("achievements")}
                >
                    Achievements
                </button>
            </nav>
            <button class="close-btn" on:click={close} title="Back to Hub">✕</button>
        </header>

        <div class="character-body">
            {#if $characterTab === "settings"}
                <label class="setting-row">
                    <span>Reduce screen shake</span>
                    <input
                            type="checkbox"
                            bind:checked={$settings.reduceScreenShake}
                            style="accent-color: {COLOR_ACHIEVEMENT_GOLD}"
                    />
                </label>
            {:else if $characterTab === "achievements"}
                <AchievementsPanel />
            {/if}
        </div>
    </div>
{/if}

<style>
    .character-overlay {
        position: fixed;
        inset: 0;
        z-index: 500;
        background: #111;
        font-family: monospace;
        display: flex;
        flex-direction: column;
    }

    .character-header {
        display: flex;
        align-items: center;
        justify-content: space-between;
        padding: 0 1.5rem;
        border-bottom: 1px solid #1e1e1e;
        flex-shrink: 0;
    }

    .tab-bar {
        display: flex;
        gap: 0.5rem;
    }

    .tab-btn {
        background: none;
        border: none;
        border-bottom: 2px solid transparent;
        color: #777;
        font-family: monospace;
        font-size: 0.85rem;
        letter-spacing: 0.05em;
        padding: 1rem 0.75rem;
        cursor: pointer;
        transition: color 0.12s, border-color 0.12s;
    }

    .tab-btn:hover { color: #ccc; }

    .tab-btn.active {
        color: #d4ac0d;
        border-bottom-color: #d4ac0d;
    }

    .close-btn {
        background: none;
        border: none;
        color: #777;
        font-size: 1.1rem;
        cursor: pointer;
        padding: 0.5rem;
        transition: color 0.12s;
    }

    .close-btn:hover { color: #ccc; }

    .character-body {
        flex: 1;
        padding: 2rem;
        overflow-y: auto;
    }

    .setting-row {
        display: flex;
        align-items: center;
        justify-content: space-between;
        max-width: 24rem;
        padding: 0.65rem 0.85rem;
        background: #161616;
        border: 1px solid #222;
        color: #ccc;
        font-size: 0.85rem;
        cursor: pointer;
    }

    .setting-row input[type="checkbox"] {
        width: 1rem;
        height: 1rem;
        cursor: pointer;
    }
</style>
