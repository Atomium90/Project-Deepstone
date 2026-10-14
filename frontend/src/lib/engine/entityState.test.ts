import { describe, expect, it } from "vitest";
import { chestSpriteKey, entitySpriteKey, isEntityInteractable } from "./entityState";
import type { EntityView } from "./protocol";

function entity(kind: EntityView["kind"], state?: EntityView["state"]): EntityView {
  return { id: "e1", kind, x: 1, y: 1, label: "x", state };
}

describe("chestSpriteKey", () => {
  it("maps each chest state to its own sprite", () => {
    expect(chestSpriteKey("closed")).toBe("chest_closed");
    expect(chestSpriteKey("open_full")).toBe("chest_open_full");
    expect(chestSpriteKey("open_empty")).toBe("chest_open_empty");
    expect(chestSpriteKey("sprung")).toBe("chest_mimic");
  });

  it("reads a chest with no reported state as closed", () => {
    expect(chestSpriteKey(undefined)).toBe("chest_closed");
  });
});

describe("entitySpriteKey", () => {
  it("draws an enemy with the sprite the server resolved for it, and with none when there is none", () => {
    expect(entitySpriteKey({ ...entity("enemy"), spriteId: "mob_orc_idle" })).toBe("mob_orc_idle");
    expect(entitySpriteKey(entity("enemy"))).toBeUndefined();
  });

  it("draws an NPC with the sprite the server resolved for it", () => {
    expect(entitySpriteKey({ ...entity("npc"), spriteId: "npc_bishop_idle" })).toBe("npc_bishop_idle");
  });

  it("falls back to the plain NPC sprite when the server sent none, null included", () => {
    expect(entitySpriteKey(entity("npc"))).toBe("npc_sage");
    expect(entitySpriteKey({ ...entity("npc"), spriteId: null as unknown as undefined })).toBe("npc_sage");
  });

  it("draws a chest with the sprite of its state", () => {
    expect(entitySpriteKey(entity("chest", "sprung"))).toBe("chest_mimic");
  });

  it("gives the Sanctuary its halo, and the kinds with a draw path of their own no sprite", () => {
    expect(entitySpriteKey(entity("sanctuary"))).toBe("sanctuary_halo_gold");
    expect(entitySpriteKey(entity("door"))).toBeUndefined();
    expect(entitySpriteKey(entity("shrine"))).toBeUndefined();
  });
});

describe("isEntityInteractable", () => {
  it("lets the player interact with a closed or still-full chest", () => {
    expect(isEntityInteractable(entity("chest", "closed"))).toBe(true);
    expect(isEntityInteractable(entity("chest", "open_full"))).toBe(true);
  });

  it("does not offer a chest that is open and empty, or sprung", () => {
    expect(isEntityInteractable(entity("chest", "open_empty"))).toBe(false);
    expect(isEntityInteractable(entity("chest", "sprung"))).toBe(false);
  });

  it("never offers the Sanctuary, which only triggers by walking into it", () => {
    expect(isEntityInteractable(entity("sanctuary"))).toBe(false);
  });

  it("offers every other kind of entity", () => {
    for (const kind of ["enemy", "door", "locked_door", "npc", "shrine"] as const) {
      expect(isEntityInteractable(entity(kind))).toBe(true);
    }
  });
});
