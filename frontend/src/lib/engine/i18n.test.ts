import { describe, test, expect, beforeEach } from "vitest";
import { get } from "svelte/store";
import {
    availableLanguages,
    detectLanguage,
    dictionaries,
    language,
    languageName,
    lookup,
    pluralCategory,
    t,
    translate,
    type Dictionaries,
} from "./i18n";

const sample: Dictionaries = {
    en: { "greeting": "Hello", "found": "Found {count} {thing}", "english.only": "Only in English" },
    fr: { "greeting": "Bonjour", "found": "{count} {thing} trouve" },
};

describe("lookup", () => {
    test("returns the text of the requested language", () => {
        expect(lookup(sample, "fr", "greeting")).toBe("Bonjour");
    });

    test("falls back to English when the language lacks the key", () => {
        expect(lookup(sample, "fr", "english.only")).toBe("Only in English");
    });

    test("is null when no language has the key, instead of giving the key back", () => {
        expect(lookup(sample, "fr", "missing.key")).toBeNull();
    });

    test("leaves the placeholders as written", () => {
        expect(lookup(sample, "en", "found")).toBe("Found {count} {thing}");
    });
});

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

describe("plurals", () => {
    const plural: Dictionaries = {
        en: { "keys.one": "{count} key", "keys.other": "{count} keys", "plain": "No forms" },
        fr: { "keys.one": "{count} cle", "keys.other": "{count} cles" },
    };

    test("picks the form of the count's plural category", () => {
        expect(translate(plural, "en", "keys", { count: 1 })).toBe("1 key");
        expect(translate(plural, "en", "keys", { count: 3 })).toBe("3 keys");
        expect(translate(plural, "en", "keys", { count: 0 })).toBe("0 keys");
    });

    test("follows the rules of the language: zero is singular in French", () => {
        expect(translate(plural, "fr", "keys", { count: 0 })).toBe("0 cle");
        expect(translate(plural, "fr", "keys", { count: 2 })).toBe("2 cles");
    });

    test("a key with no plural forms is used as is, even with a count", () => {
        expect(translate(plural, "en", "plain", { count: 2 })).toBe("No forms");
    });

    test("falls back to the .other form when the category has no text", () => {
        const only: Dictionaries = { en: { "n.other": "{count} things" } };
        expect(translate(only, "en", "n", { count: 1 })).toBe("1 things");
    });

    test("an invalid language tag counts as other instead of throwing", () => {
        expect(pluralCategory("not a language", 1)).toBe("other");
    });
});

describe("language detection", () => {
    test("takes the first preference that has a file, by its primary subtag", () => {
        expect(detectLanguage(["en", "fr"], ["de-DE", "fr-CA", "en"])).toBe("fr");
    });

    test("falls back to English when no preference has a file", () => {
        expect(detectLanguage(["en", "fr"], ["de-DE", "ja"])).toBe("en");
        expect(detectLanguage(["en", "fr"], [])).toBe("en");
    });

    test("lists English first, then the others by code", () => {
        expect(availableLanguages({ fr: {}, de: {}, en: {} })).toEqual(["en", "de", "fr"]);
    });

    test("names a language in itself, or by its code when its file has no name", () => {
        expect(languageName({ fr: { "lang.name": "Francais" }, de: {} }, "fr")).toBe("Francais");
        expect(languageName({ de: {} }, "de")).toBe("de");
    });
});

describe("language files", () => {
    test("every language file names itself", () => {
        for (const code of Object.keys(dictionaries)) {
            expect(dictionaries[code]["lang.name"], `${code}.json has no lang.name`).toBeTruthy();
        }
    });

    test("English is bundled", () => {
        expect(Object.keys(dictionaries)).toContain("en");
    });

    /** The `{name}` placeholders of a text. */
    const placeholders = (text: string): string[] => [...text.matchAll(/\{(\w+)\}/g)].map((m) => m[1]);

    /** What a translation may use: the placeholders of the English text, and the forms the log builds
     * from them (`enemy_the` for `enemy`). */
    const allowed = (english: string): Set<string> =>
        new Set(placeholders(english).flatMap((name) => [name, `${name}_the`, `${name}_The`]));

    test("every language has every key of English, so nothing falls back", () => {
        for (const [code, dictionary] of Object.entries(dictionaries)) {
            const missing = Object.keys(dictionaries.en).filter((key) => !(key in dictionary));
            expect(missing, `${code}.json lacks keys of en.json`).toEqual([]);
        }
    });

    test("a translation uses the placeholders of the English text, and none that English lacks", () => {
        const wrong: string[] = [];
        for (const [code, dictionary] of Object.entries(dictionaries)) {
            for (const [key, text] of Object.entries(dictionary)) {
                const english = dictionaries.en[key];
                if (english === undefined) continue;
                const ok = allowed(english);
                const used = new Set(placeholders(text));
                // A placeholder the English text has must still be there, in some form.
                const lost = placeholders(english).filter(
                    (name) => !used.has(name) && !used.has(`${name}_the`) && !used.has(`${name}_The`),
                );
                const extra = [...used].filter((name) => !ok.has(name));
                if (lost.length > 0 || extra.length > 0) wrong.push(`${code} ${key}: lost [${lost}] extra [${extra}]`);
            }
        }
        expect(wrong).toEqual([]);
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
