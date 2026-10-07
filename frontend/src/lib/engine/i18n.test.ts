import { describe, test, expect, beforeEach } from "vitest";
import { get } from "svelte/store";
import { dictionaries, language, t, translate, type Dictionaries } from "./i18n";

const sample: Dictionaries = {
    en: { "greeting": "Hello", "found": "Found {count} {thing}", "english.only": "Only in English" },
    fr: { "greeting": "Bonjour", "found": "{count} {thing} trouve" },
};

describe("translate", () => {
    test("returns the text of the requested language", () => {
        expect(translate(sample, "fr", "greeting")).toBe("Bonjour");
    });

    test("falls back to English when the language lacks the key", () => {
        expect(translate(sample, "fr", "english.only")).toBe("Only in English");
    });

    test("falls back to English when the language has no file at all", () => {
        expect(translate(sample, "de", "greeting")).toBe("Hello");
    });

    test("gives the key back when no language has it", () => {
        expect(translate(sample, "fr", "missing.key")).toBe("missing.key");
    });

    test("fills the placeholders from the params, numbers included", () => {
        expect(translate(sample, "en", "found", { count: 3, thing: "potions" })).toBe("Found 3 potions");
    });

    test("leaves a placeholder with no matching param as written", () => {
        expect(translate(sample, "en", "found", { count: 3 })).toBe("Found 3 {thing}");
    });
});

describe("language files", () => {
    test("English is bundled", () => {
        expect(Object.keys(dictionaries)).toContain("en");
    });

    test("no other language has a key that English lacks", () => {
        const english = new Set(Object.keys(dictionaries.en));
        for (const [code, dictionary] of Object.entries(dictionaries)) {
            const orphans = Object.keys(dictionary).filter((key) => !english.has(key));
            expect(orphans, `${code}.json has keys missing from en.json`).toEqual([]);
        }
    });
});

describe("t store", () => {
    beforeEach(() => language.set("en"));

    test("translates through the bundled English file", () => {
        expect(get(t)("hint.dismiss")).toBe("Got it");
    });

    test("an unknown language falls back to English", () => {
        language.set("xx");
        expect(get(t)("hint.dismiss")).toBe("Got it");
    });
});
