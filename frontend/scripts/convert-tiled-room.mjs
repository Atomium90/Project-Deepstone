// Converts one Tiled map (.tmx, or a Tiled JSON/.tmj export) into one room entry for
// deepstone-backend's rooms.json.
//
// ============================================================================================
// HOW TO SET UP A ROOM MAP IN TILED (read this before painting anything)
// ============================================================================================
//
// 0. Before painting your first room with a given tileset, generate its sprite atlas once:
//      node scripts/generate-tiled-tileset-atlas.mjs path/to/tileset.tsx
//    Slices the tileset's own PNG into one atlas entry per tile, copies the PNG into
//    public/sprites/tiles/, and registers the atlas in AssetManager.ts's ATLAS_URLS (add the new
//    "/atlas/{tilesetName}_tiles.json" line there by hand, next to the existing ones - the
//    generator only writes the file, it doesn't edit AssetManager.ts). Only needed again later if
//    the tileset's source PNG changes (new tiles added, etc) - not per room.
//
// 1. New map, orthogonal, tile size matching your source tileset (16x16 for every pack sourced
//    so far). Just save normally (.tmx) - this script reads a .tmx directly, including external
//    .tsx tileset references (the normal Tiled workflow: one shared tileset file per art pack,
//    reused across every map, rather than duplicating it into each room). A Tiled JSON/.tmj
//    export also works (embedded or external tilesets, either is fine) if you ever prefer that.
//    Tile layers must use CSV encoding (Tiled's default - Map > Map Properties > Tile Layer
//    Format, only relevant if you've changed it to Base64/gzip).
//
// 2. Map > Map Properties, add 3 custom properties (Map Properties panel, not per-layer/per-tile):
//      id        (string)  e.g. "dungeon_combat_012"
//      roomType  (string)  one of: combat, loot, rest, boss, vault, miniboss, fork
//      theme     (string)  e.g. "dungeon", "darkDungeon"
//    Can't be bothered setting these before a quick test conversion? Pass --id=/--roomType=/
//    --theme= on the command line instead - see USAGE below.
//
// 3. 3-4 tile/object layers, bottom to top - floor, walls, an optional floor_2, then entities.
//    Matches how the source art itself is authored: most wall tiles have a transparent background
//    specifically so a floor tile shows through wherever the wall art doesn't fully cover its
//    cell (a wall glued to one side, floor on the rest) - Renderer.ts composites the same way,
//    floor drawn first in every cell, then the wall sprite on top wherever there is one. Layer
//    names are matched case-insensitively by substring (not an exact match), so "Floor_1", "Wall
//    Layer", "Entity Layer" etc all resolve fine - name them however reads clearly to you, group
//    them under a Tiled `<group>` if you like (this script sees straight through groups).
//      floor layer (name contains "floor", but not "2") - paint it under wall cells too, not just
//      walkable ones: whatever's painted there is what shows through wherever the wall art on top
//      doesn't fully cover its cell (see point 4's tileset-atlas note), so a wall standing in the
//      middle of a room still needs a real floor tile under it to look right - only skip painting
//      under a cell that's genuinely always fully covered anyway (an outer border wall, say).
//      A walkable (non-wall) cell always needs one; leaving one unpainted fails the conversion
//      loudly (a genuine hole in the room, not a stylistic choice).
//      walls layer (name contains "wall") - PARTIAL coverage: paint a wall tile only where a wall
//      actually is, and leave every other cell empty. An empty cell here simply means "floor
//      shows through, nothing on top" - it's not an error, it's the normal case.
//      floor_2 layer (name contains both "floor" and "2") - OPTIONAL, sparse. For a sprite that
//      has to sit on top of the real floor/wall tile rather than replace it (a Tiled tile layer
//      only holds one tile per cell, so something like a column's top/bottom cap - which needs
//      the ordinary floor tile to stay visible underneath it - can't be painted directly onto
//      "floor" without destroying it). Leave every cell empty except the few that genuinely need
//      this; most rooms won't need this layer at all, and it's fine to not create it.
//
// 4. Nothing to do here anymore for a plain painted tile - its sprite key is derived
//    automatically as "{tilesetName}_{localId}" (the tileset's own `name` attribute plus Tiled's
//    own tile id), which is exactly what step 0's atlas was generated to match. Paint normally
//    (Terrain Brush for walls, Random Mode for floor variety) and every cell just resolves. Only
//    set an explicit `sprite` (string) custom property on a tile, in the Tileset editor, if you
//    want to override that - e.g. to give a specific tile a more memorable/reusable name than its
//    raw id, or to point it at a hand-curated atlas key from tiles.json/entities.json instead.
//
// 5. Exactly one object layer named "entities" (case-insensitive substring, see point 3). One
//    object per door, chest, enemy, NPC, locked door - anything the player presses E on, or that
//    structurally wires two rooms together. A door/chest/enemy/etc's own visual appearance in
//    Tiled doesn't matter at all (the real game never looks at it) - a plain rectangle is fine.
//    Use the Insert Rectangle (or Insert Point) tool with "snap to grid" on, click once per
//    object so it lands cleanly on one tile - the object's top-left pixel corner is what gets
//    divided by the tile size to find its (x, y).
//
//    Recommended: set up a Custom Type (View > Custom Types Editor) per kind - "door", "enemy",
//    "chest", "locked_door", "npc", plus "boss" (an organizational alias for "enemy" - see below)
//    - each pre-populated with that kind's own fields below. Assign the right Class to each object
//    (the dropdown at the top of the Properties panel) and its fields show up ready to fill in,
//    instead of adding every property by hand each time. The script reads `kind` straight from
//    the object's Class - no separate 'kind' property needed once you're using Custom Types
//    (though a plain 'kind' string property still works too, if you'd rather skip Custom Types).
//
//    Every object needs these custom properties, matching rooms.json's own entity fields exactly
//    - same names, same meaning, nothing Tiled-specific:
//      id      (string)  OPTIONAL - auto-generates from the kind + Tiled's own object id if left
//                         blank (e.g. "enemy_5"). Set one yourself only if you want something more
//                         readable.
//      Then, depending on kind (exactly the fields RoomLoader already expects - see
//      deepstone-backend/.../RoomLoader.scala's EntityJson if in doubt):
//        enemy:        typeId (string), label (string) - both OPTIONAL, left as a "TODO"
//                      placeholder (with a warning) if you haven't decided the monster yet. Not
//                      real game content until filled in for real, but fine to leave for later.
//        boss:         same fields as enemy - not a real entity kind, purely for your own
//                      organization while placing objects in a boss room. Normalized to "enemy"
//                      automatically.
//        chest:        trapped (bool, optional, default false) - there's no "random trapped
//                      roll" mechanic today, only this fixed authored flag; leave it unset for an
//                      always-safe chest.
//        door:         direction (UP|DOWN|LEFT|RIGHT), role (prev|next) - both required; branch
//                      (string, optional - only for a Fork room's two distinct exits)
//        locked_door:  direction, targetRoomId (required), doorTag (string, optional)
//        npc:          name (string, OPTIONAL - "TODO" placeholder + warning if left blank, must
//                      eventually match a name in npcs.json for dialogue to resolve)
//
// ============================================================================================
// USAGE
// ============================================================================================
//   node scripts/convert-tiled-room.mjs path/to/map.tmx
//   node scripts/convert-tiled-room.mjs path/to/map.tmx --out=path/to/room.json
//   node scripts/convert-tiled-room.mjs path/to/map.tmx --id=dungeon_combat_012 --theme=dungeon
//
// Without --out, prints the room JSON to stdout - review it, then paste it into rooms.json's
// array yourself (this script never touches rooms.json directly, on purpose: it's hand-curated
// content, not a build artifact to overwrite). --id/--roomType/--theme override whatever the
// map's own Map Properties say, mainly useful for a quick test conversion before you've set
// those up. Prints one warning per tileset used in the room that doesn't have a matching
// public/atlas/{tilesetName}_tiles.json yet (see step 0 above) - the room still converts (every
// cell still gets its derived sprite key), it just won't render as anything but a fallback color
// client-side until that atlas exists.

import { readFileSync, writeFileSync, existsSync } from "node:fs";
import { dirname, resolve, extname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { parseXmlDoc, readPropertiesEl } from "./lib/tiled-xml.mjs";

const __dirname = dirname(fileURLToPath(import.meta.url));
const publicAtlasDir = join(__dirname, "..", "public", "atlas");

const VALID_ROOM_TYPES = new Set(["combat", "loot", "rest", "boss", "vault", "miniboss", "fork"]);
const VALID_ENTITY_KINDS = new Set(["enemy", "chest", "door", "locked_door", "npc"]);
const VALID_DIRECTIONS = new Set(["UP", "DOWN", "LEFT", "RIGHT"]);
const VALID_ROLES = new Set(["prev", "next"]);
const VALID_DOOR_KINDS = new Set(["normal", "trapped", "secret"]);

// Tiled sets the top 3 bits of a cell's raw gid to flag horizontal/vertical/diagonal flipping.
// A top-down dungeon room's structure layer should never use these, but a raw gid still needs
// masking before it's looked up - an accidentally-flipped tile would otherwise resolve as gid 0
// (empty) and fail the "every cell must be painted" check with a confusing error otherwise.
const FLIP_MASK = ~(0x80000000 | 0x40000000 | 0x20000000) >>> 0;

function fail(message) {
    console.error(`convert-tiled-room: ${message}`);
    process.exit(1);
}

/** Non-fatal - prints a warning but lets the conversion finish. Used for fields you're allowed to
 * decide after converting (which monster, what an NPC says) rather than while placing objects. */
function warn(message) {
    console.error(`convert-tiled-room: WARNING - ${message}`);
}

/** A required-eventually field: returns the real value if set, otherwise a "TODO" placeholder and
 * a warning - the room still converts, but isn't real game content until you go back and fill
 * this in (either by hand in the JSON, or back in Tiled before re-converting). */
function placeholderField(obj, field, context) {
    const value = obj[field];
    if (value === undefined || value === null || value === "") {
        warn(`${context} has no '${field}' yet - using a "TODO" placeholder. Fill in the real value before this room is game-ready.`);
        return "TODO";
    }
    return value;
}

function parseArgs(argv) {
    const positional = [];
    const flags = {};
    for (const arg of argv) {
        if (arg.startsWith("--")) {
            const [key, value] = arg.slice(2).split("=");
            flags[key] = value ?? true;
        } else {
            positional.push(arg);
        }
    }
    return { positional, flags };
}

/** Tiled's custom-property array (`[{name, type, value}, ...]`) as a plain `{name: value}`
 * object - every property type (string/bool/int/float) already carries the right JS type in
 * `.value`, no per-type coercion needed. Works the same whether it came from a JSON export or
 * was assembled by the XML reader below - both converge on this exact shape. */
function propsOf(tiledThing) {
    const out = {};
    for (const p of tiledThing.properties ?? []) out[p.name] = p.value;
    return out;
}

// ============================================================================================
// .tmx (Tiled's native XML save format) reading
// ============================================================================================
// Tiled JSON export and .tmx describe the exact same model, just serialized differently (a
// <group> in .tmx vs a `type: "group"` layer with nested `layers` in JSON, a <properties><property
// name=".." value=".."/></properties> block vs a `properties: [{name, value}]` array, etc). This
// section's job is only to read a .tmx (or an externally-referenced .tsx tileset) into that exact
// same shape - {tilewidth, tileheight, tilesets: [{firstgid, name, tiles}], layers: [...], properties}
// - so every function below this point (buildTileLookup onward) never needs to know or care which
// file format the room actually came from.

/** Reads a <tileset> element's own tiles (each with custom properties) - works whether that
 * element is the root of an external .tsx file or embedded directly inside a .tmx <map>, since
 * both shapes are identical past the <tileset ...> tag itself. */
function parseTilesetElement(tilesetEl) {
    const name = tilesetEl.getAttribute("name");
    const tiles = [...tilesetEl.querySelectorAll(":scope > tile")].map((tileEl) => ({
        id: Number(tileEl.getAttribute("id")),
        properties: readPropertiesEl(tileEl.querySelector(":scope > properties")),
    }));
    return { name, tiles };
}

/** One <tileset firstgid=".."> reference from inside a <map> - either embedded (the tileset's own
 * content sits right there as children) or external (a `source="foo.tsx"` pointer, resolved
 * relative to the .tmx's own folder - the normal Tiled setup, one shared tileset file per art
 * pack reused across every map, rather than duplicating it into each room). */
function loadTileset(tilesetRefEl, baseDir) {
    const firstgid = Number(tilesetRefEl.getAttribute("firstgid"));
    const source = tilesetRefEl.getAttribute("source");
    if (!source) return { firstgid, ...parseTilesetElement(tilesetRefEl) };

    const tsxPath = resolve(baseDir, source);
    let tsxText;
    try {
        tsxText = readFileSync(tsxPath, "utf-8");
    } catch {
        fail(`external tileset "${source}" (referenced from the map) wasn't found at "${tsxPath}".`);
    }
    const tsxDoc = parseXmlDoc(tsxText, tsxPath);
    const root = tsxDoc.querySelector("tileset");
    if (!root) fail(`"${tsxPath}" has no root <tileset> element - is this a valid Tiled .tsx file?`);
    return { firstgid, ...parseTilesetElement(root) };
}

function parseCsvLayerData(dataEl, layerName, width, height) {
    const encoding = dataEl.getAttribute("encoding");
    if (encoding !== "csv") {
        fail(`layer "${layerName}" uses "${encoding ?? "unknown"}" tile data encoding - switch Map > Map Properties > Tile Layer Format to "CSV" and re-save.`);
    }
    const values = dataEl.textContent
        .split(",")
        .map((s) => s.trim())
        .filter((s) => s.length > 0)
        .map(Number);
    if (values.length !== width * height) {
        fail(`layer "${layerName}" has ${values.length} cells of data but is declared ${width}x${height} (${width * height}) - the map may be corrupt, try re-saving it in Tiled.`);
    }
    return values;
}

/** Recursively walks <layer>/<objectgroup>/<group> children into one flat list, in the same shape
 * Tiled's own JSON export uses ({type: "tilelayer"|"objectgroup", name, ...}) - a <group> is
 * purely an organizational folder in the Tiled UI and carries no meaning here, so its children are
 * simply flattened into the parent's list rather than needing their own handling downstream. */
function flattenTmxLayers(parentEl) {
    const out = [];
    for (const child of parentEl.children) {
        const tag = child.tagName.toLowerCase();
        const name = child.getAttribute("name") ?? "";
        if (tag === "layer") {
            const width = Number(child.getAttribute("width"));
            const height = Number(child.getAttribute("height"));
            const dataEl = child.querySelector(":scope > data");
            if (!dataEl) fail(`layer "${name}" has no <data> - an infinite/chunked map isn't supported, use a fixed-size map.`);
            out.push({ type: "tilelayer", name, width, height, data: parseCsvLayerData(dataEl, name, width, height) });
        } else if (tag === "objectgroup") {
            const objects = [...child.querySelectorAll(":scope > object")].map((obj) => ({
                id: Number(obj.getAttribute("id")),
                name: obj.getAttribute("name") ?? "",
                // Tiled's file format still calls this attribute `type` (the "Class" terminology
                // is a 1.9+ UI rename only) - keeping the JSON export's own field name, `class`,
                // here so downstream code doesn't need to know which format the room came from.
                class: obj.getAttribute("type") ?? "",
                x: Number(obj.getAttribute("x")),
                y: Number(obj.getAttribute("y")),
                properties: readPropertiesEl(obj.querySelector(":scope > properties")),
            }));
            out.push({ type: "objectgroup", name, objects });
        } else if (tag === "group") {
            out.push(...flattenTmxLayers(child));
        }
        // imagelayer and anything else isn't relevant to room conversion - skipped.
    }
    return out;
}

function loadTmx(mapPath) {
    const dir = dirname(mapPath);
    const doc = parseXmlDoc(readFileSync(mapPath, "utf-8"), mapPath);
    const mapEl = doc.querySelector("map");
    if (!mapEl) fail(`"${mapPath}" has no root <map> element - is this a valid Tiled .tmx file?`);

    return {
        tilewidth: Number(mapEl.getAttribute("tilewidth")),
        tileheight: Number(mapEl.getAttribute("tileheight")),
        tilesets: [...mapEl.querySelectorAll(":scope > tileset")].map((el) => loadTileset(el, dir)),
        layers: flattenTmxLayers(mapEl),
        properties: readPropertiesEl(mapEl.querySelector(":scope > properties")),
    };
}

/** A Tiled JSON export can also group layers (`type: "group"`, nested `layers`) - flattened here
 * the same way the .tmx reader flattens <group>, so both formats converge on one flat layers list. */
function flattenJsonGroupLayers(layers) {
    const out = [];
    for (const l of layers ?? []) {
        if (l.type === "group") out.push(...flattenJsonGroupLayers(l.layers));
        else out.push(l);
    }
    return out;
}

function loadMap(mapPath) {
    const ext = extname(mapPath).toLowerCase();
    if (ext === ".tmx") return loadTmx(mapPath);
    if (ext === ".json" || ext === ".tmj") {
        const map = JSON.parse(readFileSync(mapPath, "utf-8"));
        map.layers = flattenJsonGroupLayers(map.layers);
        return map;
    }
    fail(`"${mapPath}": unrecognized extension "${ext}" - expected a Tiled .tmx save file, or a .json/.tmj export.`);
}

// ============================================================================================
// Room conversion (format-agnostic from here on - operates on the {tilewidth, tileheight,
// tilesets, layers, properties} shape produced above, regardless of which file it came from)
// ============================================================================================

/** Builds a lookup from global tile id -> that tile's custom properties, across every tileset in
 * the map. Tiled only lists a tile in `tileset.tiles` if it has custom properties or an animation
 * - a tile with no properties simply isn't in the array, which is exactly the tiles this script
 * never needs to look up (they're never painted as structure). */
function buildTileLookup(map) {
    const tilesets = map.tilesets ?? [];
    for (const ts of tilesets) {
        if (ts.source) {
            fail(
                `tileset "${ts.name ?? ts.source}" is an external reference (${ts.source}) that wasn't resolved - ` +
                    `this only happens on a Tiled JSON export saved without embedding tilesets; re-export with ` +
                    `"Embed tileset in map" checked, or just point this script at the .tmx directly instead.`
            );
        }
    }

    return (gidRaw) => {
        const gid = gidRaw & FLIP_MASK;
        if (gid === 0) return null;

        // The tileset with the largest firstgid <= gid owns this tile.
        let owner = null;
        for (const ts of tilesets) {
            if (ts.firstgid <= gid && (!owner || ts.firstgid > owner.firstgid)) owner = ts;
        }
        if (!owner) fail(`tile gid ${gid} doesn't belong to any tileset in this map.`);

        const localId = gid - owner.firstgid;
        const tileDef = (owner.tiles ?? []).find((t) => t.id === localId);
        const props = tileDef ? propsOf(tileDef) : {};
        return { tilesetName: owner.name, localId, props };
    };
}

/** The atlas sprite key for a painted cell: an explicit `sprite` custom property (set on the tile
 * in the Tileset editor) wins if present, otherwise it's derived as "{tilesetName}_{localId}" -
 * exactly what generate-tiled-tileset-atlas.mjs's full-grid atlas is keyed by, so a plain painted
 * tile needs zero per-tile authoring to resolve to its exact sub-rect. `usedTilesets` records
 * every distinct tileset name actually painted with, so main() can warn once per tileset that has
 * no matching atlas file yet (see checkAtlasesExist) - not fatal, since the room's structure/doors/
 * entities are still worth converting even before that atlas exists. */
function spriteOf(tile, usedTilesets) {
    usedTilesets.add(tile.tilesetName);
    const { sprite } = tile.props;
    return typeof sprite === "string" ? sprite : `${tile.tilesetName}_${tile.localId}`;
}

function findLayer(map, type, matches) {
    const layers = map.layers ?? [];
    return layers.find((l) => l.type === type && matches(l.name?.toLowerCase() ?? ""));
}

function convertFloorAndWallsLayers(map, lookupTile, usedTilesets) {
    const floorLayer = findLayer(map, "tilelayer", (n) => n.includes("floor") && !n.includes("2"));
    if (!floorLayer) fail('no "floor" tile layer found (a tile layer whose name contains "floor" - see the setup notes at the top of this script).');
    const wallsLayer = findLayer(map, "tilelayer", (n) => n.includes("wall"));
    if (!wallsLayer) fail('no "walls" tile layer found (a tile layer whose name contains "wall" - see the setup notes at the top of this script).');
    // Optional - a sparse extra tile layer for anything that has to sit on top of the real floor/
    // wall tile rather than replace it (see the setup notes' point 3 for why).
    const decorationLayer = findLayer(map, "tilelayer", (n) => n.includes("floor") && n.includes("2"));

    const { width, height } = floorLayer;
    const tiles = [];
    const floorSprite = [];
    const wallSprite = [];
    const decoration = [];

    for (let y = 0; y < height; y++) {
        const tileRow = [];
        const floorRow = [];
        const wallRow = [];
        const decorationRow = [];
        for (let x = 0; x < width; x++) {
            const wallTile = lookupTile(wallsLayer.data[y * width + x]);
            const floorTile = lookupTile(floorLayer.data[y * width + x]);

            // The floor layer is captured at every cell, wall or not - a wall cell still has a
            // floor drawn underneath it client-side (many wall sprites have transparent padding
            // so the floor shows through around them), so the room author's actual floor choice
            // there has to survive the conversion, not just the wall's own sprite. A wall cell is
            // allowed to have nothing painted on the floor layer (an outer border wall, say, where
            // nothing will ever be visible underneath the always-opaque border art anyway) - only
            // a non-wall cell requires one, since that's a genuine hole in the room otherwise.
            if (wallTile) {
                tileRow.push("wall");
                wallRow.push(spriteOf(wallTile, usedTilesets));
            } else {
                if (!floorTile) fail(`cell (${x}, ${y}) has neither a wall tile nor a floor tile - looks like a genuine hole in the room, not a stylistic choice. Paint one or the other there.`);
                tileRow.push("floor");
                wallRow.push(null);
            }
            floorRow.push(floorTile ? spriteOf(floorTile, usedTilesets) : null);

            const decorationTile = decorationLayer ? lookupTile(decorationLayer.data[y * width + x]) : null;
            decorationRow.push(decorationTile ? spriteOf(decorationTile, usedTilesets) : null);
        }
        tiles.push(tileRow);
        floorSprite.push(floorRow);
        wallSprite.push(wallRow);
        decoration.push(decorationRow);
    }

    return { width, height, tiles, floorSprite, wallSprite, decoration };
}

function requireField(obj, field, context) {
    const value = obj[field];
    if (value === undefined || value === null || value === "") fail(`${context} is missing '${field}'.`);
    return value;
}

function convertEntityObject(obj, tileWidth, tileHeight) {
    const p = propsOf(obj);
    // Round, not floor: even with "snap to grid" on, Tiled routinely saves an object's position a
    // fraction of a pixel short of the tile boundary it was actually dropped on (e.g. 207.507
    // instead of 208) - floor would silently snap that down to the wrong tile every time, while
    // round tolerates that real-world imprecision and resolves to the tile actually intended.
    const x = Math.round(obj.x / tileWidth);
    const y = Math.round(obj.y / tileHeight);
    const context = `entity object "${obj.name || obj.id}" at (${x}, ${y})`;

    // A blank 'id' auto-generates from the kind plus Tiled's own object id (always present,
    // always unique within the map) - fine to leave blank on every object if you don't care
    // about readable ids; set one yourself only if you want something more memorable.
    const id = p.id || `${obj.class || p.kind || "entity"}_${obj.id}`;
    // Prefers the object's Tiled Class (`class` in a JSON export, `type` in a .tmx - both read
    // into `.class` by the loaders above) - assign a "door"/"enemy"/"chest"/"locked_door"/"npc"
    // Custom Type to the object instead of adding a redundant 'kind' property by hand. Falls back
    // to a plain 'kind' custom property for anyone not using Custom Types.
    const rawKind = obj.class || p.kind;
    if (!rawKind) fail(`${context} has no kind - assign it a Custom Type (Class), or set a 'kind' property.`);
    // "boss" isn't a real entity kind - a boss is just an enemy with a scarier typeId/label. What
    // actually makes a room a boss room is the room's own roomType, not the entity. A separate
    // "Boss" Tiled Class is still useful for your own organization while placing objects, so it's
    // accepted here and normalized to "enemy" rather than forcing you to reuse the plain "Enemy"
    // class for every boss placement.
    const kind = rawKind === "boss" ? "enemy" : rawKind;
    if (!VALID_ENTITY_KINDS.has(kind)) fail(`${context}: kind must be one of ${[...VALID_ENTITY_KINDS].join(", ")} (or "boss"), got "${rawKind}".`);

    const entity = { kind, id, x, y };

    switch (kind) {
        case "enemy":
            entity.typeId = placeholderField(p, "typeId", context);
            entity.label = placeholderField(p, "label", context);
            break;

        case "chest":
            if (p.trapped !== undefined) entity.trapped = Boolean(p.trapped);
            break;

        case "door": {
            const direction = requireField(p, "direction", context);
            if (!VALID_DIRECTIONS.has(direction)) fail(`${context}: 'direction' must be one of ${[...VALID_DIRECTIONS].join(", ")}.`);
            const role = requireField(p, "role", context);
            if (!VALID_ROLES.has(role)) fail(`${context}: 'role' must be one of ${[...VALID_ROLES].join(", ")}.`);
            entity.direction = direction;
            entity.role = role;
            if (p.branch) entity.branch = p.branch;
            if (p.doorKind) {
                if (!VALID_DOOR_KINDS.has(p.doorKind)) fail(`${context}: 'doorKind' must be one of ${[...VALID_DOOR_KINDS].join(", ")}.`);
                entity.doorKind = p.doorKind;
            }
            if (p.targetRoomId) entity.targetRoomId = p.targetRoomId;
            if (p.revealed !== undefined) entity.revealed = Boolean(p.revealed);
            break;
        }

        case "locked_door": {
            const direction = requireField(p, "direction", context);
            if (!VALID_DIRECTIONS.has(direction)) fail(`${context}: 'direction' must be one of ${[...VALID_DIRECTIONS].join(", ")}.`);
            entity.direction = direction;
            entity.targetRoomId = requireField(p, "targetRoomId", context);
            if (p.doorTag) entity.doorTag = p.doorTag;
            break;
        }

        case "npc":
            entity.name = placeholderField(p, "name", context);
            break;
    }

    return entity;
}

function convertEntitiesLayer(map) {
    const layer = findLayer(map, "objectgroup", (n) => n.includes("entit"));
    if (!layer) fail('no "entities" object layer found (an object layer whose name contains "entit" - see the setup notes at the top of this script).');

    return (layer.objects ?? []).map((obj) => convertEntityObject(obj, map.tilewidth, map.tileheight));
}

/** Non-fatal - a tileset with no generated atlas file just means every derived sprite key for it
 * will render as a fallback color client-side until you run generate-tiled-tileset-atlas.mjs
 * (step 0 at the top of this script). The room's structure/doors/entities are still worth
 * converting either way. */
function checkAtlasesExist(usedTilesets) {
    for (const name of usedTilesets) {
        const atlasPath = join(publicAtlasDir, `${name}_tiles.json`);
        if (!existsSync(atlasPath)) {
            warn(`no atlas found for tileset "${name}" (expected ${atlasPath}) - run "node scripts/generate-tiled-tileset-atlas.mjs path/to/${name}.tsx" first, or every ${name}_* sprite in this room renders as a fallback color.`);
        }
    }
}

function main() {
    const { positional, flags } = parseArgs(process.argv.slice(2));
    const mapPath = positional[0];
    if (!mapPath) fail("usage: node scripts/convert-tiled-room.mjs path/to/map.tmx [--out=path.json] [--id=...] [--roomType=...] [--theme=...]");

    const map = loadMap(mapPath);
    const mapProps = propsOf(map);

    const id = flags.id ?? mapProps.id;
    const roomType = flags.roomType ?? mapProps.roomType;
    const theme = flags.theme ?? mapProps.theme;
    if (!id) fail("room 'id' not found - set it as a Map Property in Tiled, or pass --id=...");
    if (!roomType) fail("room 'roomType' not found - set it as a Map Property in Tiled, or pass --roomType=...");
    if (!VALID_ROOM_TYPES.has(roomType)) fail(`roomType must be one of ${[...VALID_ROOM_TYPES].join(", ")}, got "${roomType}".`);
    if (!theme) fail("room 'theme' not found - set it as a Map Property in Tiled, or pass --theme=...");

    const lookupTile = buildTileLookup(map);
    const usedTilesets = new Set();
    const { width, height, tiles, floorSprite, wallSprite, decoration } = convertFloorAndWallsLayers(map, lookupTile, usedTilesets);
    const entities = convertEntitiesLayer(map);

    checkAtlasesExist(usedTilesets);

    // floorSprite carries a derived or explicit sprite for every cell, wall or not (see
    // convertFloorAndWallsLayers) - only null where the floor layer was genuinely left empty
    // under a wall cell (an outer border, typically). wallSprite is null everywhere except wall
    // cells. decorations stays sparse (almost always null) even when the optional floor_2 layer
    // exists.
    const room = { id, type: roomType, width, height, theme, tiles, floorSprites: floorSprite, wallSprites: wallSprite, decorations: decoration, entities };

    const json = JSON.stringify(room, null, 2);
    if (flags.out) {
        writeFileSync(flags.out, json + "\n");
        console.error(`Wrote ${flags.out}`);
    } else {
        console.log(json);
    }
}

main();
