import { describe, test, expect } from "vitest";
import { render } from "@testing-library/svelte";
import CreditsPanel from "./CreditsPanel.svelte";
import { CREDITS } from "../engine/CreditsCatalog";

function entryNamed(container: HTMLElement, name: string): HTMLElement {
    const entry = Array.from(container.querySelectorAll<HTMLElement>(".entry")).find(
        (el) => el.querySelector(".entry-name")?.textContent?.trim() === name,
    );
    if (!entry) throw new Error(`no credit entry named ${name}`);
    return entry;
}

describe("CreditsPanel", () => {
    test("opens with a thank-you line and the two lists", () => {
        const { container } = render(CreditsPanel);
        expect(container.querySelector(".intro")?.textContent).toContain("Thank you");
        const titles = Array.from(container.querySelectorAll(".group-title")).map((el) => el.textContent);
        expect(titles).toEqual(["Art", "Music and sound"]);
    });

    test("shows one entry for every pack of the catalog", () => {
        const { container } = render(CreditsPanel);
        expect(container.querySelectorAll(".entry")).toHaveLength(CREDITS.length);
    });

    test("a pack name links to the pack's page, opened without handing the game page over", () => {
        const { container } = render(CreditsPanel);
        const link = entryNamed(container, "Kyrise's 16x16 RPG Icon Pack").querySelector<HTMLAnchorElement>(".entry-name a")!;
        expect(link.href).toBe("https://kyrise.itch.io/kyrises-free-16x16-rpg-icon-pack");
        expect(link.target).toBe("_blank");
        expect(link.rel).toContain("noopener");
        expect(link.rel).toContain("noreferrer");
    });

    test("every link in the panel is a secure address that opens in a new tab safely", () => {
        const { container } = render(CreditsPanel);
        const links = Array.from(container.querySelectorAll<HTMLAnchorElement>("a"));
        expect(links.length).toBeGreaterThan(CREDITS.length);
        for (const link of links) {
            expect(link.href, link.textContent ?? "").toMatch(/^https:\/\//);
            expect(link.target).toBe("_blank");
            expect(link.rel).toContain("noopener");
        }
    });

    test("shows the author and, for a standard license, its name as a link to the license text", () => {
        const { container } = render(CreditsPanel);
        const entry = entryNamed(container, "Kyrise's 16x16 RPG Icon Pack");
        expect(entry.querySelector(".entry-meta")?.textContent).toContain("by Kyrise");
        const license = entry.querySelector<HTMLAnchorElement>("a.license")!;
        expect(license.textContent).toBe("CC BY 4.0");
        expect(license.href).toBe("https://creativecommons.org/licenses/by/4.0/");
    });

    test("a pack with terms of its own shows them instead of a license", () => {
        const { container } = render(CreditsPanel);
        const entry = entryNamed(container, "Pixel Crawler");
        expect(entry.querySelector(".terms")?.textContent).toBe("Free for commercial use, credit appreciated.");
        expect(entry.querySelector(".license")).toBeNull();
    });

    test("says what each pack is used for", () => {
        const { container } = render(CreditsPanel);
        expect(entryNamed(container, "Pixel Crawler").querySelector(".entry-use")?.textContent).toBe("The enemy sprites.");
    });

    test("a modified pack carries its note, and the others have none", () => {
        const { container } = render(CreditsPanel);
        expect(entryNamed(container, "DungeonTileset II").querySelector(".entry-note")?.textContent).toContain("realigned");
        expect(entryNamed(container, "UI Pack").querySelector(".entry-note")).toBeNull();
        expect(container.querySelectorAll(".entry-note")).toHaveLength(CREDITS.filter((entry) => entry.hasNote).length);
    });

    test("a work with no page of its own is named without a link", () => {
        const { container } = render(CreditsPanel);
        const entry = entryNamed(container, "Sanctuary halo");
        expect(entry.querySelector(".entry-name a")).toBeNull();
        expect(entry.querySelector(".terms")?.textContent).toContain("AI tool");
    });
});
