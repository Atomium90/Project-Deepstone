<script lang="ts">
    import { gameState, client, combatLog, combatDamageEvents } from "../engine/StateStore";
    import { HP_BAR_COLOR, RESOURCE_BAR_COLORS, COLOR_ENTITY_ENEMY } from "../engine/constants";
    import type { ItemView } from "../engine/protocol";
    import CombatLog from "./CombatLog.svelte";
    import StatBar from "./StatBar.svelte";
    import Sprite from "./Sprite.svelte";

    interface Floater {
        id: number;
        targetIsPlayer: boolean;
        amount: number;
        kind: "damage" | "heal";
    }

    let floaters: Floater[] = [];
    let nextFloaterId = 0;

    /** null = no flash active. Re-set via triggerFlash's remove-then-re-add so a CSS animation
     * restarts even when the same kind fires twice in a row (e.g. two hits back to back). */
    let playerFlashKind: "damage" | "heal" | null = null;
    let enemyFlashKind: "damage" | "heal" | null = null;

    function triggerFlash(targetIsPlayer: boolean, kind: "damage" | "heal"): void {
        const apply = (v: "damage" | "heal" | null) =>
            targetIsPlayer ? (playerFlashKind = v) : (enemyFlashKind = v);
        apply(null);
        requestAnimationFrame(() => {
            apply(kind);
            setTimeout(() => apply(null), 400);
        });
    }

    /** Spawns a floating number + triggers the hit flash/shake for every damage/heal event on the
     * action that just resolved. Reacts to the store rather than $gameState directly so it only
     * fires when there's actually something to react to (mirrors AchievementToast's pattern). */
    $: if ($combatDamageEvents.length > 0) {
        for (const evt of $combatDamageEvents) {
            const id = nextFloaterId++;
            floaters = [...floaters, { id, targetIsPlayer: evt.targetIsPlayer, amount: evt.amount, kind: evt.kind }];
            setTimeout(() => {
                floaters = floaters.filter((f) => f.id !== id);
            }, 900);
            triggerFlash(evt.targetIsPlayer, evt.kind);
        }
    }

    $: player         = $gameState?.player;
    $: combat         = $gameState?.combat;
    $: inventory      = $gameState?.inventory ?? [];
    $: abilities      = $gameState?.abilities ?? [];

    $: abilityInfo     = player ? abilities.find((a) => a.classId === player.classId) ?? null : null;
    $: resourceLabel   = abilityInfo?.resourceName ?? "Resource";
    $: resourceColor   = player ? RESOURCE_BAR_COLORS[player.classId] : "#888";
    $: isPlayerTurn    = combat?.isPlayerTurn ?? false;

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
        <div
                class="combatant player-side"
                class:flash-damage={playerFlashKind === "damage"}
                class:flash-heal={playerFlashKind === "heal"}
        >
            <p class="combatant-name">{player?.classId.toUpperCase() ?? "—"}</p>

            <StatBar label="HP" current={player?.hp ?? 0} max={player?.maxHp ?? 0} color={HP_BAR_COLOR} />
            <StatBar label={resourceLabel} current={player?.resourceCurrent ?? 0} max={player?.resourceMax ?? 0} color={resourceColor} />

            {#each floaters.filter((f) => f.targetIsPlayer) as f (f.id)}
                <span class="floater" class:heal={f.kind === "heal"}>
                    {f.kind === "heal" ? "+" : "-"}{f.amount}
                </span>
            {/each}
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
        <div
                class="combatant enemy-side"
                class:flash-damage={enemyFlashKind === "damage"}
                class:flash-heal={enemyFlashKind === "heal"}
        >
            {#if combat?.spriteId}
                <div class="portrait">
                    <Sprite spriteId={combat.spriteId} fallbackColor={COLOR_ENTITY_ENEMY} size={112} />
                </div>
            {/if}
            <p class="combatant-name">{combat?.enemyLabel ?? "—"}</p>

            <StatBar label="HP" current={combat?.enemyHp ?? 0} max={combat?.enemyMaxHp ?? 0} color={HP_BAR_COLOR} />

            {#each floaters.filter((f) => !f.targetIsPlayer) as f (f.id)}
                <span class="floater" class:heal={f.kind === "heal"}>
                    {f.kind === "heal" ? "+" : "-"}{f.amount}
                </span>
            {/each}
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
        position: relative;
        flex: 1;
        display: flex;
        flex-direction: column;
        gap: 0.6rem;
        padding: 1rem;
        background: #1a1a1a;
        border: 1px solid #2a2a2a;
    }

    .combatant.flash-damage { animation: hit-shake 0.4s ease; }
    .combatant.flash-damage::after,
    .combatant.flash-heal::after {
        content: "";
        position: absolute;
        inset: 0;
        pointer-events: none;
        animation: hit-flash 0.4s ease-out;
    }
    .combatant.flash-damage::after { background: rgba(224, 92, 92, 0.35); }
    .combatant.flash-heal::after   { background: rgba(92, 224, 122, 0.3); }

    @keyframes hit-shake {
        0%, 100% { transform: translateX(0); }
        20%      { transform: translateX(-5px); }
        40%      { transform: translateX(4px); }
        60%      { transform: translateX(-3px); }
        80%      { transform: translateX(2px); }
    }

    @keyframes hit-flash {
        0%   { opacity: 1; }
        100% { opacity: 0; }
    }

    .floater {
        position: absolute;
        top: 40%;
        left: 50%;
        transform: translateX(-50%);
        font-size: 1.1rem;
        font-weight: bold;
        color: #e05c5c;
        text-shadow: 0 1px 2px rgba(0, 0, 0, 0.8);
        pointer-events: none;
        animation: float-up 0.9s ease-out forwards;
    }

    .floater.heal { color: #5ce07a; }

    @keyframes float-up {
        0%   { opacity: 1; transform: translate(-50%, 0); }
        100% { opacity: 0; transform: translate(-50%, -2.2rem); }
    }

    .combatant-name {
        font-size: 0.85rem;
        letter-spacing: 0.1em;
        color: #ccc;
        margin-bottom: 0.25rem;
    }

    .player-side { border-color: #2a4a2a; }
    .enemy-side  { border-color: #4a2a2a; }

    .portrait {
        display: flex;
        justify-content: center;
        margin-bottom: 0.25rem;
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
        background-image: url(/sprites/ui/Grey/Default/button_rectangle_border.png);
        background-size: 100% 100%;
        image-rendering: pixelated;
        color: #ccc;
        border: 1px solid #333;
        cursor: pointer;
        font-family: monospace;
        font-size: 0.85rem;
        letter-spacing: 0.05em;
        transition: filter 0.12s, border-color 0.12s, color 0.12s;
        display: flex;
        flex-direction: column;
        align-items: center;
        gap: 0.2rem;
    }

    .action-btn:hover:not(:disabled) {
        filter: brightness(1.25);
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

    .action-btn.item.active { filter: brightness(1.3); border-color: #27ae60; color: #5ce07a; }

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