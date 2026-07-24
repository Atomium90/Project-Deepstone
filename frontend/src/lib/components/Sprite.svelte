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

    let asset = assets.getSprite(spriteId, fallbackColor);
    let rafId: number | null = null;

    function stopPolling(): void {
        if (rafId !== null) {
            cancelAnimationFrame(rafId);
            rafId = null;
        }
    }

    /** Sprite loading is async; self-poll (terminating once resolved) rather than leaving a
     * permanent fallback if this component mounts before the atlas/image finishes loading. */
    function poll(): void {
        asset = assets.getSprite(spriteId, fallbackColor);
        rafId = asset.image ? null : requestAnimationFrame(poll);
    }

    $: {
        stopPolling();
        asset = assets.getSprite(spriteId, fallbackColor);
        if (!asset.image) poll();
    }

    onDestroy(stopPolling);

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
    <div class="sprite" style="width:{size}px; height:{size}px; background-color:{fallbackColor};"></div>
{/if}

<style>
    .sprite {
        background-repeat: no-repeat;
        image-rendering: pixelated;
        flex-shrink: 0;
    }
</style>
