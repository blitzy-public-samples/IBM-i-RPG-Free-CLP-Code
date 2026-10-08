/**
 * Playwright runner configuration for the Customer Master end-to-end flows.
 *
 * The suite drives the running Docker Compose stack through one origin only: the frontend (nginx),
 * which serves the SPA and proxies `/api` to the backend. Browser pages resolve relative paths
 * against `baseURL`, and the fixtures' setup `APIRequestContext` reaches it through a loopback
 * forwarder, so this file is the one place that knows where the stack lives. No database URL,
 * second API URL or `webServer` is configured: the stack is started by
 * `docker compose up --build -d --wait` before the suite runs.
 *
 * Where it runs:
 * - Compose `e2e` service (Playwright image):  BASE_URL=http://frontend
 *     docker compose --profile e2e run --rm e2e
 * - Host, against your own stack:              BASE_URL=http://localhost:<FRONTEND_PORT>
 *     npx playwright test --workers=1
 *   With BASE_URL unset the default is http://localhost:8080, the Compose default frontend port.
 *
 * Reports: the HTML report goes to `playwright-report/` and per-test artifacts (screenshots of
 * failures, traces) to `test-results/`; both are git-ignored. Traces are off, because they record
 * entered passwords and Authorization headers. `E2E_TRACE` (`on` or `retain-on-failure`) opts in
 * only while the `CM_*` users are the demo defaults; `--trace` and UI mode are refused the same way:
 *     docker compose --profile e2e run --rm -e E2E_TRACE=retain-on-failure e2e
 *     E2E_TRACE=retain-on-failure npx playwright test --workers=1
 */
import { defineConfig, devices } from '@playwright/test';
import { assertTraceAllowed } from './fixtures/auth';

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

/** The `trace` modes `E2E_TRACE` may select. */
const TRACE_MODES = ['off', 'on', 'retain-on-failure'] as const;

/**
 * Resolves the trace mode from `E2E_TRACE`, unset or blank meaning `off`, and checks it with
 * `assertTraceAllowed` so a refused run fails at config load. The `traceGuard` fixture applies the
 * same check to the effective option, which also covers `--trace` and UI mode.
 *
 * @throws Error when the value is not one of {@link TRACE_MODES}, or tracing is requested while the
 *   `CM_*` users are not the demo defaults
 */
function resolveTrace(raw: string | undefined): (typeof TRACE_MODES)[number] {
  const value = raw === undefined ? '' : raw.trim();
  if (value === '') {
    return 'off';
  }
  const mode = TRACE_MODES.find((candidate) => candidate === value);
  if (mode === undefined) {
    throw new Error(`E2E_TRACE must be one of ${TRACE_MODES.join(', ')}, or unset; got "${value}".`);
  }
  assertTraceAllowed(mode);
  return mode;
}

const baseURL = resolveBaseURL(process.env.BASE_URL);
const url = parseBaseURL(baseURL);
const origin = url.origin;
const insecureNonLocal = url.protocol === 'http:' && !LOOPBACK_HOSTS.includes(url.hostname);
const trace = resolveTrace(process.env.E2E_TRACE);

export default defineConfig({
  testDir: './tests',
  testMatch: '**/*.spec.ts',

  // All five specs share one Compose database and its seed rows, so they run one at a time.
  // Specs that modify customers create uniquely named rows; seed-row scenarios are read-only.
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
  // (fixtures/auth.ts), and the setup API context's loopback forwarder adds the Authorization header.
  use: {
    baseURL,
    trace,
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
