<script lang="ts">
    import Sprite from "./Sprite.svelte";
    import ItemTooltip from "./ItemTooltip.svelte";
    import { ITEM_KIND_COLORS, ITEM_RARITY_COLORS } from "../engine/constants";
    import type { ItemView } from "../engine/protocol";

    /** The item in this slot, or null for an empty slot. */
    export let item: ItemView | null = null;
    /** Rendered box size in pixels (square). The icon itself is inset a few px inside it. */
    export let size = 48;

    let hovered = false;

    /** First letter of each word, max 2 chars - shown over the flat-color fallback box when the
     * item has no iconId (or the atlas hasn't resolved it yet), same convention the old flat
     * inventory grid used. */
    function abbrev(name: string): string {
        return name
            .split(" ")
            .map((w) => w[0])
            .join("")
            .slice(0, 2)
            .toUpperCase();
    }
</script>

{#if item}
    <div
        class="equip-slot occupied"
        style="width:{size}px; height:{size}px; border-color:{ITEM_RARITY_COLORS[item.rarity]}"
        on:mouseenter={() => (hovered = true)}
        on:mouseleave={() => (hovered = false)}
        on:focus={() => (hovered = true)}
        on:blur={() => (hovered = false)}
        tabindex="0"
        role="button"
        aria-label={item.name}
    >
        <Sprite spriteId={item.iconId ?? item.typeId} fallbackColor={ITEM_KIND_COLORS[item.kind]} size={size - 8}>
            <span slot="fallback" class="abbrev">{abbrev(item.name)}</span>
        </Sprite>

        {#if hovered}
            <div class="tooltip-anchor">
                <ItemTooltip {item} />
            </div>
        {/if}
    </div>
{:else}
    <div class="equip-slot" style="width:{size}px; height:{size}px;"></div>
{/if}

<style>
    .equip-slot {
        position: relative;
        background: #111;
        border: 1px solid #222;
        border-radius: 2px;
        display: flex;
        align-items: center;
        justify-content: center;
        flex-shrink: 0;
        transition: border-color 0.1s;
    }

    .equip-slot.occupied:hover,
    .equip-slot.occupied:focus-visible {
        background: #1e1e1e;
        outline: none;
    }

    .abbrev {
        font-size: 0.6rem;
        font-weight: bold;
        color: #ccc;
        letter-spacing: 0.02em;
    }

    .tooltip-anchor {
        position: absolute;
        bottom: 100%;
        left: 50%;
        transform: translateX(-50%);
        margin-bottom: 6px;
        z-index: 20;
    }
</style>
