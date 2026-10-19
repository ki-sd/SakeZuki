import { defineConfig, devices } from "@playwright/test";

export default defineConfig({
    testDir: "./tests/e2e",
    fullyParallel: false,
    workers: 1,
    timeout: 30000,
    reporter: "list",
    use: {
        baseURL: process.env.AUTH_TEST_BASE_URL ?? "http://localhost:3000",
        trace: "off", // Authentication responses and cookies must not be written to a trace archive.
        screenshot: "only-on-failure",
    },
    projects: [{ name: "chromium", use: { ...devices["Desktop Chrome"] } }],
});
