<script lang="ts">
    import { activeHint, dismissHint } from "../engine/HintStore";
    import { hintPosition } from "../engine/HintCatalog";
    import { t } from "../engine/i18n";
</script>

<!-- The texts of a hint live in the lang files under hint.<id>.title and hint.<id>.body, and its
     corner comes from the hint catalog. -->
{#if $activeHint}
    <aside class="hint-card pos-{hintPosition($activeHint)}" role="status">
        <span class="hint-title">{$t(`hint.${$activeHint}.title`)}</span>
        <p class="hint-body">{$t(`hint.${$activeHint}.body`)}</p>
        <button class="hint-dismiss" on:click={dismissHint}>{$t("hint.dismiss")}</button>
    </aside>
{/if}

<style>
    /* Fixed in a corner and above the Character screen (z-index 500) so a hint can be read from
     * anywhere. Nothing covers the screen, the game stays playable behind it. */
    .hint-card {
        position: fixed;
        z-index: 600;
        display: flex;
        flex-direction: column;
        gap: 0.4rem;
        width: 20rem;
        max-width: calc(100vw - 2rem);
        padding: 0.75rem 1rem;
        background: #1e1e1e;
        border: 1px solid #c8a84b;
        border-radius: 4px;
        font-family: monospace;
        color: #ccc;
        box-shadow: 0 2px 12px rgba(0, 0, 0, 0.4);
    }

    .pos-bottom-left { left: 1rem; bottom: 1rem; }
    .pos-bottom-right { right: 1rem; bottom: 1rem; }
    .pos-top-left { left: 1rem; top: 1rem; }
    .pos-top-right { right: 1rem; top: 1rem; }

    .hint-title {
        font-size: 0.6rem;
        text-transform: uppercase;
        letter-spacing: 0.1em;
        color: #d4ac0d;
    }

    .hint-body {
        font-size: 0.85rem;
        line-height: 1.4;
        color: #eee;
    }

    .hint-dismiss {
        align-self: flex-end;
        padding: 0.25rem 0.75rem;
        background: none;
        border: 1px solid #555;
        border-radius: 3px;
        color: #ccc;
        font-family: monospace;
        font-size: 0.75rem;
        cursor: pointer;
        transition: color 0.12s, border-color 0.12s;
    }

    .hint-dismiss:hover {
        color: #d4ac0d;
        border-color: #d4ac0d;
    }
</style>
