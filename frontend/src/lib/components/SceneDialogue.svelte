<script lang="ts">
    import { onDestroy, onMount } from "svelte";
    import { get } from "svelte/store";
    import { client } from "../engine/StateStore";
    import { characterTab } from "../engine/CharacterStore";
    import { dictionaries, language, lookup, t } from "../engine/i18n";
    import { buildScript, type SceneScript } from "../engine/sceneText";
    import { placeBubble, type Box, type Size } from "../engine/dialoguePlacement";
    import { digitOf } from "../engine/choiceKeys";
    import type { PendingSceneView } from "../engine/protocol";

    /** The conversation the server holds pending, or null. */
    export let scene: PendingSceneView | null = null;

    /** Where the conversation takes place, in the pixels of the area the bubble lives in: the bubble
     * sits next to it, under it, so that it stays close to who speaks without covering them. Without
     * it the bubble stays at the bottom of the area. */
    export let anchor: Box | null = null;

    /** The size of the area the bubble lives in, which it stays inside. */
    export let area: Size | null = null;

    /** The size of the bubble itself, which the server's text decides. */
    let boxWidth = 0;
    let boxHeight = 0;
    $: spot = anchor && area && boxWidth > 0 ? placeBubble(anchor, { width: boxWidth, height: boxHeight }, area) : null;

    /** A key press cannot advance the conversation sooner than this after a bubble appeared, so
     * the E that opened it, or a held key, does not skip a line before it is read. */
    const ADVANCE_DELAY_MS = 400;

    let script: SceneScript | null = null;
    let index = 0;
    let shownAt = 0;
    /** The answer to the offered vow, once given: its reply is on screen until the player goes on. */
    let answer: boolean | null = null;
    /** Latched once SCENE_DONE is sent, so a second key press cannot send it again. */
    let finished = false;

    // A new conversation starts from its first line. The server describes the same conversation
    // with an equal object on every update, so it is compared by content.
    $: sceneKey = scene ? JSON.stringify(scene) : null;
    $: start(sceneKey);

    function start(_key: string | null): void {
        index = 0;
        answer = null;
        finished = false;
        shownAt = Date.now();
        script = scene ? buildScript(scene, (key) => lookup(dictionaries, get(language), key)) : null;
    }

    $: onLastLine = script !== null && index === script.lines.length - 1;
    $: choosing = script?.offer != null && onLastLine && answer === null;
    $: current =
        script === null
            ? ""
            : answer === null
              ? script.lines[index]
              : answer
                ? script.offer?.replyAccept ?? ""
                : script.offer?.replyDecline ?? "";

    function advance(): void {
        if (script === null || choosing || finished) return;
        if (Date.now() - shownAt < ADVANCE_DELAY_MS) return;
        if (answer === null && !onLastLine) {
            index += 1;
            shownAt = Date.now();
            return;
        }
        finished = true;
        client.send(script.offer && answer !== null ? { type: "SCENE_DONE", accept: answer } : { type: "SCENE_DONE" });
    }

    function choose(accept: boolean): void {
        if (!choosing) return;
        answer = accept;
        shownAt = Date.now();
    }

    function handleKeyDown(e: KeyboardEvent): void {
        if (scene === null || e.repeat || $characterTab !== null) return;
        if (choosing) {
            const digit = digitOf(e);
            if (digit === 1) choose(true);
            if (digit === 2) choose(false);
            return; // anything else is left to the buttons, so Space and Enter still press the focused one
        }
        if (e.key === "e" || e.key === "E" || e.key === " " || e.key === "Enter") {
            e.preventDefault();
            advance();
        }
    }

    onMount(() => window.addEventListener("keydown", handleKeyDown));
    onDestroy(() => window.removeEventListener("keydown", handleKeyDown));
</script>

{#if script}
    <div
        class="dialogue-box"
        class:placed={spot !== null}
        class:measuring={anchor !== null && spot === null}
        style={spot ? `left: ${spot.left}px; top: ${spot.top}px;` : ""}
        role="dialog"
        aria-label={script.speaker}
        bind:clientWidth={boxWidth}
        bind:clientHeight={boxHeight}
    >
        <p class="dialogue-name">{script.speaker}</p>
        <p class="dialogue-line">{current}</p>
        {#if choosing && script.offer}
            <div class="dialogue-choices">
                <button class="choice-btn accept" on:click={() => choose(true)}>
                    <span class="choice-key">1</span>{script.offer.accept}
                </button>
                <button class="choice-btn decline" on:click={() => choose(false)}>
                    <span class="choice-key">2</span>{script.offer.decline}
                </button>
            </div>
        {:else}
            <p class="dialogue-hint">{$t("story.continue")}</p>
        {/if}
    </div>
{/if}

<style>
    .dialogue-box {
        position: absolute;
        left: 50%;
        bottom: 1.5rem;
        transform: translateX(-50%);
        width: min(34rem, 70%);
        padding: 0.75rem 1.25rem;
        background: #1a1a1a;
        border: 1px solid #2a2a2a;
        border-radius: 4px;
        font-family: monospace;
    }

    /* Next to the conversation: the position comes from the style, not from the bottom of the area. */
    .dialogue-box.placed {
        bottom: auto;
        transform: none;
    }

    /* The first frame, before the bubble has a size to be placed with. */
    .dialogue-box.measuring {
        visibility: hidden;
    }

    .dialogue-name {
        font-size: 0.65rem;
        text-transform: uppercase;
        letter-spacing: 0.12em;
        color: #4f9d78;
        margin-bottom: 0.35rem;
    }

    .dialogue-line {
        font-size: 0.85rem;
        color: #ddd;
        line-height: 1.4;
        min-height: 1.2rem;
    }

    .dialogue-hint {
        margin-top: 0.5rem;
        font-size: 0.6rem;
        color: #666;
        text-align: right;
    }

    .dialogue-choices {
        display: flex;
        gap: 0.5rem;
        margin-top: 0.6rem;
    }

    .choice-btn {
        flex: 1;
        padding: 0.45rem 0.6rem;
        background: #161616;
        border: 1px solid #3a3a3a;
        color: #ccc;
        font-family: monospace;
        font-size: 0.78rem;
        cursor: pointer;
        text-align: left;
    }

    .choice-btn:hover,
    .choice-btn:focus-visible {
        background: #1e1e1e;
        border-color: #4f9d78;
        outline: none;
    }

    .choice-key {
        display: inline-block;
        margin-right: 0.5rem;
        padding: 0 0.3rem;
        border: 1px solid #444;
        color: #888;
        font-size: 0.65rem;
    }
</style>
