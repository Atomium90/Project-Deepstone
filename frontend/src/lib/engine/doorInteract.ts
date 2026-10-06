import type { EntityView } from "./protocol";

/** The tile the interact-range check measures from for an entity (the player must be a cardinal
 * neighbor of it, never on it). Every entity is checked from its own tile except a UP door, which
 * is checked from one row further down:
 *   - A UP door's anchor sits in the wall band above the room, with at least one solid wall row
 *     between it and the first real floor row (one row in a 1-thick wall, two in a 2-thick one).
 *     entity.y + 1 reliably resolves to a tile whose interior-side neighbor is the first real floor
 *     row, whatever the thickness: isCardinalNeighbor checks the *player's* position against this
 *     tile, not whether the tile itself is floor.
 *   - A DOWN door's anchor is the wall row just below the room, so its own tile already has the
 *     floor row above it as its neighbor.
 *   - A LEFT or RIGHT door's anchor is the gap in the side wall: the tile just inside the room is
 *     its neighbor, which is where the player naturally stands in front of it. Shifting a LEFT
 *     door's check tile one column inward would put that natural spot on the check tile itself,
 *     and the interact prompt would only show one tile further back. */
export function interactCheckTile(
  entity: Pick<EntityView, "kind" | "x" | "y" | "direction">,
): { x: number; y: number } {
  const isDoor = entity.kind === "door" || entity.kind === "locked_door";
  return isDoor && entity.direction === "UP"
    ? { x: entity.x, y: entity.y + 1 }
    : { x: entity.x, y: entity.y };
}
