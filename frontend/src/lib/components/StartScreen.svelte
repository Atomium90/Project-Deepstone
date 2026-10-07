<script lang="ts">
    import { client } from "../engine/StateStore";
    import { lastStartedDifficulty } from "../engine/RunStore";
    import { skipTutorial } from "../engine/HintStore";
    import { t } from "../engine/i18n";

    /** The first run of a new save: Warrior on Easy, no perk. The class cards, the difficulty and
     * the perks only show up in the Hub, once a run is behind the player. */
    function begin(): void {
        lastStartedDifficulty.set("easy");
        client.send({ type: "HUB_ACTION", action: "STARTRUN", classId: "warrior", difficulty: "easy" });
    }
</script>

<div class="start-root">
    <h1 class="title">DEEPSTONE</h1>
    <p class="goal">{$t("start.goal")}</p>
    <button class="begin-btn" on:click={begin}>{$t("start.begin")}</button>
    <button class="skip-btn" on:click={skipTutorial}>{$t("start.skip")}</button>
</div>

<style>
    .start-root {
        width: 100%;
        height: 100%;
        display: flex;
        flex-direction: column;
        align-items: center;
        justify-content: center;
        gap: 1.25rem;
        background: #111;
        font-family: monospace;
    }

    .title {
        font-size: 2.4rem;
        letter-spacing: 0.3em;
        color: #ccc;
    }

    .goal {
        max-width: 28rem;
        text-align: center;
        font-size: 0.85rem;
        line-height: 1.5;
        color: #777;
    }

    .begin-btn {
        margin-top: 0.75rem;
        padding: 0.85rem 3rem;
        background: #1a2a1a;
        border: 1px solid #2a4a2a;
        color: #5ce07a;
        font-family: monospace;
        font-size: 1rem;
        letter-spacing: 0.1em;
        cursor: pointer;
        transition: background 0.12s, border-color 0.12s;
    }

    .begin-btn:hover {
        background: #1f351f;
        border-color: #5ce07a;
    }

    .skip-btn {
        background: none;
        border: none;
        color: #555;
        font-family: monospace;
        font-size: 0.75rem;
        letter-spacing: 0.05em;
        cursor: pointer;
        transition: color 0.12s;
    }

    .skip-btn:hover {
        color: #aaa;
    }
</style>
