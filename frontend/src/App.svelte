<script lang="ts">
  import { onMount } from "svelte";
  import { gameState, gamePhase, connectToServer } from "./lib/engine/StateStore";

  onMount(() => {
    connectToServer();
  });
</script>

<main>
  {#if $gameState === null}
    <div class="screen center">
      <p>Connecting to server…</p>
    </div>

  {:else if $gamePhase === "Hub"}
    <!-- HubScreen will be implemented in week 5 -->
    <div class="screen center">
      <h1>Deepstone</h1>
      <p>You are in the hub. <code>meta_currency: {$gameState.player.metaCurrency}</code></p>
      <button on:click={() => {
        import("./lib/engine/StateStore").then(({ client }) => {
          client.send({ type: "HUB_ACTION", action: "START_RUN", classId: "warrior" });
        });
      }}>
        Start Run (Warrior)
      </button>
    </div>

  {:else if $gamePhase === "Exploration"}
    <!-- ExplorationHUD will be implemented in week 2 -->
    <div class="screen center">
      <p>Exploring — phase placeholder</p>
      <p>Player: {$gameState.player.classId} | HP: {$gameState.player.hp}/{$gameState.player.maxHp}</p>
      {#if $gameState.log.length > 0}
        <ul class="log">
          {#each $gameState.log as line}
            <li>{line}</li>
          {/each}
        </ul>
      {/if}
    </div>

  {:else if $gamePhase === "Combat"}
    <!-- CombatScreen will be implemented in week 3 -->
    <div class="screen center"><p>Combat — phase placeholder</p></div>

  {:else if $gamePhase === "GameOver"}
    <div class="screen center"><h2>Game Over</h2></div>
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
    gap: 1rem;
  }

  button {
    padding: 0.75rem 1.5rem;
    background: #333;
    color: #eee;
    border: 1px solid #666;
    cursor: pointer;
    font-family: monospace;
    font-size: 1rem;
  }

  button:hover {
    background: #444;
  }

  .log {
    list-style: none;
    text-align: center;
    color: #aaa;
    font-size: 0.875rem;
  }
</style>