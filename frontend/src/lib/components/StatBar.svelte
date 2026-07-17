<script lang="ts">
    /** Shared labeled progress bar (HP/resource) used by CombatScreen and ExplorationHUD. */
    export let label: string;
    export let current: number;
    export let max: number;
    export let color: string;
    export let layout: "row" | "column" = "row";

    $: percent = max > 0 ? (current / max) * 100 : 0;
</script>

<div class="stat-bar" class:row={layout === "row"} class:column={layout === "column"}>
    <span class="bar-label">{label}</span>
    <div class="bar-track">
        <div class="bar" style="width:{percent}%; background:{color}" />
    </div>
    <span class="bar-value">{current} / {max}</span>
</div>

<style>
    .stat-bar.row {
        display: grid;
        grid-template-columns: 3rem 1fr 4rem;
        align-items: center;
        gap: 0.5rem;
    }

    .stat-bar.column {
        display: flex;
        flex-direction: column;
        gap: 0.3rem;
    }

    .bar-label {
        font-size: 0.65rem;
        color: #555;
        text-transform: uppercase;
        letter-spacing: 0.1em;
    }

    .bar-track {
        height: 6px;
        background: #252525;
        border-radius: 2px;
        overflow: hidden;
    }

    .bar {
        height: 100%;
        border-radius: 2px;
        transition: width 0.2s ease;
    }

    .stat-bar.row .bar-value {
        font-size: 0.7rem;
        color: #888;
        text-align: right;
    }

    .stat-bar.column .bar-value {
        font-size: 0.78rem;
        color: #bbb;
    }
</style>
