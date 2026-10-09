import { describe, test, expect } from "vitest";
import { render, screen } from "@testing-library/svelte";
import StatBar from "./StatBar.svelte";
import { RESOURCE_BAR_COLORS } from "../engine/constants";

describe("StatBar", () => {
    test("renders the label and the current/max value", () => {
        render(StatBar, { label: "HP", current: 30, max: 100, color: "#c0392b" });
        expect(screen.getByText("HP")).toBeTruthy();
        expect(screen.getByText("30 / 100")).toBeTruthy();
    });

    test("fills proportionally to current/max", () => {
        const { container } = render(StatBar, { label: "HP", current: 25, max: 100, color: "#c0392b" });
        const bar = container.querySelector(".bar") as HTMLElement;
        expect(bar.style.width).toBe("25%");
    });

    test("does not divide by zero when max is 0", () => {
        const { container } = render(StatBar, { label: "HP", current: 0, max: 0, color: "#c0392b" });
        const bar = container.querySelector(".bar") as HTMLElement;
        expect(bar.style.width).toBe("0%");
    });

    test("uses a sprite fill for a known bar color", () => {
        const { container } = render(StatBar, { label: "HP", current: 50, max: 100, color: "#c0392b" });
        const bar = container.querySelector(".bar") as HTMLElement;
        expect(bar.classList.contains("sprite-fill")).toBe(true);
        expect(bar.style.backgroundImage).toContain("barRed_horizontalMid.png");
    });

    test("every class resource bar has its own sprite fill, the Mage's blue Mana bar included", () => {
        // The blue bar of the Kenney pack is named barBlue_horizontalBlue.png, not ..._horizontalMid.png.
        const expected = [
            [RESOURCE_BAR_COLORS.warrior, "barRed_horizontalMid.png"],
            [RESOURCE_BAR_COLORS.archer, "barGreen_horizontalMid.png"],
            [RESOURCE_BAR_COLORS.mage, "barBlue_horizontalBlue.png"],
        ] as const;
        for (const [color, file] of expected) {
            const { container } = render(StatBar, { label: "Resource", current: 40, max: 80, color });
            const bar = container.querySelector(".bar") as HTMLElement;
            expect(bar.classList.contains("sprite-fill"), color).toBe(true);
            expect(bar.style.backgroundImage, color).toContain(file);
        }
    });

    test("falls back to a flat CSS fill for an unrecognized color", () => {
        const { container } = render(StatBar, { label: "HP", current: 50, max: 100, color: "#ffffff" });
        const bar = container.querySelector(".bar") as HTMLElement;
        expect(bar.classList.contains("sprite-fill")).toBe(false);
        expect(bar.style.background).toBe("rgb(255, 255, 255)");
    });
});
