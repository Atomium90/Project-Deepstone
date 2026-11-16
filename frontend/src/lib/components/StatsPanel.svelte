<script lang="ts">
    import { gameState } from "../engine/StateStore";
    import { t } from "../engine/i18n";
    import type { StatTermView } from "../engine/protocol";

    $: player = $gameState?.player;
    $: stats = player?.stats;
    $: ability = ($gameState?.abilities ?? []).find((a) => a.classId === player?.classId);
    /** A crit chance of 100 or more is a guaranteed crit: more than that adds nothing to show. */
    $: critShown = Math.min(100, stats?.critChance ?? 0);
    $: abilityCost = stats?.abilityCost ?? ability?.cost ?? 0;
    $: discounted = ability !== undefined && abilityCost < ability.cost;

    /** The value of a term with its sign, as listed in a breakdown. */
    function signed(value: number): string {
        return value >= 0 ? `+${value}` : `${value}`;
    }

    function total(terms: StatTermView[]): number {
        return terms.reduce((sum, term) => sum + term.value, 0);
    }
</script>

{#if stats && player}
    <div class="stats-panel">
        <p class="section-label">{$t("stats.title")}</p>

        <!-- Each row is focusable so the breakdown also shows from the keyboard. -->
        <div class="stat-row" tabindex="0" role="button" aria-label={$t("stats.attack")}>
            <span class="stat-name">{$t("stats.attack")}</span>
            <span class="stat-value">{stats.attack}</span>
            <div class="stat-tooltip">
                {#each stats.attackTerms as term}
                    <p class="term">
                        <span>{$t(`stats.term.${term.source}`)}{term.affinity ? ` (${$t("stats.affinity")})` : ""}</span>
                        <span>{signed(term.value)}</span>
                    </p>
                {/each}
                <p class="term total"><span>{$t("stats.total")}</span><span>{total(stats.attackTerms)}</span></p>
            </div>
        </div>

        <div class="stat-row" tabindex="0" role="button" aria-label={$t("stats.defense")}>
            <span class="stat-name">{$t("stats.defense")}</span>
            <span class="stat-value">{stats.defense}</span>
            <div class="stat-tooltip">
                {#each stats.defenseTerms as term}
                    <p class="term">
                        <span>{$t(`stats.term.${term.source}`)}{term.affinity ? ` (${$t("stats.affinity")})` : ""}</span>
                        <span>{signed(term.value)}</span>
                    </p>
                {/each}
                <p class="term total"><span>{$t("stats.total")}</span><span>{total(stats.defenseTerms)}</span></p>
            </div>
        </div>

        <div class="stat-row" tabindex="0" role="button" aria-label={$t("stats.crit")}>
            <span class="stat-name">{$t("stats.crit")}</span>
            <span class="stat-value">{critShown}%</span>
            <span class="stat-note">{$t("stats.critDamage", { multiplier: stats.critMultiplier })}</span>
            {#if stats.critTerms.length > 0}
                <div class="stat-tooltip">
                    {#each stats.critTerms as term}
                        <p class="term">
                            <span>{$t(`stats.term.${term.source}`)}{term.affinity ? ` (${$t("stats.affinity")})` : ""}</span>
                            <span>{signed(term.value)}%</span>
                        </p>
                    {/each}
                    <p class="term total"><span>{$t("stats.total")}</span><span>{total(stats.critTerms)}%</span></p>
                </div>
            {/if}
        </div>

        <div class="stat-row plain">
            <span class="stat-name">{$t("stats.maxHp")}</span>
            <span class="stat-value">{player.maxHp}</span>
        </div>

        {#if ability}
            <div class="stat-row plain ability-row">
                <span class="stat-name">{$t("stats.ability")}</span>
                <span class="stat-value ability-name">{ability.name}</span>
                <span class="stat-note">
                    {#if discounted}<s class="base-cost">{ability.cost}</s>{/if}
                    {$t("stats.abilityCost", { cost: abilityCost, resource: ability.resourceName })}
                </span>
            </div>
        {/if}
    </div>
{/if}

<style>
    .stats-panel {
        display: flex;
        flex-direction: column;
        gap: 0.25rem;
        min-width: 13rem;
    }

    .section-label {
        font-size: 0.65rem;
        text-transform: uppercase;
        letter-spacing: 0.15em;
        color: #444;
        margin-bottom: 0.75rem;
    }

    .stat-row {
        position: relative;
        display: grid;
        grid-template-columns: 1fr auto;
        align-items: baseline;
        column-gap: 1rem;
        padding: 0.4rem 0.6rem;
        background: #141414;
        border: 1px solid #222;
        font-family: monospace;
        outline: none;
    }

    .stat-row:not(.plain) {
        cursor: help;
    }

    .stat-row:not(.plain):hover,
    .stat-row:not(.plain):focus-visible {
        border-color: #3a3a3a;
        background: #181818;
    }

    .stat-name {
        font-size: 0.68rem;
        text-transform: uppercase;
        letter-spacing: 0.08em;
        color: #666;
    }

    .stat-value {
        font-size: 0.95rem;
        font-weight: bold;
        color: #ddd;
        text-align: right;
    }

    .ability-name {
        font-size: 0.8rem;
        color: #c9a0dc;
    }

    .stat-note {
        grid-column: 1 / -1;
        font-size: 0.62rem;
        color: #555;
        text-align: right;
    }

    .base-cost {
        color: #444;
        margin-right: 0.3rem;
    }

    .stat-tooltip {
        display: none;
        position: absolute;
        left: 0;
        top: calc(100% + 4px);
        z-index: 10;
        flex-direction: column;
        gap: 0.15rem;
        min-width: 17rem;
        padding: 0.55rem 0.7rem;
        background: #141414;
        border: 1px solid #2a2a2a;
        pointer-events: none;
    }

    .stat-row:hover .stat-tooltip,
    .stat-row:focus-visible .stat-tooltip {
        display: flex;
    }

    .term {
        display: flex;
        justify-content: space-between;
        gap: 1.5rem;
        font-size: 0.7rem;
        color: #5ce07a;
    }

    .term span:first-child {
        color: #999;
        white-space: nowrap;
    }

    .term.total {
        margin-top: 0.2rem;
        padding-top: 0.25rem;
        border-top: 1px solid #2a2a2a;
        color: #ddd;
    }

    .term.total span:first-child {
        color: #777;
    }
</style>
