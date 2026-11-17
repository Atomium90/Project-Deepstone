<script lang="ts">
    import { gameState, equippedSetCounts } from "../engine/StateStore";
    import { ITEM_RARITY_COLORS } from "../engine/constants";
    import { t } from "../engine/i18n";
    import EquipSlotBox from "./EquipSlotBox.svelte";
    import StatsPanel from "./StatsPanel.svelte";

    $: equipment = $gameState?.equipment;
    $: keyCount = equipment?.keys.reduce((sum, k) => sum + k.count, 0) ?? 0;

    /** Sets with an active (2pc+) bonus for the current class - class-gated the same way the
     * server decides whether a bonus is actually granted, so this never shows a badge for gear
     * from another class's set. */
    $: activeSets = ($gameState?.sets ?? [])
        .filter((s) => s.classId === $gameState?.player.classId)
        .map((s) => ({ set: s, count: $equippedSetCounts[s.id] ?? 0 }))
        .filter(({ count }) => count >= 2);
</script>

<div class="equipment-layout">
<div class="equipment-panel">
    <p class="section-label">{$t("equipment.title")}</p>

    {#if equipment}
        <div class="slot-row">
            <div class="slot-group">
                <span class="slot-label">{$t("equipment.weapon")}</span>
                <EquipSlotBox item={equipment.weapon} size={64} />
            </div>
            <div class="slot-group">
                <span class="slot-label">{$t("equipment.armor")}</span>
                <EquipSlotBox item={equipment.armor} size={64} />
            </div>
            {#each equipment.accessories as accessory, i}
                <div class="slot-group">
                    <span class="slot-label">{$t("equipment.accessory", { n: i + 1 })}</span>
                    <EquipSlotBox item={accessory} size={64} />
                </div>
            {/each}
        </div>

        <p class="section-label">{$t("equipment.potions")}</p>
        <div class="slot-row">
            {#each equipment.potionBelt as potion, i}
                <div class="slot-group">
                    <span class="slot-label">{$t("equipment.slot", { n: i + 1 })}</span>
                    <EquipSlotBox item={potion} size={64} />
                </div>
            {/each}
        </div>

        {#if activeSets.length > 0}
            <div
                class="set-badges"
                style="--set-accent:{ITEM_RARITY_COLORS.epic}; --set-tint:{ITEM_RARITY_COLORS.epic}1a"
            >
                {#each activeSets as { set, count } (set.id)}
                    <span class="set-badge" title="{count >= 4 ? set.bonus4pcLabel : set.bonus2pcLabel}">
                        {$t("set.progress", { name: set.name, count: Math.min(count, 4) })}
                    </span>
                {/each}
            </div>
        {/if}

        {#if keyCount > 0}
            <p class="key-count">🔑 {$t("equipment.keys", { count: keyCount })}</p>
        {/if}
    {:else}
        <p class="muted">{$t("equipment.empty")}</p>
    {/if}
</div>
<StatsPanel />
</div>

<style>
    .equipment-layout {
        display: flex;
        flex-wrap: wrap;
        align-items: flex-start;
        gap: 2.5rem 3.5rem;
        margin-top: 1.5rem;
    }

    .equipment-layout > :global(.stats-panel) {
        /* lines the first row of stats up with the first row of slots */
        padding-top: 0;
    }

    .equipment-panel {
        display: flex;
        flex-direction: column;
    }

    .section-label {
        font-size: 0.65rem;
        text-transform: uppercase;
        letter-spacing: 0.15em;
        color: #444;
        margin-bottom: 1rem;
    }

    .section-label:not(:first-child) {
        margin-top: 1.5rem;
    }

    .slot-row {
        display: flex;
        gap: 1rem;
        flex-wrap: wrap;
    }

    .slot-group {
        display: flex;
        flex-direction: column;
        align-items: center;
        gap: 0.4rem;
    }

    .slot-label {
        font-size: 0.6rem;
        text-transform: uppercase;
        letter-spacing: 0.08em;
        color: #555;
    }

    .key-count {
        margin-top: 1.25rem;
        font-size: 0.8rem;
        color: #999;
    }

    .set-badges {
        display: flex;
        flex-wrap: wrap;
        gap: 0.5rem;
        margin-top: 1.25rem;
    }

    .set-badge {
        padding: 0.3rem 0.6rem;
        background: var(--set-tint);
        border: 1px solid var(--set-accent);
        border-radius: 2px;
        color: #c9a0dc;
        font-size: 0.68rem;
        letter-spacing: 0.03em;
        cursor: default;
    }

    .muted { color: #444; font-size: 0.85rem; }
</style>
