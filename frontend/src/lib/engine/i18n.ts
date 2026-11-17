import { derived, writable, type Readable } from "svelte/store";

/** One language file: flat keys (e.g. "hint.affinity.title") mapped to the text to show. */
export type Dictionary = Record<string, string>;

/** Every language file, keyed by language code (the file name without ".json"). */
export type Dictionaries = Record<string, Dictionary>;

export type TranslateParams = Record<string, string | number>;

/** The language any missing key falls back to, so a half-translated file never shows a raw key. */
export const FALLBACK_LANGUAGE = "en";

/** Every file in src/lang is bundled at build time, so adding a language is dropping a copy of
 * en.json in that folder with the right name, no code change. Bundling (rather than fetching at
 * runtime) means the texts are there on the very first render. */
const files = import.meta.glob<Dictionary>("../../lang/*.json", { eager: true, import: "default" });

export const dictionaries: Dictionaries = Object.fromEntries(
    Object.entries(files).map(([path, dictionary]) => [path.replace(/^.*\/([^/]+)\.json$/, "$1"), dictionary]),
);

/**
 * The text of `key` in `language`, else in the fallback language, else null. Unlike `translate`, a
 * missing key is not given back as text, so a caller can tell a text from nothing, for instance to
 * read numbered lines until they run out.
 */
export function lookup(all: Dictionaries, language: string, key: string): string | null {
    return all[language]?.[key] ?? all[FALLBACK_LANGUAGE]?.[key] ?? null;
}

/** The plural category of `count` in `language` ("one", "other", and for some languages "few",
 * "many"...). An unknown language tag counts everything as "other". */
export function pluralCategory(language: string, count: number): string {
    try {
        return new Intl.PluralRules(language).select(count);
    } catch {
        return "other";
    }
}

/**
 * The plural form of `key` for `count`: the text of `key.<category>` (for example `key.one`),
 * else `key.other`, else null so the caller falls back to the plain key. Only used when a numeric
 * `count` param is given, so a key that has no plural forms keeps working untouched.
 */
function lookupPlural(
    all: Dictionaries,
    language: string,
    key: string,
    count: string | number | undefined,
): string | null {
    if (typeof count !== "number") return null;
    return (
        lookup(all, language, `${key}.${pluralCategory(language, count)}`) ??
        lookup(all, language, `${key}.other`)
    );
}

/**
 * Looks up `key` in `language`, then in the fallback language, then gives the key back so a typo
 * is visible on screen instead of blank. `{name}` placeholders are filled from `params`; one with
 * no matching param is left as written.
 */
export function translate(
    all: Dictionaries,
    language: string,
    key: string,
    params: TranslateParams = {},
): string {
    const template = lookupPlural(all, language, key, params.count) ?? lookup(all, language, key) ?? key;
    return template.replace(/\{(\w+)\}/g, (placeholder, name: string) =>
        name in params ? String(params[name]) : placeholder,
    );
}

/** The language codes that have a file, English first (the fallback), then the others by code. */
export function availableLanguages(all: Dictionaries = dictionaries): string[] {
    return Object.keys(all).sort((a, b) =>
        a === FALLBACK_LANGUAGE ? -1 : b === FALLBACK_LANGUAGE ? 1 : a.localeCompare(b),
    );
}

/** The name of a language in that language itself (the `lang.name` key of its own file), so the
 * selector shows "Francais" whatever the language currently shown. The code when it has none. */
export function languageName(all: Dictionaries, code: string): string {
    return all[code]?.["lang.name"] ?? code;
}

/**
 * The best available language for a browser's preference list (`navigator.languages`): the first
 * preference whose primary subtag ("fr" of "fr-CA") has a file, else the fallback language.
 */
export function detectLanguage(available: readonly string[], preferred: readonly string[]): string {
    for (const tag of preferred) {
        const primary = tag.toLowerCase().split("-")[0];
        if (available.includes(primary)) return primary;
    }
    return FALLBACK_LANGUAGE;
}

/** The language currently shown. Mirrors the `language` setting (see SettingsStore.ts), which is
 * what is persisted and what the Settings tab changes. */
export const language = writable<string>(FALLBACK_LANGUAGE);

export type Translator = (key: string, params?: TranslateParams) => string;

/** Use as `$t("some.key")` in a component. A store, so a language change redraws the texts. */
export const t: Readable<Translator> = derived(
    language,
    ($language): Translator =>
        (key, params) => translate(dictionaries, $language, key, params),
);
