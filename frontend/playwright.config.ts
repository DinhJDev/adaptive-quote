import { randomBytes } from "node:crypto";
import { defineConfig } from "@playwright/test";

const port = process.env.QUOTE_TEST_PORT ?? "8765";
const baseURL = "http://127.0.0.1:" + port;

export default defineConfig({
  testDir: "./e2e",
  use: { baseURL },
  webServer: {
    command: "java -jar ../backend/target/adaptive-quote.jar --server.port=" + port,
    env: {
      QUOTE_PUBLIC_ORIGIN: baseURL,
      QUOTE_SESSION_SECRET: randomBytes(32).toString("hex"),
      QUOTE_AI_ENABLED: "false",
      QUOTE_VPIC_ENABLED: "false",
    },
    url: baseURL + "/actuator/health",
    timeout: 30_000,
    reuseExistingServer: false,
  },
});
