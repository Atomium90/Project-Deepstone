import { defineConfig } from "vitest/config";
import { svelte } from "@sveltejs/vite-plugin-svelte";

export default defineConfig({
    plugins: [svelte({ hot: false })],
    resolve: {
        conditions: process.env.VITEST ? ["browser"] : [],
    },
    test: {
        environment: "jsdom",
        include: ["src/**/*.test.ts"],
        coverage: {
            provider: "v8",
            reporter: ["text", "lcov"],
            reportsDirectory: "coverage",
            include: ["src/**/*.ts", "src/**/*.svelte"],
            exclude: ["src/**/*.d.ts", "src/lib/engine/protocol.ts"],
        },
    },
});
