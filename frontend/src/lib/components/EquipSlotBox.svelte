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

    /** Upper bound on the tooltip's rendered width, used to keep it from crossing the left edge
     * of the viewport - matches ItemTooltip's CSS max-width (16rem). Clamping against max-width
     * rather than min-width is deliberate: a set item's tooltip (name + both bonus lines) often
     * renders close to that width, and clamping only to the shorter min-width let the real thing
     * overflow past x=0 for any slot sitting close to the left edge (e.g. the Equipment tab's
     * leftmost Weapon slot). */
    const TOOLTIP_MAX_WIDTH = 256;

    /** Minimum gap kept between the tooltip and the true left edge of the viewport once clamped,
     * so it never ends up sitting flush against the edge. */
    const EDGE_MARGIN = 16;

    /** Slot's own bounding rect determines where the (portaled) tooltip renders: right edge of
     * the tooltip aligned to the right edge of the slot, bottom edge just above the slot's top.
     * Computed on hover/focus rather than continuously, since the slot doesn't move while open. */
    function showTooltip(e: MouseEvent | FocusEvent): void {
        const rect = (e.currentTarget as HTMLElement).getBoundingClientRect();
        tooltipTop = rect.top - 6;
        tooltipLeft = Math.max(rect.right, TOOLTIP_MAX_WIDTH + EDGE_MARGIN);
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
        style="width:{size}px; height:{size}px; border-color:{ITEM_RARITY_COLORS[item.rarity]}; --rarity-tint:{ITEM_RARITY_COLORS[item.rarity]}1a"
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
        {#if item.count && item.count > 1}
            <span class="stack-count">x{item.count}</span>
        {/if}
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

    .equip-slot.occupied {
        background: var(--rarity-tint, #111);
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

    .stack-count {
        position: absolute;
        right: 1px;
        bottom: 1px;
        padding: 0 3px;
        background: rgba(0, 0, 0, 0.75);
        border-radius: 2px;
        font-size: 0.6rem;
        font-weight: bold;
        color: #eee;
        line-height: 1.3;
        pointer-events: none;
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
