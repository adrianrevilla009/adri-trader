import { defineConfig } from "vitest/config";

// End-to-end tests: the real client (src/api.ts) against the real running bot and database. Started by scripts/e2e.sh.
export default defineConfig({
  test: {
    environment: "node",
    include: ["e2e/**/*.e2e.test.ts"],
    testTimeout: 30_000,
    hookTimeout: 30_000,
    fileParallelism: false,
  },
});
