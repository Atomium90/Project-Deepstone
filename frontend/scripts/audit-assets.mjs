// Lists which files under public/sprites and public/audio the game really references, and which
// it does not. Run it with the assets in place (see sync-assets.ps1 at the repo root), after adding
// or removing an asset, or before a release:
//   npm run audit:assets                 summary, plus every referenced file that is missing
//   npm run audit:assets -- --unused     also lists every file nothing refers to
//   npm run audit:assets -- --used       also lists every referenced file and where it is named
//
// A file counts as referenced when its path is written out in the source (components, constants,
// styles), when an atlas file names it as a sheet, or when AudioManager.ts lists its file name. A
// path assembled at run time from pieces written in other places would be missed, so read the
// unused list before deleting anything from it.
//
// Exits with 1 when a referenced file is missing (a typo in a path, like a file name that the pack
// does not have), and with 2 when there are no assets on disk to check.

import { existsSync, readdirSync, readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import path from "node:path";

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const frontendDir = path.join(__dirname, "..");
const publicDir = path.join(frontendDir, "public");
const srcDir = path.join(frontendDir, "src");

function walk(dir) {
    if (!existsSync(dir)) return [];
    return readdirSync(dir, { withFileTypes: true }).flatMap((entry) => {
        const full = path.join(dir, entry.name);
        return entry.isDirectory() ? walk(full) : [full];
    });
}

const relativeToPublic = (file) => path.relative(publicDir, file).split(path.sep).join("/");
const relativeToSrc = (file) => path.relative(srcDir, file).split(path.sep).join("/");

/** Every file on disk under sprites and audio, as "sprites/..." and "audio/..." paths. */
function filesOnDisk() {
    return new Set([...walk(path.join(publicDir, "sprites")), ...walk(path.join(publicDir, "audio"))].map(relativeToPublic));
}

/** Map of referenced path -> where it is named. */
function referencedFiles(onDisk) {
    const used = new Map();
    const note = (file, where) => {
        if (!used.has(file)) used.set(file, where);
    };

    // Paths written out in the source. Tests are left out: they are not the game.
    const sources = walk(srcDir).filter((file) => /\.(ts|svelte|css)$/.test(file) && !/\.test\./.test(file));
    for (const file of sources) {
        const text = readFileSync(file, "utf8");
        for (const match of text.matchAll(/\/((?:sprites|audio)\/[A-Za-z0-9_./ -]+?\.(?:png|ogg|jpg|webp))/g)) {
            note(match[1], relativeToSrc(file));
        }
    }

    // Sheets named by the atlas files.
    const atlasDir = path.join(publicDir, "atlas");
    for (const name of existsSync(atlasDir) ? readdirSync(atlasDir) : []) {
        const visit = (value) => {
            if (value && typeof value === "object") {
                if (typeof value.sheet === "string") note(value.sheet.replace(/^\//, ""), `atlas/${name}`);
                Object.values(value).forEach(visit);
            }
        };
        visit(JSON.parse(readFileSync(path.join(atlasDir, name), "utf8")));
    }

    // File names listed in AudioManager.ts: the folder is the one that holds the file.
    const audioManager = readFileSync(path.join(srcDir, "lib", "engine", "AudioManager.ts"), "utf8");
    for (const match of audioManager.matchAll(/"([A-Za-z0-9_.-]+\.ogg)"/g)) {
        const sfx = `audio/sfx/${match[1]}`;
        const music = `audio/music/${match[1]}`;
        if (onDisk.has(sfx)) note(sfx, "lib/engine/AudioManager.ts");
        else if (onDisk.has(music)) note(music, "lib/engine/AudioManager.ts");
        else note(`audio/?/${match[1]}`, "lib/engine/AudioManager.ts");
    }
    return used;
}

/** The folder a file is counted under: the colour or kind folder for the interface sheets. */
function groupOf(file) {
    return file.split("/").slice(0, file.startsWith("sprites/ui/") ? 3 : 2).join("/");
}

function main() {
    const wantUsed = process.argv.includes("--used");
    const wantUnused = process.argv.includes("--unused");

    const onDisk = filesOnDisk();
    if (onDisk.size === 0) {
        console.error("No asset files under public/sprites or public/audio. Run sync-assets.ps1 first.");
        process.exit(2);
    }

    const used = referencedFiles(onDisk);
    const usedHere = [...onDisk].filter((file) => used.has(file)).sort();
    const unused = [...onDisk].filter((file) => !used.has(file)).sort();
    const missing = [...used.keys()].filter((file) => !onDisk.has(file)).sort();

    const count = (files) => files.reduce((acc, file) => ({ ...acc, [groupOf(file)]: (acc[groupOf(file)] ?? 0) + 1 }), {});
    const usedCount = count(usedHere);
    const unusedCount = count(unused);
    console.log(`${"folder".padEnd(28)} ${"used".padStart(6)} ${"unused".padStart(7)}`);
    for (const group of [...new Set([...Object.keys(usedCount), ...Object.keys(unusedCount)])].sort()) {
        console.log(`${group.padEnd(28)} ${String(usedCount[group] ?? 0).padStart(6)} ${String(unusedCount[group] ?? 0).padStart(7)}`);
    }
    console.log(`\n${onDisk.size} files on disk: ${usedHere.length} referenced, ${unused.length} not referenced.`);

    if (wantUsed) {
        console.log("\nReferenced files:");
        usedHere.forEach((file) => console.log(`  ${file}  (${used.get(file)})`));
    }
    if (wantUnused) {
        console.log("\nFiles nothing refers to:");
        unused.forEach((file) => console.log(`  ${file}`));
    }

    if (missing.length > 0) {
        console.error(`\n${missing.length} referenced file(s) are missing from disk:`);
        missing.forEach((file) => console.error(`  ${file}  (named in ${used.get(file)})`));
        process.exit(1);
    }
    console.log("Every referenced file is on disk.");
}

main();
