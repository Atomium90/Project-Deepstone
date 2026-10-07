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
    const template = all[language]?.[key] ?? all[FALLBACK_LANGUAGE]?.[key] ?? key;
    return template.replace(/\{(\w+)\}/g, (placeholder, name: string) =>
        name in params ? String(params[name]) : placeholder,
    );
}

/** The language currently shown. Not persisted and not offered in Settings yet, there is only
 * one language file. */
export const language = writable<string>(FALLBACK_LANGUAGE);

export type Translator = (key: string, params?: TranslateParams) => string;

/** Use as `$t("some.key")` in a component. A store, so a language change redraws the texts. */
export const t: Readable<Translator> = derived(
    language,
    ($language): Translator =>
        (key, params) => translate(dictionaries, $language, key, params),
);
