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

/** Atlas sprite per entity kind, for the kinds that don't vary by instance. "enemy" and "npc" use
 * EntityView.spriteId, which the server resolves from its catalogs (enemies.json, npcs.json), and
 * "chest" varies by state (see chestSpriteKey). "door"/"locked_door" aren't here: they get their own
 * bespoke 2-tile draw path, see Renderer.drawDoor. "npc" is the sprite of an NPC the server sent no
 * sprite for. */
const FIXED_ENTITY_SPRITES: Record<string, string> = {
  npc: "npc_sage",
  sanctuary: "sanctuary_halo_gold",
};

/** The atlas sprite an entity is drawn with: an enemy's or NPC's server-resolved sprite, a chest's
 * sprite for its current state, or the one fixed sprite of any other kind (undefined for the kinds
 * with their own draw path, which the caller handles before ever using it). */
export function entitySpriteKey(entity: EntityView): string | undefined {
  if (entity.kind === "enemy") return entity.spriteId ?? undefined;
  if (entity.kind === "npc") return entity.spriteId ?? FIXED_ENTITY_SPRITES.npc;
  if (entity.kind === "chest") return chestSpriteKey(entity.state);
  return FIXED_ENTITY_SPRITES[entity.kind];
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
