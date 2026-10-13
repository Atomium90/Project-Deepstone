import { describe, test, expect } from "vitest";
import storyFile from "../../../../deepstone-backend/src/main/resources/data/story.json?raw";
import chaptersFile from "../../../../deepstone-backend/src/main/resources/data/chapters.json?raw";
import { dictionaries, lookup } from "./i18n";
import { numberedTexts, type TextLookup } from "./sceneText";

/** The longest a line may be: one bubble, a sentence or two very short ones. */
const MAX_LINE_LENGTH = 90;

interface Scene {
    id: string;
    npc: string;
    offersVow?: { type: string };
}

const scenes: Scene[] = JSON.parse(storyFile);
const chapters: { id: number }[] = JSON.parse(chaptersFile);
/** The cards the server can ask to show: the prologue, and the one each chapter earns. */
const cardIds = ["prologue", ...chapters.map((chapter) => `ch${chapter.id}`)];
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
