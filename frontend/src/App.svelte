<script lang="ts">
  import { onMount } from "svelte";
  import { gameState, gamePhase, connectToServer, client } from "./lib/engine/StateStore";
  import ExplorationHUD from "./lib/components/ExplorationHUD.svelte";
  import CombatScreen from "./lib/components/CombatScreen.svelte";

  onMount(() => {
    connectToServer();
  });

  function startRun(classId: "warrior" | "archer" | "mage"): void {
    client.send({ type: "HUB_ACTION", action: "STARTRUN", classId });
  }
</script>

<main>
  {#if $gameState === null}
    <div class="screen center">
      <p class="muted">Connecting to server…</p>
    </div>

  {:else if $gamePhase === "HUB"}
    <div class="screen center">
      <h1>DeepStone</h1>
      <p class="muted">Choose your class</p>
      <div class="class-select">
        <button on:click={() => startRun("warrior")}>⚔ Warrior</button>
        <button on:click={() => startRun("archer")}>🏹 Archer</button>
        <button on:click={() => startRun("mage")}>✦ Mage</button>
      </div>
      <p class="currency">◈ {$gameState.player.metaCurrency}</p>
    </div>

  {:else if $gamePhase === "EXPLORATION"}
    <ExplorationHUD />

  {:else if $gamePhase === "COMBAT"}
    <CombatScreen />

  {:else if $gamePhase === "GAMEOVER"}
    <div class="screen center">
      <h2>Game Over</h2>
      <p class="muted">The dungeon claims another soul.</p>
      <button on:click={() => startRun("warrior")}>Return to Hub</button>
    </div>
  {/if}
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

  .screen {
    width: 100%;
    height: 100%;
  }

  .center {
    display: flex;
    flex-direction: column;
    align-items: center;
    justify-content: center;
    gap: 1.25rem;
  }

  h1 {
    font-size: 2.5rem;
    letter-spacing: 0.2em;
    color: #eee;
  }

  h2 {
    font-size: 1.5rem;
    color: #c0392b;
  }

  .muted {
    color: #555;
    font-size: 0.875rem;
  }

  .class-select {
    display: flex;
    gap: 0.75rem;
  }

  button {
    padding: 0.6rem 1.25rem;
    background: #1e1e1e;
    color: #ccc;
    border: 1px solid #444;
    cursor: pointer;
    font-family: monospace;
    font-size: 0.9rem;
    transition: background 0.15s, border-color 0.15s;
  }

  button:hover {
    background: #2a2a2a;
    border-color: #777;
    color: #eee;
  }

  .currency {
    color: #888;
    font-size: 0.8rem;
    letter-spacing: 0.1em;
  }
</style>