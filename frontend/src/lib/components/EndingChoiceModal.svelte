<script lang="ts">
    import { pendingEndingChoice, client } from "../engine/StateStore";
    import { t, dictionaries, language, lookup } from "../engine/i18n";

    /** Which screen of the question the player is on: the three answers, the list of who can take their
     * place, or the warning before the answer that cannot be undone and takes everything. */
    type View = "answers" | "who" | "sure";
    let view: View = "answers";

    // A question that is gone (answered, or the run ended) starts again from the answers next time.
    $: if (!$pendingEndingChoice) view = "answers";

    $: candidates = $pendingEndingChoice?.candidates ?? [];

    /** The name the player knows an NPC by: the real name once given away, else their title. A
     * candidate has finished their story, so their name has been given away if they give one. */
    function nameOf(npc: string): string {
        const text = (key: string) => lookup(dictionaries, $language, key);
        return text(`story.${npc}.name_revealed`) ?? text(`story.${npc}.name`) ?? npc;
    }

    function enter(): void {
        client.send({ type: "ENDING_CHOICE", answer: "ENTER" });
    }

    function replaceWith(npc: string): void {
        client.send({ type: "ENDING_CHOICE", answer: "REPLACE", npc });
    }

    function refuse(): void {
        client.send({ type: "ENDING_CHOICE", answer: "REFUSE" });
    }
</script>

{#if $pendingEndingChoice}
    <div class="backdrop">
        <div class="modal">
            {#if view === "answers"}
                <button class="answer" on:click={enter}>{$t("story.ending.enter")}</button>
                <button class="answer" disabled={candidates.length === 0} on:click={() => (view = "who")}>
                    {$t("story.ending.replace")}
                </button>
                {#if candidates.length === 0}
                    <p class="closed">{$t("story.ending.nobody")}</p>
                {/if}
                <button class="answer refuse" on:click={() => (view = "sure")}>{$t("story.ending.refuse")}</button>
            {:else if view === "who"}
                <p class="question">{$t("story.ending.who")}</p>
                {#each candidates as npc (npc)}
                    <button class="answer candidate" on:click={() => replaceWith(npc)}>{nameOf(npc)}</button>
                {/each}
                <button class="back" on:click={() => (view = "answers")}>{$t("story.ending.back")}</button>
            {:else}
                <p class="question warning">{$t("story.ending.sure")}</p>
                <button class="answer refuse" on:click={refuse}>{$t("story.ending.refuse")}</button>
                <button class="back" on:click={() => (view = "answers")}>{$t("story.ending.back")}</button>
            {/if}
        </div>
    </div>
{/if}

<style>
    .backdrop {
        position: fixed;
        inset: 0;
        z-index: 2000;
        display: flex;
        align-items: center;
        justify-content: center;
        background: rgba(0, 0, 0, 0.8);
    }

    .modal {
        width: 340px;
        max-width: 90vw;
        padding: 1.5rem 1.25rem;
        background: #141414;
        border: 1px solid #2a2a2a;
        border-radius: 4px;
        font-family: monospace;
        display: flex;
        flex-direction: column;
        gap: 0.65rem;
    }

    .answer {
        padding: 0.7rem 0.8rem;
        background: #1e1e1e;
        border: 1px solid #3a3a3a;
        color: #ddd;
        font-family: monospace;
        font-size: 0.85rem;
        cursor: pointer;
        text-align: center;
        transition: background 0.1s, border-color 0.1s;
    }

    .answer:hover:not(:disabled) {
        background: #262626;
        border-color: #c9a227;
        color: #fff;
    }

    .answer:disabled {
        opacity: 0.35;
        cursor: not-allowed;
    }

    /* The answer that takes everything is told apart, and asks for a second click. */
    .answer.refuse:hover:not(:disabled) {
        border-color: #e05c5c;
    }

    .closed {
        margin-top: -0.3rem;
        font-size: 0.68rem;
        color: #777;
        text-align: center;
    }

    .question {
        font-size: 0.8rem;
        color: #aaa;
        text-align: center;
        line-height: 1.5;
    }

    .warning {
        color: #e0a15c;
    }

    .back {
        padding: 0.4rem;
        background: transparent;
        border: none;
        color: #777;
        font-family: monospace;
        font-size: 0.72rem;
        cursor: pointer;
    }

    .back:hover {
        color: #ccc;
    }
</style>
