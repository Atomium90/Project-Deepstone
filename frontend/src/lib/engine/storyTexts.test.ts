import { describe, test, expect } from "vitest";
import storyFile from "../../../../deepstone-backend/src/main/resources/data/story.json?raw";
import chaptersFile from "../../../../deepstone-backend/src/main/resources/data/chapters.json?raw";
import enemiesFile from "../../../../deepstone-backend/src/main/resources/data/enemies.json?raw";
import npcsFile from "../../../../deepstone-backend/src/main/resources/data/npcs.json?raw";
import { dictionaries, lookup } from "./i18n";
import { numberedTexts, type TextLookup } from "./sceneText";

/** The longest a line may be: one bubble, a sentence or two very short ones. */
const MAX_LINE_LENGTH = 90;

interface Scene {
    id: string;
    npc: string;
    offersVow?: { type: string };
    requiresRelicRead?: number;
}

interface NpcEntry {
    id: string;
    revealedBy?: string;
    candidate?: boolean;
    epilogue?: boolean;
}

const scenes: Scene[] = JSON.parse(storyFile);
const npcEntries: NpcEntry[] = JSON.parse(npcsFile);
const chapters: { id: number }[] = JSON.parse(chaptersFile);
/** The Devoted of the game: the numbers the enemy catalog gives its Boss types. */
const devotedNumbers: number[] = (JSON.parse(enemiesFile) as { devoted?: number }[])
    .map((enemy) => enemy.devoted)
    .filter((n): n is number => n !== undefined);

/** What the server can ask the god's voice to say: the moments and the tones of `GodVoice`. A death
 * for the first time only has a line in the first tone, the server falls to the next case after it. */
const GOD_MOMENTS = ["start", "death.first", "death.streak", "death.boss", "death.depth", "death.ordinary"];
const GOD_PHASES = ["a", "b", "c"];
/** The first death after an ending has one line per ending, in the last tone only. */
const GOD_END_MOMENTS = ["death.end1", "death.end2", "death.end3"];

/** The cards the server can ask to show: the prologue, the one each chapter earns, the one that
 * announces Hardcore, and the one of each ending. */
const cardIds = ["prologue", ...chapters.map((chapter) => `ch${chapter.id}`), "hardcore", "end1", "end2", "end3"];
const texts = dictionaries.en;
const find: TextLookup = (key) => lookup(dictionaries, "en", key);
const npcs = [...new Set(scenes.map((scene) => scene.npc))];
const storyKeys = Object.keys(texts).filter((key) => key.startsWith("story."));

describe("the texts of the story catalog", () => {
    test("every scene has at least one line", () => {
        for (const scene of scenes) {
            expect(numberedTexts(`story.${scene.id}`, find).length, `scene ${scene.id} has no text`).toBeGreaterThan(0);
        }
    });

    test("every NPC has a name", () => {
        for (const npc of npcs) expect(find(`story.${npc}.name`), `${npc} has no name`).not.toBeNull();
    });

    test("an NPC that gives its name away has the name it gives, and no other NPC has one", () => {
        for (const npc of npcEntries) {
            const hasRevealedName = find(`story.${npc.id}.name_revealed`) !== null;
            expect(hasRevealedName, `${npc.id}: name_revealed`).toBe(npc.revealedBy !== undefined);
        }
    });

    test("every NPC of the story is in the NPC catalog, which says which one gives its name away", () => {
        expect(npcEntries.map((npc) => npc.id).sort()).toEqual([...npcs].sort());
    });

    test("{name} is written only in the lines of an NPC that names the Devoted it is looking for", () => {
        for (const key of storyKeys.filter((k) => texts[k].includes("{name}"))) {
            const npc = key.split(".").slice(1, 3).join(".");
            const devoted = scenes.filter((scene) => scene.npc === npc).find((scene) => scene.requiresRelicRead !== undefined)?.requiresRelicRead;
            expect(devoted, `${key} has {name}, but ${npc} waits for no relic`).toBeDefined();
            expect(find(`story.${npc}.loved`), `${npc} has no loved name for ${key}`).toBe(find(`story.boss.${devoted}.name`));
        }
    });

    test("every scene that offers a vow has its choice and the reply to each answer", () => {
        for (const scene of scenes.filter((s) => s.offersVow)) {
            for (const part of ["choice.accept", "choice.decline", "reply.accept", "reply.decline"]) {
                expect(find(`story.${scene.id}.${part}`), `scene ${scene.id} has no ${part}`).not.toBeNull();
            }
        }
    });

    test("an NPC that offers a vow comments on it kept and failed", () => {
        for (const npc of new Set(scenes.filter((s) => s.offersVow).map((s) => s.npc))) {
            for (const outcome of ["kept", "failed"]) {
                expect(numberedTexts(`story.${npc}.vow.${outcome}`, find).length, `${npc} has nothing to say on a ${outcome} vow`).toBeGreaterThan(0);
            }
        }
    });

    test("every NPC has short lines for each pool, so no visit comes up empty", () => {
        for (const npc of npcs) {
            for (const pool of ["general", "death", "victory"]) {
                expect(numberedTexts(`story.${npc}.idle.${pool}`, find).length, `${npc} has no ${pool} line`).toBeGreaterThan(0);
            }
        }
    });

    test("the numbered lines have no gap, a line after a gap would never be read", () => {
        const numbered = storyKeys.filter((key) => /\.\d+$/.test(key));
        for (const key of numbered) {
            const [, prefix, n] = key.match(/^(.*)\.(\d+)$/)!;
            expect(find(`${prefix}.${Number(n) - 1}`) !== null || Number(n) === 1, `${key} follows a missing line`).toBe(true);
        }
    });

    test(`no line is longer than ${MAX_LINE_LENGTH} characters`, () => {
        for (const key of storyKeys) {
            expect(texts[key].length, `${key} is ${texts[key].length} characters long`).toBeLessThanOrEqual(MAX_LINE_LENGTH);
        }
    });

    test("no text uses a dash or a semicolon, the style of the writing", () => {
        for (const key of storyKeys) {
            expect(texts[key], `${key} has a dash or a semicolon`).not.toMatch(/[—–;]/);
        }
    });

    test("every card of the story has its lines", () => {
        for (const id of cardIds) {
            expect(numberedTexts(`story.card.${id}`, find).length, `card ${id} has no text`).toBeGreaterThan(0);
        }
    });

    test("the god's voice has lines for every moment and tone the server can ask for", () => {
        for (const moment of GOD_MOMENTS) {
            for (const phase of GOD_PHASES) {
                if (moment === "death.first" && phase !== "a") continue;
                expect(numberedTexts(`story.god.${moment}.${phase}`, find).length, `no god line for ${moment} in tone ${phase}`).toBeGreaterThan(0);
            }
        }
    });

    test("every text under story.god belongs to a moment and a tone the server can ask for", () => {
        for (const key of storyKeys.filter((k) => k.startsWith("story.god."))) {
            const [, , ...rest] = key.split(".");
            const phase = rest[rest.length - 2];
            const moment = rest.slice(0, -2).join(".");
            expect([...GOD_MOMENTS, ...GOD_END_MOMENTS], `${key} is a line for a moment the server never asks for`).toContain(moment);
            expect(GOD_PHASES, `${key} is a line in a tone the server never asks for`).toContain(phase);
            if (GOD_END_MOMENTS.includes(moment)) expect(phase, `${key} is not in the last tone`).toBe("c");
        }
    });

    test("the voice has a line for each ending, in the last tone", () => {
        for (const moment of GOD_END_MOMENTS) {
            expect(numberedTexts(`story.god.${moment}.c`, find).length, `no god line for ${moment}`).toBeGreaterThan(0);
        }
    });

    test("every NPC that can take the player's place has a last scene", () => {
        const candidates = npcEntries.filter((npc) => npc.candidate);
        expect(candidates.length).toBeGreaterThan(0);
        for (const npc of candidates) {
            expect(numberedTexts(`story.${npc.id}.end`, find).length, `${npc.id} has no last scene`).toBeGreaterThan(0);
        }
    });

    test("every NPC with something to say about the ending has a line for each ending", () => {
        const speakers = npcEntries.filter((npc) => npc.epilogue);
        expect(speakers.length).toBeGreaterThan(0);
        for (const npc of speakers) {
            for (const ending of ["end1", "end2", "end3"]) {
                expect(numberedTexts(`story.${npc.id}.epilogue.${ending}`, find).length, `${npc.id} says nothing for ${ending}`).toBeGreaterThan(0);
            }
        }
    });

    test("the question of the light has its texts", () => {
        for (const key of ["enter", "replace", "refuse", "nobody", "who", "sure", "back"]) {
            expect(find(`story.ending.${key}`), `story.ending.${key} is missing`).not.toBeNull();
        }
    });

    test("every Devoted has a name, two lines before the fight, one as it falls and a relic inscription", () => {
        for (const n of devotedNumbers) {
            for (const slot of ["name", "pre1", "pre2", "post", "relic"]) {
                expect(find(`story.boss.${n}.${slot}`), `Devoted ${n} has no ${slot}`).not.toBeNull();
            }
        }
    });

    test("the worn-smooth inscription has its text", () => {
        expect(find("story.boss.unreadable")).not.toBeNull();
    });

    test("every text under story.boss belongs to a Devoted of the enemy catalog", () => {
        for (const key of storyKeys.filter((k) => k.startsWith("story.boss."))) {
            const n = key.split(".")[2];
            if (n === "unreadable") continue;
            expect(devotedNumbers.map(String), `${key} belongs to a Devoted no enemy is`).toContain(n);
        }
    });

    test("every text under story.card belongs to a card the story can earn", () => {
        for (const key of storyKeys.filter((k) => k.startsWith("story.card."))) {
            expect(cardIds, `${key} belongs to a card nobody earns`).toContain(key.split(".")[2]);
        }
    });

    test("every text under story. belongs to an NPC of the catalog or to the interface", () => {
        for (const key of storyKeys.filter((k) => k.startsWith("story.npc."))) {
            const npc = key.split(".").slice(1, 3).join(".");
            expect(npcs, `${key} belongs to ${npc}, which has no scene`).toContain(npc);
        }
    });
});
