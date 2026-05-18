<script lang="ts">
    import { gameState, client, combatLog } from "../engine/StateStore";
    import { HP_BAR_COLOR, RESOURCE_BAR_COLORS, RESOURCE_LABELS, ABILITY_INFO } from "../engine/constants";
    import type { ItemView } from "../engine/protocol";
    import CombatLog from "./CombatLog.svelte";

    $: player         = $gameState?.player;
    $: combat         = $gameState?.combat;
    $: inventory      = $gameState?.inventory ?? [];

    $: hpPercent       = player ? (player.hp / player.maxHp) * 100 : 100;
    $: enemyHpPercent  = combat ? (combat.enemyHp / combat.enemyMaxHp) * 100 : 100;
    $: resourcePercent = player ? (player.resourceCurrent / player.resourceMax) * 100 : 100;
    $: resourceLabel   = player ? RESOURCE_LABELS[player.classId] : "Resource";
    $: resourceColor   = player ? RESOURCE_BAR_COLORS[player.classId] : "#888";
    $: isPlayerTurn    = combat?.isPlayerTurn ?? false;

    $: abilityInfo     = player ? ABILITY_INFO[player.classId] : null;
    $: abilityDisabled = !isPlayerTurn || !player || player.resourceCurrent < (abilityInfo?.cost ?? Infinity);

    /** Only consumables can be used in combat. */
    $: consumables = inventory.filter((i): i is ItemView => i.kind === "consumable");
    $: hasConsumables = consumables.length > 0;

    /** Whether the item picker panel is open. */
    let itemPickerOpen = false;

    function sendAction(action: "ATTACK" | "ABILITY" | "DEFEND"): void {
        itemPickerOpen = false;
        client.send({ type: "COMBAT_ACTION", action });
    }

    function useItem(item: ItemView): void {
        itemPickerOpen = false;
        client.send({ type: "COMBAT_ACTION", action: "ITEM", itemId: item.id });
    }

    function toggleItemPicker(): void {
        itemPickerOpen = !itemPickerOpen;
    }
</script>

<div class="combat-root">

    <!-- -- Top: combatants ------------------------------------------------ -->
    <div class="combatants">

        <!-- Player side -->
        <div class="combatant player-side">
            <p class="combatant-name">{player?.classId.toUpperCase() ?? "—"}</p>

            <div class="bar-row">
                <span class="bar-label">HP</span>
                <div class="bar-track">
                    <div class="bar" style="width:{hpPercent}%; background:{HP_BAR_COLOR}" />
                </div>
                <span class="bar-value">{player?.hp ?? 0} / {player?.maxHp ?? 0}</span>
            </div>

            <div class="bar-row">
                <span class="bar-label">{resourceLabel}</span>
                <div class="bar-track">
                    <div class="bar" style="width:{resourcePercent}%; background:{resourceColor}" />
                </div>
                <span class="bar-value">{player?.resourceCurrent ?? 0} / {player?.resourceMax ?? 0}</span>
            </div>
        </div>

        <!-- VS divider -->
        <div class="vs-divider">
            <span>VS</span>
            {#if isPlayerTurn}
                <span class="turn-badge player-turn">Your turn</span>
            {:else}
                <span class="turn-badge enemy-turn">Enemy turn</span>
            {/if}
        </div>

        <!-- Enemy side -->
        <div class="combatant enemy-side">
            <p class="combatant-name">{combat?.enemyLabel ?? "—"}</p>

            <div class="bar-row">
                <span class="bar-label">HP</span>
                <div class="bar-track">
                    <div class="bar enemy-hp-bar" style="width:{enemyHpPercent}%" />
                </div>
                <span class="bar-value">{combat?.enemyHp ?? 0} / {combat?.enemyMaxHp ?? 0}</span>
            </div>
        </div>

    </div>

    <!-- -- Middle: action buttons + item picker --------------------------- -->
    <div class="actions-area">
        <div class="actions">
            <button
                    class="action-btn attack"
                    disabled={!isPlayerTurn}
                    on:click={() => sendAction("ATTACK")}
            >
                ⚔ Attack
            </button>

            <button
                    class="action-btn defend"
                    disabled={!isPlayerTurn}
                    on:click={() => sendAction("DEFEND")}
            >
                🛡 Defend
            </button>

            <button
                    class="action-btn ability"
                    disabled={abilityDisabled}
                    title={abilityInfo?.description ?? ""}
                    on:click={() => sendAction("ABILITY")}
            >
                ✦ {abilityInfo?.name ?? "Ability"}
                <span class="ability-cost">
                    {abilityInfo?.cost ?? 0} {resourceLabel}
                </span>
            </button>

            <!-- Item button toggles the consumable picker -->
            <button
                    class="action-btn item"
                    class:active={itemPickerOpen}
                    disabled={!isPlayerTurn || !hasConsumables}
                    title={hasConsumables ? "Use a consumable" : "No consumables in inventory"}
                    on:click={toggleItemPicker}
            >
                ⊕ Item {hasConsumables ? `(${consumables.length})` : ""}
            </button>
        </div>

        <!-- Consumable picker (shown when Item button is toggled) -->
        {#if itemPickerOpen && isPlayerTurn}
            <div class="item-picker">
                <p class="picker-title">Choose a consumable</p>
                <div class="picker-list">
                    {#each consumables as item}
                        <button
                                class="picker-row"
                                on:click={() => useItem(item)}
                        >
                            <span class="picker-name">{item.name}</span>
                            <span class="picker-stat">{item.statLine}</span>
                            <span
                                    class="picker-rarity"
                                    class:uncommon={item.rarity === "uncommon"}
                            >
                                {item.rarity}
                            </span>
                        </button>
                    {/each}
                </div>
            </div>
        {/if}
    </div>

    <!-- -- Bottom: combat log -------------------------------------------- -->
    <div class="log-panel">
        <CombatLog log={$combatLog} />
    </div>

</div>

<style>
    .combat-root {
        display: flex;
        flex-direction: column;
        width: 100%;
        height: 100%;
        padding: 1.5rem 2rem;
        gap: 1.5rem;
        font-family: monospace;
    }

    /* -- Combatants ------------------------------------------------------- */

    .combatants {
        display: flex;
        align-items: center;
        gap: 2rem;
        flex-shrink: 0;
    }

    .combatant {
        flex: 1;
        display: flex;
        flex-direction: column;
        gap: 0.6rem;
        padding: 1rem;
        background: #1a1a1a;
        border: 1px solid #2a2a2a;
    }

    .combatant-name {
        font-size: 0.85rem;
        letter-spacing: 0.1em;
        color: #ccc;
        margin-bottom: 0.25rem;
    }

    .player-side { border-color: #2a4a2a; }
    .enemy-side  { border-color: #4a2a2a; }

    .bar-row {
        display: grid;
        grid-template-columns: 3rem 1fr 4rem;
        align-items: center;
        gap: 0.5rem;
    }

    .bar-label {
        font-size: 0.65rem;
        color: #555;
        text-transform: uppercase;
        letter-spacing: 0.1em;
    }

    .bar-track {
        height: 6px;
        background: #252525;
        border-radius: 2px;
        overflow: hidden;
    }

    .bar {
        height: 100%;
        border-radius: 2px;
        transition: width 0.3s ease;
    }

    .enemy-hp-bar { background: #c0392b; }

    .bar-value {
        font-size: 0.7rem;
        color: #888;
        text-align: right;
    }

    /* -- VS divider ------------------------------------------------------- */

    .vs-divider {
        display: flex;
        flex-direction: column;
        align-items: center;
        gap: 0.5rem;
        color: #444;
        font-size: 0.9rem;
        letter-spacing: 0.1em;
        flex-shrink: 0;
    }

    .turn-badge {
        font-size: 0.65rem;
        padding: 0.2rem 0.5rem;
        border-radius: 2px;
        text-transform: uppercase;
        letter-spacing: 0.1em;
    }

    .player-turn { background: #1a3a1a; color: #5ce07a; }
    .enemy-turn  { background: #3a1a1a; color: #e05c5c; }

    /* -- Actions area ----------------------------------------------------- */

    .actions-area {
        display: flex;
        flex-direction: column;
        gap: 0.75rem;
        flex-shrink: 0;
    }

    .actions {
        display: grid;
        grid-template-columns: repeat(4, 1fr);
        gap: 0.75rem;
    }

    .action-btn {
        padding: 0.75rem 0.5rem;
        background: #1e1e1e;
        color: #ccc;
        border: 1px solid #333;
        cursor: pointer;
        font-family: monospace;
        font-size: 0.85rem;
        letter-spacing: 0.05em;
        transition: background 0.12s, border-color 0.12s, color 0.12s;
        display: flex;
        flex-direction: column;
        align-items: center;
        gap: 0.2rem;
    }

    .action-btn:hover:not(:disabled) {
        background: #2a2a2a;
        border-color: #666;
        color: #eee;
    }

    .action-btn:disabled { opacity: 0.35; cursor: not-allowed; }

    .action-btn.attack  { border-color: #4a2222; }
    .action-btn.defend  { border-color: #22354a; }
    .action-btn.ability { border-color: #3a2a4a; }
    .action-btn.item    { border-color: #2a4a2a; }

    .action-btn.attack:hover:not(:disabled)  { border-color: #c0392b; }
    .action-btn.defend:hover:not(:disabled)  { border-color: #2980b9; }
    .action-btn.ability:hover:not(:disabled) { border-color: #8e44ad; }
    .action-btn.item:hover:not(:disabled)    { border-color: #27ae60; }

    .action-btn.item.active { background: #1a2a1a; border-color: #27ae60; color: #5ce07a; }

    .ability-cost {
        font-size: 0.6rem;
        color: #666;
        letter-spacing: 0.08em;
        text-transform: uppercase;
    }

    .action-btn.ability:not(:disabled) .ability-cost { color: #7a5a9a; }

    /* -- Item picker ------------------------------------------------------ */

    .item-picker {
        background: #141414;
        border: 1px solid #2a4a2a;
        padding: 0.75rem;
    }

    .picker-title {
        font-size: 0.65rem;
        text-transform: uppercase;
        letter-spacing: 0.12em;
        color: #555;
        margin-bottom: 0.5rem;
    }

    .picker-list {
        display: flex;
        flex-direction: column;
        gap: 0.3rem;
    }

    .picker-row {
        display: grid;
        grid-template-columns: 1fr auto auto;
        gap: 0.75rem;
        align-items: center;
        padding: 0.45rem 0.6rem;
        background: #1a1a1a;
        border: 1px solid #252525;
        color: #bbb;
        font-family: monospace;
        font-size: 0.8rem;
        cursor: pointer;
        text-align: left;
        transition: background 0.1s, border-color 0.1s;
    }

    .picker-row:hover {
        background: #222;
        border-color: #27ae60;
        color: #eee;
    }

    .picker-name { color: #ccc; }

    .picker-stat {
        font-size: 0.72rem;
        color: #5ce07a;
    }

    .picker-rarity {
        font-size: 0.6rem;
        text-transform: uppercase;
        letter-spacing: 0.1em;
        color: #555;
    }

    .picker-rarity.uncommon { color: #8e44ad; }

    /* -- Log -------------------------------------------------------------- */

    .log-panel {
        flex: 1 1 0;
        min-height: 0;
        padding: 1rem;
        background: #141414;
        border: 1px solid #222;
    }
</style>