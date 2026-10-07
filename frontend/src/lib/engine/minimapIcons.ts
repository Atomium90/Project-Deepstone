import type { Direction, RoomTypeName } from "./protocol";

/** The stroke paths of one 24x24 icon, drawn with a 2px round stroke and no fill. Keeping the
 * glyphs as data in this one file, rather than as markup or image files, makes swapping the icon
 * set a change to this file alone, and needs no network. The glyphs are Tabler Icons (MIT), see
 * CREDITS.md. */
export type IconPaths = readonly string[];

const BOX: IconPaths = [
  "M12 3l8 4.5l0 9l-8 4.5l-8 -4.5l0 -9l8 -4.5",
  "M12 12l8 -4.5",
  "M12 12l0 9",
  "M12 12l-8 -4.5",
];

/** The icon of each room type on the map. */
export const ROOM_ICONS: Record<RoomTypeName, IconPaths> = {
  combat: [
    "M21 3v5l-11 9l-4 4l-3 -3l4 -4l9 -11z",
    "M5 13l6 6",
    "M14.32 17.32l3.68 3.68l3 -3l-3.365 -3.365",
    "M10 5.5l-2 -2.5h-5v5l3 2.5",
  ],
  loot: BOX,
  rest: [
    "M4 21l16 -4",
    "M20 21l-16 -4",
    "M12 15a4 4 0 0 0 4 -4c0 -3 -2 -3 -2 -8c-4 2 -6 5 -6 8a4 4 0 0 0 4 4z",
  ],
  fork: [
    "M21 17h-5.397a5 5 0 0 1 -4.096 -2.133l-.514 -.734a5 5 0 0 0 -4.096 -2.133h-3.897",
    "M21 7h-5.395a5 5 0 0 0 -4.098 2.135l-.51 .73a5 5 0 0 1 -4.097 2.135h-3.9",
    "M18 10l3 -3l-3 -3",
    "M18 20l3 -3l-3 -3",
  ],
  miniboss: [
    "M12 4c4.418 0 8 3.358 8 7.5c0 1.901 -.755 3.637 -2 4.96l0 2.54a1 1 0 0 1 -1 1h-10a1 1 0 0 1 -1 -1v-2.54c-1.245 -1.322 -2 -3.058 -2 -4.96c0 -4.142 3.582 -7.5 8 -7.5z",
    "M10 17v3",
    "M14 17v3",
    "M9 11m-1 0a1 1 0 1 0 2 0a1 1 0 1 0 -2 0",
    "M15 11m-1 0a1 1 0 1 0 2 0a1 1 0 1 0 -2 0",
  ],
  boss: ["M12 6l4 6l5 -4l-2 10h-14l-2 -10l5 4z"],
  sanctuary: [
    "M16 18a2 2 0 0 1 2 2a2 2 0 0 1 2 -2a2 2 0 0 1 -2 -2a2 2 0 0 1 -2 2zm0 -12a2 2 0 0 1 2 2a2 2 0 0 1 2 -2a2 2 0 0 1 -2 -2a2 2 0 0 1 -2 2zm-7 12a6 6 0 0 1 6 -6a6 6 0 0 1 -6 -6a6 6 0 0 1 -6 6a6 6 0 0 1 6 6z",
  ],
  vault: BOX,
};

/** The icon of a room whose type the player does not know yet. */
export const UNKNOWN_ICON: IconPaths = [
  "M8 8a3.5 3 0 0 1 3.5 -3h1a3.5 3 0 0 1 3.5 3a3 3 0 0 1 -2 3a3 4 0 0 0 -2 4",
  "M12 19l0 .01",
];

/** The marker over the room the player is in. */
export const PIN_ICON: IconPaths = [
  "M9 11a3 3 0 1 0 6 0a3 3 0 0 0 -6 0",
  "M17.657 16.657l-4.243 4.243a2 2 0 0 1 -2.827 0l-4.244 -4.243a8 8 0 1 1 11.314 0z",
];

/** The arrow naming the wall a fork's door sits on. */
export const ARROW_ICONS: Record<Direction, IconPaths> = {
  UP: ["M12 5l0 14", "M18 11l-6 -6", "M6 11l6 -6"],
  DOWN: ["M12 5l0 14", "M18 13l-6 6", "M6 13l6 6"],
  LEFT: ["M5 12l14 0", "M5 12l6 6", "M5 12l6 -6"],
  RIGHT: ["M5 12l14 0", "M13 18l6 -6", "M13 6l6 6"],
};
