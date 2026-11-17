import { derived, type Readable } from "svelte/store";
import { catalogTextFor } from "./catalogText";
import { dictionaries, language, lookup, translate } from "./i18n";
import type { LogLine } from "./protocol";

/** The key of a line that carries its own English text (dev-only lines and safety nets). */
export const RAW_KEY = "log.raw";

/** Turns the param `name` of a log line, which holds an id, into the text of that id. */
type ParamResolver = (language: string, id: string) => string;

/**
 * The params of a log line that name something of the game by its id. The server never sends a name:
 * the client looks the id up in its own language file, so a line reads the same whatever the language.
 * A param that is not listed here is shown as it is (a figure, or a text made for the player).
 */
const RESOLVERS: Record<string, ParamResolver> = {
  enemy: (lang, id) => catalogTextFor(lang).enemyName(id),
  item: (lang, id) => catalogTextFor(lang).itemName({ typeId: id }),
  ability: (lang, id) => catalogTextFor(lang).abilityName({ id }),
  // The resource a line speaks of (Rage, Focus, Mana) is named by the ability that uses it.
  resource: (lang, id) => catalogTextFor(lang).abilityResource({ id }),
  upgrade: (lang, id) => catalogTextFor(lang).upgradeLabel({ id }),
  perk: (lang, id) => catalogTextFor(lang).perkLabel({ id }),
  class: (lang, id) => translate(dictionaries, lang, `class.${id}.name`),
  difficulty: (lang, id) => translate(dictionaries, lang, `difficulty.${id}.name`),
  direction: (lang, id) => translate(dictionaries, lang, `direction.${id}`),
};

/** The text of one line of the log, in `lang`. */
export function logLineText(line: LogLine, lang: string): string {
  if (line.key === RAW_KEY) return String(line.params.text ?? "");
  const params = Object.fromEntries(
    Object.entries(line.params).map(([name, value]) => {
      return [name, name in RESOLVERS && typeof value === "string" ? RESOLVERS[name](lang, value) : value];
    }),
  );
  return translate(dictionaries, lang, line.key, { ...params, ...articleForms(line, lang) });
}

/**
 * An enemy's name with its definite article, `enemy_the` and, at the start of a sentence,
 * `enemy_The` ("the Goblin" and "The Goblin", "le Gobelin" and "Le Gobelin"). The article depends on
 * the noun (le, la, l'), so it is a text of the language file: `enemy.<id>.the` and `enemy.<id>.The`.
 * A language with no such text gets the plain name.
 */
function articleForms(line: LogLine, lang: string): Record<string, string> {
  const id = line.params.enemy;
  if (typeof id !== "string") return {};
  const plain = catalogTextFor(lang).enemyName(id);
  return {
    enemy_the: lookup(dictionaries, lang, `enemy.${id}.the`) ?? plain,
    enemy_The: lookup(dictionaries, lang, `enemy.${id}.The`) ?? plain,
  };
}

/** Use as `$logText(line)` in a component. A store, so a language change rewrites the lines. */
export const logText: Readable<(line: LogLine) => string> = derived(
  language,
  ($language) => (line: LogLine) => logLineText(line, $language),
);
