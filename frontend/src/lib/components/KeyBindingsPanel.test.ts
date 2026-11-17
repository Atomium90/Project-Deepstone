import { describe, test, expect, beforeEach, afterEach } from "vitest";
import { render, fireEvent, cleanup } from "@testing-library/svelte";
import { get } from "svelte/store";
import { tick } from "svelte";
import KeyBindingsPanel from "./KeyBindingsPanel.svelte";
import { settings } from "../engine/SettingsStore";
import { bindings } from "../engine/KeyStore";

const BASE = { reduceScreenShake: false, sfxVolume: 70, musicVolume: 50, showHints: true, language: "en" };

function pressKey(key: string, code: string): void {
    window.dispatchEvent(new KeyboardEvent("keydown", { key, code, bubbles: true, cancelable: true }));
}

const button = (container: HTMLElement, action: string) => container.querySelector(`[data-action="${action}"]`) as HTMLButtonElement;

describe("KeyBindingsPanel", () => {
    beforeEach(() => settings.set({ ...BASE, keys: {} }));
    afterEach(cleanup);

    test("lists the seven actions with the key each is on", () => {
        const { container } = render(KeyBindingsPanel);
        const rows = Array.from(container.querySelectorAll(".binding-row"));
        expect(rows).toHaveLength(7);
        expect(button(container, "moveUp").textContent?.trim()).toBe("W");
        expect(button(container, "interact").textContent?.trim()).toBe("E");
        expect(button(container, "map").textContent?.trim()).toBe("M");
    });

    test("a click waits for a key, and the next key is the new one", async () => {
        const { container } = render(KeyBindingsPanel);
        await fireEvent.click(button(container, "interact"));
        expect(button(container, "interact").textContent?.trim()).toBe("Press a key…");

        pressKey("f", "KeyF");
        await tick();
        expect(get(settings).keys).toEqual({ interact: "KeyF" });
        expect(get(bindings).interact).toEqual({ code: "KeyF" });
        expect(button(container, "interact").textContent?.trim()).toBe("F");
    });

    test("the key being chosen reaches nothing else in the game", async () => {
        const { container } = render(KeyBindingsPanel);
        let reached = false;
        const spy = () => (reached = true);
        window.addEventListener("keydown", spy);
        await fireEvent.click(button(container, "interact"));
        pressKey("f", "KeyF");
        window.removeEventListener("keydown", spy);
        expect(reached).toBe(false);
    });

    test("Escape cancels and keeps the key", async () => {
        const { container } = render(KeyBindingsPanel);
        await fireEvent.click(button(container, "interact"));
        pressKey("Escape", "Escape");
        await tick();
        expect(get(settings).keys).toEqual({});
        expect(button(container, "interact").textContent?.trim()).toBe("E");
    });

    test("a key another action is on is refused, and says which", async () => {
        const { container } = render(KeyBindingsPanel);
        await fireEvent.click(button(container, "interact"));
        pressKey("w", "KeyW");
        await tick();
        expect(container.querySelector(".controls-refusal")?.textContent).toBe("Already used for: Move up.");
        expect(get(settings).keys).toEqual({});
        // Still waiting for a key that works.
        expect(button(container, "interact").textContent?.trim()).toBe("Press a key…");
    });

    test("the letter of another action is refused on any place", async () => {
        const { container } = render(KeyBindingsPanel);
        await fireEvent.click(button(container, "moveUp"));
        // The map is on the letter M, wherever M is on this keyboard.
        pressKey("m", "Semicolon");
        await tick();
        expect(container.querySelector(".controls-refusal")?.textContent).toBe("Already used for: Map.");
    });

    test("a key the game keeps for itself is refused", async () => {
        const { container } = render(KeyBindingsPanel);
        await fireEvent.click(button(container, "interact"));
        for (const [key, code] of [["1", "Digit1"], [" ", "Space"], ["Enter", "Enter"], ["ArrowUp", "ArrowUp"], ["Tab", "Tab"]]) {
            pressKey(key, code);
            await tick();
            expect(container.querySelector(".controls-refusal")?.textContent, code).toBe("That key cannot be used.");
        }
        expect(get(settings).keys).toEqual({});
    });

    test("a bare modifier waits for the key it goes with", async () => {
        const { container } = render(KeyBindingsPanel);
        await fireEvent.click(button(container, "interact"));
        pressKey("Shift", "ShiftLeft");
        await tick();
        expect(button(container, "interact").textContent?.trim()).toBe("Press a key…");
        expect(container.querySelector(".controls-refusal")).toBeNull();
    });

    test("Reset puts every key back", async () => {
        settings.set({ ...BASE, keys: { interact: "KeyF", moveUp: "KeyT" } });
        const { container } = render(KeyBindingsPanel);
        expect(button(container, "interact").textContent?.trim()).toBe("F");
        await fireEvent.click(container.querySelector(".binding-reset") as HTMLButtonElement);
        expect(get(settings).keys).toEqual({});
        expect(button(container, "interact").textContent?.trim()).toBe("E");
    });

    test("a second click on the same key stops waiting", async () => {
        const { container } = render(KeyBindingsPanel);
        await fireEvent.click(button(container, "map"));
        await fireEvent.click(button(container, "map"));
        expect(button(container, "map").textContent?.trim()).toBe("M");
    });
});
