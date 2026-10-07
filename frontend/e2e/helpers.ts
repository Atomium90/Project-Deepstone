import { expect, type Page } from "@playwright/test";
import type { StateUpdate, EntityView, Direction } from "../src/lib/engine/protocol";

declare global {
    interface Window {
        __DEEPSTONE_STATE__?: StateUpdate | null;
        __DEEPSTONE_RENDERER__?: { nearestInteractable(): EntityView | null };
    }
}

export async function currentState(page: Page): Promise<StateUpdate | null> {
    return page.evaluate(() => window.__DEEPSTONE_STATE__ ?? null);
}

/** Waits until the state exposed on `window` (StateStore.ts's __DEEPSTONE_STATE__ mirror) differs
 * from `previous`, returning the new value. Reads the same store every component reacts to,
 * instead of independently observing WebSocket traffic at the network level - that was the first
 * approach tried here, and it proved unreliable: no guarantee of observing frames in the same
 * order or timing as the page's own reactivity, which made both movement and interact flaky. */
export async function waitForStateChange(page: Page, previous: StateUpdate | null, timeoutMs = 10_000): Promise<StateUpdate> {
    const previousJson = JSON.stringify(previous);
    await expect
        .poll(async () => JSON.stringify(await currentState(page)), { timeout: timeoutMs, intervals: [50] })
        .not.toBe(previousJson);
    return (await currentState(page))!;
}

export const DIRECTION_KEY: Record<Direction, string> = {
    UP: "ArrowUp",
    DOWN: "ArrowDown",
    LEFT: "ArrowLeft",
    RIGHT: "ArrowRight",
};

function chebyshev(x1: number, y1: number, x2: number, y2: number): number {
    return Math.max(Math.abs(x1 - x2), Math.abs(y1 - y2));
}

/** True only when (x2, y2) sits directly north/south/east/west of (x1, y1) - matches
 * Renderer.ts's own isCardinalNeighbor, since interact (and this helper's BFS goal below) only
 * succeeds on real cardinal adjacency, not any Chebyshev-distance-1 tile (diagonal included). */
function isCardinalNeighbor(x1: number, y1: number, x2: number, y2: number): boolean {
    const sameRow = y1 === y2;
    const sameCol = x1 === x2;
    if (sameRow === sameCol) return false; // same tile or diagonal
    return chebyshev(x1, y1, x2, y2) <= 1;
}

/** 4-directional BFS from the player's tile to a walkable tile directly cardinal-adjacent to
 * (targetX, targetY) - a door embedded in a wall isn't itself walkable, only adjacent to it is.
 * A tile occupied by an entity doesn't count as walkable either, matching Room.isWalkable
 * server-side - the target entity's own tile is naturally excluded from the path (only tiles
 * *adjacent* to it are ever BFS goals), so this never needs to special-case the target itself.
 * Returns an empty path when the player already stands next to the target, and `null` when no tile
 * next to it can be reached at all. */
export function pathTo(room: NonNullable<StateUpdate["room"]>, targetX: number, targetY: number): Direction[] | null {
    const tiles = room.tiles;
    const occupied = new Set(room.entities.map((e) => `${e.x},${e.y}`));
    const isWalkable = (x: number, y: number) =>
        y >= 0 &&
        y < tiles.length &&
        x >= 0 &&
        x < tiles[y].length &&
        tiles[y][x] === "floor" &&
        !occupied.has(`${x},${y}`);
    const start = { x: room.playerX, y: room.playerY };
    if (isCardinalNeighbor(start.x, start.y, targetX, targetY)) return [];

    const steps: { dir: Direction; dx: number; dy: number }[] = [
        { dir: "UP", dx: 0, dy: -1 },
        { dir: "DOWN", dx: 0, dy: 1 },
        { dir: "LEFT", dx: -1, dy: 0 },
        { dir: "RIGHT", dx: 1, dy: 0 },
    ];
    const visited = new Set<string>([`${start.x},${start.y}`]);
    const queue: { x: number; y: number; path: Direction[] }[] = [{ ...start, path: [] }];
    while (queue.length > 0) {
        const cur = queue.shift()!;
        if (isCardinalNeighbor(cur.x, cur.y, targetX, targetY)) return cur.path;
        for (const s of steps) {
            const nx = cur.x + s.dx;
            const ny = cur.y + s.dy;
            const key = `${nx},${ny}`;
            if (visited.has(key) || !isWalkable(nx, ny)) continue;
            visited.add(key);
            queue.push({ x: nx, y: ny, path: [...cur.path, s.dir] });
        }
    }
    return null;
}

/** The cardinal direction from (x1,y1) to an adjacent (x2,y2) - callers only ever pass points
 * already known to be cardinal-adjacent (e.g. the tile pathTo just walked next to). */
export function directionBetween(x1: number, y1: number, x2: number, y2: number): Direction {
    if (x2 > x1) return "RIGHT";
    if (x2 < x1) return "LEFT";
    return y2 > y1 ? "DOWN" : "UP";
}

/** The tile the client's interact-range check actually uses for an entity (mirrors doorInteract.ts's
 * interactCheckTile): the anchor of an UP door sits in the wall one tile outside the tile the player
 * has to be next to. Walking to a neighbor of the raw anchor instead can leave the player standing on
 * the check tile itself, which is never a neighbor of itself, so E does nothing. */
export function interactTile(entity: EntityView): { x: number; y: number } {
    const isDoor = entity.kind === "door" || entity.kind === "locked_door";
    return isDoor && entity.direction === "UP" ? { x: entity.x, y: entity.y + 1 } : { x: entity.x, y: entity.y };
}

/** The room's doors, farthest from where the player entered first. InteractionResolver.
 * findSpawnPoint places the player in front of the door they just came through, so that door is
 * always the nearest one to the entry point and the farthest door is the way forward, not back.
 * Measuring from the entry point rather than the player's current position matters once they have
 * walked around the room (after a fight, say): from the middle, both doors can be equally far, and
 * picking the entrance sends the run back and forth forever. */
export function doorsFarthestFirst(doors: EntityView[], entry: { x: number; y: number }): EntityView[] {
    return [...doors].sort(
        (a, b) => chebyshev(entry.x, entry.y, b.x, b.y) - chebyshev(entry.x, entry.y, a.x, a.y)
    );
}
