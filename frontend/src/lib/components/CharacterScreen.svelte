<script lang="ts">
    import { characterTab } from "../engine/CharacterStore";
    import { settings } from "../engine/SettingsStore";
    import { minimap } from "../engine/StateStore";
    import { resetTutorial } from "../engine/HintStore";
    import { t } from "../engine/i18n";
    import { COLOR_ACHIEVEMENT_GOLD } from "../engine/constants";
    import AchievementsPanel from "./AchievementsPanel.svelte";
    import EquipmentPanel from "./EquipmentPanel.svelte";
    import MinimapPanel from "./MinimapPanel.svelte";

    /** Set once the reset button was pressed, so the press visibly did something. Cleared when the
     * Settings tab is left, so it reads "Reset" again next time. */
    let tutorialWasReset = false;
    $: if ($characterTab !== "settings") tutorialWasReset = false;

    function close(): void {
        characterTab.set(null);
    }

    function resetHints(): void {
        resetTutorial();
        tutorialWasReset = true;
    }
</script>

{#if $characterTab}
    <div class="character-overlay">
        <header class="character-header">
            <nav class="tab-bar">
                <button
                        class="tab-btn"
                        class:active={$characterTab === "equipment"}
                        on:click={() => characterTab.set("equipment")}
                >
                    Equipment
                </button>
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
                <!-- A map exists only while a run is being explored, so there is nothing to open from the Hub. -->
                {#if $minimap}
                    <button
                            class="tab-btn"
                            class:active={$characterTab === "map"}
                            on:click={() => characterTab.set("map")}
                    >
                        Map
                    </button>
                {/if}
            </nav>
            <button class="close-btn" on:click={close} title="Back to Hub">✕</button>
        </header>

        <div class="character-body">
            {#if $characterTab === "equipment"}
                <EquipmentPanel />
            {:else if $characterTab === "settings"}
                <label class="setting-row">
                    <span>Reduce screen shake</span>
                    <input
                            type="checkbox"
                            bind:checked={$settings.reduceScreenShake}
                            style="accent-color: {COLOR_ACHIEVEMENT_GOLD}"
                    />
                </label>
                <label class="setting-row">
                    <span>SFX volume</span>
                    <input
                            type="range"
                            min="0"
                            max="100"
                            bind:value={$settings.sfxVolume}
                            style="accent-color: {COLOR_ACHIEVEMENT_GOLD}"
                    />
                </label>
                <label class="setting-row">
                    <span>Music volume</span>
                    <input
                            type="range"
                            min="0"
                            max="100"
                            bind:value={$settings.musicVolume}
                            style="accent-color: {COLOR_ACHIEVEMENT_GOLD}"
                    />
                </label>
                <label class="setting-row">
                    <span>{$t("settings.showHints")}</span>
                    <input
                            type="checkbox"
                            bind:checked={$settings.showHints}
                            style="accent-color: {COLOR_ACHIEVEMENT_GOLD}"
                    />
                </label>
                <div class="setting-row">
                    <span>{$t("settings.resetTutorial")}</span>
                    <button class="setting-btn" on:click={resetHints}>
                        {tutorialWasReset ? $t("settings.resetDone") : $t("settings.resetButton")}
                    </button>
                </div>
            {:else if $characterTab === "achievements"}
                <AchievementsPanel />
            {:else if $characterTab === "map"}
                <MinimapPanel />
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
        gap: 1rem;
        max-width: 24rem;
        padding: 0.65rem 0.85rem;
        background: #161616;
        border: 1px solid #222;
        color: #ccc;
        font-size: 0.85rem;
        cursor: pointer;
    }

    .setting-row + .setting-row {
        margin-top: 0.5rem;
    }

    .setting-row input[type="checkbox"] {
        width: 1rem;
        height: 1rem;
        cursor: pointer;
    }

    .setting-row input[type="range"] {
        width: 10rem;
        cursor: pointer;
    }

    .setting-btn {
        padding: 0.25rem 0.75rem;
        background: none;
        border: 1px solid #555;
        border-radius: 3px;
        color: #ccc;
        font-family: monospace;
        font-size: 0.75rem;
        cursor: pointer;
        transition: color 0.12s, border-color 0.12s;
    }

    .setting-btn:hover {
        color: #d4ac0d;
        border-color: #d4ac0d;
    }
</style>
