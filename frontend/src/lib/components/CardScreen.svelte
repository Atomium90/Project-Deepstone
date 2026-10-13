<script lang="ts">
    import { onDestroy, onMount } from "svelte";
    import { get } from "svelte/store";
    import { client } from "../engine/StateStore";
    import { dictionaries, language, lookup, t } from "../engine/i18n";
    import { numberedTexts } from "../engine/sceneText";

    /** The card the server waits for the player to have seen, or null when there is none. */
    export let cardId: string | null = null;

    /** How long a line takes to follow the one before it. */
    const LINE_DELAY_MS = 2000;

    /** A key press cannot close the card sooner than this after the last line appeared, so a held
     * key, or the press that revealed the rest of the card, does not skip it unread. */
    const DISMISS_DELAY_MS = 400;

    let lines: string[] = [];
    /** How many lines are on screen. */
    let shown = 1;
    let shownAt = 0;
    let timer: ReturnType<typeof setInterval> | null = null;
    /** Latched once CARD_SEEN is sent, so a second key press cannot send it again. */
    let finished = false;

    $: start(cardId);

    function stop(): void {
        if (timer !== null) clearInterval(timer);
        timer = null;
    }

    function start(id: string | null): void {
        stop();
        finished = false;
        shown = 1;
        shownAt = Date.now();
        // A card whose texts are missing shows its key instead of nothing, so the gap is visible and
        // the player is never stuck behind a black screen.
        const key = `story.card.${id}`;
        const texts = id === null ? [] : numberedTexts(key, (k) => lookup(dictionaries, get(language), k));
        lines = id === null ? [] : texts.length > 0 ? texts : [key];
        if (lines.length > 1) timer = setInterval(revealNext, LINE_DELAY_MS);
    }

    function revealNext(): void {
        shown = Math.min(shown + 1, lines.length);
        shownAt = Date.now();
        if (shown >= lines.length) stop();
    }

    /** The first press shows the rest of the card, the next one closes it. */
    function advance(): void {
        if (cardId === null || finished) return;
        if (shown < lines.length) {
            shown = lines.length;
            shownAt = Date.now();
            stop();
            return;
        }
        if (Date.now() - shownAt < DISMISS_DELAY_MS) return;
        finished = true;
        client.send({ type: "CARD_SEEN", cardId });
    }

    function handleKeyDown(e: KeyboardEvent): void {
        if (cardId === null || e.repeat) return;
        if (e.key === "e" || e.key === "E" || e.key === " " || e.key === "Enter") {
            e.preventDefault();
            advance();
        }
    }

    onMount(() => window.addEventListener("keydown", handleKeyDown));
    onDestroy(() => {
        window.removeEventListener("keydown", handleKeyDown);
        stop();
    });
</script>

{#if cardId !== null}
    <!-- The keys are handled on the window, the click is only a second way to go on. -->
    <!-- svelte-ignore a11y-click-events-have-key-events -->
    <!-- svelte-ignore a11y-no-static-element-interactions -->
    <div class="card" on:click={advance}>
        <div class="card-text">
            {#each lines as line, i}
                <p class="card-line" class:visible={i < shown}>{line}</p>
            {/each}
        </div>
        <p class="card-hint" class:visible={shown >= lines.length}>{$t("story.continue")}</p>
    </div>
{/if}

<style>
    /* Above every modal (2000): while a card is up nothing else on screen matters. */
    .card {
        position: fixed;
        inset: 0;
        z-index: 3000;
        background: #000;
        display: flex;
        flex-direction: column;
        align-items: center;
        justify-content: center;
        gap: 2.5rem;
        padding: 2rem;
        font-family: monospace;
        cursor: pointer;
    }

    .card-text {
        width: min(36rem, 100%);
        display: flex;
        flex-direction: column;
        gap: 1.1rem;
        text-align: center;
    }

    .card-line {
        font-size: 1.05rem;
        line-height: 1.6;
        color: #e8dfc4;
        opacity: 0;
        transition: opacity 0.9s ease;
    }

    .card-line.visible {
        opacity: 1;
    }

    .card-hint {
        font-size: 0.65rem;
        color: #666;
        opacity: 0;
        transition: opacity 0.6s ease;
    }

    .card-hint.visible {
        opacity: 1;
    }

    @media (prefers-reduced-motion: reduce) {
        .card-line,
        .card-hint {
            transition: none;
        }
    }
</style>
