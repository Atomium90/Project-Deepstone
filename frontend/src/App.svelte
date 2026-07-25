<script lang="ts">
    import { onMount } from "svelte";
    import { fade } from "svelte/transition";
    import { gameState, gamePhase, connectToServer } from "./lib/engine/StateStore";
    import { assets } from "./lib/engine/AssetManager";
    import { audio } from "./lib/engine/AudioManager";
    import ExplorationHUD  from "./lib/components/ExplorationHUD.svelte";
    import CombatScreen    from "./lib/components/CombatScreen.svelte";
    import HubScreen       from "./lib/components/HubScreen.svelte";
    import GameOverScreen  from "./lib/components/GameOverScreen.svelte";
    import AchievementToast from "./lib/components/AchievementToast.svelte";
    import CharacterScreen  from "./lib/components/CharacterScreen.svelte";

    onMount(() => {
        connectToServer();
        assets.preloadAtlases();
        audio.init();
    });
</script>

<main>
    {#key $gamePhase ?? "connecting"}
        <div class="phase-transition" transition:fade={{ duration: 220 }}>
            {#if $gameState === null}
                <div class="connecting">
                    <p>Connecting to server…</p>
                </div>

            {:else if $gamePhase === "HUB"}
                <HubScreen />

            {:else if $gamePhase === "EXPLORATION"}
                <ExplorationHUD />

            {:else if $gamePhase === "COMBAT"}
                <CombatScreen />

            {:else if $gamePhase === "GAMEOVER"}
                <GameOverScreen />

            {/if}
        </div>
    {/key}

    <AchievementToast />
    <CharacterScreen />
</main>

<style>
    :global(*, *::before, *::after) {
        box-sizing: border-box;
        margin: 0;
        padding: 0;
    }

    :global(body) {
        background: #111;
        color: #eee;
        font-family: monospace;
    }

    main {
        width: 100vw;
        height: 100vh;
        position: relative;
    }

    /* Absolutely positioned so the outgoing and incoming phase overlap in place during the
     * fade crossfade, instead of briefly stacking one after another in normal flow. */
    .phase-transition {
        position: absolute;
        inset: 0;
        width: 100%;
        height: 100%;
    }

    .connecting {
        width: 100%;
        height: 100%;
        display: flex;
        align-items: center;
        justify-content: center;
        color: #444;
        font-family: monospace;
        font-size: 0.875rem;
        letter-spacing: 0.1em;
    }
</style>