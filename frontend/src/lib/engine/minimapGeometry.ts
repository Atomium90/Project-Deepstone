import type { Direction, MinimapEdgeView, MinimapNodeView, MinimapSectionView, MinimapView } from "./protocol";
import { ROOM_TYPE_LABELS, THEME_LABELS } from "./constants";

// All in SVG user units, which the panel scales to its width.

/** Side of a room's square. */
export const NODE_SIZE = 44;
/** Distance between two columns' left edges. */
export const COLUMN_PITCH = 76;
/** Distance between the two lanes of a fork. */
export const LANE_PITCH = 64;
/** Margin left and right of a row. */
export const PAD_X = 28;
/** Height of a section's title line. */
export const SECTION_HEADER_HEIGHT = 34;
/** Room kept above a row for the "you are here" pin. */
export const PIN_HEIGHT = 18;
/** Empty space between two sections. */
export const SECTION_GAP = 30;
/** Side of the arrow chip on a fork's exit. */
export const CHIP_SIZE = 20;

/** How far along an edge, from its start, the arrow chip sits. */
const CHIP_POSITION = 0.42;
/** Rough width of one character of the section title, used to start the rule after the text. */
const TITLE_CHAR_WIDTH = 7.4;
/** Gap between a section's title and its rule, and between the title and the row's first room. */
const TITLE_GAP = 14;

/** How a room is drawn: where the player is, a room already visited, a room not visited whose type
 * is known in advance, or a room nothing is known about. */
export type NodeKind = "current" | "visited" | "known" | "unknown";

export interface PlacedNode {
  node: MinimapNodeView;
  /** Top-left corner. */
  x: number;
  y: number;
  kind: NodeKind;
  /** Hover text: the room type, never anything for a room whose type is hidden. */
  label: string;
}

export interface PlacedChip {
  /** Top-left corner. */
  x: number;
  y: number;
  direction: Direction;
  label: string;
}

export interface PlacedEdge {
  edge: MinimapEdgeView;
  /** SVG path data. */
  path: string;
  /** True once the player has been in both rooms it joins. */
  solid: boolean;
  /** Set on the edges leaving a fork. */
  chip: PlacedChip | null;
}

export interface PlacedSection {
  section: MinimapSectionView;
  title: string;
  theme: string;
  /** Left edge and baseline of the title. */
  x: number;
  baseline: number;
  /** Where the rule after the title starts, and its height. */
  ruleX: number;
  ruleY: number;
}

export interface MinimapGeometry {
  width: number;
  height: number;
  sections: PlacedSection[];
  nodes: PlacedNode[];
  edges: PlacedEdge[];
}

const WALL_NAMES: Record<Direction, string> = { UP: "top", DOWN: "bottom", LEFT: "left", RIGHT: "right" };

function kindOf(node: MinimapNodeView): NodeKind {
  if (node.current) return "current";
  if (node.visited) return "visited";
  if (node.roomType !== null) return "known";
  return "unknown";
}

function labelOf(node: MinimapNodeView): string {
  if (node.roomType === null) return "Unexplored";
  const name = ROOM_TYPE_LABELS[node.roomType];
  return node.visited ? name : `${name} (not visited)`;
}

/** Works out where everything on the map goes. A section is a row; a row with a fork has two lanes,
 * the rooms on the fork's branches sit on one lane each and every other room of that row sits on the
 * middle line between them. */
export function layoutMinimap(map: MinimapView): MinimapGeometry {
  const maxColumn = Math.max(0, ...map.nodes.map((n) => n.column));
  const width = PAD_X * 2 + maxColumn * COLUMN_PITCH + NODE_SIZE;

  // Rooms sharing a column with a lane-1 room are the two branches of a fork.
  const branchColumns = new Set(map.nodes.filter((n) => n.lane === 1).map((n) => `${n.section}:${n.column}`));
  const twoLaneSections = new Set(map.nodes.filter((n) => n.lane === 1).map((n) => n.section));

  const tops = new Map<number, number>();
  let y = 0;
  for (const section of [...map.sections].sort((a, b) => a.index - b.index)) {
    tops.set(section.index, y);
    const lanes = twoLaneSections.has(section.index) ? LANE_PITCH : 0;
    y += SECTION_HEADER_HEIGHT + PIN_HEIGHT + lanes + NODE_SIZE + SECTION_GAP;
  }
  const height = Math.max(0, y - SECTION_GAP / 2);

  const nodes: PlacedNode[] = map.nodes.map((node) => {
    let laneOffset = 0;
    if (twoLaneSections.has(node.section)) {
      laneOffset = branchColumns.has(`${node.section}:${node.column}`) ? node.lane * LANE_PITCH : LANE_PITCH / 2;
    }
    return {
      node,
      x: PAD_X + node.column * COLUMN_PITCH,
      y: (tops.get(node.section) ?? 0) + SECTION_HEADER_HEIGHT + PIN_HEIGHT + laneOffset,
      kind: kindOf(node),
      label: labelOf(node),
    };
  });
  const placedById = new Map(nodes.map((p) => [p.node.id, p]));

  const edges: PlacedEdge[] = [];
  for (const edge of map.edges) {
    const from = placedById.get(edge.from);
    const to = placedById.get(edge.to);
    if (!from || !to) continue;

    const x1 = from.x + NODE_SIZE / 2;
    const y1 = from.y + NODE_SIZE / 2;
    const x2 = to.x + NODE_SIZE / 2;
    const y2 = to.y + NODE_SIZE / 2;

    let path: string;
    if (from.node.section === to.node.section) {
      path = `M${x1} ${y1} L${x2} ${y2}`;
    } else {
      // Leaves the row from the bottom of the room and runs in the gap between the two rows.
      const gapY = (tops.get(to.node.section) ?? 0) - SECTION_GAP / 2;
      path = `M${x1} ${from.y + NODE_SIZE} V${gapY} H${x2} V${to.y}`;
    }

    const chip: PlacedChip | null = edge.exit
      ? {
          x: x1 + (x2 - x1) * CHIP_POSITION - CHIP_SIZE / 2,
          y: y1 + (y2 - y1) * CHIP_POSITION - CHIP_SIZE / 2,
          direction: edge.exit,
          label: `Take the door on the ${WALL_NAMES[edge.exit]} wall`,
        }
      : null;

    edges.push({ edge, path, solid: from.node.visited && to.node.visited, chip });
  }

  const sections: PlacedSection[] = map.sections.map((section) => {
    const title = `Section ${section.index + 1}`;
    const theme = THEME_LABELS[section.theme] ?? section.theme;
    const top = tops.get(section.index) ?? 0;
    // Starts to the right of the room column, so the link coming down from the previous section
    // does not run through the title.
    const x = PAD_X + NODE_SIZE + TITLE_GAP;
    return {
      section,
      title,
      theme,
      x,
      baseline: top + 18,
      ruleX: x + (title.length + theme.length + 3) * TITLE_CHAR_WIDTH + TITLE_GAP,
      ruleY: top + 14,
    };
  });

  return { width, height, sections, nodes, edges };
}
