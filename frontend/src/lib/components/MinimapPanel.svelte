<script lang="ts">
    import { minimap } from "../engine/StateStore";
    import { layoutMinimap, NODE_SIZE, CHIP_SIZE, PAD_X } from "../engine/minimapGeometry";
    import { ROOM_ICONS, UNKNOWN_ICON, PIN_ICON, ARROW_ICONS } from "../engine/minimapIcons";
    import { MINIMAP_COLORS as C, ROOM_TYPE_LABELS } from "../engine/constants";
    import type { RoomTypeName } from "../engine/protocol";

    const ICON_SIZE = 22;
    const PIN_SIZE = 16;
    const ARROW_SIZE = 12;

    const LEGEND_TYPES: RoomTypeName[] = ["combat", "loot", "rest", "fork", "miniboss", "boss", "sanctuary"];

    $: geometry = $minimap ? layoutMinimap($minimap) : null;

    /** The glyph of a room: its type's icon, or a question mark while the type is hidden. */
    function iconFor(roomType: RoomTypeName | null): readonly string[] {
        return roomType ? ROOM_ICONS[roomType] : UNKNOWN_ICON;
    }

    /** The class that tints a room by its type, empty while the type is hidden. */
    function typeClass(roomType: RoomTypeName | null): string {
        return roomType ? `t-${roomType}` : "";
    }
</script>

{#if geometry}
    <div
        class="minimap-panel"
        style="--fill:{C.nodeFill}; --border:{C.nodeBorder}; --visited-border:{C.visitedBorder}; --icon:{C.icon};
               --icon-dim:{C.iconDim}; --icon-unknown:{C.iconUnknown}; --current-fill:{C.currentFill};
               --current:{C.currentBorder}; --enemy:{C.enemy}; --sanctuary:{C.sanctuary};
               --edge-done:{C.edgeDone}; --edge-todo:{C.edgeTodo}; --section-name:{C.sectionName};
               --section-theme:{C.sectionTheme}; --section-rule:{C.sectionRule}; --chip-fill:{C.chipFill};
               --chip-border:{C.chipBorder}; --chip-icon:{C.chipIcon}"
    >
        <svg
            class="map"
            viewBox="0 0 {geometry.width} {geometry.height}"
            style="max-width:{geometry.width}px"
            role="img"
            aria-label="Dungeon map"
        >
            {#each geometry.sections as s (s.section.index)}
                <text class="section-title" data-section x={s.x} y={s.baseline}>
                    <tspan class="section-name">{s.title}</tspan><tspan class="section-theme" dx="8">{s.theme}</tspan>
                </text>
                <line class="section-rule" x1={s.ruleX} x2={geometry.width - PAD_X} y1={s.ruleY} y2={s.ruleY} />
            {/each}

            {#each geometry.edges as e}
                <path class="edge" class:solid={e.solid} d={e.path} />
            {/each}

            {#each geometry.edges as e}
                {#if e.chip}
                    <g class="chip" data-chip data-direction={e.chip.direction} transform="translate({e.chip.x} {e.chip.y})">
                        <title>{e.chip.label}</title>
                        <rect width={CHIP_SIZE} height={CHIP_SIZE} rx="6" />
                        <svg x={(CHIP_SIZE - ARROW_SIZE) / 2} y={(CHIP_SIZE - ARROW_SIZE) / 2} width={ARROW_SIZE} height={ARROW_SIZE} viewBox="0 0 24 24">
                            {#each ARROW_ICONS[e.chip.direction] as d}<path {d} />{/each}
                        </svg>
                    </g>
                {/if}
            {/each}

            {#each geometry.nodes as n (n.node.id)}
                <g
                    class="node {n.kind} {typeClass(n.node.roomType)}"
                    data-node
                    data-kind={n.kind}
                    data-type={n.node.roomType ?? ""}
                    transform="translate({n.x} {n.y})"
                >
                    <title>{n.label}</title>
                    <rect width={NODE_SIZE} height={NODE_SIZE} rx="8" />
                    <svg x={(NODE_SIZE - ICON_SIZE) / 2} y={(NODE_SIZE - ICON_SIZE) / 2} width={ICON_SIZE} height={ICON_SIZE} viewBox="0 0 24 24">
                        {#each iconFor(n.node.roomType) as d}<path {d} />{/each}
                    </svg>
                    {#if n.node.current}
                        <svg class="pin" x={(NODE_SIZE - PIN_SIZE) / 2} y={-PIN_SIZE - 2} width={PIN_SIZE} height={PIN_SIZE} viewBox="0 0 24 24">
                            {#each PIN_ICON as d}<path {d} />{/each}
                        </svg>
                    {/if}
                </g>
            {/each}
        </svg>

        <ul class="legend">
            {#each LEGEND_TYPES as type}
                <li>
                    <span class="swatch visited t-{type}">
                        <svg width="14" height="14" viewBox="0 0 24 24">{#each ROOM_ICONS[type] as d}<path {d} />{/each}</svg>
                    </span>
                    {ROOM_TYPE_LABELS[type]}
                </li>
            {/each}
            <li>
                <span class="swatch current">
                    <svg width="14" height="14" viewBox="0 0 24 24">{#each PIN_ICON as d}<path {d} />{/each}</svg>
                </span>
                You are here
            </li>
            <li>
                <span class="swatch known t-boss">
                    <svg width="14" height="14" viewBox="0 0 24 24">{#each ROOM_ICONS.boss as d}<path {d} />{/each}</svg>
                </span>
                Known, not visited
            </li>
            <li>
                <span class="swatch unknown">
                    <svg width="14" height="14" viewBox="0 0 24 24">{#each UNKNOWN_ICON as d}<path {d} />{/each}</svg>
                </span>
                Unexplored
            </li>
        </ul>
    </div>
{:else}
    <p class="muted">The map is only available during a run.</p>
{/if}

<style>
    .minimap-panel {
        display: flex;
        flex-direction: column;
        align-items: center;
        gap: 1.25rem;
        font-family: monospace;
    }

    .map {
        width: 100%;
        height: auto;
    }

    /* Every icon is a set of plain strokes; the edges override the color below. */
    path {
        fill: none;
        stroke: currentColor;
        stroke-width: 2;
        stroke-linecap: round;
        stroke-linejoin: round;
    }

    /* -- Section titles --------------------------------------------------- */

    .section-title { font-size: 12px; }
    .section-name  { fill: var(--section-name); }
    .section-theme { fill: var(--section-theme); font-size: 11px; }
    .section-rule  { stroke: var(--section-rule); stroke-width: 1; }

    /* -- Links ------------------------------------------------------------- */

    .edge {
        fill: none;
        stroke: var(--edge-todo);
        stroke-width: 2;
        stroke-dasharray: 4 4;
    }

    .edge.solid {
        stroke: var(--edge-done);
        stroke-dasharray: none;
    }

    .chip rect {
        fill: var(--chip-fill);
        stroke: var(--chip-border);
        stroke-width: 1;
    }

    .chip { color: var(--chip-icon); }

    /* -- Rooms ------------------------------------------------------------- */

    .node {
        color: var(--icon-dim);
    }

    .node rect {
        fill: var(--fill);
        stroke: var(--border);
        stroke-width: 1.5;
    }

    .node.known rect,
    .node.unknown rect {
        stroke-dasharray: 4 3;
    }

    .node.unknown { color: var(--icon-unknown); }

    .node.visited { color: var(--icon); }
    .node.visited rect { stroke: var(--visited-border); }

    .node.t-boss,
    .node.t-miniboss { color: var(--enemy); }
    .node.known.t-boss rect,
    .node.known.t-miniboss rect { stroke: var(--enemy); }

    .node.t-sanctuary { color: var(--sanctuary); }
    .node.known.t-sanctuary rect { stroke: var(--sanctuary); }

    .node.current { color: var(--current); }
    .node.current rect {
        fill: var(--current-fill);
        stroke: var(--current);
        stroke-width: 2.5;
        stroke-dasharray: none;
    }

    .pin { color: var(--current); }

    /* -- Legend ------------------------------------------------------------ */

    .legend {
        display: flex;
        flex-wrap: wrap;
        justify-content: center;
        gap: 0.5rem 1.25rem;
        list-style: none;
        padding: 0.9rem 0 0;
        border-top: 1px solid var(--section-rule);
        width: 100%;
        font-size: 0.7rem;
        color: var(--section-theme);
    }

    .legend li {
        display: flex;
        align-items: center;
        gap: 0.4rem;
    }

    .swatch {
        display: inline-flex;
        align-items: center;
        justify-content: center;
        width: 1.7rem;
        height: 1.7rem;
        border-radius: 6px;
        background: var(--fill);
        border: 1.5px solid var(--border);
        color: var(--icon-dim);
    }

    .swatch.visited { border-color: var(--visited-border); color: var(--icon); }
    .swatch.known,
    .swatch.unknown { border-style: dashed; }
    .swatch.unknown { color: var(--icon-unknown); }
    .swatch.t-boss,
    .swatch.t-miniboss { color: var(--enemy); }
    .swatch.known.t-boss { border-color: var(--enemy); }
    .swatch.t-sanctuary { color: var(--sanctuary); }
    .swatch.current {
        background: var(--current-fill);
        border-color: var(--current);
        color: var(--current);
    }

    .muted { color: #444; font-size: 0.85rem; }
</style>
