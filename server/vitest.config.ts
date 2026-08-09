import { cloudflareTest } from "@cloudflare/vitest-pool-workers";
import { randomUUID } from "node:crypto";
import { defineConfig } from "vitest/config";

// Ephemeral test-only value: never a checked-in upload credential.
const integrationUploadToken = randomUUID();

export default defineConfig({
  plugins: [
    cloudflareTest({
      wrangler: { configPath: "./wrangler.jsonc" },
      miniflare: {
        bindings: {
          ANDROID_UPLOAD_TOKEN: integrationUploadToken,
          SITE_READ_TOKEN: randomUUID(),
          ALLOWED_GITHUB_USER_ID: "123456789",
          GITHUB_CLIENT_ID: "test-github-client-id",
          GITHUB_CLIENT_SECRET: "test-github-client-secret",
          COOKIE_ENCRYPTION_KEY: "test-cookie-encryption-key-not-a-secret",
        },
      },
    }),
  ],
  test: {
    include: ["test/**/*.integration.test.ts"],
  },
});

