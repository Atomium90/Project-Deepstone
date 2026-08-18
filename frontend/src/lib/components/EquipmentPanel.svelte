<script lang="ts">
    import { gameState } from "../engine/StateStore";
    import EquipSlotBox from "./EquipSlotBox.svelte";

    $: equipment = $gameState?.equipment;
    $: keyCount = equipment?.keys.reduce((sum, k) => sum + k.count, 0) ?? 0;
</script>

<div class="equipment-panel">
    <p class="section-label">Equipment</p>

    {#if equipment}
        <div class="slot-row">
            <div class="slot-group">
                <span class="slot-label">Weapon</span>
                <EquipSlotBox item={equipment.weapon} size={64} />
            </div>
            <div class="slot-group">
                <span class="slot-label">Armor</span>
                <EquipSlotBox item={equipment.armor} size={64} />
            </div>
            {#each equipment.accessories as accessory, i}
                <div class="slot-group">
                    <span class="slot-label">Accessory {i + 1}</span>
                    <EquipSlotBox item={accessory} size={64} />
                </div>
            {/each}
        </div>

        <p class="section-label">Potions</p>
        <div class="slot-row">
            {#each equipment.potionBelt as potion, i}
                <div class="slot-group">
                    <span class="slot-label">Slot {i + 1}</span>
                    <EquipSlotBox item={potion} size={64} />
                </div>
            {/each}
        </div>

        {#if keyCount > 0}
            <p class="key-count">🔑 {keyCount} key{keyCount !== 1 ? "s" : ""}</p>
        {/if}
    {:else}
        <p class="muted">No equipment loaded.</p>
    {/if}
</div>

<style>
    .equipment-panel {
        display: flex;
        flex-direction: column;
        margin-top: 1.5rem;
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

    .muted { color: #444; font-size: 0.85rem; }
</style>
