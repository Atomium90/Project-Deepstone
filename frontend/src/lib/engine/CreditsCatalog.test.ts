import { describe, test, expect } from "vitest";
import { CREDITS, creditsIn } from "./CreditsCatalog";
import { dictionaries } from "./i18n";
// The file in the repository root that lists the same packs for people reading the code.
import creditsFile from "../../../../CREDITS.md?raw";

describe("CreditsCatalog", () => {
    test("every entry has a distinct id", () => {
        const ids = CREDITS.map((entry) => entry.id);
        expect(new Set(ids).size).toBe(ids.length);
    });

    test("every entry names its pack and its author", () => {
        for (const entry of CREDITS) {
            expect(entry.name.trim(), entry.id).not.toBe("");
            expect(entry.author.trim(), entry.id).not.toBe("");
        }
    });

    test("every link is a secure web address", () => {
        for (const entry of CREDITS) {
            for (const link of [entry.url, entry.licenseUrl]) {
                if (link !== undefined) expect(link, entry.id).toMatch(/^https:\/\/[^\s]+$/);
            }
        }
    });

    test("every entry says what the pack is used for", () => {
        for (const { id } of CREDITS) {
            expect(dictionaries.en[`credits.${id}.use`], `credits.${id}.use`).toBeTruthy();
        }
    });

    test("an entry with no standard license has its own terms written, and no other entry does", () => {
        for (const entry of CREDITS) {
            const terms = dictionaries.en[`credits.${entry.id}.terms`];
            if (entry.license === null) expect(terms, `credits.${entry.id}.terms`).toBeTruthy();
            else expect(terms, `credits.${entry.id}.terms`).toBeUndefined();
        }
    });

    test("a standard license names where to read it", () => {
        for (const entry of CREDITS) {
            if (entry.license !== null) expect(entry.licenseUrl, entry.id).toBeDefined();
        }
    });

    test("a modified pack has its note, and a note belongs to a modified pack", () => {
        for (const entry of CREDITS) {
            const note = dictionaries.en[`credits.${entry.id}.note`];
            if (entry.hasNote) expect(note, `credits.${entry.id}.note`).toBeTruthy();
            else expect(note, `credits.${entry.id}.note`).toBeUndefined();
        }
    });

    test("no credit text in the English file belongs to a pack missing from the catalog", () => {
        const ids = new Set(CREDITS.map((entry) => entry.id));
        const textIds = Object.keys(dictionaries.en)
            .map((key) => key.match(/^credits\.(.+)\.(use|note|terms)$/)?.[1])
            .filter((id): id is string => id !== undefined);
        expect(textIds.filter((id) => !ids.has(id))).toEqual([]);
    });

    test("the two lists together hold every entry", () => {
        expect(creditsIn("art").length + creditsIn("audio").length).toBe(CREDITS.length);
        expect(creditsIn("audio").length).toBeGreaterThan(0);
    });

    test("CREDITS.md lists every pack, with its author and its link", () => {
        for (const entry of CREDITS) {
            expect(creditsFile, `${entry.id}: name`).toContain(entry.name);
            if (entry.author !== "Deepstone") expect(creditsFile, `${entry.id}: author`).toContain(entry.author);
            if (entry.url !== undefined) expect(creditsFile, `${entry.id}: link`).toContain(entry.url);
            if (entry.licenseUrl !== undefined) expect(creditsFile, `${entry.id}: license link`).toContain(entry.licenseUrl);
        }
    });
});
