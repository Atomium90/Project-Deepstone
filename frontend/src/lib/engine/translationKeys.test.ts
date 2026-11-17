import { describe, test, expect } from "vitest";
import { dictionaries } from "./i18n";

/** Every source file of the app, as text. */
const sources = import.meta.glob<string>(["../**/*.svelte", "../**/*.ts", "../../App.svelte"], {
    eager: true,
    query: "?raw",
    import: "default",
});

/** `$t("some.key"` and `translate(..., "some.key"` written with a plain string. A key built from
 * a template (`class.${id}.name`) cannot be checked here: texts built that way have their own
 * tests (see constantsTexts.test.ts). */
const LITERAL_KEY = /\$t\(\s*"([a-zA-Z0-9_.]+)"/g;

describe("texts used in the code", () => {
    test("every key written as a plain string in a component exists in English", () => {
        const missing: string[] = [];
        for (const [path, source] of Object.entries(sources)) {
            // i18n.ts only quotes a made-up key in its documentation.
            if (path.endsWith(".test.ts") || path.endsWith("/i18n.ts")) continue;
            for (const match of source.matchAll(LITERAL_KEY)) {
                const key = match[1];
                // A key ending in "." is the start of a built key, for instance "story." + id.
                if (key.endsWith(".")) continue;
                // A plural text is written as key.one / key.other.
                const exists = key in dictionaries.en || `${key}.other` in dictionaries.en;
                if (!exists) missing.push(`${path}: ${key}`);
            }
        }
        expect(missing).toEqual([]);
    });
});
