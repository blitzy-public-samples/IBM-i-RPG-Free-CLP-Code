/**
 * e2e sign-in: the shared Playwright fixtures and helpers of the Customer Master end-to-end suite.
 * Every spec imports `test` and `expect` from here, never from `@playwright/test` directly.
 *
 * The IBM i application has no authentication: the caller asserts the mode, and the program leaves
 * security to "a tested menu or some program that enforced security"
 * [5250_Subfile/PMTCUSTR.SQLRPGLE:230-231]; the readme gives Inquiry to general users and
 * Maintenance to Sales [5250_Subfile/README.md:33-40].
 * The target authenticates every request with HTTP Basic, derives the mode from the session roles,
 * and stamps the authenticated principal as `chgUser` where MTNCUSTR stamped the job user
 * [5250_Subfile/MTNCUSTR.SQLRPGLE:548-566].
 *
 * Constraints every spec inherits:
 * - **Credentials live in browser memory only**, never in a cookie or Web Storage, so a
 *   `page.goto`, a reload or a new tab after sign-in signs the user out. Sign-in rides the guard
 *   redirect of `startPath`; a spec then moves on only by links, buttons and keys.
 * - **One origin.** Pages and the setup API context go through `baseURL` (the frontend, whose nginx
 *   proxies `/api`), never the database; specs create the customers they modify with
 *   `createCustomer`.
 * - **No frontend at run time.** The Compose `e2e` service mounts only `./e2e`; the one import from
 *   the frontend tree is type-only, which Playwright's TypeScript transform erases.
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
  type PlaywrightWorkerOptions,
} from '@playwright/test';
import { randomBytes, randomInt, timingSafeEqual } from 'node:crypto';
import { createServer, request as httpRequest, type IncomingHttpHeaders, type OutgoingHttpHeaders } from 'node:http';
import { request as httpsRequest } from 'node:https';
import type { AddressInfo } from 'node:net';
import { pipeline } from 'node:stream';
// Type-only by necessity: the Compose e2e container has no frontend tree, and only an erased
// import keeps this module loadable there. Never import a runtime value from the frontend.
import type { components } from '../../frontend/src/api/schema';

export { expect };
export type { APIRequestContext, BrowserContext, Locator, Page };

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

type CompleteCustomerFields = { [K in keyof CustomerFields]-?: string };

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

type WorkerFixtures = { traceGuard: void };

/**
 * Reads a credential variable, falling back to its documented demo default when the variable is
 * unset or blank. The value itself is used exactly as given: usernames are case-sensitive.
 */
function envOrDefault(name: string, fallback: string): string {
  const value = process.env[name];
  return value === undefined || value.trim() === '' ? fallback : value;
}

/**
 * The Compose demo users, `inquiry`/`inquiry-demo` (INQUIRY) and `sales`/`sales-demo`
 * (MAINTENANCE): the only credential values the suite carries.
 */
export const DEMO_USERS: Readonly<{ inquiry: E2EUser; maintenance: E2EUser }> = Object.freeze({
  inquiry: Object.freeze({ username: 'inquiry', password: 'inquiry-demo' }),
  maintenance: Object.freeze({ username: 'sales', password: 'sales-demo' }),
});

/**
 * The two users the suite signs in as. Each value comes from the variable the API's user list is
 * bound from (`CM_INQUIRY_USER`, `CM_INQUIRY_PASSWORD`, `CM_MAINTENANCE_USER`,
 * `CM_MAINTENANCE_PASSWORD`), which the Compose `e2e` service passes through, so an override in
 * `.env` reaches both sides. Unset or blank, each falls back to its {@link DEMO_USERS} value.
 */
export const USERS: Readonly<{ inquiry: E2EUser; maintenance: E2EUser }> = Object.freeze({
  inquiry: Object.freeze({
    username: envOrDefault('CM_INQUIRY_USER', DEMO_USERS.inquiry.username),
    password: envOrDefault('CM_INQUIRY_PASSWORD', DEMO_USERS.inquiry.password),
  }),
  maintenance: Object.freeze({
    username: envOrDefault('CM_MAINTENANCE_USER', DEMO_USERS.maintenance.username),
    password: envOrDefault('CM_MAINTENANCE_PASSWORD', DEMO_USERS.maintenance.password),
  }),
});

/** Whether both {@link USERS} are exactly the {@link DEMO_USERS}, the only users traces may record. */
function usesDemoUsers(): boolean {
  return (['inquiry', 'maintenance'] as const).every(
    (role) =>
      USERS[role].username === DEMO_USERS[role].username && USERS[role].password === DEMO_USERS[role].password,
  );
}

/**
 * Refuses tracing unless the suite signs in as the {@link DEMO_USERS}: a trace records the entered
 * passwords and `Authorization` headers, and the HTML report publishes it. The message names no
 * credential.
 *
 * @param trace a `trace` option: a mode, or an object with `mode`; unset means `off`
 * @throws Error when the mode is not `off` and a `CM_*` user is not its demo default
 */
export function assertTraceAllowed(trace: PlaywrightWorkerOptions['trace']): void {
  const mode = (typeof trace === 'string' ? trace : trace?.mode) || 'off';
  if (mode !== 'off' && !usesDemoUsers()) {
    throw new Error(
      `Trace mode "${mode}" is refused: traces record entered passwords and Authorization headers, and the ` +
        'HTML report publishes them, so tracing (E2E_TRACE, --trace or UI mode) is allowed only while the ' +
        'CM_* users are the demo defaults. Turn tracing off or unset the CM_* user overrides.',
    );
  }
}

/**
 * The `Authorization` value for `user`: `Basic` plus the base64 of `username:password` encoded as
 * UTF-8, the same encoding the SPA's `api/client.ts` sends.
 */
function basicAuthorization(user: E2EUser): string {
  return `Basic ${Buffer.from(`${user.username}:${user.password}`, 'utf8').toString('base64')}`;
}

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
 * Enters `value` into the input `field` as typing would: it focuses the input, sets its value
 * through the native setter and dispatches `input`, so React's `onChange` runs. Playwright renders
 * the value of a `fill`, `type` or `insertText` into step titles, call logs and errors, which the
 * HTML report keeps; an evaluation's argument appears in none of them, only in a trace, which
 * {@link assertTraceAllowed} allows only with the {@link DEMO_USERS}. No message here carries `value`.
 *
 * @throws Error when `field` is not an editable `<input>`, or `value` exceeds its `maxLength`
 */
async function enterSecret(field: Locator, value: string): Promise<void> {
  await expect(field).toBeEditable();
  await field.evaluate((input, secret) => {
    if (!(input instanceof HTMLInputElement)) {
      throw new Error(`enterSecret: the field is a <${input.nodeName.toLowerCase()}>, not an <input>.`);
    }
    if (input.maxLength >= 0 && secret.length > input.maxLength) {
      throw new Error(
        `enterSecret: the value is longer than the field's maxLength of ${input.maxLength}, so a user could not type it either.`,
      );
    }
    const setValue = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value')?.set;
    if (setValue === undefined) {
      throw new Error('enterSecret: HTMLInputElement.prototype has no value setter.');
    }
    input.focus();
    setValue.call(input, secret);
    input.dispatchEvent(new Event('input', { bubbles: true }));
  }, value);
}

/**
 * Signs `user` in through the sign-in page the route guard redirects `path` to, entering the
 * password with {@link enterSecret}, and waits until `path` is shown with the sign-in form gone.
 * A rejected sign-in leaves the page on `/sign-in`, and the URL assertion fails naming the username.
 *
 * After this call the spec must never call `page.goto` or `page.reload`: the credentials live in
 * the page's memory only, and either call discards them.
 *
 * @param page a page of a context whose `baseURL` is the frontend origin
 * @param user the credentials to enter
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
  await enterSecret(page.getByLabel('Password', { exact: true }), user.password);
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

/** Hop-by-hop headers (RFC 9110 section 7.6.1): they describe one connection and are never relayed. */
const HOP_BY_HOP_HEADERS: ReadonlySet<string> = new Set([
  'connection',
  'keep-alive',
  'proxy-connection',
  'proxy-authorization',
  'transfer-encoding',
  'te',
  'trailer',
  'upgrade',
]);

/** `headers` without the hop-by-hop ones, including any the `Connection` header names. */
function endToEndHeaders(headers: IncomingHttpHeaders): OutgoingHttpHeaders {
  const named = (headers.connection ?? '').split(',').map((token) => token.trim().toLowerCase());
  const relayed: OutgoingHttpHeaders = {};
  for (const [name, value] of Object.entries(headers)) {
    if (value !== undefined && !HOP_BY_HOP_HEADERS.has(name) && !named.includes(name)) {
      relayed[name] = value;
    }
  }
  return relayed;
}

/** Header carrying the forwarder's per-run token; it is checked, then never relayed. */
const FORWARDER_TOKEN_HEADER = 'x-e2e-forwarder-token';

/** `headers` holds this forwarder's token, compared in constant time. */
function hasForwarderToken(headers: IncomingHttpHeaders, token: Buffer): boolean {
  const presented = headers[FORWARDER_TOKEN_HEADER];
  if (typeof presented !== 'string') {
    return false;
  }
  const candidate = Buffer.from(presented, 'utf8');
  return candidate.length === token.length && timingSafeEqual(candidate, token);
}

/** A running forwarder: its URL, the headers a client must send, and its shutdown. */
type CredentialForwarder = { url: string; headers: Record<string, string>; close: () => Promise<void> };

/**
 * Starts a loopback HTTP server that relays each request to the origin of `target` with `user`'s
 * Basic `Authorization` header added. Playwright writes every request header of an
 * `APIRequestContext` into its call logs, traces and reports and offers no redaction, so the
 * context talks to this forwarder and never holds the credential. Only a client presenting the
 * returned `headers` (a random token valid until `close`) is relayed, so no other local process can
 * borrow the credential. An upstream failure answers 502 naming the request, the origin and the
 * error, never a header value.
 *
 * @throws Error when `target` is not an http(s) URL or the server cannot listen
 */
async function startCredentialForwarder(target: string, user: E2EUser): Promise<CredentialForwarder> {
  const parsed = URL.canParse(target) ? new URL(target) : undefined;
  if (parsed === undefined || (parsed.protocol !== 'http:' && parsed.protocol !== 'https:')) {
    throw new Error(`api: baseURL must be an absolute http(s) URL; got "${target}".`);
  }
  const upstream = new URL(parsed.origin);
  const send = upstream.protocol === 'https:' ? httpsRequest : httpRequest;
  const authorization = basicAuthorization(user);
  const token = randomBytes(32).toString('hex');
  const tokenBytes = Buffer.from(token, 'utf8');

  const server = createServer((req, res) => {
    if (!hasForwarderToken(req.headers, tokenBytes)) {
      req.resume();
      res.writeHead(403, { 'content-type': 'text/plain; charset=utf-8' });
      res.end('api forwarder: a request without this forwarder token is not relayed.');
      return;
    }
    const relayed = endToEndHeaders(req.headers);
    delete relayed[FORWARDER_TOKEN_HEADER];
    const headers = { ...relayed, host: upstream.host, authorization };
    const relay = send(upstream, { method: req.method, path: req.url, headers, agent: false }, (answer) => {
      res.writeHead(answer.statusCode ?? 502, answer.statusMessage, endToEndHeaders(answer.headers));
      pipeline(answer, res, (error) => {
        if (error) {
          res.destroy();
        }
      });
    });
    relay.on('error', (error) => {
      if (res.headersSent || res.destroyed) {
        res.destroy();
        return;
      }
      res.writeHead(502, { 'content-type': 'text/plain; charset=utf-8' });
      res.end(`api forwarder: ${req.method} ${req.url} to ${upstream.origin} failed: ${error.message}`);
    });
    // The client went away first (an APIRequestContext timeout or abort): stop the upstream call.
    res.on('close', () => {
      if (!res.writableFinished) {
        relay.destroy();
      }
    });
    req.on('error', () => relay.destroy());
    req.pipe(relay);
  });

  await new Promise<void>((resolve, reject) => {
    server.once('error', reject);
    server.listen(0, '127.0.0.1', () => {
      server.off('error', reject);
      resolve();
    });
  });
  const { port } = server.address() as AddressInfo;
  return {
    url: `http://127.0.0.1:${port}`,
    headers: { [FORWARDER_TOKEN_HEADER]: token },
    close: () =>
      new Promise<void>((resolve, reject) => {
        server.closeAllConnections();
        server.close((error) => (error ? reject(error) : resolve()));
      }),
  };
}

/**
 * Playwright's `test` extended with the suite's fixtures. Import it, with `expect`, from this module
 * in every spec.
 *
 * - `startPath` (option, default `/`): where `asInquiry` and `asMaintenance` land.
 * - `asInquiry` / `asMaintenance`: a page in its own browser context, signed in through the
 *   sign-in page and showing `startPath`; the context is closed after the test.
 * - `api`: an API request context authenticated as the Sales user. It goes through a loopback
 *   forwarder that adds the explicit Basic `Authorization` header to each request it relays to
 *   `baseURL`, so no Playwright call log, trace or report holds the credential (never
 *   `httpCredentials`: with `X-Requested-With` present the server sends no challenge, so
 *   challenge-driven credentials would never be sent).
 *
 * Browser contexts and the forwarder receive `baseURL` explicitly rather than inheriting it from
 * the config.
 */
export const test = base.extend<Fixtures, WorkerFixtures>({
  // Worker-scoped and auto: checks the effective trace (config, --trace, UI mode) before any credential.
  traceGuard: [
    async ({ trace }, use) => {
      assertTraceAllowed(trace);
      await use();
    },
    { scope: 'worker', auto: true, box: true },
  ],

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
    const forwarder = await startCredentialForwarder(requireBaseURL(baseURL), USERS.maintenance);
    try {
      const context = await playwright.request.newContext({
        baseURL: forwarder.url,
        extraHTTPHeaders: {
          ...forwarder.headers,
          'X-Requested-With': 'XMLHttpRequest',
          Accept: 'application/json, application/problem+json',
        },
      });
      try {
        await use(context);
      } finally {
        await context.dispose();
      }
    } catch (error) {
      try {
        await forwarder.close();
      } catch (closeError) {
        throw new AggregateError(
          [error, closeError],
          'api: the request context failed, and closing its forwarder failed',
        );
      }
      throw error;
    }
    await forwarder.close();
  },
});
