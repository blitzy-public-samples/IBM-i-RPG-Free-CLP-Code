/**
 * Playwright runner configuration for the Customer Master end-to-end flows.
 *
 * The suite drives the running Docker Compose stack through one origin only: the frontend (nginx),
 * which serves the SPA and proxies `/api` to the backend. Browser pages and the `APIRequestContext`
 * the fixtures use for setup both resolve relative paths against `baseURL`, so this file is the one
 * place that knows where the stack lives. No database URL, second API URL or `webServer` is
 * configured: the stack is started by `docker compose up --build -d --wait` before the suite runs.
 *
 * Where it runs:
 * - Compose `e2e` service (Playwright image):  BASE_URL=http://frontend
 *     docker compose --profile e2e run --rm e2e
 * - Host, against your own stack:              BASE_URL=http://localhost:<FRONTEND_PORT>
 *     npx playwright test --workers=1
 *   With BASE_URL unset the default is http://localhost:8080, the Compose default frontend port.
 *
 * Reports: the HTML report goes to `playwright-report/` and per-test artifacts (traces, screenshots
 * of failures) to `test-results/`; both are git-ignored.
 */
import { defineConfig, devices } from '@playwright/test';

/** Frontend origin users open when the stack runs with its default ports. */
const DEFAULT_BASE_URL = 'http://localhost:8080';

/** Hosts Chromium already treats as secure contexts over plain http. */
const LOOPBACK_HOSTS: readonly string[] = ['localhost', '127.0.0.1', '[::1]'];

/**
 * Resolves the frontend base URL from `BASE_URL`, removing one trailing slash so specs can join
 * their leading-slash paths without producing `//`. A blank value counts as unset, so an empty
 * `BASE_URL=` in a shell falls back to the default instead of failing to parse.
 */
function resolveBaseURL(raw: string | undefined): string {
  const value = raw === undefined || raw.trim() === '' ? DEFAULT_BASE_URL : raw.trim();
  return value.endsWith('/') ? value.slice(0, -1) : value;
}

/** Parses the base URL, failing fast with a readable message rather than on the first navigation. */
function parseBaseURL(value: string): URL {
  let parsed: URL;
  try {
    parsed = new URL(value);
  } catch {
    throw new Error(`BASE_URL must be an absolute http(s) URL such as ${DEFAULT_BASE_URL}; got "${value}".`);
  }
  if (parsed.protocol !== 'http:' && parsed.protocol !== 'https:') {
    throw new Error(`BASE_URL must use http or https; got "${value}".`);
  }
  return parsed;
}

const baseURL = resolveBaseURL(process.env.BASE_URL);
const url = parseBaseURL(baseURL);
const origin = url.origin;
const insecureNonLocal = url.protocol === 'http:' && !LOOPBACK_HOSTS.includes(url.hostname);

export default defineConfig({
  testDir: './tests',
  testMatch: '**/*.spec.ts',

  // All five specs share one Compose database and its seed rows, so they run one at a time.
  // Each spec creates its own uniquely named customers, so the order between files does not matter.
  fullyParallel: false,
  workers: 1,

  retries: 0,
  forbidOnly: !!process.env.CI,

  // The first requests after `docker compose up` can be slow while the JVM warms up.
  timeout: 60_000,
  expect: { timeout: 10_000 },

  outputDir: 'test-results',
  // `open: 'never'`: nothing answers a prompt to open the report inside the container.
  reporter: [['list'], ['html', { outputFolder: 'playwright-report', open: 'never' }]],

  // No httpCredentials or extraHTTPHeaders: browser sign-in goes through the SignInPage
  // (fixtures/auth.ts), and the setup API context sets its own Authorization headers.
  use: {
    baseURL,
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
    video: 'off',
    locale: 'en-US',
    timezoneId: 'UTC',
    actionTimeout: 10_000,
    navigationTimeout: 30_000,
  },

  projects: [
    {
      name: 'chromium',
      use: {
        ...devices['Desktop Chrome'],
        // Full Chromium in new headless mode, not the default chrome-headless-shell, which ignores
        // the insecure-origin flag below; both builds ship in the Playwright image and the host cache.
        channel: 'chromium',
        launchOptions: {
          // Chromium treats http://frontend (Compose) as non-secure, unlike users' http://localhost, so mark it secure to keep APIs such as crypto.randomUUID available.
          args: insecureNonLocal ? [`--unsafely-treat-insecure-origin-as-secure=${origin}`] : [],
        },
      },
    },
  ],
});
