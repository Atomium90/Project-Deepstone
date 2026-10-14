import { describe, test, expect } from "vitest";
import { BUBBLE_GAP, conversationBox, placeBubble, speakerTile } from "./dialoguePlacement";
import { TILE_SIZE } from "./constants";
import type { EntityView, PendingSceneView, RoomView } from "./protocol";

function entity(overrides: Partial<EntityView>): EntityView {
    return { id: "e", kind: "enemy", x: 0, y: 0, label: "e", ...overrides };
}

function room(overrides: Partial<RoomView> = {}): RoomView {
    return {
        roomId: "r",
        width: 10,
        height: 8,
        tiles: [],
        theme: "dungeon",
        floorSprite: [],
        wallSprite: [],
        decoration: [],
        entities: [],
        playerX: 5,
        playerY: 4,
        ...overrides,
    } as RoomView;
}

const npcScene: PendingSceneView = { npc: "npc.1", kind: "scene", sceneId: "npc.1.s1" };
const bossBefore: PendingSceneView = { npc: "boss.2", kind: "devoted", sceneId: "boss.2.pre1" };
const bossFallen: PendingSceneView = { npc: "boss.2", kind: "devoted", sceneId: "boss.2.post" };

describe("speakerTile", () => {
    test("an NPC is the entity that carries its id", () => {
        const r = room({ entities: [entity({ id: "npc.2", kind: "npc", x: 1, y: 1 }), entity({ id: "npc.1", kind: "npc", x: 4, y: 2 })] });
        expect(speakerTile(r, npcScene)).toEqual({ x: 4, y: 2 });
    });

    test("an NPC that is not in the room has no tile", () => {
        expect(speakerTile(room(), npcScene)).toBeNull();
    });

    test("a Devoted before the fight is the enemy next to the player, whichever side they stand on", () => {
        for (const [x, y] of [[5, 3], [5, 5], [4, 4], [6, 4]]) {
            const r = room({ entities: [entity({ id: "boss_1", kind: "enemy", x, y })] });
            expect(speakerTile(r, bossBefore)).toEqual({ x, y });
        }
    });

    test("an enemy that is not next to the player is not the one who speaks", () => {
        const r = room({ entities: [entity({ kind: "enemy", x: 8, y: 1 })] });
        expect(speakerTile(r, bossBefore)).toBeNull();
    });

    test("an enemy that is not next to the player is left out when another one is", () => {
        const r = room({ entities: [entity({ id: "far", kind: "enemy", x: 5, y: 3 }), entity({ id: "near", kind: "enemy", x: 5, y: 5 })] });
        const withNear = { ...r, playerY: 5, playerX: 6 };
        expect(speakerTile(withNear, bossBefore)).toEqual({ x: 5, y: 5 });
    });

    test("a Devoted that has fallen leaves the Shrine that rose where it stood", () => {
        const r = room({ entities: [entity({ kind: "enemy", x: 5, y: 3 }), entity({ id: "shrine_1", kind: "shrine", x: 5, y: 5 })] });
        expect(speakerTile(r, bossFallen)).toEqual({ x: 5, y: 5 });
    });

    test("a Devoted that has fallen leaves nobody to show when no Shrine rose next to the player", () => {
        expect(speakerTile(room(), bossFallen)).toBeNull();
    });
});

describe("conversationBox", () => {
    const big = { width: 1400, height: 1000 };

    test("covers the player's tile alone when the speaker is not in the room", () => {
        const box = conversationBox(room(), npcScene, big);
        expect(box.width).toBe(TILE_SIZE);
        expect(box.height).toBe(TILE_SIZE);
    });

    test("covers the player and the speaker, with the room centered in the canvas", () => {
        const r = room({ entities: [entity({ id: "npc.1", kind: "npc", x: 5, y: 3 })] });
        const offsetX = (big.width - r.width * TILE_SIZE) / 2;
        const offsetY = (big.height - r.height * TILE_SIZE) / 2;
        expect(conversationBox(r, npcScene, big)).toEqual({
            left: offsetX + 5 * TILE_SIZE,
            top: offsetY + 3 * TILE_SIZE,
            width: TILE_SIZE,
            height: 2 * TILE_SIZE,
        });
    });

    test("covers two tiles side by side too", () => {
        const r = room({ entities: [entity({ id: "npc.1", kind: "npc", x: 6, y: 4 })] });
        const box = conversationBox(r, npcScene, big);
        expect(box.width).toBe(2 * TILE_SIZE);
        expect(box.height).toBe(TILE_SIZE);
    });

    test("starts at the corner of the canvas when the room is larger than it", () => {
        const r = room({ playerX: 2, playerY: 3 });
        const box = conversationBox(r, npcScene, { width: 300, height: 200 });
        expect(box.left).toBe(2 * TILE_SIZE);
        expect(box.top).toBe(3 * TILE_SIZE);
    });
});

describe("placeBubble", () => {
    const area = { width: 1000, height: 800 };
    const bubble = { width: 400, height: 120 };

    test("goes just under the conversation, centered on it", () => {
        const anchor = { left: 500, top: 100, width: 64, height: 128 };
        const spot = placeBubble(anchor, bubble, area);
        expect(spot.left).toBe(500 + 32 - 200);
        expect(spot.top - (anchor.top + anchor.height)).toBe(BUBBLE_GAP);
    });

    test("never covers the conversation when it fits under it", () => {
        const anchor = { left: 300, top: 200, width: 128, height: 64 };
        const spot = placeBubble(anchor, bubble, area);
        expect(spot.top).toBeGreaterThanOrEqual(anchor.top + anchor.height);
    });

    test("goes above the conversation when there is no room left under it", () => {
        const anchor = { left: 400, top: 650, width: 64, height: 64 };
        const spot = placeBubble(anchor, bubble, area);
        expect(anchor.top - (spot.top + bubble.height)).toBe(BUBBLE_GAP);
    });

    test("stays inside the area on the left", () => {
        const anchor = { left: 0, top: 100, width: 64, height: 64 };
        expect(placeBubble(anchor, bubble, area).left).toBeGreaterThanOrEqual(0);
    });

    test("stays inside the area on the right", () => {
        const anchor = { left: 940, top: 100, width: 64, height: 64 };
        const spot = placeBubble(anchor, bubble, area);
        expect(spot.left + bubble.width).toBeLessThanOrEqual(area.width);
    });

    test("stays at the top of the area when it fits neither under nor above", () => {
        const anchor = { left: 400, top: 40, width: 64, height: 700 };
        const spot = placeBubble(anchor, bubble, area);
        expect(spot.top).toBeGreaterThanOrEqual(0);
    });
});
