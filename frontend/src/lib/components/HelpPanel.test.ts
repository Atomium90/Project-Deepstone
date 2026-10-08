import { describe, test, expect, beforeEach } from "vitest";
import { render } from "@testing-library/svelte";
import { tick } from "svelte";
import HelpPanel from "./HelpPanel.svelte";
import { HINTS } from "../engine/HintCatalog";
import { dismissHint, resetTutorial, showHint, skipTutorial } from "../engine/HintStore";
import { settings } from "../engine/SettingsStore";

function titles(container: HTMLElement): (string | null)[] {
    return Array.from(container.querySelectorAll(".entry-title")).map((el) => el.textContent);
}

/** Discovers a hint the way a player does: it shows, then it is dismissed. */
function discover(id: string): void {
    showHint(id);
    dismissHint();
}

describe("HelpPanel", () => {
    beforeEach(() => {
        settings.update((s) => ({ ...s, showHints: true }));
        resetTutorial();
    });

    test("says there is nothing yet while no hint was discovered", () => {
        const { container } = render(HelpPanel);
        expect(container.querySelector(".empty")?.textContent).toBe("Nothing here yet. The hints you come across are kept here.");
        expect(container.querySelectorAll(".entry")).toHaveLength(0);
    });

    test("lists a discovered hint with its title and body from the lang file", () => {
        discover("controls");
        const { container } = render(HelpPanel);
        expect(titles(container)).toEqual(["Moving around"]);
        expect(container.querySelector(".entry-body")?.textContent).toContain("Move with WASD");
        expect(container.querySelector(".empty")).toBeNull();
    });

    test("lists hints in the catalog's order, not the order they were discovered in", () => {
        discover("rarity");
        discover("controls");
        const { container } = render(HelpPanel);
        expect(titles(container)).toEqual(["Moving around", "Rarity"]);
    });

    test("leaves out the hints not discovered yet", () => {
        discover("controls");
        const { container } = render(HelpPanel);
        expect(titles(container)).not.toContain("Rarity");
    });

    test("a hint on screen but not dismissed yet is not listed", () => {
        showHint("controls");
        const { container } = render(HelpPanel);
        expect(container.querySelectorAll(".entry")).toHaveLength(0);
    });

    test("after Skip, every hint of the catalog is listed", () => {
        skipTutorial();
        const { container } = render(HelpPanel);
        expect(container.querySelectorAll(".entry")).toHaveLength(HINTS.length);
    });

    test("a hint discovered while the panel is open shows up at once", async () => {
        const { container } = render(HelpPanel);
        discover("combat");
        await tick();
        expect(titles(container)).toEqual(["Combat"]);
    });

    test("emptying the journal with a reset brings the empty message back", async () => {
        discover("controls");
        const { container } = render(HelpPanel);
        resetTutorial();
        await tick();
        expect(container.querySelector(".empty")).not.toBeNull();
    });
});
