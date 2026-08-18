<script lang="ts">
    import { onDestroy } from "svelte";
    import { assets } from "../engine/AssetManager";

    /** Atlas sprite key (see public/atlas/*.json), e.g. an enemy's spriteId. */
    export let spriteId: string;
    /** Shown as a flat color square if the sprite hasn't resolved (not in the atlas, or not
     * loaded yet). */
    export let fallbackColor: string;
    /** Rendered size in pixels (square). */
    export let size = 96;
    /** When true, continuously cycles frames for an animated atlas entry (see
     * AssetManager.frameIndexFor). Static entries are unaffected either way - frameIndexFor
     * always returns 0 for them regardless of elapsed time. */
    export let animated = false;

    let asset = assets.getSprite(spriteId, fallbackColor);

    /** Self-poll (terminating once resolved) rather than leaving a permanent fallback if this
     * component mounts before the atlas/image finishes loading. Separate from animRafId below -
     * one polls until the image loads, the other runs continuously while `animated`. */
    let rafId: number | null = null;
    function stopPolling(): void {
        if (rafId !== null) {
            cancelAnimationFrame(rafId);
            rafId = null;
        }
    }
    function poll(): void {
        asset = assets.getSprite(spriteId, fallbackColor);
        rafId = asset.image ? null : requestAnimationFrame(poll);
    }

    let animRafId: number | null = null;
    let animStart = 0;
    function stopAnimating(): void {
        if (animRafId !== null) {
            cancelAnimationFrame(animRafId);
            animRafId = null;
        }
    }
    function animate(ts: number): void {
        asset = assets.getSprite(spriteId, fallbackColor, ts - animStart);
        animRafId = requestAnimationFrame(animate);
    }

    $: {
        stopPolling();
        stopAnimating();
        asset = assets.getSprite(spriteId, fallbackColor, 0);
        if (animated) {
            animStart = performance.now();
            animRafId = requestAnimationFrame(animate);
        } else if (!asset.image) {
            poll();
        }
    }

    onDestroy(() => {
        stopPolling();
        stopAnimating();
    });

    $: image = asset.image;
    $: sourceRect = asset.sourceRect;
</script>

{#if image && sourceRect}
    <div
        class="sprite"
        style="
            width:{size}px; height:{size}px;
            background-image:url({image.src});
            background-position:{-sourceRect.x * (size / sourceRect.w)}px {-sourceRect.y * (size / sourceRect.h)}px;
            background-size:{image.naturalWidth * (size / sourceRect.w)}px {image.naturalHeight * (size / sourceRect.h)}px;
        "
    ></div>
{:else}
    <div class="sprite" style="width:{size}px; height:{size}px; background-color:{fallbackColor};">
        <slot name="fallback" />
    </div>
{/if}

<style>
    .sprite {
        background-repeat: no-repeat;
        image-rendering: pixelated;
        flex-shrink: 0;
    }
</style>
