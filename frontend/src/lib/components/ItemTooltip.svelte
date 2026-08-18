<script lang="ts">
    import { ITEM_RARITY_COLORS, SET_NAMES } from "../engine/constants";
    import type { ItemView } from "../engine/protocol";

    export let item: ItemView;
</script>

<div class="item-tooltip">
    <p class="tooltip-name" style="color:{ITEM_RARITY_COLORS[item.rarity]}">{item.name}</p>
    <p class="tooltip-kind">{item.kind}</p>
    <p class="tooltip-stat">{item.statLine}</p>
    {#if item.setId}
        <p class="tooltip-set">Part of: {SET_NAMES[item.setId] ?? item.setId}</p>
    {/if}
</div>

<style>
    .item-tooltip {
        display: flex;
        flex-direction: column;
        gap: 0.15rem;
        min-width: 10rem;
        max-width: 16rem;
        padding: 0.55rem 0.7rem;
        background: #141414;
        border: 1px solid #2a2a2a;
        font-family: monospace;
        pointer-events: none;
    }

    .tooltip-name {
        font-size: 0.8rem;
        font-weight: bold;
    }

    .tooltip-kind {
        font-size: 0.62rem;
        text-transform: uppercase;
        letter-spacing: 0.1em;
        color: #555;
    }

    .tooltip-stat {
        font-size: 0.72rem;
        color: #5ce07a;
    }

    .tooltip-set {
        font-size: 0.68rem;
        color: #8e44ad;
        font-style: italic;
    }
</style>
