import { derived, type Readable } from "svelte/store";
import { dictionaries, language, lookup, translate, type TranslateParams } from "./i18n";
import type { ItemView } from "./protocol";

const STAT_ORDER = [
  ["maxHp", "item.stat.maxHp"],
  ["attack", "item.stat.attack"],
  ["defense", "item.stat.defense"],
  ["critChance", "item.stat.crit"],
] as const;

/** The server writes an empty stat as `null` (Circe encodes a None that way), a test or a hand-built view leaves it out. */
function isPresent(value: number | null | undefined): value is number {
  return value !== undefined && value !== null;
}

/**
 * The names and descriptions of the game's catalogs (items, enemies, perks, upgrades, achievements,
 * abilities, sets). The server sends ids and figures and the client owns the words, so each text is
 * found by id in the language file (`item.<typeId>.name`, `perk.<id>.label`...). An id with no text
 * is shown as the id itself, so a missing text is noticed on screen instead of being blank.
 */
export interface CatalogText {
  itemName(item: Pick<ItemView, "typeId">): string;
  /** The one-line stat summary of an item ("+8 ATK [heavy]", "Heal 30 HP"). */
  itemStatLine(item: Pick<ItemView, "stats" | "typeTag">): string;
  enemyName(typeId: string): string;
  perkLabel(perk: { id: string }): string;
  perkDescription(perk: { id: string }): string;
  upgradeLabel(upgrade: { id: string }): string;
  upgradeDescription(upgrade: { id: string }): string;
  achievementLabel(achievement: { id: string }): string;
  achievementDescription(achievement: { id: string }): string;
  abilityName(ability: { id: string }): string;
  abilityDescription(ability: { id: string }): string;
  abilityResource(ability: { id: string }): string;
  setName(set: { id: string }): string;
  setBonus(set: { bonus2pc: { effect: string; value: number }; bonus4pc: { effect: string; value: number } }, pieces: 2 | 4): string;
}

/** Builds the catalog texts for one language. Exported for tests. */
export function catalogTextFor(language: string): CatalogText {
  const text = (key: string, id: string): string =>
    lookup(dictionaries, language, key) === null ? id : translate(dictionaries, language, key);
  const tr = (key: string, params: TranslateParams): string => translate(dictionaries, language, key, params);
  return {
    itemName: (item) => text(`item.${item.typeId}.name`, item.typeId),
    itemStatLine: (item) => {
      const stats = item.stats;
      if (stats.effect) {
        // A turn count is passed as `count`, which picks the singular or plural text.
        return tr(`item.effect.${stats.effect}`, {
          ...(isPresent(stats.amount) ? { amount: stats.amount } : {}),
          ...(isPresent(stats.percent) ? { percent: stats.percent } : {}),
          ...(isPresent(stats.turns) ? { count: stats.turns } : {}),
        });
      }
      const parts = STAT_ORDER.flatMap(([field, key]) => {
        const value = stats[field];
        return isPresent(value) ? [tr(key, { value })] : [];
      });
      const tag = item.typeTag ? ` ${tr("item.stat.tag", { tag: tr(`tag.${item.typeTag}`, {}) })}` : "";
      return parts.join(", ") + tag;
    },
    enemyName: (typeId) => text(`enemy.${typeId}`, typeId),
    perkLabel: (perk) => text(`perk.${perk.id}.label`, perk.id),
    perkDescription: (perk) => text(`perk.${perk.id}.desc`, perk.id),
    upgradeLabel: (upgrade) => text(`upgrade.${upgrade.id}.label`, upgrade.id),
    upgradeDescription: (upgrade) => text(`upgrade.${upgrade.id}.desc`, upgrade.id),
    achievementLabel: (achievement) => text(`achievement.${achievement.id}.label`, achievement.id),
    achievementDescription: (achievement) => text(`achievement.${achievement.id}.desc`, achievement.id),
    abilityName: (ability) => text(`ability.${ability.id}.name`, ability.id),
    abilityDescription: (ability) => text(`ability.${ability.id}.desc`, ability.id),
    abilityResource: (ability) => text(`ability.${ability.id}.resource`, ability.id),
    setName: (set) => text(`set.${set.id}.name`, set.id),
    setBonus: (set, pieces) => {
      const bonus = pieces === 2 ? set.bonus2pc : set.bonus4pc;
      return tr(`set.effect.${bonus.effect}`, { value: bonus.value });
    },
  };
}

/** Use as `$catalog.itemName(item)` in a component. A store, so a language change redraws them. */
export const catalog: Readable<CatalogText> = derived(language, ($language) => catalogTextFor($language));
