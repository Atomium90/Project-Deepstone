import { describe, expect, it } from "vitest";
import { interactCheckTile } from "./doorInteract";
import type { EntityView } from "./protocol";

function entity(kind: EntityView["kind"], direction?: EntityView["direction"]): EntityView {
  return { id: "e1", kind, x: 6, y: 4, label: "x", direction };
}

describe("interactCheckTile", () => {
  it("checks a UP door from one row below its anchor, which sits in the wall band", () => {
    expect(interactCheckTile(entity("door", "UP"))).toEqual({ x: 6, y: 5 });
  });

  it("checks a DOWN door from its own tile", () => {
    expect(interactCheckTile(entity("door", "DOWN"))).toEqual({ x: 6, y: 4 });
  });

  it("checks a LEFT door from its own tile, so the spot right in front of it works", () => {
    expect(interactCheckTile(entity("door", "LEFT"))).toEqual({ x: 6, y: 4 });
  });

  it("checks a RIGHT door from its own tile", () => {
    expect(interactCheckTile(entity("door", "RIGHT"))).toEqual({ x: 6, y: 4 });
  });

  it("treats a locked door like a door", () => {
    expect(interactCheckTile(entity("locked_door", "UP"))).toEqual({ x: 6, y: 5 });
    expect(interactCheckTile(entity("locked_door", "LEFT"))).toEqual({ x: 6, y: 4 });
  });

  it("checks a door with no direction from its own tile", () => {
    expect(interactCheckTile(entity("door"))).toEqual({ x: 6, y: 4 });
  });

  it("checks every other entity from its own tile, whatever its direction field says", () => {
    for (const kind of ["enemy", "chest", "npc", "shrine"] as const) {
      expect(interactCheckTile(entity(kind, "UP"))).toEqual({ x: 6, y: 4 });
    }
  });
});
