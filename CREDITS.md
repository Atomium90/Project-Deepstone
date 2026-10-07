# Credits

Third-party art and audio assets used by Deepstone, and their licenses.

## Requires attribution

- **Kyrise's 16x16 RPG Icon Pack** by Kyrise (https://kyrise.itch.io/kyrises-free-16x16-rpg-icon-pack)
  Used for: class icons (sword/bow/staff), currency icon, item icons (accessories, several weapons).
  License: [CC BY 4.0](https://creativecommons.org/licenses/by/4.0/) - attribution required.

## CC0 (no attribution required, credited here anyway)

- **16x16 Dungeon Tileset** by 0x72 (https://0x72.itch.io/16x16-dungeon-tileset)
  Used for: dungeon tiles, doors, chests, NPCs, decorative sprites.
- **DungeonTileset II** by 0x72 (https://0x72.itch.io/dungeontileset-ii)
  Used for: hand-authored Tiled rooms' dungeon-theme tiles (see `frontend/scripts/
  generate-tiled-tileset-atlas.mjs`), master sheet imported into Tiled for precise autotiling.
  Modified: the block of vertical wall sprites on the master sheet (x 0-96, y 128-192) was moved
  up by 8 px so every wall piece sits on the same 16 px grid phase and a raw 16x16 cut lines up
  (`frontend/public/sprites/tiles/0x72_DungeonTilesetII_v1.7_aligned.png`). No pixel was added or
  removed. The original, unmodified sheet is kept next to it and is still used for the chests and
  the door leaf.
- **16x16 Dark Dungeon Tileset** by Kosinaz (https://kosinaz.itch.io/16x16-dark-dungeon-tileset)
  Based on 0x72's DungeonTileset II. Used for: hand-authored Tiled rooms' darkDungeon theme.
- **Surplus sheet** (`frontend/public/sprites/tiles/dungeonSurplus.png`), reworked extras derived
  from the two tilesets above so the dungeon theme can use darkDungeon-style walls. It holds the
  four DungeonTileset II wall banners cut out on a transparent background (every pixel that is not
  wall palette), the lava and water wall fountains of the Dark Dungeon Tileset adapted to the
  dungeon theme (the water recoloured with DungeonTileset II's water colors, the floor built into
  the base tile replaced by rows from DungeonTileset II's fountain base), and four half floors
  (top, bottom, left, right) cut from DungeonTileset II's plain floor tile.
- **Pixel Crawler** pack
  Used for: player and enemy character sprites.
- **UI Pack**, **UI Pack (RPG Expansion)**, **Game Icons**, **Game Icons (Expansion)** by Kenney (https://kenney.nl)
  Used for: buttons, bars, panels, checkmarks, settings/lock icons.
- **High Quality 16-bit RPG Music** by HydroGene (https://hydrogene.itch.io/high-quality-16-bit-music)
  Used for: hub, combat, and exploration background music.

## MIT (copyright notice kept here)

- **Tabler Icons** by Paweł Kuna (https://tabler.io/icons)
  Copyright (c) 2020-2023 Paweł Kuna, [MIT License](https://github.com/tabler/tabler-icons/blob/master/LICENSE).
  Used for: the room type, marker and arrow glyphs of the dungeon map
  (`frontend/src/lib/engine/minimapIcons.ts`), as placeholders until a game-style icon set replaces them.

## AI-generated (no third-party pack/license)

- **Sanctuary halo** (`lightHalo_anim`, gold variant) - AI-generated, 4-frame animation.
  Used for: the Sanctuary entity's exploration-view visual (the dungeon's true final room).

## Credited, kept out of the public repo

- **Fantasy Boss Battle Music Pack Vol. 2** by AlkaKrab (https://alkakrab.itch.io/fantasy-boss-battle-music-pack-vol-2) - boss music.
- **4SS Platform** by MoDsama (https://modsama.itch.io/4ssplatform) - warrior/archer/mage sprites.
- **RPG Weapons Pack**, **RPG Armour Pack**, **RPG Mage Pack** by CaseIRL (https://caseirl.itch.io/)
  Paid Edition - item icons (weapons, armor, some accessories). Attribution appreciated, not required.
