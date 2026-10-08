/**
 * e2e sign-in: the shared Playwright fixtures and helpers of the Customer Master end-to-end suite.
 *
 * Every spec under `tests/` imports `test` and `expect` from here (`'../fixtures/auth'`) and never
 * from `@playwright/test` directly, so sign-in, the setup API context and the toast lookup exist
 * exactly once.
 *
 * Why sign-in exists. The IBM i application has no authentication: the caller asserts the mode
 * (I, M or S) in PMTCUSTR's first parameter, and the program leaves security to "a tested menu or
 * some program that enforced security" [5250_Subfile/PMTCUSTR.SQLRPGLE:230-231]. The readme gives
 * Inquiry to the general user population and Maintenance to Sales [5250_Subfile/README.md:33-40].
 * The target authenticates every request with HTTP Basic and derives the mode from the session
 * roles, so a spec signs in as one of two users:
 * - `asInquiry`: the general user, role INQUIRY (Inquiry mode, 5=Display only).
 * - `asMaintenance`: the Sales user, role MAINTENANCE (Maintenance mode, 2=Edit, 5=Display, F6=Add).
 * A customer added through the API is stamped with the authenticated principal as `chgUser`, where
 * MTNCUSTR stamped the job user [5250_Subfile/MTNCUSTR.SQLRPGLE:548-566].
 *
 * Constraints every spec inherits:
 * - **Credentials live in browser memory only.** `AuthProvider` hands them to a module variable of
 *   the SPA's `api/client.ts`; nothing is stored in a cookie or Web Storage. A `page.goto`, a
 *   reload or a new tab after sign-in therefore signs the user out. Sign-in rides the guard
 *   redirect of the start path (`startPath` option), and a spec moves on only by clicking links,
 *   buttons or pressing keys.
 * - **One origin.** Pages and the setup API context both go through `baseURL` (the frontend, whose
 *   nginx proxies `/api` to the backend). Nothing here reaches the database: specs create the
 *   customers they modify with `createCustomer` and never touch a seed row they change.
 * - **No frontend at run time.** The Compose `e2e` service mounts only `./e2e`. The one import from
 *   the frontend tree is type-only, which Playwright's TypeScript transform erases (and
 *   `verbatimModuleSyntax` keeps type-only), so this file loads without `../../frontend`.
 *
 * @example
 * ```ts
 * import { test, expect, createCustomer, toasts, uniqueName } from '../fixtures/auth';
 *
 * test.use({ startPath: '/customers' });
 *
 * test('finds a customer it created', async ({ asMaintenance: page, api }) => {
 *   const { name, filter } = uniqueName('FIND');
 *   await createCustomer(api, { name });
 *   await page.getByLabel('Name starts with:').fill(filter);
 *   await page.keyboard.press('Enter');
 *   await expect(page.getByRole('table')).toContainText(name);
 *   await expect(toasts(page).alert).toHaveText('');
 * });
 * ```
 */
import {
  test as base,
  expect,
  type APIRequestContext,
  type Browser,
  type BrowserContext,
  type Locator,
  type Page,
} from '@playwright/test';
import { randomInt } from 'node:crypto';
// Type-only by necessity: the Compose e2e container has no frontend tree, and only an erased
// import keeps this module loadable there. Never import a runtime value from the frontend.
import type { components } from '../../frontend/src/api/schema';

export { expect };
export type { APIRequestContext, BrowserContext, Locator, Page };

// ---------------------------------------------------------------------------
// Types
// ---------------------------------------------------------------------------

/**
 * The nine customer data fields a client may send on add, review and update (the OpenAPI
 * `CustomerFields` schema). The API rejects any other property with 400 APP0400.
 */
export type CustomerFields = components['schemas']['CustomerFields'];

/** A stored customer as the API returns it: the nine fields plus id, change stamp and version. */
export type CustomerResponse = components['schemas']['CustomerResponse'];

/** HTTP Basic credentials of one configured user. */
export type E2EUser = { username: string; password: string };

/** A browser context of its own with a page signed in on it; the caller closes `context`. */
export type SignedInSession = { context: BrowserContext; page: Page };

/** A customer name unique to one test, and the search prefix that finds only that customer. */
export type UniqueName = { name: string; filter: string };

/** The two live regions of `ToastRegion`, where every client and server message is published. */
export type ToastLocators = { status: Locator; alert: Locator };

/** Every one of the nine customer fields, each present with a string value. */
type CompleteCustomerFields = { [K in keyof CustomerFields]-?: string };

/** The fixtures this module adds to Playwright's built-in ones. */
type Fixtures = {
  /**
   * The guarded path the signed-in fixtures open: `/` (menu), `/customers` (search) or
   * `/demo/selection` (host form). Set it per file with `test.use({ startPath: '/customers' })`.
   */
  startPath: string;
  /** A page signed in as the general user (role INQUIRY), showing `startPath`. */
  asInquiry: Page;
  /** A page signed in as the Sales user (role MAINTENANCE), showing `startPath`. */
  asMaintenance: Page;
  /** An API request context authenticated as the Sales user, for setup and verification. */
  api: APIRequestContext;
};

// ---------------------------------------------------------------------------
// Users
// ---------------------------------------------------------------------------

/**
 * Reads a credential variable, falling back to its documented demo default when the variable is
 * unset or blank. The value itself is used exactly as given: usernames are case-sensitive.
 */
function envOrDefault(name: string, fallback: string): string {
  const value = process.env[name];
  return value === undefined || value.trim() === '' ? fallback : value;
}

/**
 * The two users the suite signs in as. Each value comes from the variable the API's user list is
 * bound from (`CM_INQUIRY_USER`, `CM_INQUIRY_PASSWORD`, `CM_MAINTENANCE_USER`,
 * `CM_MAINTENANCE_PASSWORD`), which the Compose `e2e` service passes through, so an override in
 * `.env` reaches both sides. Unset or blank, each falls back to the Compose demo defaults:
 * `inquiry`/`inquiry-demo` (INQUIRY) and `sales`/`sales-demo` (MAINTENANCE). These demo defaults
 * are the only credential values the suite carries.
 */
export const USERS: Readonly<{ inquiry: E2EUser; maintenance: E2EUser }> = Object.freeze({
  inquiry: Object.freeze({
    username: envOrDefault('CM_INQUIRY_USER', 'inquiry'),
    password: envOrDefault('CM_INQUIRY_PASSWORD', 'inquiry-demo'),
  }),
  maintenance: Object.freeze({
    username: envOrDefault('CM_MAINTENANCE_USER', 'sales'),
    password: envOrDefault('CM_MAINTENANCE_PASSWORD', 'sales-demo'),
  }),
});

/**
 * The `Authorization` value for `user`: `Basic` plus the base64 of `username:password` encoded as
 * UTF-8, the same encoding the SPA's `api/client.ts` sends.
 */
function basicAuthorization(user: E2EUser): string {
  return `Basic ${Buffer.from(`${user.username}:${user.password}`, 'utf8').toString('base64')}`;
}

// ---------------------------------------------------------------------------
// Sign-in
// ---------------------------------------------------------------------------

/** The public sign-in route the guard redirects a signed-out visitor to. */
const SIGN_IN_PATH = '/sign-in';

/**
 * Checks that `path` is a guarded in-app path the sign-in can return to. `RequireRole` keeps only
 * the pathname as `state.from`, so a query or hash would be lost; `SignInPage` replaces a
 * protocol-relative path (`//`, `/\`) or the sign-in route itself with `/`, and signing in "to"
 * the sign-in page would loop.
 *
 * @throws Error naming the rejected path and the reason
 */
function assertGuardedPath(path: string): void {
  if (!path.startsWith('/') || path.startsWith('//') || path.startsWith('/\\')) {
    throw new Error(`signIn: path must be an in-app path starting with a single "/"; got "${path}".`);
  }
  if (path.includes('?') || path.includes('#')) {
    throw new Error(
      `signIn: path must not carry a query or hash, because the sign-in redirect keeps only the pathname; got "${path}".`,
    );
  }
  if (path.toLowerCase().startsWith(SIGN_IN_PATH)) {
    throw new Error(`signIn: path must be a guarded screen, not the sign-in page itself; got "${path}".`);
  }
}

/**
 * Signs `user` in through the SPA's sign-in page and waits until `path` is shown.
 *
 * Steps: open `path`, which the route guard redirects to `/sign-in` (with `path` as the return
 * location); fill "User" and "Password"; press "Sign in"; wait for the URL of `path` and for the
 * sign-in form to disappear, so the routed screen has rendered. A rejected sign-in (for example a
 * wrong password) leaves the page on `/sign-in`, and the URL assertion fails with a message that
 * names the username.
 *
 * After this call the spec must never call `page.goto` or `page.reload`: the credentials live in
 * the page's memory only, and either call discards them. Navigate by links, buttons and keys.
 *
 * @param page a page of a context whose `baseURL` is the frontend origin
 * @param user the credentials to type
 * @param path the guarded screen to land on: `/`, `/customers` or `/demo/selection`
 * @throws Error when `path` is not a guarded in-app path (see {@link assertGuardedPath})
 */
export async function signIn(page: Page, user: E2EUser, path = '/'): Promise<void> {
  assertGuardedPath(path);

  await page.goto(path);
  await expect(page, `route guard redirect from ${path} to ${SIGN_IN_PATH}`).toHaveURL(/\/sign-in$/);

  // Resolved against the current origin, so no baseURL is needed to know where sign-in returns.
  const target = new URL(path, page.url()).toString();

  await page.getByLabel('User', { exact: true }).fill(user.username);
  await page.getByLabel('Password', { exact: true }).fill(user.password);
  const submit = page.getByRole('button', { name: 'Sign in', exact: true });
  await submit.click();

  await expect(page, `sign-in as ${user.username}`).toHaveURL(target);
  await expect(submit, `sign-in form gone after signing in as ${user.username}`).toHaveCount(0);
}

/**
 * Opens a new browser context on `baseURL` and signs `user` in on a page of it, landing on `path`.
 * Use it for a second, independent session in one test (two users editing the same customer).
 *
 * The caller owns the returned context and must close it, typically in a `finally` block. When
 * sign-in fails the context is closed here and the error rethrown.
 *
 * @param browser Playwright's worker-scoped `browser` fixture
 * @param baseURL the frontend origin; pass `requireBaseURL(baseURL)` from the test's fixtures
 * @param user the credentials to sign in with
 * @param path the guarded screen to land on
 * @throws the sign-in failure, after closing the context; an `AggregateError` when closing fails too
 */
export async function openSignedInPage(
  browser: Browser,
  baseURL: string,
  user: E2EUser,
  path = '/',
): Promise<SignedInSession> {
  const context: BrowserContext = await browser.newContext({ baseURL });
  try {
    const page = await context.newPage();
    await signIn(page, user, path);
    return { context, page };
  } catch (error) {
    try {
      await context.close({ reason: `sign-in as ${user.username} failed` });
    } catch (closeError) {
      throw new AggregateError([error, closeError], `sign-in as ${user.username} failed, and closing its context failed`);
    }
    throw error;
  }
}

/**
 * Returns Playwright's `baseURL`, which `playwright.config.ts` derives from `BASE_URL`.
 *
 * @throws Error when it is undefined or blank, naming `BASE_URL` and `playwright.config.ts`
 */
export function requireBaseURL(baseURL?: string): string {
  if (baseURL === undefined || baseURL.trim() === '') {
    throw new Error(
      'baseURL is not set: playwright.config.ts must set use.baseURL from BASE_URL (the frontend origin, ' +
        'for example http://frontend in the Compose e2e service or http://localhost:8080 on the host).',
    );
  }
  return baseURL;
}

// ---------------------------------------------------------------------------
// Test data
// ---------------------------------------------------------------------------

/** Fixed lead of every generated filter, so suite-created customers are recognisable. */
const FILTER_PREFIX = 'E2E';

/** Random characters after the lead: 36^8 (about 2.8 x 10^12) combinations per filter. */
const FILTER_RANDOM_LENGTH = 8;

/** Characters drawn for the random part; uppercase, because the server uppercases names. */
const FILTER_ALPHABET = '0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ';

/** Length of the customer name column (`name varchar(40)`, MTNCUSTD `SD_NAME` 40). */
const NAME_MAX_LENGTH = 40;

/**
 * Tags are limited to printable ASCII, where uppercasing never changes the length and trailing
 * whitespace means the same in JavaScript and on the server, so the name returned here is exactly
 * the name the server stores.
 */
const PRINTABLE_ASCII = /^[\x20-\x7E]*$/;

/**
 * Generates a customer name no other test or run shares, plus the "Name starts with:" filter that
 * finds that customer alone.
 *
 * - `filter` is `E2E` plus eight random characters from `0-9A-Z`: 11 characters. It must stay at
 *   most 12 characters. The search appends `%` within the field's 13-character limit, as PMTCUSTR
 *   does, so a full 13-character entry carries no wildcard, is matched against the blank-padded
 *   40-character name and finds nothing (a preserved source defect). Search with `filter`, never
 *   with the full name.
 * - `name` is `${filter} ${tag}`, uppercased, cut to 40 characters and stripped of trailing blanks,
 *   which is the value the server stores (it uppercases and strips trailing blanks).
 *
 * @param tag what the customer is for, for example `EDIT` or `CONF`; printable ASCII only
 * @throws Error when `tag` contains a character outside printable ASCII
 */
export function uniqueName(tag: string): UniqueName {
  if (!PRINTABLE_ASCII.test(tag)) {
    throw new Error(`uniqueName: tag must be printable ASCII; got ${JSON.stringify(tag)}.`);
  }
  let random = '';
  for (let i = 0; i < FILTER_RANDOM_LENGTH; i += 1) {
    random += FILTER_ALPHABET.charAt(randomInt(FILTER_ALPHABET.length));
  }
  const filter = `${FILTER_PREFIX}${random}`;
  const name = `${filter} ${tag}`.toUpperCase().slice(0, NAME_MAX_LENGTH).trimEnd();
  return { name, filter };
}

/**
 * Valid values for every field except `name`, which is fresh per customer. The type requires each
 * of the nine fields (`name` aside) and rejects any other key, so this object and the API contract
 * cannot drift apart.
 */
const CUSTOMER_DEFAULTS: Readonly<Omit<CompleteCustomerFields, 'name'>> = Object.freeze({
  active: 'Y',
  addr: '100 E2E TEST STREET',
  city: 'SPRINGFIELD',
  state: 'IL',
  zip: '62701',
  acctPhone: '(217) 555-0101',
  acctMgr: 'E2E MANAGER',
  corpPhone: '(217) 555-0100',
});

/** The nine property names the API accepts in a customer body. */
const CUSTOMER_FIELD_NAMES: ReadonlySet<string> = new Set(['name', ...Object.keys(CUSTOMER_DEFAULTS)]);

/**
 * Builds a valid customer body: every one of the nine fields set (Active `Y`, a fresh
 * {@link uniqueName} name, an Illinois address and phones), then `overrides` applied on top.
 * Each call generates a new name.
 *
 * The result holds exactly the nine `CustomerFields` properties: the API rejects any other one,
 * such as `custId`, `chgUser`, `chgTime` or `version`, with 400 APP0400.
 *
 * @param overrides field values to use instead of the defaults
 * @throws Error when `overrides` carries a property that is not one of the nine fields
 */
export function newCustomerFields(overrides: Partial<CustomerFields> = {}): CustomerFields {
  const unknownKeys = Object.keys(overrides).filter((key) => !CUSTOMER_FIELD_NAMES.has(key));
  if (unknownKeys.length > 0) {
    throw new Error(
      `newCustomerFields: ${unknownKeys.join(', ')} is not a customer field; the API accepts only ` +
        `${[...CUSTOMER_FIELD_NAMES].join(', ')}.`,
    );
  }
  return { ...CUSTOMER_DEFAULTS, name: uniqueName('CUSTOMER').name, ...overrides };
}

/** Narrows a parsed JSON value to a plain object. */
function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

/**
 * Parses and checks a `CustomerResponse` body, so a spec fails on the response that broke the
 * contract rather than on a later `undefined`.
 *
 * @param text the response body
 * @param what the request, for the error message
 * @throws Error when the body is not JSON or a member is missing or of the wrong type
 */
function parseCustomerResponse(text: string, what: string): CustomerResponse {
  let parsed: unknown;
  try {
    parsed = JSON.parse(text);
  } catch {
    throw new Error(`${what}: the body is not JSON: ${text}`);
  }
  if (!isRecord(parsed)) {
    throw new Error(`${what}: the body is not a JSON object: ${text}`);
  }
  const body = parsed;

  /** Reads a required string member. */
  const str = (key: string): string => {
    const value = body[key];
    if (typeof value !== 'string') {
      throw new Error(`${what}: member "${key}" is not a string: ${text}`);
    }
    return value;
  };

  const chgTime = body.chgTime;
  if (chgTime !== null && typeof chgTime !== 'string') {
    throw new Error(`${what}: member "chgTime" is neither a string nor null: ${text}`);
  }
  const version = body.version;
  if (typeof version !== 'number' || !Number.isInteger(version)) {
    throw new Error(`${what}: member "version" is not an integer: ${text}`);
  }

  return {
    custId: str('custId'),
    name: str('name'),
    addr: str('addr'),
    city: str('city'),
    state: str('state'),
    zip: str('zip'),
    corpPhone: str('corpPhone'),
    acctMgr: str('acctMgr'),
    acctPhone: str('acctPhone'),
    active: str('active'),
    chgTime,
    chgUser: str('chgUser'),
    version,
  };
}

/** A customer id: four characters of the base-36 alphabet. */
const CUSTOMER_ID = /^[A-Z0-9]{4}$/;

/**
 * Adds a customer through `POST /api/customers` and returns the stored record.
 *
 * The body is {@link newCustomerFields}`(overrides)`. The call must answer 201 with a
 * `CustomerResponse` whose `custId` is a 4-character base-36 id, `version` 0 and `chgUser` the
 * Sales user, because the {@link test} `api` fixture authenticates as that user and the server
 * stamps the principal.
 *
 * @param api the `api` fixture (or another context authenticated as the Sales user)
 * @param overrides field values to use instead of the defaults
 * @returns the created customer as the server stored it (normalised to uppercase)
 */
export async function createCustomer(
  api: APIRequestContext,
  overrides: Partial<CustomerFields> = {},
): Promise<CustomerResponse> {
  const response = await api.post('/api/customers', { data: newCustomerFields(overrides) });
  const text = await response.text();
  expect(response.status(), `POST /api/customers: ${text}`).toBe(201);

  const created = parseCustomerResponse(text, 'POST /api/customers');
  expect(created.custId, `POST /api/customers custId: ${text}`).toMatch(CUSTOMER_ID);
  expect(created.version, `POST /api/customers version: ${text}`).toBe(0);
  expect(created.chgUser, `POST /api/customers chgUser: ${text}`).toBe(USERS.maintenance.username);
  return created;
}

/**
 * Reads one customer through `GET /api/customers/{custId}`, which must answer 200.
 *
 * @param api an authenticated API context, such as the `api` fixture
 * @param custId the 4-character customer id
 * @returns the customer as currently stored, including its `version`
 */
export async function getCustomer(api: APIRequestContext, custId: string): Promise<CustomerResponse> {
  const path = `/api/customers/${encodeURIComponent(custId)}`;
  const response = await api.get(path);
  const text = await response.text();
  expect(response.status(), `GET ${path}: ${text}`).toBe(200);
  return parseCustomerResponse(text, `GET ${path}`);
}

/**
 * The two containers of the SPA's `ToastRegion`: `status` (information, notices, confirmations)
 * and `alert` (errors). Each is always rendered, empty when there is no message.
 *
 * Toasts clear on the next click anywhere and on the next command key, so assert a message right
 * after the action that raised it, before any further click or key.
 */
export function toasts(page: Page): ToastLocators {
  return {
    status: page.locator('.toast-region [role="status"]'),
    alert: page.locator('.toast-region [role="alert"]'),
  };
}

// ---------------------------------------------------------------------------
// Fixtures
// ---------------------------------------------------------------------------

/**
 * Playwright's `test` extended with the suite's fixtures. Import it, with `expect`, from this module
 * in every spec.
 *
 * - `startPath` (option, default `/`): where `asInquiry` and `asMaintenance` land.
 * - `asInquiry` / `asMaintenance`: a page in its own browser context, signed in through the
 *   sign-in page and showing `startPath`; the context is closed after the test.
 * - `api`: an API request context on the same origin, authenticated as the Sales user with an
 *   explicit `Authorization` header (never `httpCredentials`: with `X-Requested-With` present the
 *   server sends no challenge, so challenge-driven credentials would never be sent).
 *
 * Every context receives `baseURL` explicitly rather than relying on inheritance from the config.
 */
export const test = base.extend<Fixtures>({
  startPath: ['/', { option: true }],

  asInquiry: async ({ browser, baseURL, startPath }, use) => {
    const { context, page } = await openSignedInPage(browser, requireBaseURL(baseURL), USERS.inquiry, startPath);
    try {
      await use(page);
    } finally {
      await context.close();
    }
  },

  asMaintenance: async ({ browser, baseURL, startPath }, use) => {
    const { context, page } = await openSignedInPage(browser, requireBaseURL(baseURL), USERS.maintenance, startPath);
    try {
      await use(page);
    } finally {
      await context.close();
    }
  },

  api: async ({ playwright, baseURL }, use) => {
    const context = await playwright.request.newContext({
      baseURL: requireBaseURL(baseURL),
      extraHTTPHeaders: {
        Authorization: basicAuthorization(USERS.maintenance),
        'X-Requested-With': 'XMLHttpRequest',
        Accept: 'application/json, application/problem+json',
      },
    });
    try {
      await use(context);
    } finally {
      await context.dispose();
    }
  },
});
