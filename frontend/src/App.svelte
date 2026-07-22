<script lang="ts">
    import { onMount } from "svelte";
    import { gameState, gamePhase, connectToServer } from "./lib/engine/StateStore";
    import ExplorationHUD  from "./lib/components/ExplorationHUD.svelte";
    import CombatScreen    from "./lib/components/CombatScreen.svelte";
    import HubScreen       from "./lib/components/HubScreen.svelte";
    import GameOverScreen  from "./lib/components/GameOverScreen.svelte";
    import AchievementToast from "./lib/components/AchievementToast.svelte";

    onMount(() => {
        connectToServer();
    });
</script>

<main>
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

    <AchievementToast />
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