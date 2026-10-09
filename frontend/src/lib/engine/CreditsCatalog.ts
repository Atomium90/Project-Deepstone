/** Which list of the Credits tab an entry belongs to. */
export type CreditGroup = "art" | "audio";

/** One pack the game uses. Names, authors and licenses are proper nouns, so they live here; what
 * the pack is used for, and any note or custom terms, are texts in the language file under
 * `credits.<id>.use`, `credits.<id>.note` and `credits.<id>.terms`. CREDITS.md lists the same packs
 * and a test keeps the two in step. */
export interface CreditEntry {
    /** Stable id, the middle part of the text keys. */
    id: string;
    /** The pack's name, as CREDITS.md writes it. */
    name: string;
    author: string;
    /** The pack's page, when it has one. */
    url?: string;
    /** A standard license by name (CC0, CC BY 4.0, MIT). Null when the pack has terms of its own,
     * which then come from the `terms` text. */
    license: string | null;
    /** Where the standard license is written out. */
    licenseUrl?: string;
    group: CreditGroup;
    /** Set when the pack was modified, with the `note` text saying how. */
    hasNote?: boolean;
}

const CC0 = { license: "CC0", licenseUrl: "https://creativecommons.org/publicdomain/zero/1.0/" } as const;
const CC_BY = { license: "CC BY 4.0", licenseUrl: "https://creativecommons.org/licenses/by/4.0/" } as const;
const OWN_TERMS = { license: null } as const;

export const CREDITS: CreditEntry[] = [
    {
        id: "dungeon-tileset",
        name: "16x16 Dungeon Tileset",
        author: "0x72",
        url: "https://0x72.itch.io/16x16-dungeon-tileset",
        ...CC0,
        group: "art",
    },
    {
        id: "dungeon-tileset-ii",
        name: "DungeonTileset II",
        author: "0x72",
        url: "https://0x72.itch.io/dungeontileset-ii",
        ...CC0,
        group: "art",
        hasNote: true,
    },
    {
        id: "dark-dungeon",
        name: "16x16 Dark Dungeon Tileset",
        author: "Kosinaz",
        url: "https://kosinaz.itch.io/16x16-dark-dungeon-tileset",
        ...CC0,
        group: "art",
    },
    {
        id: "pixel-crawler",
        name: "Pixel Crawler",
        author: "Anokolisa",
        url: "https://anokolisa.itch.io/free-pixel-art-asset-pack-topdown-tileset-rpg-16x16-sprites",
        ...OWN_TERMS,
        group: "art",
    },
    {
        id: "kyrise-icons",
        name: "Kyrise's 16x16 RPG Icon Pack",
        author: "Kyrise",
        url: "https://kyrise.itch.io/kyrises-free-16x16-rpg-icon-pack",
        ...CC_BY,
        group: "art",
        hasNote: true,
    },
    {
        id: "modsama-4ss",
        name: "4SS Platform",
        author: "MoDsama",
        url: "https://modsama.itch.io/4ssplatform",
        ...OWN_TERMS,
        group: "art",
        hasNote: true,
    },
    {
        id: "case-packs",
        name: "RPG Weapons, Armour and Mage Packs",
        author: "CaseIRL",
        url: "https://caseirl.itch.io/",
        ...OWN_TERMS,
        group: "art",
    },
    {
        id: "kenney-ui-pack",
        name: "UI Pack",
        author: "Kenney",
        url: "https://kenney.nl",
        ...CC0,
        group: "art",
    },
    {
        id: "kenney-ui-pack-rpg",
        name: "UI Pack (RPG Expansion)",
        author: "Kenney",
        url: "https://kenney.nl",
        ...CC0,
        group: "art",
    },
    {
        id: "kenney-game-icons",
        name: "Game Icons",
        author: "Kenney",
        url: "https://kenney.nl",
        ...CC0,
        group: "art",
    },
    {
        id: "tabler-icons",
        name: "Tabler Icons",
        author: "Paweł Kuna",
        url: "https://tabler.io/icons",
        license: "MIT",
        licenseUrl: "https://github.com/tabler/tabler-icons/blob/master/LICENSE",
        group: "art",
    },
    {
        id: "sanctuary-halo",
        name: "Sanctuary halo",
        author: "Deepstone",
        ...OWN_TERMS,
        group: "art",
    },
    {
        id: "hydrogene-music",
        name: "High Quality 16-bit RPG Music",
        author: "HydroGene",
        url: "https://hydrogene.itch.io/high-quality-16-bit-music",
        ...CC0,
        group: "audio",
    },
    {
        id: "alkakrab-boss-music",
        name: "Fantasy Boss Battle Music Pack Vol. 2",
        author: "AlkaKrab",
        url: "https://alkakrab.itch.io/fantasy-boss-battle-music-pack-vol-2",
        ...OWN_TERMS,
        group: "audio",
    },
    {
        id: "kenney-rpg-audio",
        name: "RPG Audio",
        author: "Kenney",
        url: "https://kenney.nl",
        ...CC0,
        group: "audio",
    },
];

/** The entries of one list, in the order they are written above. */
export function creditsIn(group: CreditGroup): CreditEntry[] {
    return CREDITS.filter((entry) => entry.group === group);
}
