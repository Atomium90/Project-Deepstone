import { describe, expect, it } from "vitest";
import { DARK_DUNGEON_DOOR_NATIVE_Y_OFFSET, doorNativeYOffset } from "./doorArt";

/** A 4x3 room whose wall sprites are `grid`, "." standing for a cell with no wall sprite. */
function room(theme: string, grid: string[][]) {
  return {
    theme,
    wallSprite: grid.map((row) => row.map((key) => (key === "." ? null : key))),
  };
}

const door = { x: 1, y: 0 };

describe("doorNativeYOffset", () => {
  it("shifts a door set into darkDungeon walls", () => {
    const r = room("darkDungeon", [[".", "darkDungeon_12", "darkDungeon_13", "."]]);
    expect(doorNativeYOffset(r, door)).toBe(DARK_DUNGEON_DOOR_NATIVE_Y_OFFSET);
  });

  it("does not shift a door set into plain dungeon walls", () => {
    const r = room("dungeon", [[".", "dungeon_3", "dungeon_3", "."]]);
    expect(doorNativeYOffset(r, door)).toBe(0);
  });

  it("follows the wall art, not the theme, in a dungeon room built from darkDungeon walls", () => {
    const r = room("dungeon", [[".", "darkDungeon_12", "darkDungeon_13", "."]]);
    expect(doorNativeYOffset(r, door)).toBe(DARK_DUNGEON_DOOR_NATIVE_Y_OFFSET);
  });

  it("follows the wall art, not the theme, in a darkDungeon room with a plain dungeon outer frame", () => {
    const r = room("darkDungeon", [[".", "dungeon_3", "dungeon_3", "."]]);
    expect(doorNativeYOffset(r, door)).toBe(0);
  });

  it("reads the next cell of the door's box when the anchor has no wall sprite", () => {
    const r = room("dungeon", [[".", ".", "darkDungeon_13", "."]]);
    expect(doorNativeYOffset(r, door)).toBe(DARK_DUNGEON_DOOR_NATIVE_Y_OFFSET);
  });

  it("reads the row below the anchor when the whole top row of the box is empty", () => {
    const r = room("dungeon", [
      [".", ".", ".", "."],
      [".", "darkDungeon_21", ".", "."],
    ]);
    expect(doorNativeYOffset(r, door)).toBe(DARK_DUNGEON_DOOR_NATIVE_Y_OFFSET);
  });

  it("takes the anchor's wall when the box mixes two arts", () => {
    const r = room("dungeon", [[".", "dungeon_3", "darkDungeon_13", "."]]);
    expect(doorNativeYOffset(r, door)).toBe(0);
  });

  it("falls back to the theme when no wall sprite sits under the door", () => {
    const empty = [[".", ".", ".", "."], [".", ".", ".", "."]];
    expect(doorNativeYOffset(room("darkDungeon", empty), door)).toBe(DARK_DUNGEON_DOOR_NATIVE_Y_OFFSET);
    expect(doorNativeYOffset(room("dungeon", empty), door)).toBe(0);
  });

  it("treats any other tileset's wall as plain dungeon art", () => {
    const r = room("darkDungeon", [[".", "wall_stone", ".", "."]]);
    expect(doorNativeYOffset(r, door)).toBe(0);
  });

  it("copes with a door whose box reaches outside the grid", () => {
    const r = room("dungeon", [[".", "."]]);
    expect(doorNativeYOffset(r, { x: 5, y: 5 })).toBe(0);
  });
});
