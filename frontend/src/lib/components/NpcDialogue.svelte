<script lang="ts">
    import type { DialogueView } from "../engine/protocol";

    export let dialogue: DialogueView | null = null;
</script>

{#if dialogue}
    <div class="dialogue-box">
        <p class="dialogue-name">{dialogue.npcName}</p>
        <p class="dialogue-line">{dialogue.line}</p>
    </div>
{/if}

<style>
    .dialogue-box {
        position: absolute;
        left: 50%;
        bottom: 1.5rem;
        transform: translateX(-50%);
        max-width: 70%;
        padding: 0.75rem 1.25rem;
        background: #1a1a1a;
        border: 1px solid #2a2a2a;
        border-radius: 4px;
        font-family: monospace;
        pointer-events: none;
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
    }
</style>
