<script lang="ts">
    /** Shared labeled progress bar (HP/resource) used by CombatScreen and ExplorationHUD. */
    export let label: string;
    export let current: number;
    export let max: number;
    export let color: string;
    export let layout: "row" | "column" = "row";

    $: percent = max > 0 ? (current / max) * 100 : 0;

    /** Maps the small, fixed set of hex values this app actually uses (HP_BAR_COLOR,
     * RESOURCE_BAR_COLORS in constants.ts) to the matching Kenney rpg-expansion bar asset. Any
     * color outside this set falls back to a flat CSS fill, same as before this component had
     * sprites at all. */
    const BAR_FILL_BY_COLOR: Record<string, string> = {
        "#c0392b": "/sprites/ui/rpg-expansion/barRed_horizontalMid.png",
        "#27ae60": "/sprites/ui/rpg-expansion/barGreen_horizontalMid.png",
        "#2980b9": "/sprites/ui/rpg-expansion/barBlue_horizontalMid.png",
    };
    $: fillImage = BAR_FILL_BY_COLOR[color];
</script>

<div class="stat-bar" class:row={layout === "row"} class:column={layout === "column"}>
    <span class="bar-label">{label}</span>
    <div class="bar-track">
        <div
            class="bar"
            class:sprite-fill={Boolean(fillImage)}
            style={fillImage
                ? `width:${percent}%; background-image:url(${fillImage});`
                : `width:${percent}%; background:${color};`}
        />
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
        height: 14px;
        background-image: url(/sprites/ui/rpg-expansion/barBack_horizontalMid.png);
        background-size: 100% 100%;
        image-rendering: pixelated;
        border-radius: 2px;
        overflow: hidden;
    }

    .bar {
        height: 100%;
        border-radius: 2px;
        transition: width 0.2s ease;
    }

    .bar.sprite-fill {
        background-repeat: no-repeat;
        background-size: 100% 100%;
        image-rendering: pixelated;
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
