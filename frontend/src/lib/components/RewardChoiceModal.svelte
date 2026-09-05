<script lang="ts">
    import { gameState, pendingRewardChoice, client } from "../engine/StateStore";
    import { ITEM_RARITY_COLORS } from "../engine/constants";
    import type { ItemView } from "../engine/protocol";

    function pick(option: ItemView): void {
        client.send({ type: "REWARD_CHOICE", itemId: option.id });
    }

    function leave(): void {
        client.send({ type: "REWARD_CHOICE" });
    }

    /** True when `item` carries an affinity tag the player's class doesn't have - `statLine` is
     * already the correct (unscaled) number in that case, this only dims it as a visual cue. */
    function isOffAffinity(item: ItemView): boolean {
        return item.typeTag !== undefined && !($gameState?.player.affinityTags ?? []).includes(item.typeTag);
    }
</script>

{#if $pendingRewardChoice}
    {@const choice = $pendingRewardChoice}
    <div class="backdrop">
        <div class="modal">
            <p class="modal-title">Shrine's offering - choose one</p>

            <div class="options">
                {#each choice.options as option (option.id)}
                    <button
                        class="option-row"
                        style="border-color:{ITEM_RARITY_COLORS[option.rarity]}"
                        on:click={() => pick(option)}
                    >
                        <span class="option-name">{option.name}</span>
                        <span class="option-stat" class:off-affinity={isOffAffinity(option)}>{option.statLine}</span>
                        <span class="option-action">Take</span>
                    </button>
                {/each}
            </div>

            <button class="leave-btn" on:click={leave}>Leave empty-handed</button>
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
        color: #eee;
    }

    .option-name {
        color: #ccc;
    }

    .option-stat {
        color: #5ce07a;
        font-size: 0.72rem;
    }

    .option-stat.off-affinity {
        color: #666;
    }

    .option-action {
        font-size: 0.65rem;
        text-transform: uppercase;
        letter-spacing: 0.08em;
        color: #c9a227;
    }

    .leave-btn {
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

    .leave-btn:hover {
        background: #2a2a2a;
        color: #ccc;
    }
</style>
