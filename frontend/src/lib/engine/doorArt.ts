import type { RoomView } from "./protocol";

/** How far, in native pixels, to shift the door panel from the box's natural bottom edge -
 * darkDungeon's own wall/floor art sits slightly off the plain dungeon theme's grid, where the
 * door sits on the box's bottom edge with no shift (see doorNativeYOffset). The arch moves with
 * the panel as one rigid unit, see Renderer.drawDoor. Defined in native pixels and scaled by
 * TILE_SIZE/16 at the point of use, like every other atlas source rect in this codebase - every
 * source pack so far is authored on a 16px native grid, TILE_SIZE is that same tile at 4x (64px
 * on screen). */
export const DARK_DUNGEON_DOOR_NATIVE_Y_OFFSET = 6;

/** Sprite keys of the darkDungeon tileset's tiles (see scripts/generate-tiled-tileset-atlas.mjs:
 * "{tilesetName}_{localId}"). */
const DARK_DUNGEON_WALL_PREFIX = "darkDungeon_";

/** The door's 2x2 draw box, anchor first: the wall art is read from the first of these cells that
 * has a wall sprite. */
const DOOR_BOX_CELLS: ReadonlyArray<readonly [number, number]> = [
  [0, 0],
  [1, 0],
  [0, 1],
  [1, 1],
];

/** The native-pixel shift a door's two-piece draw takes, decided by the wall art the door is drawn
 * into rather than by the room's theme: a room can mix the plain dungeon walls (a simple outer
 * frame) with darkDungeon walls (side exits, interior walls joined to the frame), and the door has
 * to sit right against whichever one surrounds it. A door with no wall sprite under it (the room
 * left that cell on its theme's default wall) falls back to the theme. */
export function doorNativeYOffset(
  room: Pick<RoomView, "theme" | "wallSprite">,
  door: { x: number; y: number },
): number {
  const key = wallSpriteUnder(room, door);
  const darkDungeonArt =
    key === null
      ? room.theme === "darkDungeon"
      : key.startsWith(DARK_DUNGEON_WALL_PREFIX);
  return darkDungeonArt ? DARK_DUNGEON_DOOR_NATIVE_Y_OFFSET : 0;
}

function wallSpriteUnder(
  room: Pick<RoomView, "wallSprite">,
  door: { x: number; y: number },
): string | null {
  for (const [dx, dy] of DOOR_BOX_CELLS) {
    const key = room.wallSprite?.[door.y + dy]?.[door.x + dx];
    if (key) return key;
  }
  return null;
}
