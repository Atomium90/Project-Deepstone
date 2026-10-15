<script lang="ts">
    import { onDestroy, tick } from "svelte";
    import { get } from "svelte/store";
    import { godLine, gamePhase } from "../engine/StateStore";
    import { dictionaries, language, lookup } from "../engine/i18n";
    import { pickGodLine } from "../engine/godVoice";
    import type { GodLineView } from "../engine/protocol";

    /** How long the line stays on screen before it fades away. */
    const SHOWN_MS = 6000;

    let text: string | null = null;
    let visible = false;
    let timer: ReturnType<typeof setTimeout> | null = null;

    // The server sends the line on the single update of the run's start, and nothing on the next
    // ones, so the line is kept here and removed by the timer, not by the updates that follow.
    // The lines said on a death belong to the Game Over screen.
    $: if ($godLine?.moment === "start") show($godLine);

    // The line is about the walk into the dungeon: it goes as soon as anything else begins, a fight
    // or the end of the run, instead of staying over it until its timer runs out.
    $: if ($gamePhase !== "EXPLORATION") remove();

    function remove(): void {
        if (timer !== null) clearTimeout(timer);
        timer = null;
        visible = false;
        text = null;
    }

    async function show(line: GodLineView): Promise<void> {
        if (timer !== null) clearTimeout(timer);
        text = pickGodLine(line, (key) => lookup(dictionaries, get(language), key));
        // Shown one frame after it is drawn, so it fades in instead of appearing.
        visible = false;
        await tick();
        visible = text !== null;
        timer = setTimeout(() => (visible = false), SHOWN_MS);
    }

    onDestroy(() => {
        if (timer !== null) clearTimeout(timer);
    });
</script>

{#if text !== null}
    <p class="god-banner" class:visible aria-live="polite">{text}</p>
{/if}

<style>
    /* Over the game but under every modal and card, and never in the way of a click. */
    .god-banner {
        position: fixed;
        top: 14%;
        left: 50%;
        transform: translateX(-50%);
        width: min(32rem, 80%);
        z-index: 700;
        text-align: center;
        font-family: monospace;
        font-size: 1rem;
        font-style: italic;
        line-height: 1.5;
        color: #e8dfc4;
        text-shadow: 0 0 6px #000, 0 0 14px #000;
        opacity: 0;
        transition: opacity 1.2s ease;
        pointer-events: none;
    }

    .god-banner.visible {
        opacity: 0.92;
        transition-duration: 0.8s;
    }

    @media (prefers-reduced-motion: reduce) {
        .god-banner {
            transition: none;
        }
    }
</style>
