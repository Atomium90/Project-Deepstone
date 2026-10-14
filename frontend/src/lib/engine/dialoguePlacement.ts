import { TILE_SIZE } from "./constants";
import type { PendingSceneView, RoomView } from "./protocol";

/** A rectangle in pixels. */
export interface Box {
    left: number;
    top: number;
    width: number;
    height: number;
}

export interface Size {
    width: number;
    height: number;
}

export interface Tile {
    x: number;
    y: number;
}

/** How far the bubble keeps from the edge of the area it lives in. */
const MARGIN = 8;

/** How far the bubble keeps from what it explains, in pixels: close enough to read as the words of
 * whoever speaks, and not so close as to touch them. Set by eye. */
export const BUBBLE_GAP = 20;

/** The player talks to whoever stands right next to them (the interact range is one tile, never
 * diagonal), so the speaker is never farther than this. */
const SPEAKER_DISTANCE = 1;

const distance = (a: Tile, b: Tile): number => Math.abs(a.x - b.x) + Math.abs(a.y - b.y);

/**
 * The tile of whoever the conversation is with, or null when the room has nobody to show for it (the
 * NPC who takes the player's place is not in the room, and a Devoted that has fallen leaves only the
 * Shrine its tile became).
 *
 * An NPC is the entity that carries its id, since the Rest room's NPC takes the id of whoever the
 * story seated there. A Devoted is whoever the player stands next to: the enemy they face before the
 * fight, and the Shrine that rises where it stood once it has fallen.
 */
export function speakerTile(room: RoomView, scene: PendingSceneView): Tile | null {
    if (scene.npc.startsWith("boss.")) {
        const fallen = scene.sceneId?.endsWith(".post") ?? false;
        const player = { x: room.playerX, y: room.playerY };
        const near = room.entities
            .filter((entity) => entity.kind === (fallen ? "shrine" : "enemy") && distance(entity, player) <= SPEAKER_DISTANCE)
            .sort((a, b) => distance(a, player) - distance(b, player));
        return near.length > 0 ? { x: near[0].x, y: near[0].y } : null;
    }
    const npc = room.entities.find((entity) => entity.kind === "npc" && entity.id === scene.npc);
    return npc ? { x: npc.x, y: npc.y } : null;
}

/**
 * Where the conversation takes place on the canvas: the box around the player and the speaker, in the
 * pixels of the canvas, whose room is drawn centered (see Renderer.draw), or around the player alone
 * when the speaker is not in the room.
 */
export function conversationBox(room: RoomView, scene: PendingSceneView, canvas: Size): Box {
    const tiles: Tile[] = [{ x: room.playerX, y: room.playerY }];
    const speaker = speakerTile(room, scene);
    if (speaker !== null) tiles.push(speaker);

    const offsetX = Math.max(0, (canvas.width - room.width * TILE_SIZE) / 2);
    const offsetY = Math.max(0, (canvas.height - room.height * TILE_SIZE) / 2);
    const minX = Math.min(...tiles.map((t) => t.x));
    const maxX = Math.max(...tiles.map((t) => t.x));
    const minY = Math.min(...tiles.map((t) => t.y));
    const maxY = Math.max(...tiles.map((t) => t.y));
    return {
        left: offsetX + minX * TILE_SIZE,
        top: offsetY + minY * TILE_SIZE,
        width: (maxX - minX + 1) * TILE_SIZE,
        height: (maxY - minY + 1) * TILE_SIZE,
    };
}

/**
 * Where the bubble goes: close to the conversation in height, without covering it, and always in the
 * same place across. It is just under the conversation, or just above it when there is no room left
 * under it, and never out of the area. An NPC stands against a wall and a Devoted can be faced from
 * any side, so under the lowest of the two tiles is clear of both. Across it is centered in the area,
 * which is also the center of the room, whatever column the conversation is in: following the player
 * from side to side made the bubble jump from one end to the other.
 */
export function placeBubble(anchor: Box, bubble: Size, area: Size): { left: number; top: number } {
    const left = Math.max(MARGIN, (area.width - bubble.width) / 2);

    const under = anchor.top + anchor.height + BUBBLE_GAP;
    const above = anchor.top - BUBBLE_GAP - bubble.height;
    const fitsUnder = under + bubble.height + MARGIN <= area.height;
    const top = fitsUnder ? under : Math.max(MARGIN, above);
    return { left, top };
}
