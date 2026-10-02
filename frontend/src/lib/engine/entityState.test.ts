import { describe, expect, it } from "vitest";
import { chestSpriteKey, isEntityInteractable } from "./entityState";
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
