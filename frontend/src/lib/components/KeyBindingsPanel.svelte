<script lang="ts">
    import { onDestroy } from "svelte";
    import { settings } from "../engine/SettingsStore";
    import { bindings, layoutMap } from "../engine/KeyStore";
    import { t } from "../engine/i18n";
    import { bindingLabel, isBindableCode, KEY_ACTIONS, takenBy, type KeyAction } from "../engine/keybindings";

    /** The action waiting for a key, or null when none is. */
    let listening: KeyAction | null = null;
    /** Why the last key was refused, shown under the list until the next attempt. */
    let refusal: string | null = null;

    function listen(action: KeyAction): void {
        refusal = null;
        listening = listening === action ? null : action;
        syncCapture();
    }

    /** The window is listened to in the capture phase while a key is awaited, so that no other part of
     * the game (the HUD's own keys, for one) reacts to the key that is being chosen. */
    let capturing = false;

    function syncCapture(): void {
        if (listening !== null && !capturing) {
            window.addEventListener("keydown", capture, true);
            capturing = true;
        } else if (listening === null && capturing) {
            window.removeEventListener("keydown", capture, true);
            capturing = false;
        }
    }

    function capture(e: KeyboardEvent): void {
        if (listening === null) return;
        e.preventDefault();
        e.stopImmediatePropagation();
        if (e.repeat) return;
        if (e.key === "Escape") {
            listening = null;
            syncCapture();
            return;
        }
        // A bare modifier is only the start of a combination: wait for the key itself.
        if (["Shift", "Control", "Alt", "Meta", "AltGraph", "CapsLock"].includes(e.key)) return;

        const press = { key: e.key, code: e.code };
        const action = listening;
        if (!isBindableCode(e.code)) {
            refusal = $t("controls.reserved");
            return;
        }
        const other = takenBy(press, action, $bindings);
        if (other !== null) {
            refusal = $t("controls.taken", { action: $t(`controls.action.${other}`) });
            return;
        }
        settings.update((s) => ({ ...s, keys: { ...s.keys, [action]: e.code } }));
        refusal = null;
        listening = null;
        syncCapture();
    }

    function reset(): void {
        settings.update((s) => ({ ...s, keys: {} }));
        refusal = null;
        listening = null;
        syncCapture();
    }

    onDestroy(() => {
        if (capturing) window.removeEventListener("keydown", capture, true);
    });
</script>

<section class="controls" aria-labelledby="controls-title">
    <p class="controls-title" id="controls-title">{$t("controls.title")}</p>
    <p class="controls-help">{$t("controls.help")}</p>

    {#each KEY_ACTIONS as action (action)}
        <div class="binding-row">
            <span>{$t(`controls.action.${action}`)}</span>
            <button
                class="binding-btn"
                class:listening={listening === action}
                data-action={action}
                on:click={() => listen(action)}
            >
                {listening === action ? $t("controls.press") : bindingLabel($bindings[action], $layoutMap)}
            </button>
        </div>
    {/each}

    {#if refusal}
        <p class="controls-refusal" role="alert">{refusal}</p>
    {/if}

    <button class="binding-reset" on:click={reset}>{$t("controls.reset")}</button>
</section>

<style>
    .controls {
        max-width: 24rem;
        margin-top: 1.75rem;
        font-family: monospace;
    }

    .controls-title {
        font-size: 0.65rem;
        text-transform: uppercase;
        letter-spacing: 0.1em;
        color: #777;
        margin-bottom: 0.5rem;
    }

    .controls-help {
        font-size: 0.7rem;
        line-height: 1.5;
        color: #666;
        margin-bottom: 0.75rem;
    }

    .binding-row {
        display: flex;
        align-items: center;
        justify-content: space-between;
        gap: 1rem;
        padding: 0.5rem 0.85rem;
        background: #161616;
        border: 1px solid #222;
        color: #ccc;
        font-size: 0.85rem;
    }

    .binding-row + .binding-row {
        margin-top: 0.4rem;
    }

    .binding-btn {
        min-width: 5.5rem;
        padding: 0.25rem 0.75rem;
        background: #111;
        border: 1px solid #555;
        border-radius: 3px;
        color: #d4ac0d;
        font-family: monospace;
        font-size: 0.75rem;
        cursor: pointer;
        transition: color 0.12s, border-color 0.12s;
    }

    .binding-btn:hover {
        border-color: #d4ac0d;
    }

    .binding-btn.listening {
        color: #ccc;
        border-color: #d4ac0d;
        animation: wait 1s ease-in-out infinite;
    }

    @keyframes wait {
        50% {
            border-color: #555;
        }
    }

    @media (prefers-reduced-motion: reduce) {
        .binding-btn.listening {
            animation: none;
        }
    }

    .controls-refusal {
        margin-top: 0.6rem;
        font-size: 0.75rem;
        color: #e05c5c;
    }

    .binding-reset {
        margin-top: 0.75rem;
        padding: 0.25rem 0.75rem;
        background: none;
        border: 1px solid #555;
        border-radius: 3px;
        color: #ccc;
        font-family: monospace;
        font-size: 0.75rem;
        cursor: pointer;
    }

    .binding-reset:hover {
        color: #d4ac0d;
        border-color: #d4ac0d;
    }
</style>
