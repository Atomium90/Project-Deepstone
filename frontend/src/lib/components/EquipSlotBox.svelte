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
    let tooltipTop = 0;
    let tooltipLeft = 0;

    /** Approximate tooltip width used only to keep it from going off the left edge of the
     * viewport - matches ItemTooltip's CSS min-width. The real rendered width can grow up to
     * max-width depending on content, but this is enough slack for the common case. */
    const TOOLTIP_MIN_WIDTH = 170;

    /** Slot's own bounding rect determines where the (portaled) tooltip renders: right edge of
     * the tooltip aligned to the right edge of the slot, bottom edge just above the slot's top.
     * Computed on hover/focus rather than continuously, since the slot doesn't move while open. */
    function showTooltip(e: MouseEvent | FocusEvent): void {
        const rect = (e.currentTarget as HTMLElement).getBoundingClientRect();
        tooltipTop = rect.top - 6;
        tooltipLeft = Math.max(rect.right, TOOLTIP_MIN_WIDTH);
        hovered = true;
    }

    function hideTooltip(): void {
        hovered = false;
    }

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

    /** Moves the tooltip node to <body> so its position:fixed coordinates aren't clipped by an
     * ancestor's overflow:auto/hidden - ExplorationHUD's sidebar scrolls, which otherwise clips
     * any popup positioned relative to a slot inside it, in every direction, not just the one
     * that happens to overflow. Svelte 4 has no first-class portal primitive, this is the
     * standard action-based workaround. */
    function portal(node: HTMLElement): { destroy(): void } {
        document.body.appendChild(node);
        return { destroy: () => node.remove() };
    }
</script>

{#if item}
    <div
        class="equip-slot occupied"
        style="width:{size}px; height:{size}px; border-color:{ITEM_RARITY_COLORS[item.rarity]}"
        on:mouseenter={showTooltip}
        on:mouseleave={hideTooltip}
        on:focus={showTooltip}
        on:blur={hideTooltip}
        tabindex="0"
        role="button"
        aria-label={item.name}
    >
        <Sprite spriteId={item.iconId ?? item.typeId} fallbackColor={ITEM_KIND_COLORS[item.kind]} size={size - 8}>
            <span slot="fallback" class="abbrev">{abbrev(item.name)}</span>
        </Sprite>
    </div>

    {#if hovered}
        <div class="tooltip-portal" use:portal style="top:{tooltipTop}px; left:{tooltipLeft}px;">
            <ItemTooltip {item} />
        </div>
    {/if}
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

    /* position:fixed + a portal to <body> (see the `portal` action) - the coordinates are the
     * tooltip's own bottom-right corner, translate(-100%, -100%) grows it up and to the left from
     * there. Escapes any scrolling/clipping ancestor the trigger slot happens to sit inside. */
    .tooltip-portal {
        position: fixed;
        transform: translate(-100%, -100%);
        z-index: 1000;
        pointer-events: none;
    }
</style>
