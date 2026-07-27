import { defineConfig } from '@playwright/test'

export default defineConfig({
  testDir: './tests',
  timeout: 45_000,
  expect: { timeout: 8_000 },
  fullyParallel: false,
  // The seeded super-admin account intentionally has single-session semantics.
  // Serial workers prevent parallel sign-ins from invalidating each other's token.
  workers: 1,
  retries: 0,
  reporter: [['list'], ['html', { outputFolder: 'playwright-report', open: 'never' }]],
  use: {
    baseURL: process.env.AXIOM_WEB_URL ?? 'http://localhost:4280',
    channel: process.env.AXIOM_BROWSER_CHANNEL ?? 'msedge',
    headless: true,
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
    video: 'off',
  },
})
