<script lang="ts">
    import { gameState, client } from "../engine/StateStore";
    import { characterTab } from "../engine/CharacterStore";
    import {
        PLAYER_CLASS_COLORS,
        CURRENCY_NAME,
        CLASS_INFO,
        CLASS_UNLOCK_UPGRADE_ID,
        DIFFICULTY_INFO,
        DIFFICULTY_COLORS,
    } from "../engine/constants";

    const ICON_LOCK = "/sprites/ui/icons/icon_lock.png";
    const ICON_GEAR = "/sprites/ui/icons/icon_gear.png";
    const ICON_SHARD = "/sprites/ui/icons/icon_shard.png";
    import type { ClassId, Difficulty, UpgradeView } from "../engine/protocol";

    // Selected class (Warrior by default)
    let selectedClass: ClassId = "warrior";

    // Selected difficulty (Normal by default)
    let selectedDifficulty: Difficulty = "normal";

    $: player   = $gameState?.player;
    $: upgrades = $gameState?.hub?.upgrades ?? [];
    $: shards   = player?.metaCurrency ?? 0;

    const classes: ClassId[] = ["warrior", "archer", "mage"];
    const difficulties: Difficulty[] = ["easy", "normal", "hard"];

    /** A class is unlocked if it has no gating upgrade, or that upgrade has been bought. */
    function isClassUnlocked(c: ClassId): boolean {
        const upgradeId = CLASS_UNLOCK_UPGRADE_ID[c];
        if (upgradeId === null) return true;
        return upgrades.find((u) => u.id === upgradeId)?.unlocked ?? false;
    }

    function selectClass(c: ClassId): void {
        if (!isClassUnlocked(c)) return;
        selectedClass = c;
    }

    function selectDifficulty(d: Difficulty): void {
        selectedDifficulty = d;
    }

    function startRun(): void {
        client.send({
            type: "HUB_ACTION",
            action: "STARTRUN",
            classId: selectedClass,
            difficulty: selectedDifficulty,
        });
    }

    function buyUpgrade(u: UpgradeView): void {
        if (u.unlocked || shards < u.cost) return;
        client.send({ type: "HUB_ACTION", action: "BUYUPGRADE", upgradeId: u.id });
    }

    /**
     * The last log line from the server, shown as feedback after a buy attempt.
     * Fragile: if another log entry arrives before the player reads it, the
     * displayed message no longer corresponds to their action. Fine for now,
     * would need a dedicated feedback field in the protocol to fix properly.
     */
    $: feedback = $gameState?.log.at(-1) ?? "";
</script>

<div class="hub-root">

    <!-- ── Header ── -->
    <header class="hub-header">
        <h1 class="title">DEEPSTONE</h1>
        <div class="shards">
            <img class="shards-symbol" src={ICON_SHARD} alt="" />
            <span class="shards-amount">{shards}</span>
            <span class="shards-label">{CURRENCY_NAME}</span>
        </div>
    </header>

    <div class="hub-body">

        <!-- ── Left: class selection + start ── -->
        <section class="left-panel">
            <p class="section-label">Choose your class</p>

            <div class="class-list">
                {#each classes as c}
                    {@const info  = CLASS_INFO[c]}
                    {@const color = PLAYER_CLASS_COLORS[c]}
                    {@const unlocked = isClassUnlocked(c)}
                    <button
                        class="class-card"
                        class:selected={selectedClass === c}
                        class:locked={!unlocked}
                        disabled={!unlocked}
                        style="--accent: {color}"
                        on:click={() => selectClass(c)}
                    >
                        {#if selectedClass === c}
                            <span class="selected-badge">Selected</span>
                        {:else if !unlocked}
                            <img class="lock-badge" src={ICON_LOCK} alt="" />
                        {/if}
                        <img class="class-icon" src={info.icon} alt="" />
                        <span class="class-name">{info.label}</span>
                        <span class="class-desc">{info.description}</span>
                        <span class="class-affinity">Affinity: {info.affinity}</span>
                    </button>
                {/each}
            </div>

            <p class="section-label">Difficulty</p>

            <div class="difficulty-list">
                {#each difficulties as d}
                    {@const info  = DIFFICULTY_INFO[d]}
                    {@const color = DIFFICULTY_COLORS[d]}
                    <button
                        class="difficulty-card"
                        class:selected={selectedDifficulty === d}
                        style="--accent: {color}"
                        on:click={() => selectDifficulty(d)}
                        title={info.description}
                    >
                        <span class="difficulty-icon">{info.icon}</span>
                        <span class="difficulty-name">{info.label}</span>
                    </button>
                {/each}
            </div>

            <button class="start-btn" on:click={startRun}>
                ▶ Start Run
                <span class="start-class">
                    as {CLASS_INFO[selectedClass].label} · {DIFFICULTY_INFO[selectedDifficulty].label}
                </span>
            </button>
        </section>

        <!-- ── Right: hub upgrades ── -->
        <section class="right-panel">
            <p class="section-label">Hub Upgrades</p>

            {#if upgrades.length === 0}
                <p class="muted">No upgrades available.</p>
            {:else}
                <div class="upgrade-list">
                    {#each upgrades as u}
                        <div class="upgrade-row" class:owned={u.unlocked}>
                            <div class="upgrade-main">
                                <span class="upgrade-icon">{u.icon}</span>
                                <div class="upgrade-info">
                                    <span class="upgrade-label">{u.label}</span>
                                    <span class="upgrade-desc">{u.description}</span>
                                </div>
                            </div>
                            <div class="upgrade-action">
                                {#if u.unlocked}
                                    <span class="owned-badge">
                                        <img class="badge-check" src="/sprites/ui/Green/Default/icon_outline_checkmark.png" alt="" />
                                        Owned
                                    </span>
                                {:else}
                                    <span
                                        class="upgrade-cost"
                                        class:unaffordable={shards < u.cost}
                                    >
                                        <img class="cost-icon" src={ICON_SHARD} alt="" />
                                        {u.cost}
                                    </span>
                                    <button
                                        class="buy-btn"
                                        disabled={shards < u.cost}
                                        on:click={() => buyUpgrade(u)}
                                    >
                                        Buy
                                    </button>
                                {/if}
                            </div>
                        </div>
                    {/each}
                </div>
            {/if}

            {#if feedback}
                <p class="feedback">{feedback}</p>
            {/if}
        </section>

    </div>

    <!-- No Inventory nav here: the Hub has no inventory context. -->
    <footer class="hub-footer">
        <button class="footer-nav-btn" on:click={() => characterTab.set("settings")}>
            <img class="footer-nav-icon" src={ICON_GEAR} alt="" />
            <span class="footer-nav-label">Settings</span>
        </button>
        <button class="footer-nav-btn" on:click={() => characterTab.set("achievements")}>
            <span class="footer-nav-icon">🏆</span>
            <span class="footer-nav-label">Achievements</span>
        </button>
    </footer>
</div>

<style>
    .hub-root {
        width: 100%;
        height: 100%;
        display: flex;
        flex-direction: column;
        background: #111;
        font-family: monospace;
        overflow: hidden;
    }

    /* ── Footer nav ── */

    .hub-footer {
        display: flex;
        justify-content: center;
        gap: 3rem;
        padding: 0.85rem 2rem;
        border-top: 1px solid #1e1e1e;
        flex-shrink: 0;
    }

    .footer-nav-btn {
        display: flex;
        flex-direction: column;
        align-items: center;
        gap: 0.25rem;
        background: none;
        border: none;
        color: #777;
        font-family: monospace;
        cursor: pointer;
        padding: 0.2rem 0.6rem;
        transition: color 0.12s;
    }

    .footer-nav-btn:hover { color: #ccc; }

    .footer-nav-icon { font-size: 1.1rem; width: 1.1rem; height: 1.1rem; object-fit: contain; }
    .footer-nav-label { font-size: 0.65rem; text-transform: uppercase; letter-spacing: 0.08em; }

    /* ── Header ── */

    .hub-header {
        display: flex;
        align-items: center;
        justify-content: space-between;
        padding: 1.25rem 2rem;
        border-bottom: 1px solid #1e1e1e;
        flex-shrink: 0;
    }

    .title {
        font-size: 1.6rem;
        letter-spacing: 0.25em;
        color: #ccc;
    }

    .shards {
        display: flex;
        align-items: center;
        gap: 0.4rem;
    }

    .shards-symbol { width: 1.1rem; height: 1.1rem; image-rendering: pixelated; }
    .shards-amount { font-size: 1.4rem; color: #d4ac0d; font-weight: bold; }
    .shards-label  { font-size: 0.7rem; color: #666; text-transform: uppercase; letter-spacing: 0.1em; }

    /* ── Body ── */

    .hub-body {
        flex: 1;
        display: grid;
        grid-template-columns: 1fr 1fr;
        gap: 0;
        overflow: hidden;
    }

    .section-label {
        font-size: 0.65rem;
        text-transform: uppercase;
        letter-spacing: 0.15em;
        color: #444;
        margin-bottom: 1rem;
    }

    /* ── Left panel ── */

    .left-panel {
        padding: 2rem;
        border-right: 1px solid #1e1e1e;
        display: flex;
        flex-direction: column;
        gap: 1.25rem;
        overflow-y: auto;
    }

    .class-list {
        display: flex;
        flex-direction: column;
        gap: 0.6rem;
    }

    .class-card {
        position: relative;
        display: grid;
        grid-template-columns: 2rem 1fr;
        grid-template-rows: auto auto;
        grid-template-areas:
            "icon name"
            "icon desc"
            "icon affinity";
        column-gap: 0.75rem;
        row-gap: 0.1rem;
        padding: 0.75rem 1rem;
        background: #161616;
        border: 1px solid #252525;
        color: #aaa;
        cursor: pointer;
        text-align: left;
        font-family: monospace;
        transition: background 0.12s, border-color 0.12s;
    }

    .class-card:hover {
        background: #1e1e1e;
        border-color: #444;
    }

    .class-card.selected {
        background: #1a1a1a;
        border-color: var(--accent);
        color: #ddd;
    }

    .class-icon {
        grid-area: icon;
        align-self: center;
        width: 1.75rem;
        height: 1.75rem;
        object-fit: contain;
        image-rendering: pixelated;
    }
    .class-name    { grid-area: name; font-size: 0.9rem; color: #ccc; }
    .class-desc    { grid-area: desc; font-size: 0.72rem; color: #666; }
    .class-affinity { grid-area: affinity; font-size: 0.65rem; color: #555; margin-top: 0.15rem; }

    .class-card.selected .class-name { color: var(--accent); }

    .class-card.locked {
        opacity: 0.45;
        cursor: not-allowed;
    }

    .class-card.locked:hover {
        background: #161616;
        border-color: #252525;
    }

    .selected-badge,
    .lock-badge {
        position: absolute;
        top: 0.4rem;
        right: 0.4rem;
    }

    .selected-badge {
        font-size: 0.6rem;
        text-transform: uppercase;
        letter-spacing: 0.1em;
        padding: 0.15rem 0.4rem;
        background: #1a1a1a;
        border: 1px solid var(--accent);
        color: var(--accent);
    }

    .lock-badge {
        width: 0.85rem;
        height: 0.85rem;
    }

    .difficulty-list {
        display: flex;
        gap: 0.5rem;
    }

    .difficulty-card {
        flex: 1;
        display: flex;
        flex-direction: column;
        align-items: center;
        gap: 0.2rem;
        padding: 0.6rem 0.4rem;
        background: #161616;
        border: 1px solid #252525;
        color: #aaa;
        cursor: pointer;
        font-family: monospace;
        transition: background 0.12s, border-color 0.12s;
    }

    .difficulty-card:hover {
        background: #1e1e1e;
        border-color: #444;
    }

    .difficulty-card.selected {
        background: #1a1a1a;
        border-color: var(--accent);
        color: var(--accent);
    }

    .difficulty-icon { font-size: 1.1rem; }
    .difficulty-name { font-size: 0.72rem; text-transform: uppercase; letter-spacing: 0.05em; }

    .start-btn {
        margin-top: auto;
        padding: 0.85rem 1rem;
        background: #1a2a1a;
        border: 1px solid #2a4a2a;
        color: #5ce07a;
        font-family: monospace;
        font-size: 0.95rem;
        letter-spacing: 0.08em;
        cursor: pointer;
        display: flex;
        flex-direction: column;
        align-items: center;
        gap: 0.2rem;
        transition: background 0.12s, border-color 0.12s;
    }

    .start-btn:hover {
        background: #1f351f;
        border-color: #5ce07a;
    }

    .start-class {
        font-size: 0.65rem;
        color: #3a7a4a;
        text-transform: uppercase;
        letter-spacing: 0.12em;
    }

    /* ── Right panel ── */

    .right-panel {
        padding: 2rem;
        display: flex;
        flex-direction: column;
        overflow-y: auto;
    }

    .upgrade-list {
        display: flex;
        flex-direction: column;
        gap: 0.4rem;
    }

    .upgrade-row {
        display: flex;
        align-items: center;
        justify-content: space-between;
        gap: 1rem;
        padding: 0.65rem 0.85rem;
        background: #161616;
        border: 1px solid #222;
        transition: border-color 0.1s;
    }

    .upgrade-row:hover:not(.owned) { border-color: #333; }
    .upgrade-row.owned { opacity: 0.5; }

    .upgrade-main {
        display: flex;
        align-items: center;
        gap: 0.65rem;
        min-width: 0;
    }

    .upgrade-icon {
        font-size: 1.15rem;
        flex-shrink: 0;
        width: 1.4rem;
        text-align: center;
    }

    .upgrade-info {
        display: flex;
        flex-direction: column;
        gap: 0.15rem;
        min-width: 0;
    }

    .upgrade-label {
        font-size: 0.85rem;
        color: #ccc;
    }

    .upgrade-desc {
        font-size: 0.7rem;
        color: #555;
    }

    .upgrade-action {
        display: flex;
        align-items: center;
        gap: 0.6rem;
        flex-shrink: 0;
    }

    .upgrade-cost {
        display: inline-flex;
        align-items: center;
        gap: 0.3rem;
        font-size: 0.8rem;
        color: #c8a84b;
        white-space: nowrap;
    }

    .cost-icon {
        width: 0.85rem;
        height: 0.85rem;
        image-rendering: pixelated;
        flex-shrink: 0;
    }

    .upgrade-cost.unaffordable { color: #444; }
    .upgrade-cost.unaffordable .cost-icon { opacity: 0.4; }

    .buy-btn {
        padding: 0.3rem 0.7rem;
        background: #1a1a1a;
        border: 1px solid #333;
        color: #aaa;
        font-family: monospace;
        font-size: 0.75rem;
        cursor: pointer;
        transition: background 0.1s, border-color 0.1s, color 0.1s;
    }

    .buy-btn:hover:not(:disabled) {
        background: #222;
        border-color: #c8a84b;
        color: #d4ac0d;
    }

    .buy-btn:disabled {
        opacity: 0.3;
        cursor: not-allowed;
    }

    .owned-badge {
        display: inline-flex;
        align-items: center;
        gap: 0.3rem;
        font-size: 0.72rem;
        color: #3a7a4a;
        letter-spacing: 0.05em;
        flex-shrink: 0;
    }

    .badge-check {
        display: block;
        width: 0.75rem;
        height: auto;
        image-rendering: pixelated;
        flex-shrink: 0;
    }

    .feedback {
        margin-top: 1rem;
        font-size: 0.75rem;
        color: #888;
        font-style: italic;
    }

    .muted { color: #444; font-size: 0.85rem; }
</style>