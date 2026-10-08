<script lang="ts">
    import { HINTS } from "../engine/HintCatalog";
    import { isHintSeen, tutorial } from "../engine/HintStore";
    import { t } from "../engine/i18n";

    // A journal of the hints already discovered, in the catalog's order rather than the order they
    // were seen in. After Skip every hint counts as discovered, so the whole list shows.
    $: discovered = HINTS.filter((hint) => isHintSeen($tutorial, hint.id));
</script>

<div class="help-panel">
    {#if discovered.length === 0}
        <p class="empty">{$t("help.empty")}</p>
    {:else}
        {#each discovered as hint (hint.id)}
            <article class="entry">
                <h3 class="entry-title">{$t(`hint.${hint.id}.title`)}</h3>
                <p class="entry-body">{$t(`hint.${hint.id}.body`)}</p>
            </article>
        {/each}
    {/if}
</div>

<style>
    .help-panel {
        display: flex;
        flex-direction: column;
        gap: 0.5rem;
        max-width: 40rem;
        font-family: monospace;
    }

    .empty {
        color: #555;
        font-size: 0.85rem;
        line-height: 1.5;
    }

    .entry {
        padding: 0.65rem 0.85rem;
        background: #161616;
        border: 1px solid #222;
    }

    .entry-title {
        font-size: 0.7rem;
        font-weight: normal;
        text-transform: uppercase;
        letter-spacing: 0.1em;
        color: #d4ac0d;
        margin-bottom: 0.3rem;
    }

    .entry-body {
        font-size: 0.85rem;
        line-height: 1.45;
        color: #ccc;
    }
</style>
