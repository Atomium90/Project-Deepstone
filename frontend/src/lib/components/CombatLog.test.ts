import { describe, test, expect } from "vitest";
import { render } from "@testing-library/svelte";
import CombatLog from "./CombatLog.svelte";

describe("CombatLog", () => {
    test("shows a placeholder line when the log is empty", () => {
        const { container } = render(CombatLog, { log: [] });
        const lines = container.querySelectorAll(".log-line");
        expect(lines.length).toBe(1);
        expect(lines[0].classList.contains("muted")).toBe(true);
        expect(lines[0].textContent).toBe("No events yet.");
    });

    test("renders one line per log entry, in order, without the placeholder", () => {
        const { container } = render(CombatLog, { log: ["You hit the Goblin.", "The Goblin hits back."] });
        const lines = Array.from(container.querySelectorAll(".log-line"));
        expect(lines.map((l) => l.textContent)).toEqual(["You hit the Goblin.", "The Goblin hits back."]);
        expect(lines.some((l) => l.classList.contains("muted"))).toBe(false);
    });
});
