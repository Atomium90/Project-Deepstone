import { rmSync } from "node:fs";
import { defineConfig } from "@playwright/test";

/** The save the end-to-end backend plays on: a file of its own, never the real deepstone.db. It is
 * relative to the backend's working directory, and has no space in it since it goes through sbt's
 * own argument parsing. */
const E2E_DATABASE = "target/e2e.db";

// Every run starts from a brand-new save, the same as CI does, so a spec can rely on one. Only the
// runner process does this: each worker loads this file too, and would otherwise delete the
// database the server is using (TEST_WORKER_INDEX is only set in the workers).
if (process.env.TEST_WORKER_INDEX === undefined) {
    for (const suffix of ["", "-shm", "-wal"]) {
        rmSync(`../deepstone-backend/${E2E_DATABASE}${suffix}`, { force: true });
    }
}

export default defineConfig({
    testDir: "./e2e",
    // Bumped alongside full-run.spec.ts's iteration budget - Normal difficulty's dungeon grew
    // substantially once biomes landed, so a full run now takes noticeably longer end to end.
    timeout: 240_000,
    // Playwright actions have no timeout by default, so a click on an element that never appears
    // would wait out the whole test timeout. 15s makes a stuck step fail with a readable error.
    use: { baseURL: "http://localhost:5173", headless: true, actionTimeout: 15_000 },
    reporter: process.env.CI ? [["list"], ["html", { open: "never" }]] : "list",
    // All the specs share one backend and one database. The first-run spec needs a save with no
    // finished run, and the others finish runs, so it goes first: the other project waits for it.
    projects: [
        { name: "first-run", testMatch: "first-run.spec.ts" },
        { name: "game", testIgnore: "first-run.spec.ts", dependencies: ["first-run"] },
    ],
    webServer: [
        {
            command: "npm run dev",
            url: "http://localhost:5173",
            // The frontend dev server holds no save, so one already running is fine to use.
            reuseExistingServer: !process.env.CI,
            timeout: 30_000,
        },
        {
            // No friendly HTTP route at "/" (the WS route lives at /ws) - `port` waits for the
            // TCP port to accept connections instead of polling for an HTTP response.
            command: `sbt "run --db ${E2E_DATABASE}"`,
            cwd: "../deepstone-backend",
            port: 8080,
            // Never reuse a backend that is already up: it would be the dev one, playing on the
            // real save. With one running, Playwright stops with "already used" instead - stop the
            // dev backend, then run the tests.
            reuseExistingServer: false,
            timeout: 120_000, // sbt's own startup (JVM + compile if needed) can be slow.
        },
    ],
});
