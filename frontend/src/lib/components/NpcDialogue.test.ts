import { describe, test, expect } from "vitest";
import { render } from "@testing-library/svelte";
import NpcDialogue from "./NpcDialogue.svelte";

describe("NpcDialogue", () => {
    test("renders nothing when dialogue is null", () => {
        const { container } = render(NpcDialogue, { dialogue: null });
        expect(container.querySelector(".dialogue-box")).toBeNull();
    });

    test("shows the NPC name and line when dialogue is present", () => {
        const { container } = render(NpcDialogue, { dialogue: { npcName: "Wren", line: "Careful out there." } });
        expect(container.querySelector(".dialogue-name")?.textContent).toBe("Wren");
        expect(container.querySelector(".dialogue-line")?.textContent).toBe("Careful out there.");
    });
});
