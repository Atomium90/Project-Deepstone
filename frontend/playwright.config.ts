import { defineConfig } from "@playwright/test";

export default defineConfig({
    testDir: "./e2e",
    timeout: 120_000,
    use: { baseURL: "http://localhost:5173", headless: true },
    reporter: process.env.CI ? [["list"], ["html", { open: "never" }]] : "list",
    webServer: [
        {
            command: "npm run dev",
            url: "http://localhost:5173",
            reuseExistingServer: !process.env.CI,
            timeout: 30_000,
        },
        {
            // No friendly HTTP route at "/" (the WS route lives at /ws) - `port` waits for the
            // TCP port to accept connections instead of polling for an HTTP response.
            command: "sbt run",
            cwd: "../deepstone-backend",
            port: 8080,
            reuseExistingServer: !process.env.CI,
            timeout: 120_000, // sbt's own startup (JVM + compile if needed) can be slow.
        },
    ],
});
