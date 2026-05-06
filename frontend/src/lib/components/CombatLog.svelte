<script lang="ts">
    import { afterUpdate } from "svelte";

    export let log: string[] = [];

    let logEl: HTMLUListElement;

    /** Scroll to the bottom whenever new log lines arrive. */
    afterUpdate(() => {
        if (logEl) {
            logEl.scrollTop = logEl.scrollHeight;
        }
    });
</script>

<div class="log-wrapper">
    <p class="log-title">Combat Log</p>
    <ul class="log-list" bind:this={logEl}>
        {#each log as line, i (i)}
            <li class="log-line">{line}</li>
        {/each}
        {#if log.length === 0}
            <li class="log-line muted">No events yet.</li>
        {/if}
    </ul>
</div>

<style>
    .log-wrapper {
        display: flex;
        flex-direction: column;
        height: 100%;
        min-height: 0;
    }

    .log-title {
        font-size: 0.65rem;
        text-transform: uppercase;
        letter-spacing: 0.12em;
        color: #555;
        padding: 0 0 0.5rem 0;
        border-bottom: 1px solid #2a2a2a;
        margin-bottom: 0.5rem;
        flex-shrink: 0;
    }

    .log-list {
        list-style: none;
        overflow-y: auto;
        flex: 1 1 0;
        display: flex;
        flex-direction: column;
        gap: 0.3rem;
    }

    .log-line {
        font-size: 0.75rem;
        color: #aaa;
        line-height: 1.4;
        font-family: monospace;
    }

    .log-line.muted {
        color: #444;
        font-style: italic;
    }
</style>