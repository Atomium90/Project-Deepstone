<script lang="ts">
    import { pendingEquipChoice, client } from "../engine/StateStore";
    import { ITEM_RARITY_COLORS } from "../engine/constants";
    import type { EquipChoiceOptionView } from "../engine/protocol";

    function replace(option: EquipChoiceOptionView): void {
        client.send({ type: "EQUIP_CHOICE", targetSlot: option.slot });
    }

    function keepCurrent(): void {
        client.send({ type: "EQUIP_CHOICE" });
    }
</script>

{#if $pendingEquipChoice}
    {@const choice = $pendingEquipChoice}
    <div class="backdrop">
        <div class="modal">
            <p class="modal-title">New item found</p>

            <div
                class="new-item"
                style="border-color:{ITEM_RARITY_COLORS[choice.newItem.rarity]}; background-color:{ITEM_RARITY_COLORS[choice.newItem.rarity]}1a"
            >
                <span class="item-name">{choice.newItem.name}</span>
                <span class="item-stat">{choice.newItem.statLine}</span>
            </div>

            <!-- Weapon/armor naturally offer a single option here; accessories/potions can offer
                 up to 2-3, the player picks which one to replace. No special-casing needed either
                 way, it's just however many rows `options` has. -->
            <div class="options">
                {#each choice.options as option (option.slot)}
                    <button class="option-row" on:click={() => replace(option)}>
                        <span class="option-name">{option.current.name}</span>
                        <span class="option-stat">{option.current.statLine}</span>
                        <span class="option-action">Replace</span>
                    </button>
                {/each}
            </div>

            <button class="keep-btn" on:click={keepCurrent}>Keep what I have</button>
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
        background: rgba(0, 0, 0, 0.65);
    }

    .modal {
        width: 340px;
        max-width: 90vw;
        padding: 1.25rem;
        background: #1a1a1a;
        border: 1px solid #2a2a2a;
        border-radius: 4px;
        font-family: monospace;
        display: flex;
        flex-direction: column;
        gap: 0.75rem;
    }

    .modal-title {
        font-size: 0.65rem;
        text-transform: uppercase;
        letter-spacing: 0.12em;
        color: #555;
    }

    .new-item {
        display: flex;
        flex-direction: column;
        gap: 0.2rem;
        padding: 0.6rem 0.75rem;
        background: #141414;
        border: 1px solid;
        border-radius: 2px;
    }

    .item-name {
        color: #eee;
        font-size: 0.9rem;
    }

    .item-stat {
        color: #5ce07a;
        font-size: 0.75rem;
    }

    .options {
        display: flex;
        flex-direction: column;
        gap: 0.4rem;
    }

    .option-row {
        display: grid;
        grid-template-columns: 1fr auto auto;
        gap: 0.6rem;
        align-items: center;
        padding: 0.5rem 0.65rem;
        background: #1e1e1e;
        border: 1px solid #2a2a2a;
        color: #bbb;
        font-family: monospace;
        font-size: 0.8rem;
        cursor: pointer;
        text-align: left;
        transition: background 0.1s, border-color 0.1s;
    }

    .option-row:hover {
        background: #262626;
        border-color: #c0392b;
        color: #eee;
    }

    .option-name {
        color: #ccc;
    }

    .option-stat {
        color: #888;
        font-size: 0.72rem;
    }

    .option-action {
        font-size: 0.65rem;
        text-transform: uppercase;
        letter-spacing: 0.08em;
        color: #c0392b;
    }

    .keep-btn {
        padding: 0.55rem;
        background: #222;
        border: 1px solid #333;
        border-radius: 2px;
        color: #999;
        font-family: monospace;
        font-size: 0.75rem;
        letter-spacing: 0.04em;
        cursor: pointer;
        transition: background 0.1s, color 0.1s;
    }

    .keep-btn:hover {
        background: #2a2a2a;
        color: #ccc;
    }
</style>
