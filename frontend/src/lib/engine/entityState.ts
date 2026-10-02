import type { EntityView } from "./protocol";

/** Atlas sprite for a chest in the given state. A chest with no reported state reads as closed. */
export function chestSpriteKey(state: EntityView["state"]): string {
  switch (state) {
    case "open_full":
      return "chest_open_full";
    case "open_empty":
      return "chest_open_empty";
    case "sprung":
      return "chest_mimic";
    default:
      return "chest_closed";
  }
}

/** Whether pressing E on this entity can do anything, which decides both the keycap badge and what
 * the E key targets. The Sanctuary has no E-key path at all: walking into it is the only way it
 * ever triggers (see the server's Move handling). A chest that is open and empty, or sprung, has
 * nothing left to give; a closed or still-full chest does. */
export function isEntityInteractable(entity: EntityView): boolean {
  if (entity.kind === "sanctuary") return false;
  if (entity.kind === "chest") {
    return entity.state !== "open_empty" && entity.state !== "sprung";
  }
  return true;
}
