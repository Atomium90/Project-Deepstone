<script lang="ts">
    import { creditsIn, type CreditGroup } from "../engine/CreditsCatalog";
    import { t } from "../engine/i18n";

    const GROUPS: CreditGroup[] = ["art", "audio"];
</script>

<div class="credits-panel">
    <p class="intro">{$t("credits.intro")}</p>
    {#each GROUPS as group (group)}
        <section class="group">
            <h2 class="group-title">{$t(`credits.group.${group}`)}</h2>
            {#each creditsIn(group) as entry (entry.id)}
                <article class="entry">
                    <h3 class="entry-name">
                        {#if entry.url}
                            <a href={entry.url} target="_blank" rel="noopener noreferrer">{entry.name}</a>
                        {:else}
                            {entry.name}
                        {/if}
                    </h3>
                    <p class="entry-meta">
                        {$t("credits.by", { author: entry.author })}
                        <span class="separator">·</span>
                        {#if entry.license === null}
                            <span class="terms">{$t(`credits.${entry.id}.terms`)}</span>
                        {:else if entry.licenseUrl}
                            <a class="license" href={entry.licenseUrl} target="_blank" rel="noopener noreferrer">{entry.license}</a>
                        {:else}
                            <span class="license">{entry.license}</span>
                        {/if}
                    </p>
                    <p class="entry-use">{$t(`credits.${entry.id}.use`)}</p>
                    {#if entry.hasNote}
                        <p class="entry-note">{$t(`credits.${entry.id}.note`)}</p>
                    {/if}
                </article>
            {/each}
        </section>
    {/each}
</div>

<style>
    .credits-panel {
        display: flex;
        flex-direction: column;
        gap: 1.25rem;
        max-width: 40rem;
        font-family: monospace;
    }

    .intro {
        color: #aaa;
        font-size: 0.85rem;
        line-height: 1.5;
    }

    .group {
        display: flex;
        flex-direction: column;
        gap: 0.5rem;
    }

    .group-title {
        font-size: 0.7rem;
        font-weight: normal;
        text-transform: uppercase;
        letter-spacing: 0.1em;
        color: #d4ac0d;
    }

    .entry {
        padding: 0.65rem 0.85rem;
        background: #161616;
        border: 1px solid #222;
    }

    .entry-name {
        font-size: 0.9rem;
        font-weight: normal;
        color: #ddd;
        margin-bottom: 0.2rem;
    }

    .entry-meta,
    .entry-use,
    .entry-note {
        font-size: 0.8rem;
        line-height: 1.45;
    }

    .entry-meta {
        color: #888;
    }

    .separator {
        margin: 0 0.25rem;
    }

    .entry-use {
        color: #ccc;
    }

    .entry-note {
        color: #777;
        margin-top: 0.2rem;
    }

    a {
        color: #d4ac0d;
    }

    a:hover {
        color: #f0c93a;
    }
</style>
