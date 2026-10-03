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
//      id        (string)  just the part that's unique within this theme+roomType, e.g. "012" -
//                           the real rooms.json id is built as "{theme}_{roomType}_{id}"
//                           (e.g. "dungeon_combat_012"). An already-full id (already starting with
//                           that exact prefix) is also accepted unchanged, for older rooms.
//      roomType  (string)  one of: combat, loot, rest, boss, vault, miniboss, fork, sanctuary
//      theme     (string)  e.g. "dungeon", "darkDungeon"
//    Can't be bothered setting these before a quick test conversion? Pass --id=/--roomType=/
//    --theme= on the command line instead - see USAGE below.
//
// 3. 3-5 tile/object layers, bottom to top - floor, walls, an optional floor_2 and/or wall_2, then
//    entities.
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
//      walls layer (name contains "wall", but not "2") - PARTIAL coverage: paint a wall tile only
//      where a wall actually is, and leave every other cell empty. An empty cell here simply means
//      "floor shows through, nothing on top" - it's not an error, it's the normal case.
//      floor_2 layer (name contains both "floor" and "2") - OPTIONAL, sparse. For a sprite that
//      has to sit on top of the real floor/wall tile rather than replace it (a Tiled tile layer
//      only holds one tile per cell, so something like a column's top/bottom cap - which needs
//      the ordinary floor tile to stay visible underneath it - can't be painted directly onto
//      "floor" without destroying it). Leave every cell empty except the few that genuinely need
//      this; most rooms won't need this layer at all, and it's fine to not create it.
//      wall_2 layer (name contains both "wall" and "2") - OPTIONAL, sparse. For a cell that needs
//      to become a *real* wall (blocks movement) but isn't part of the primary walls layer's own
//      shape - e.g. an obstacle whose art overlaps into a neighboring cell for perspective reasons,
//      where that neighboring cell needs actual collision, not just a floor_2 sprite drawn on top
//      of walkable floor. The primary walls layer wins if a cell is somehow painted on both.
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
//    Tiled doesn't matter at all (the real game never looks at it).
//    Either the Insert Point tool (recommended - one click per object, no dragging) or Insert
//    Rectangle sized to exactly one tile both resolve to the correct (x, y) with "snap to grid"
//    on - the two tools snap to different anchors (a Point to the cell's center, a Rectangle to
//    its corner), and this script accounts for both correctly. Don't drag a Rectangle to some
//    other size "to see it better" - an arbitrarily-sized rectangle's corner no longer reliably
//    lands on a tile boundary, which is exactly the kind of mistake Point avoids entirely.
//
//    Recommended: set up a Custom Type (View > Custom Types Editor) per kind - "door", "enemy",
//    "chest", "locked_door", "npc", "sanctuary", plus "boss"/"mini_boss"/"lightHalo" (aliases that
//    normalize to a real kind - see ENTITY_KIND_ALIASES below) - each pre-populated with that
//    kind's own fields below. Assign the right Class to each object
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
//        boss, mini_boss: same fields as enemy - aliases, not real entity kinds (see above).
//        chest:        trapped (bool, optional, default false) - leave it unset: chests are
//                      trapped at random when a run's dungeon is built (see DungeonBuilder's
//                      rollTrappedChests). Set it to true only to force one chest to always be
//                      trapped.
//        door:         direction (UP|DOWN|LEFT|RIGHT), role (prev|next) - both required; branch
//                      (string, optional - only for a Fork room's two distinct exits)
//        locked_door:  direction, targetRoomId (required), doorTag (string, optional)
//        npc:          name (string, OPTIONAL - "TODO" placeholder + warning if left blank, must
//                      eventually match a name in npcs.json for dialogue to resolve)
//        sanctuary:    no extra fields - id/x/y only. The single object a Sanctuary room needs is
//                      typically the halo sprite's own placement - see "lightHalo" above.
//
// ============================================================================================
// USAGE
// ============================================================================================
//   node scripts/convert-tiled-room.mjs path/to/map.tmx
//   node scripts/convert-tiled-room.mjs path/to/map.tmx --out=path/to/room.json
//   node scripts/convert-tiled-room.mjs path/to/map.tmx --id=012 --theme=dungeon
//
// Without --out, writes to deepstone-backend/debug-rooms/<map's own filename>.json (creating that
// folder if needed), inside the same subfolder the map has in the Tiled project (a map in
// Tiled/darkDungeon/ goes to debug-rooms/darkDungeon/) - the hub's "Debug Rooms" dev tool reads
// straight out of there, so a plain conversion is immediately loadable in a live session. A
// tileset file stays in the Tiled project folder even for a map in a subfolder: it is looked for in
// each parent folder up to the project folder when it isn't next to the map. This script never touches rooms.json
// directly, on purpose: it's hand-curated content, not a build artifact to overwrite - once a
// debug-rooms preview looks right, paste it into rooms.json's array yourself. --id/--roomType/
// --theme override whatever the map's own Map Properties say, mainly useful for a quick test
// conversion before you've set those up. Prints one warning per tileset used in the room that
// doesn't have a matching public/atlas/{tilesetName}_tiles.json yet (see step 0 above) - the room
// still converts (every cell still gets its derived sprite key), it just won't render as anything
// but a fallback color client-side until that atlas exists.

import { readFileSync, writeFileSync, existsSync, mkdirSync, readdirSync } from "node:fs";
import { dirname, resolve, extname, join, basename, relative } from "node:path";
import { fileURLToPath } from "node:url";
import { parseXmlDoc, readPropertiesEl } from "./lib/tiled-xml.mjs";

const __dirname = dirname(fileURLToPath(import.meta.url));
const publicAtlasDir = join(__dirname, "..", "public", "atlas");
const defaultDebugRoomsDir = join(__dirname, "..", "..", "deepstone-backend", "debug-rooms");

const VALID_ROOM_TYPES = new Set(["combat", "loot", "rest", "boss", "vault", "miniboss", "fork", "sanctuary"]);
const VALID_ENTITY_KINDS = new Set(["enemy", "chest", "door", "locked_door", "npc", "sanctuary"]);
const VALID_DIRECTIONS = new Set(["UP", "DOWN", "LEFT", "RIGHT"]);
const VALID_ROLES = new Set(["prev", "next"]);
const VALID_DOOR_KINDS = new Set(["normal", "trapped", "secret"]);

// Not real entity kinds in their own right - purely organizational Tiled Classes that normalize
// to a real kind. "boss"/"mini_boss" are just an enemy with a scarier typeId/label (what actually
// makes a room a boss/miniboss room is the room's own roomType, not the entity) - a separate Class
// is still useful while placing objects, rather than reusing the plain "Enemy" class every time.
// "lightHalo" is the Sanctuary's own halo sprite sheet's filename (see
// public/sprites/entities/sanctuary/lightHalo_sheet.png) - naming the object after the asset it
// visually marks reads naturally in Tiled, even though the one object placed there is the actual
// Sanctuary trigger entity itself, not a separate decoration.
const ENTITY_KIND_ALIASES = { boss: "enemy", mini_boss: "enemy", lightHalo: "sanctuary" };

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

/** The Tiled project folder (the one holding a `*.tiled-project` file), found by walking up from
 * `dir`, or null when the map isn't inside a Tiled project. */
function findTiledProjectRoot(dir) {
    let current = resolve(dir);
    for (;;) {
        try {
            if (readdirSync(current).some((f) => f.endsWith(".tiled-project"))) return current;
        } catch {
            // an unreadable folder just means keep walking up
        }
        const parent = dirname(current);
        if (parent === current) return null;
        current = parent;
    }
}

/** Where a tileset `source` really is. Tiled writes it relative to the map's own folder, so a map
 * moved into a subfolder by hand (outside Tiled) still says "dungeon.tsx" while the shared tileset
 * file stayed in the project folder. When it isn't next to the map, each parent folder is tried up
 * to the Tiled project folder (Tiled itself rewrites the path the next time the map is saved). */
function resolveTilesetPath(baseDir, source) {
    const direct = resolve(baseDir, source);
    if (existsSync(direct)) return direct;

    const root = findTiledProjectRoot(baseDir);
    if (!root) return direct;
    let dir = resolve(baseDir);
    while (dir !== root && dirname(dir) !== dir) {
        dir = dirname(dir);
        const candidate = resolve(dir, basename(source));
        if (existsSync(candidate)) {
            warn(`tileset "${source}" isn't next to the map, using "${candidate}" - Tiled fixes the reference the next time this map is saved.`);
            return candidate;
        }
    }
    return direct;
}

/** One <tileset firstgid=".."> reference from inside a <map> - either embedded (the tileset's own
 * content sits right there as children) or external (a `source="foo.tsx"` pointer, resolved
 * relative to the .tmx's own folder, see resolveTilesetPath - the normal Tiled setup, one shared
 * tileset file per art pack reused across every map, rather than duplicating it into each room). */
function loadTileset(tilesetRefEl, baseDir) {
    const firstgid = Number(tilesetRefEl.getAttribute("firstgid"));
    const source = tilesetRefEl.getAttribute("source");
    if (!source) return { firstgid, ...parseTilesetElement(tilesetRefEl) };

    const tsxPath = resolveTilesetPath(baseDir, source);
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
                // A Point-tool object has no width/height attribute at all in Tiled's XML (as
                // opposed to a Rectangle sized to 0, which doesn't happen via the UI) - defaulting
                // to 0 here reproduces that same "no size" shape for the point-vs-rectangle check
                // in convertEntityObject below.
                width: Number(obj.getAttribute("width") ?? 0),
                height: Number(obj.getAttribute("height") ?? 0),
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
    const wallsLayer = findLayer(map, "tilelayer", (n) => n.includes("wall") && !n.includes("2"));
    if (!wallsLayer) fail('no "walls" tile layer found (a tile layer whose name contains "wall" - see the setup notes at the top of this script).');
    // Optional - a sparse extra tile layer for anything that has to sit on top of the real floor/
    // wall tile rather than replace it (see the setup notes' point 3 for why).
    const decorationLayer = findLayer(map, "tilelayer", (n) => n.includes("floor") && n.includes("2"));
    // Optional - a second, sparse wall layer for a cell that needs real collision (blocks movement)
    // but whose sprite has to sit on top of the primary wall/floor tile rather than replace it - the
    // same "composite, don't replace" need decorationLayer already solves, just for a cell that also
    // needs to become a wall. A wall_2 cell always counts as a wall for collision (even where the
    // primary walls layer has nothing painted); its sprite feeds into `decoration` below rather than
    // `wallSprite`, so it renders as an overlay on top of whatever the primary walls layer resolved
    // there, not instead of it.
    const walls2Layer = findLayer(map, "tilelayer", (n) => n.includes("wall") && n.includes("2"));

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
            const wall2Tile = walls2Layer ? lookupTile(walls2Layer.data[y * width + x]) : null;
            const floorTile = lookupTile(floorLayer.data[y * width + x]);

            // The floor layer is captured at every cell, wall or not - a wall cell still has a
            // floor drawn underneath it client-side (many wall sprites have transparent padding
            // so the floor shows through around them), so the room author's actual floor choice
            // there has to survive the conversion, not just the wall's own sprite. A wall cell is
            // allowed to have nothing painted on the floor layer (an outer border wall, say, where
            // nothing will ever be visible underneath the always-opaque border art anyway) - only
            // a non-wall cell requires one, since that's a genuine hole in the room otherwise.
            //
            // wall_2 plays two different roles depending on whether the primary walls layer also
            // has something at this cell: if it does, wall_2 is an overlay on top of that real wall
            // (goes to decoration below, not wallRow - a null wallRow override already means "use
            // the theme's default wall", not "nothing painted", so it must never overwrite a real
            // wallTile). If the primary layer has nothing here, wall_2 *is* the wall - its own
            // sprite gets promoted into wallRow directly, not decoration, so it renders as its own
            // (correctly themed) tile instead of falling back to Renderer.ts's theme-unaware default
            // wall sprite with wall_2's sprite floating on top of that mismatched fallback.
            let wall2AsDecoration = null;
            if (wallTile) {
                tileRow.push("wall");
                wallRow.push(spriteOf(wallTile, usedTilesets));
                wall2AsDecoration = wall2Tile;
            } else if (wall2Tile) {
                tileRow.push("wall");
                wallRow.push(spriteOf(wall2Tile, usedTilesets));
            } else {
                if (!floorTile) fail(`cell (${x}, ${y}) has neither a wall tile nor a floor tile - looks like a genuine hole in the room, not a stylistic choice. Paint one or the other there.`);
                tileRow.push("floor");
                wallRow.push(null);
            }
            floorRow.push(floorTile ? spriteOf(floorTile, usedTilesets) : null);

            // decoration composites on top of whatever floor/wall the cell already resolved to. The
            // primary decoration source (floor_2) wins if a cell somehow has both that and a wall_2
            // overlay; the two are for different purposes and shouldn't normally collide.
            const decorationTile =
                (decorationLayer ? lookupTile(decorationLayer.data[y * width + x]) : null) ?? wall2AsDecoration;
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
    // One rule, regardless of tool or size: find the object's own center, then floor that onto
    // the tile grid. A Point has no width/height (Tiled never writes them for this tool), so its
    // "center" is just its own x/y - and Tiled already snaps a Point to the center of whichever
    // cell it marks, not a corner, so dividing by the tile size lands just past a ".5" every time
    // (e.g. a point centered on column 5 saves as roughly x=88, i.e. 5.5 tiles) - floor recovers
    // the cell the point actually marks. A Rectangle's center is x + width/2 - this is exactly
    // what makes the same floor-the-center formula correct regardless of size: a 1-tile door
    // (center lands mid-tile, same ".5" shape as a Point) and a multi-tile object like the
    // Sanctuary's halo marker (center lands wherever the rectangle was actually centered,
    // whichever tile that happens to fall in) both resolve correctly with no special-casing - a
    // plain corner-anchored read, which is what this looked like before this was unified, only
    // ever worked by coincidence for an exactly-1-tile rectangle, and silently gave the wrong
    // tile for anything bigger.
    const centerX = obj.x + obj.width / 2;
    const centerY = obj.y + obj.height / 2;
    const x = Math.floor(centerX / tileWidth);
    const y = Math.floor(centerY / tileHeight);
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
    const kind = ENTITY_KIND_ALIASES[rawKind] ?? rawKind;
    if (!VALID_ENTITY_KINDS.has(kind)) {
        const aliases = Object.keys(ENTITY_KIND_ALIASES).map((a) => `"${a}"`).join("/");
        fail(`${context}: kind must be one of ${[...VALID_ENTITY_KINDS].join(", ")} (or ${aliases}), got "${rawKind}".`);
    }

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

/** The subfolder of debug-rooms a map's output goes to: the map's own folder relative to the Tiled
 * project folder, so `Tiled/darkDungeon/x.tmx` lands in `debug-rooms/darkDungeon/x.json`. A map
 * straight in the project folder, or outside any Tiled project, lands in debug-rooms itself. */
function debugRoomSubdir(mapPath) {
    const mapDir = dirname(resolve(mapPath));
    const root = findTiledProjectRoot(mapDir);
    if (!root) return "";
    const rel = relative(root, mapDir);
    return rel.startsWith("..") ? "" : rel;
}

function main() {
    const { positional, flags } = parseArgs(process.argv.slice(2));
    const mapPath = positional[0];
    if (!mapPath) fail("usage: node scripts/convert-tiled-room.mjs path/to/map.tmx [--out=path.json] [--id=...] [--roomType=...] [--theme=...]");

    const map = loadMap(mapPath);
    const mapProps = propsOf(map);

    const idProp = flags.id ?? mapProps.id;
    const roomType = flags.roomType ?? mapProps.roomType;
    const theme = flags.theme ?? mapProps.theme;
    if (!idProp) fail("room 'id' not found - set it as a Map Property in Tiled, or pass --id=...");
    if (!roomType) fail("room 'roomType' not found - set it as a Map Property in Tiled, or pass --roomType=...");
    if (!VALID_ROOM_TYPES.has(roomType)) fail(`roomType must be one of ${[...VALID_ROOM_TYPES].join(", ")}, got "${roomType}".`);
    if (!theme) fail("room 'theme' not found - set it as a Map Property in Tiled, or pass --theme=...");

    // 'id' only needs to be whatever is unique within this theme+roomType (e.g. "001") - the full,
    // globally-unique rooms.json id is built here as "{theme}_{roomType}_{id}". Still accepts an
    // already-full id unchanged (if it already starts with that exact prefix) so older rooms
    // authored before this convention don't need their Tiled file edited to keep converting.
    const idPrefix = `${theme}_${roomType}_`;
    const id = idProp.startsWith(idPrefix) ? idProp : `${idPrefix}${idProp}`;

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
    // Defaults to deepstone-backend/debug-rooms/<same subfolder and name as the source map> - the
    // hub's "Debug Rooms" dev tool (see README.md) reads straight out of that folder tree, so
    // converting a map and previewing it in a live session needs no --out bookkeeping for the common
    // case. --out still overrides this for a one-off custom destination.
    const outPath = flags.out ?? join(defaultDebugRoomsDir, debugRoomSubdir(mapPath), `${basename(mapPath, extname(mapPath))}.json`);
    mkdirSync(dirname(outPath), { recursive: true });
    writeFileSync(outPath, json + "\n");
    console.error(`Wrote ${outPath}`);
}

main();
