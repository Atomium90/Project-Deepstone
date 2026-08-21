<script lang="ts">
    import { ITEM_RARITY_COLORS } from "../engine/constants";
    import { gameState, equippedSetCounts } from "../engine/StateStore";
    import type { ItemView } from "../engine/protocol";

    export let item: ItemView;

    /** Full SetView for this item's set, if any - the client never hardcodes set names/bonus
     * text, it comes from StateUpdate.sets (same discipline as AbilityView). */
    $: setView = item.setId ? $gameState?.sets.find((s) => s.id === item.setId) : undefined;
    $: setCount = item.setId ? $equippedSetCounts[item.setId] ?? 0 : 0;
    /** Set bonuses are class-gated server-side - equipping another class's set never grants its
     * bonus, so the tooltip must reflect that rather than showing a misleading "active" state. */
    $: classMatches = setView !== undefined && setView.classId === $gameState?.player.classId;

    /** True when this item carries an affinity tag the player's class doesn't have - statLine is
     * already the correct (unscaled) number in that case, this only dims it as a visual cue. */
    $: offAffinity = item.typeTag !== undefined && !($gameState?.player.affinityTags ?? []).includes(item.typeTag);
</script>

<div class="item-tooltip">
    <p class="tooltip-name" style="color:{ITEM_RARITY_COLORS[item.rarity]}">{item.name}</p>
    <p class="tooltip-kind">{item.kind}{item.count && item.count > 1 ? ` · x${item.count}` : ""}</p>
    <p class="tooltip-stat" class:off-affinity={offAffinity}>{item.statLine}</p>
    {#if item.setId}
        <div class="tooltip-set" style="--set-accent:{ITEM_RARITY_COLORS.epic}">
            <p class="set-name">Part of: {setView?.name ?? item.setId} ({setCount}/4)</p>
            {#if setView}
                <p class="set-bonus" class:active={classMatches && setCount >= 2}>
                    2pc: {setView.bonus2pcLabel}
                </p>
                <p class="set-bonus" class:active={classMatches && setCount >= 4}>
                    4pc: {setView.bonus4pcLabel}
                </p>
            {/if}
        </div>
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

    .tooltip-stat.off-affinity {
        color: #666;
    }

    .tooltip-set {
        display: flex;
        flex-direction: column;
        gap: 0.1rem;
        margin-top: 0.15rem;
    }

    .set-name {
        font-size: 0.68rem;
        color: var(--set-accent);
        font-style: italic;
    }

    .set-bonus {
        font-size: 0.62rem;
        color: #555;
    }

    .set-bonus.active {
        color: var(--set-accent);
    }
</style>
