import { describe, test, expect, beforeAll, afterAll, beforeEach } from "vitest";
import { render, fireEvent } from "@testing-library/svelte";
import { get } from "svelte/store";
import { tick } from "svelte";
import HintCard from "./HintCard.svelte";
import { activeHint, resetTutorial, showHint, tutorial } from "../engine/HintStore";
import { settings } from "../engine/SettingsStore";
import { dictionaries } from "../engine/i18n";

/** Two stand-in hints, texts added to the English dictionary for the duration of this file only. */
const TEST_TEXTS: Record<string, string> = {
    "hint.first.title": "First title",
    "hint.first.body": "First body",
    "hint.second.title": "Second title",
    "hint.second.body": "Second body",
};

describe("HintCard", () => {
    beforeAll(() => Object.assign(dictionaries.en, TEST_TEXTS));
    afterAll(() => Object.keys(TEST_TEXTS).forEach((key) => delete dictionaries.en[key]));

    beforeEach(() => {
        settings.update((s) => ({ ...s, showHints: true }));
        resetTutorial();
    });

    test("renders nothing while no hint is active", () => {
        const { container } = render(HintCard);
        expect(container.querySelector(".hint-card")).toBeNull();
    });

    test("shows the title and body of the active hint, read from the lang file", async () => {
        const { container } = render(HintCard);
        showHint("first");
        await tick();
        expect(container.querySelector(".hint-title")?.textContent).toBe("First title");
        expect(container.querySelector(".hint-body")?.textContent).toBe("First body");
    });

    test("labels the dismiss button from the lang file", async () => {
        const { container } = render(HintCard);
        showHint("first");
        await tick();
        expect(container.querySelector(".hint-dismiss")?.textContent).toBe("Got it");
    });

    test("a hint with no texts in the lang file shows its keys, so the gap is visible", async () => {
        const { container } = render(HintCard);
        showHint("untranslated");
        await tick();
        expect(container.querySelector(".hint-title")?.textContent).toBe("hint.untranslated.title");
        expect(container.querySelector(".hint-body")?.textContent).toBe("hint.untranslated.body");
    });

    test("dismissing closes the card and records the hint as seen", async () => {
        const { container } = render(HintCard);
        showHint("first");
        await tick();
        await fireEvent.click(container.querySelector(".hint-dismiss")!);

        expect(container.querySelector(".hint-card")).toBeNull();
        expect(get(tutorial).seen).toEqual(["first"]);
    });

    test("dismissing brings up the next waiting hint", async () => {
        const { container } = render(HintCard);
        showHint("first");
        showHint("second");
        await tick();
        expect(container.querySelector(".hint-title")?.textContent).toBe("First title");

        await fireEvent.click(container.querySelector(".hint-dismiss")!);
        expect(container.querySelector(".hint-title")?.textContent).toBe("Second title");
        expect(get(activeHint)).toBe("second");
    });

    test("a hint with no corner of its own shows bottom left", async () => {
        const { container } = render(HintCard);
        showHint("first");
        await tick();
        expect(container.querySelector(".hint-card")?.classList.contains("pos-bottom-left")).toBe(true);
    });

    test("a hint shows in the corner its catalog entry names", async () => {
        const { container } = render(HintCard);
        showHint("shop");
        await tick();
        expect(container.querySelector(".hint-card")?.classList.contains("pos-top-right")).toBe(true);
    });

    test("turning hints off in Settings removes the card", async () => {
        const { container } = render(HintCard);
        showHint("first");
        await tick();
        settings.update((s) => ({ ...s, showHints: false }));
        await tick();
        expect(container.querySelector(".hint-card")).toBeNull();
    });
});
