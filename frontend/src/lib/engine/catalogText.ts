import { derived, type Readable } from "svelte/store";
import { dictionaries, language, lookup, translate } from "./i18n";
import type { AbilityView, AchievementView, ItemView, PerkView, SetView, UpgradeView } from "./protocol";

/**
 * The names and descriptions of the game's catalogs (items, enemies, perks, upgrades, achievements,
 * abilities, sets). The server sends an id and, for now, its own English text; the client owns the
 * words, so each text is looked up by id (`item.<typeId>.name`, `perk.<id>.label`...) and the
 * server's text is only the fallback while a key is missing. Once the server stops sending text
 * (see the log work), the fallbacks go.
 */
export interface CatalogText {
  itemName(item: Pick<ItemView, "typeId" | "name">): string;
  enemyName(typeId: string, fallback: string): string;
  perkLabel(perk: Pick<PerkView, "id" | "label">): string;
  perkDescription(perk: Pick<PerkView, "id" | "description">): string;
  upgradeLabel(upgrade: Pick<UpgradeView, "id" | "label">): string;
  upgradeDescription(upgrade: Pick<UpgradeView, "id" | "description">): string;
  achievementLabel(achievement: Pick<AchievementView, "id" | "label">): string;
  achievementDescription(achievement: Pick<AchievementView, "id" | "description">): string;
  abilityName(ability: Pick<AbilityView, "id" | "name">): string;
  abilityDescription(ability: Pick<AbilityView, "id" | "description">): string;
  abilityResource(ability: Pick<AbilityView, "id" | "resourceName">): string;
  setName(set: Pick<SetView, "id" | "name">): string;
  setBonus(set: Pick<SetView, "id" | "bonus2pcLabel" | "bonus4pcLabel">, pieces: 2 | 4): string;
}

/** Builds the catalog texts for one language. Exported for tests. */
export function catalogTextFor(language: string): CatalogText {
  const text = (key: string, fallback: string): string =>
    lookup(dictionaries, language, key) === null ? fallback : translate(dictionaries, language, key);
  return {
    itemName: (item) => text(`item.${item.typeId}.name`, item.name),
    enemyName: (typeId, fallback) => text(`enemy.${typeId}`, fallback),
    perkLabel: (perk) => text(`perk.${perk.id}.label`, perk.label),
    perkDescription: (perk) => text(`perk.${perk.id}.desc`, perk.description),
    upgradeLabel: (upgrade) => text(`upgrade.${upgrade.id}.label`, upgrade.label),
    upgradeDescription: (upgrade) => text(`upgrade.${upgrade.id}.desc`, upgrade.description),
    achievementLabel: (achievement) => text(`achievement.${achievement.id}.label`, achievement.label),
    achievementDescription: (achievement) => text(`achievement.${achievement.id}.desc`, achievement.description),
    abilityName: (ability) => text(`ability.${ability.id}.name`, ability.name),
    abilityDescription: (ability) => text(`ability.${ability.id}.desc`, ability.description),
    abilityResource: (ability) => text(`ability.${ability.id}.resource`, ability.resourceName),
    setName: (set) => text(`set.${set.id}.name`, set.name),
    setBonus: (set, pieces) =>
      text(`set.${set.id}.bonus${pieces}`, pieces === 2 ? set.bonus2pcLabel : set.bonus4pcLabel),
  };
}

/** Use as `$catalog.itemName(item)` in a component. A store, so a language change redraws them. */
export const catalog: Readable<CatalogText> = derived(language, ($language) => catalogTextFor($language));
