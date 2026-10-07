import { describe, test, expect } from "vitest";
import {
    layoutMinimap,
    NODE_SIZE,
    COLUMN_PITCH,
    LANE_PITCH,
    PAD_X,
    SECTION_HEADER_HEIGHT,
    PIN_HEIGHT,
    SECTION_GAP,
} from "./minimapGeometry";
import type { MinimapEdgeView, MinimapNodeView, MinimapView } from "./protocol";

function node(id: string, section: number, column: number, lane = 0, overrides: Partial<MinimapNodeView> = {}): MinimapNodeView {
    return { id, roomType: "combat", visited: false, current: false, section, column, lane, ...overrides };
}

function edge(from: string, to: string, exit: MinimapEdgeView["exit"] = null): MinimapEdgeView {
    return { from, to, exit };
}

function map(parts: Partial<MinimapView>): MinimapView {
    return { sections: [{ index: 0, theme: "dungeon" }], nodes: [], edges: [], ...parts };
}

const ROW_TOP = SECTION_HEADER_HEIGHT + PIN_HEIGHT;

describe("layoutMinimap rooms", () => {
    test("the rooms of a row are one column pitch apart, starting at the left margin", () => {
        const g = layoutMinimap(map({ nodes: [node("a", 0, 0), node("b", 0, 1), node("c", 0, 2)] }));
        expect(g.nodes.map((n) => n.x)).toEqual([PAD_X, PAD_X + COLUMN_PITCH, PAD_X + 2 * COLUMN_PITCH]);
        expect(new Set(g.nodes.map((n) => n.y))).toEqual(new Set([ROW_TOP]));
    });

    test("the width fits the widest row between two margins", () => {
        const g = layoutMinimap(map({ nodes: [node("a", 0, 0), node("b", 0, 4)] }));
        expect(g.width).toBe(PAD_X * 2 + 4 * COLUMN_PITCH + NODE_SIZE);
    });

    test("the two branches of a fork sit on two lanes and the rooms around the fork on the middle line", () => {
        const g = layoutMinimap(
            map({
                nodes: [node("fork", 0, 0), node("upper", 0, 1, 0), node("lower", 0, 1, 1), node("merge", 0, 2)],
            })
        );
        const y = Object.fromEntries(g.nodes.map((n) => [n.node.id, n.y]));
        expect(y.upper).toBe(ROW_TOP);
        expect(y.lower).toBe(ROW_TOP + LANE_PITCH);
        expect(y.fork).toBe(ROW_TOP + LANE_PITCH / 2);
        expect(y.merge).toBe(ROW_TOP + LANE_PITCH / 2);
    });

    test("a section without a fork has no second lane, so it is shorter", () => {
        const plain = layoutMinimap(map({ nodes: [node("a", 0, 0)] }));
        const forked = layoutMinimap(map({ nodes: [node("a", 0, 0), node("b", 0, 1, 0), node("c", 0, 1, 1)] }));
        expect(forked.height - plain.height).toBe(LANE_PITCH);
    });

    test("sections stack, each below the one before it", () => {
        const g = layoutMinimap(
            map({
                sections: [
                    { index: 0, theme: "dungeon" },
                    { index: 1, theme: "darkDungeon" },
                ],
                nodes: [node("a", 0, 0), node("b", 1, 0)],
            })
        );
        const [first, second] = g.nodes;
        // A whole section down: its title and pin room, its one row of rooms, and the gap.
        expect(second.y - first.y).toBe(ROW_TOP + NODE_SIZE + SECTION_GAP);
    });

    test("an empty map has no size", () => {
        const g = layoutMinimap({ sections: [], nodes: [], edges: [] });
        expect(g.height).toBe(0);
        expect(g.nodes).toEqual([]);
    });
});

describe("layoutMinimap how a room is drawn", () => {
    test("the room the player is in is current, even though it is also visited", () => {
        const g = layoutMinimap(map({ nodes: [node("a", 0, 0, 0, { visited: true, current: true })] }));
        expect(g.nodes[0].kind).toBe("current");
    });

    test("a visited room, a room known in advance and a hidden room are told apart", () => {
        const g = layoutMinimap(
            map({
                nodes: [
                    node("seen", 0, 0, 0, { visited: true }),
                    node("boss", 0, 1, 0, { roomType: "boss" }),
                    node("hidden", 0, 2, 0, { roomType: null }),
                ],
            })
        );
        expect(g.nodes.map((n) => n.kind)).toEqual(["visited", "known", "unknown"]);
    });

    test("the hover text names the type, and says nothing for a hidden room", () => {
        const g = layoutMinimap(
            map({
                nodes: [
                    node("seen", 0, 0, 0, { visited: true, roomType: "loot" }),
                    node("boss", 0, 1, 0, { roomType: "boss" }),
                    node("hidden", 0, 2, 0, { roomType: null }),
                ],
            })
        );
        expect(g.nodes.map((n) => n.label)).toEqual(["Loot", "Boss (not visited)", "Unexplored"]);
    });
});

describe("layoutMinimap links", () => {
    const row = map({
        nodes: [node("a", 0, 0, 0, { visited: true }), node("b", 0, 1, 0, { visited: true }), node("c", 0, 2)],
        edges: [edge("a", "b"), edge("b", "c")],
    });

    test("a link inside a section is a straight line between the two room centres", () => {
        const g = layoutMinimap(row);
        const half = NODE_SIZE / 2;
        expect(g.edges[0].path).toBe(`M${PAD_X + half} ${ROW_TOP + half} L${PAD_X + COLUMN_PITCH + half} ${ROW_TOP + half}`);
    });

    test("a link is solid only when the player has been in both rooms", () => {
        expect(layoutMinimap(row).edges.map((e) => e.solid)).toEqual([true, false]);
    });

    test("a link into the next section leaves from the bottom of the room and runs in the gap between the two rows", () => {
        const g = layoutMinimap(
            map({
                sections: [
                    { index: 0, theme: "dungeon" },
                    { index: 1, theme: "dungeon" },
                ],
                nodes: [node("end", 0, 2), node("start", 1, 0)],
                edges: [edge("end", "start")],
            })
        );
        const [end, start] = g.nodes;
        const gapY = start.y - ROW_TOP - SECTION_GAP / 2;
        expect(g.edges[0].path).toBe(
            `M${end.x + NODE_SIZE / 2} ${end.y + NODE_SIZE} V${gapY} H${start.x + NODE_SIZE / 2} V${start.y}`
        );
        expect(gapY).toBeGreaterThan(end.y + NODE_SIZE);
        expect(gapY).toBeLessThan(start.y);
    });

    test("only the links leaving a fork get an arrow chip, naming the wall of the door", () => {
        const g = layoutMinimap(
            map({
                nodes: [node("fork", 0, 0), node("up", 0, 1, 0), node("down", 0, 1, 1)],
                edges: [edge("fork", "up", "RIGHT"), edge("fork", "down", "DOWN")],
            })
        );
        expect(g.edges.map((e) => e.chip?.direction)).toEqual(["RIGHT", "DOWN"]);
        expect(g.edges.map((e) => e.chip?.label)).toEqual([
            "Take the door on the right wall",
            "Take the door on the bottom wall",
        ]);
        expect(layoutMinimap(row).edges.map((e) => e.chip)).toEqual([null, null]);
    });

    test("a link to a room that is not on the map is left out", () => {
        const g = layoutMinimap(map({ nodes: [node("a", 0, 0)], edges: [edge("a", "missing")] }));
        expect(g.edges).toEqual([]);
    });
});

describe("layoutMinimap section titles", () => {
    test("sections are numbered from 1 and show the theme's display name, or its id when it has none", () => {
        const g = layoutMinimap(
            map({
                sections: [
                    { index: 0, theme: "dungeon" },
                    { index: 1, theme: "darkDungeon" },
                    { index: 2, theme: "crypt" },
                ],
                nodes: [node("a", 0, 0), node("b", 1, 0), node("c", 2, 0)],
            })
        );
        expect(g.sections.map((s) => [s.title, s.theme])).toEqual([
            ["Section 1", "Dungeon"],
            ["Section 2", "Dark dungeon"],
            ["Section 3", "crypt"],
        ]);
    });

    test("the title starts to the right of the first room, clear of the link coming down to it", () => {
        const g = layoutMinimap(map({ nodes: [node("a", 0, 0)] }));
        expect(g.sections[0].x).toBeGreaterThan(PAD_X + NODE_SIZE);
        expect(g.sections[0].ruleX).toBeGreaterThan(g.sections[0].x);
    });
});
