/**
 * Default MSW handlers and shared fixtures for the Vitest suites.
 *
 * Part of the frontend test base, with `server.ts` (the one MSW server, built
 * from `handlers` below) and `setup.ts` (its lifecycle). Every `/api` call a
 * component makes under Vitest is answered here, so no test reaches a network.
 * A test that needs another answer overrides one route for itself:
 *
 * ```ts
 * server.use(
 *   http.post('/api/customers/review', () =>
 *     problem(422, 'DEM0502', {
 *       args: ['Name'],
 *       errors: [{ field: 'name', code: 'DEM0502', message: messageText('DEM0502', ['Name']) }],
 *     }),
 *   ),
 * );
 * ```
 *
 * Layering. This file imports nothing from `src/` (not even types from
 * `../api` or the generated `schema.d.ts`): the transport tests and the feature
 * tests import from here, so any import back would create a folder cycle. The
 * fixture types are therefore declared locally, with the property names of the
 * OpenAPI contract in `customer-master/openapi/customer-master-api.yaml`.
 *
 * Fixture data. The states are the 58 rows of 5250_Subfile/States.sql; the
 * customers are the first 30 seed rows of 5250_Subfile/Custmast.sql after the
 * seed migration's transforms (base-36 re-keying, uppercase name and city,
 * even ids made active, the special-character names of ids 3, 4 and 6); the
 * message texts are the backend catalog `messages.properties`. All exported
 * fixtures are deep-frozen, so neither a handler nor a test can change them
 * and every test starts from the same data.
 *
 * MSW 3 serves `http` and `HttpResponse` from the `msw/http` entry point;
 * `setupServer` stays in `msw/node`, which only `server.ts` imports.
 */
import { http, HttpResponse } from 'msw/http';

// ---------------------------------------------------------------------------
// Fixture types (shaped by the OpenAPI schemas of the same purpose)
// ---------------------------------------------------------------------------

/** A role as `GET /api/session` reports it. */
export type Role = 'INQUIRY' | 'MAINTENANCE';

/** One row of `GET /api/states` (schema `StateResponse`). */
export interface StateFixture {
  state: string;
  name: string;
}

/** One search result row (schema `CustomerSummaryResponse`). */
export interface CustomerSummaryFixture {
  custId: string;
  name: string;
  city: string;
  state: string;
  zip5: string;
  active: string;
}

/** The nine editable customer fields (schema `CustomerFields`). */
export interface CustomerFieldsFixture {
  name: string;
  addr: string;
  city: string;
  state: string;
  zip: string;
  corpPhone: string;
  acctMgr: string;
  acctPhone: string;
  active: string;
}

/** A stored customer (schema `CustomerResponse`); `chgTime` is an ISO-8601 instant. */
export interface CustomerResponseFixture extends CustomerFieldsFixture {
  custId: string;
  chgTime: string;
  chgUser: string;
  version: number;
}

/** One entry of a problem's `errors`; the first entry receives focus. */
export interface FieldErrorFixture {
  field: string;
  code: string;
  message: string;
}

/** Optional members of a problem built by {@link problem}. */
export interface ProblemExtra {
  /** Substitution values for the catalog text; also sent as `args`. */
  args?: string[];
  /** The request path; defaults to `/api`. */
  instance?: string;
  /** Overrides the catalog text, e.g. for DEM9898's USPS description. */
  detail?: string;
  errors?: FieldErrorFixture[];
  /** 409 DEM1002 only: the customer as now stored. */
  current?: CustomerResponseFixture;
  /** Review failures after the State rule passed. */
  stateAccepted?: string;
  /** 500 only. */
  errorId?: string;
}

/** The problem+json body, members in the order the backend writes them. */
interface ProblemBody {
  type: string;
  title: string;
  status: number;
  instance: string;
  detail: string;
  code: string;
  args: string[];
  errors?: FieldErrorFixture[];
  current?: CustomerResponseFixture;
  stateAccepted?: string;
  errorId?: string;
}

/** A demo user: the Compose defaults of `CM_INQUIRY_*` and `CM_MAINTENANCE_*`. */
interface UserFixture {
  username: string;
  password: string;
  roles: Role[];
}

/** A non-error message carried in a success payload (schema `Notice`). */
interface NoticeFixture {
  code: string;
  message: string;
}

// ---------------------------------------------------------------------------
// Constants
// ---------------------------------------------------------------------------

/** The `chgTime` of every fixture customer and of every write answered here. */
export const FIXTURE_CHG_TIME = '2026-10-05T14:03:09Z';

/** The stamp user of seed and generated rows. */
const SYSTEM_USER = '*SYSTEM*';

/** The first id an interactive add receives on a fresh database. */
const FIRST_ADDED_ID = 'EEEF';

/** Search page size the UI uses, and the API's upper bound. */
const DEFAULT_PAGE_SIZE = 12;
const MAX_PAGE_SIZE = 100;

/** Four characters of the base-36 alphabet, as the API validates path ids. */
const CUST_ID_PATTERN = /^[A-Z0-9]{4}$/;

/** The opaque search cursor of these handlers: the next page number. */
const CURSOR_PATTERN = /^page:(\d+)$/;

/** HTTP reason phrases for the problem `title`. */
const REASON_PHRASES: Readonly<Record<number, string>> = {
  400: 'Bad Request',
  401: 'Unauthorized',
  403: 'Forbidden',
  404: 'Not Found',
  405: 'Method Not Allowed',
  409: 'Conflict',
  415: 'Unsupported Media Type',
  422: 'Unprocessable Entity',
  500: 'Internal Server Error',
  502: 'Bad Gateway',
  503: 'Service Unavailable',
};

/** Freezes a fixture and everything it holds; returns it with its declared type. */
function deepFreeze<T>(value: T): T {
  if (typeof value === 'object' && value !== null && !Object.isFrozen(value)) {
    Object.freeze(value);
    const members: unknown[] = Object.values(value);
    for (const member of members) {
      deepFreeze(member);
    }
  }
  return value;
}

// ---------------------------------------------------------------------------
// Message catalog
// ---------------------------------------------------------------------------

/**
 * The 22 catalog texts, identical to the backend's
 * `customer-api/src/main/resources/messages/messages.properties` (checked key
 * for key; no difference). The DEM texts are the customer message file's, with
 * `&1` written `{0}` and three typo fixes: DEM0007 "is invalid", DEM0009 with a
 * single space, DEM1002 "Review" with a single space. There is no DEM0001.
 * Test-only data: the application bundle carries no message text and reads the
 * catalog from `GET /api/messages`.
 */
export const catalog: Readonly<Record<string, string>> = deepFreeze({
  DEM0000: 'Press Enter to update. F12 to Cancel.',
  DEM0002: 'No records match the selection criteria',
  DEM0003: 'Key is not active now',
  DEM0004: '{0} is not a valid option at this time.',
  DEM0005: 'Use F4 only if + is on field',
  DEM0006: 'Too many records. Change the selection criteria.',
  DEM0007: 'State selection field is invalid.',
  DEM0008: 'Use F4 only in field followed by +',
  DEM0009: 'Press Enter to add. Press F12 to cancel',
  DEM0501: '{0}: Must be Y or N',
  DEM0502: '{0}: Must not be blank',
  DEM0503: 'State invalid. Can use F4 to prompt.',
  DEM0599: 'Customer deleted. Exit & redo search.',
  DEM1001: 'Customer being updated by another user or job.',
  DEM1002: 'Someone else changed record. Review data.',
  DEM9898: 'USPS: {0}',
  DEM9999: 'Program Error! Please contact IT now.',
  APP0400: 'Request is not valid: {0}',
  APP0401: 'Sign in required.',
  APP0403: 'You are not authorized to perform this action.',
  APP0502: 'Address service is unavailable. Try again later.',
  APP0503: 'No customer ids are left. Contact IT.',
});

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

/**
 * Returns the catalog text of `code` with every `{n}` replaced literally by
 * `args[n]`, the substitution rule the server and the client share. It is not
 * `MessageFormat`: apostrophes and braces need no quoting, and a placeholder
 * without an argument stays as written. An unknown code yields the code itself.
 *
 * @example messageText('DEM0004', ['X']) // 'X is not a valid option at this time.'
 */
export function messageText(code: string, args: readonly string[] = []): string {
  const template = Object.hasOwn(catalog, code) ? (catalog[code] ?? code) : code;
  // A replacer function inserts its result verbatim, so `$` in an argument is
  // never read as a replacement pattern.
  return template.replace(/\{(\d+)\}/g, (match: string, index: string) => args[Number(index)] ?? match);
}

/** The `Authorization` header value for HTTP Basic credentials. */
export function basicAuth(username: string, password: string): string {
  return `Basic ${btoa(`${username}:${password}`)}`;
}

/**
 * The Compose demo users and their roles. `roles` is exactly what
 * `GET /api/session` returns for each. The passwords are the published,
 * demo-only Compose defaults (overridden through `.env` in any real
 * deployment), not secrets; they are kept verbatim so tests sign in exactly
 * as a user of the demo stack does.
 */
export const users: readonly UserFixture[] = deepFreeze([
  { username: 'inquiry', password: 'inquiry-demo', roles: ['INQUIRY'] },
  { username: 'sales', password: 'sales-demo', roles: ['MAINTENANCE'] },
]);

/**
 * The demo user a request authenticates as, from its Basic `Authorization`
 * header, or `undefined` for a missing, malformed or unknown credential.
 */
function principal(request: Request): { username: string; roles: Role[] } | undefined {
  const header = request.headers.get('Authorization');
  const scheme = 'Basic ';
  if (header === null || !header.startsWith(scheme)) {
    return undefined;
  }
  let decoded: string;
  try {
    decoded = atob(header.slice(scheme.length));
  } catch {
    return undefined;
  }
  // The password may itself contain ':', so split at the first one only.
  const colon = decoded.indexOf(':');
  if (colon < 0) {
    return undefined;
  }
  const username = decoded.slice(0, colon);
  const password = decoded.slice(colon + 1);
  const user = users.find((u) => u.username === username && u.password === password);
  return user === undefined ? undefined : { username: user.username, roles: [...user.roles] };
}

/**
 * An RFC 9457 `application/problem+json` response in the API's error shape:
 * `type`, `title`, `status`, `instance`, `detail`, `code` and `args`, then
 * `errors`, `current`, `stateAccepted` and `errorId` only when supplied.
 * `detail` defaults to the catalog text of `code` with `extra.args` substituted.
 *
 * @example problem(409, 'DEM1002', { current: customerDetail, instance: '/api/customers/AAAD' })
 */
export function problem(status: number, code: string, extra: ProblemExtra = {}) {
  const args = extra.args ?? [];
  const body: ProblemBody = {
    type: `urn:customer-master:problem:${code}`,
    title: REASON_PHRASES[status] ?? 'Error',
    status,
    instance: extra.instance ?? '/api',
    detail: extra.detail ?? messageText(code, args),
    code,
    args: [...args],
  };
  if (extra.errors !== undefined) {
    body.errors = extra.errors.map((error) => ({ ...error }));
  }
  if (extra.current !== undefined) {
    body.current = { ...extra.current };
  }
  if (extra.stateAccepted !== undefined) {
    body.stateAccepted = extra.stateAccepted;
  }
  if (extra.errorId !== undefined) {
    body.errorId = extra.errorId;
  }
  // An explicit Content-Type is kept by HttpResponse.json, which sets
  // application/json only when the caller supplies none.
  return HttpResponse.json(body, {
    status,
    headers: { 'Content-Type': 'application/problem+json' },
  });
}

/**
 * The length-preserving uppercase rule of the server's text normalizer: each
 * code point takes its uppercase form only when that form is a single code
 * point, so `ß` stays `ß` (its full mapping is `SS`) while `é` becomes `É`.
 */
function upper(value: string): string {
  let result = '';
  for (const ch of value) {
    const mapped = ch.toUpperCase();
    result += [...mapped].length === 1 ? mapped : ch;
  }
  return result;
}

/** Whether a parsed body is a JSON object (not an array), as every write endpoint requires. */
function isJsonObject(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

/** A JSON object as a record, or `{}` for anything else (arrays included). */
function asRecord(value: unknown): Record<string, unknown> {
  return isJsonObject(value) ? value : {};
}

/** A string value, or `''` for anything else. */
function str(value: unknown): string {
  return typeof value === 'string' ? value : '';
}

/** The parsed JSON body, or `undefined` when the body is absent or not JSON. */
async function readJson(request: Request): Promise<unknown> {
  try {
    const body: unknown = await request.json();
    return body;
  } catch {
    return undefined;
  }
}

/** The path a problem's `instance` names. */
function pathOf(request: Request): string {
  return new URL(request.url).pathname;
}

/** A success-payload notice with its catalog text. */
function notice(code: string): NoticeFixture {
  return { code, message: messageText(code) };
}

/** Code-unit order, which for these fixtures equals the database collation. */
function compareText(a: string, b: string): number {
  if (a < b) {
    return -1;
  }
  return a > b ? 1 : 0;
}

/**
 * The nine customer fields of a request body, normalized as the server does:
 * uppercased by {@link upper}, trailing whitespace removed, a non-string read
 * as `''`. With `defaultActive`, an `active` that is absent or `null` becomes
 * `Y` (the add default); a value that is present is kept as given.
 */
function customerFields(body: Record<string, unknown>, defaultActive: boolean): CustomerFieldsFixture {
  const field = (key: keyof CustomerFieldsFixture): string => upper(str(body[key])).trimEnd();
  const active = body.active;
  return {
    name: field('name'),
    addr: field('addr'),
    city: field('city'),
    state: field('state'),
    zip: field('zip'),
    corpPhone: field('corpPhone'),
    acctMgr: field('acctMgr'),
    acctPhone: field('acctPhone'),
    active: defaultActive && (active === undefined || active === null) ? 'Y' : field('active'),
  };
}

// ---------------------------------------------------------------------------
// States (5250_Subfile/States.sql, insertion order, names as written)
// ---------------------------------------------------------------------------

/** The 58 STATES rows in source insertion order. */
export const states: readonly StateFixture[] = deepFreeze([
  { state: 'AA', name: 'Armed Forces America' },
  { state: 'AE', name: 'Armed Forces' },
  { state: 'AK', name: 'Alaska' },
  { state: 'AL', name: 'Alabama' },
  { state: 'AS', name: 'American Samoa' },
  { state: 'AZ', name: 'Arizona' },
  { state: 'AR', name: 'Arkansas' },
  { state: 'CA', name: 'California' },
  { state: 'CO', name: 'Colorado' },
  { state: 'CT', name: 'Connecticut' },
  { state: 'DE', name: 'Delaware' },
  { state: 'DC', name: 'District of Columbia' },
  { state: 'FL', name: 'Florida' },
  { state: 'GA', name: 'Georgia' },
  { state: 'GU', name: 'Guam' },
  { state: 'HI', name: 'Hawaii' },
  { state: 'ID', name: 'Idaho' },
  { state: 'IL', name: 'Illinois' },
  { state: 'IN', name: 'Indiana' },
  { state: 'IA', name: 'Iowa' },
  { state: 'KS', name: 'Kansas' },
  { state: 'KY', name: 'Kentucky' },
  { state: 'LA', name: 'Louisiana' },
  { state: 'ME', name: 'Maine' },
  { state: 'MD', name: 'Maryland' },
  { state: 'MA', name: 'Massachusetts' },
  { state: 'MI', name: 'Michigan' },
  { state: 'MN', name: 'Minnesota' },
  { state: 'MP', name: 'Northern Mariana Islands' },
  { state: 'MS', name: 'Mississippi' },
  { state: 'MO', name: 'Missouri' },
  { state: 'MT', name: 'Montana' },
  { state: 'NE', name: 'Nebraska' },
  { state: 'NV', name: 'Nevada' },
  { state: 'NH', name: 'New Hampshire' },
  { state: 'NJ', name: 'New Jersey' },
  { state: 'NM', name: 'New Mexico' },
  { state: 'NY', name: 'New York' },
  { state: 'NC', name: 'North Carolina' },
  { state: 'ND', name: 'North Dakota' },
  { state: 'OH', name: 'Ohio' },
  { state: 'OK', name: 'Oklahoma' },
  { state: 'OR', name: 'Oregon' },
  { state: 'PA', name: 'Pennsylvania' },
  { state: 'PR', name: 'Puerto Rico' },
  { state: 'RI', name: 'Rhode Island' },
  { state: 'SC', name: 'South Carolina' },
  { state: 'SD', name: 'South Dakota' },
  { state: 'TN', name: 'Tennessee' },
  { state: 'TX', name: 'Texas' },
  { state: 'UT', name: 'Utah' },
  { state: 'VT', name: 'Vermont' },
  { state: 'VA', name: 'Virginia' },
  { state: 'WA', name: 'Washington' },
  { state: 'WV', name: 'West Virginia' },
  { state: 'WI', name: 'Wisconsin' },
  { state: 'VI', name: 'Virgin Islands' },
  { state: 'WY', name: 'Wyoming' },
]);


// ---------------------------------------------------------------------------
// Customers (first 30 seed rows of 5250_Subfile/Custmast.sql, as seeded)
// ---------------------------------------------------------------------------

/**
 * Seed rows 1..30 as the search returns them, ordered by name, city, state and
 * custId. Ids are the base-36 ordinals (A..Z = 0..25, 0..9 = 26..35), so
 * original id 1 is AAAB, 26 is AAA0 and 30 is AAA4; name and city are
 * uppercased; even original ids are active; ids 3, 4 and 6 carry the
 * apostrophe, double-quote and backslash names; `zip5` is the ZIP's first five
 * characters. 23 rows are active and 7 inactive, so at the default size of 12
 * the active-only list has two pages (12 + 11) and the full list three
 * (12 + 12 + 6).
 */
export const customers: readonly CustomerSummaryFixture[] = deepFreeze([
  { custId: 'AAAX', name: 'ALIQUAM ORNARE LIBERO ASSOCIATES', city: 'LEXINGTON', state: 'KY', zip5: '75787', active: 'N' },
  { custId: 'AAAK', name: 'ALIQUET INC.', city: 'CEDAR RAPIDS', state: 'IA', zip5: '45367', active: 'Y' },
  { custId: 'AAAB', name: 'ALIQUET NEC IMPERDIET LIMITED', city: 'DES MOINES', state: 'IA', zip5: '90911', active: 'N' },
  { custId: 'AAAY', name: 'AUCTOR LIMITED', city: 'GILLETTE', state: 'WY', zip5: '91423', active: 'Y' },
  { custId: 'AAAE', name: 'BLANDIT "AT NISI" INDUSTRIES', city: 'NAMPA', state: 'ID', zip5: '59290', active: 'Y' },
  { custId: 'AAAI', name: 'CONSEQUAT ENIM DIAM CONSULTING', city: 'SPRINGDALE', state: 'AR', zip5: '72663', active: 'Y' },
  { custId: 'AAAW', name: 'FACILISIS ASSOCIATES', city: 'BILOXI', state: 'MS', zip5: '12944', active: 'Y' },
  { custId: 'AAAP', name: 'FACILISIS NON LLC', city: 'TUCSON', state: 'AZ', zip5: '86022', active: 'N' },
  { custId: 'AAAJ', name: 'FEUGIAT TELLUS LLC', city: 'LAKEWOOD', state: 'CO', zip5: '62383', active: 'N' },
  { custId: 'AAA2', name: 'INTERDUM COMPANY', city: 'SAINT LOUIS', state: 'MO', zip5: '17666', active: 'Y' },
  { custId: 'AAAS', name: 'LOBORTIS QUAM INCORPORATED', city: 'JACKSON', state: 'MS', zip5: '48255', active: 'Y' },
  { custId: 'AAAN', name: 'LOBORTIS ULTRICES VIVAMUS CORPORATION', city: 'JOLIET', state: 'IL', zip5: '77259', active: 'N' },
  { custId: 'AAAM', name: 'MAGNA PHASELLUS DOLOR INDUSTRIES', city: 'BLOOMINGTON', state: 'MN', zip5: '94856', active: 'Y' },
  { custId: 'AAAZ', name: 'MASSA INCORPORATED', city: 'CLARKSVILLE', state: 'TN', zip5: '47955', active: 'Y' },
  { custId: 'AAA0', name: 'MI TEMPOR LOREM INCORPORATED', city: 'SACRAMENTO', state: 'CA', zip5: '93423', active: 'Y' },
  { custId: 'AAAL', name: 'MOLESTIE SED LLP', city: 'HILO', state: 'HI', zip5: '68029', active: 'Y' },
  { custId: 'AAAC', name: 'NAM PORTTITOR LLP', city: 'ROCKVILLE', state: 'MD', zip5: '60342', active: 'Y' },
  { custId: 'AAAD', name: "NIBH L'LOR COMPANY", city: 'AUBURN', state: 'ME', zip5: '15762', active: 'Y' },
  { custId: 'AAAH', name: 'ORCI IN INDUSTRIES', city: 'LOS ANGELES', state: 'CA', zip5: '91019', active: 'Y' },
  { custId: 'AAA3', name: 'ORCI TINCIDUNT INDUSTRIES', city: 'ATHENS', state: 'GA', zip5: '92897', active: 'N' },
  { custId: 'AAAO', name: 'ORNARE LIBERO LIMITED', city: 'SOUTH BEND', state: 'IN', zip5: '95819', active: 'Y' },
  { custId: 'AAA1', name: 'ORNARE PLACERAT INSTITUTE', city: 'MADISON', state: 'WI', zip5: '56718', active: 'Y' },
  { custId: 'AAAU', name: 'PHASELLUS NULLA FOUNDATION', city: 'KENOSHA', state: 'WI', zip5: '56417', active: 'Y' },
  { custId: 'AAAT', name: 'PURUS CORPORATION', city: 'MONTGOMERY', state: 'AL', zip5: '35900', active: 'N' },
  { custId: 'AAAQ', name: 'SED SEM ASSOCIATES', city: 'EVANSVILLE', state: 'IN', zip5: '89459', active: 'Y' },
  { custId: 'AAAF', name: 'TINCIDUNT NEQUE PC', city: 'TULSA', state: 'OK', zip5: '59509', active: 'Y' },
  { custId: 'AAAG', name: 'URNA \\NUNC\\ COMPANY', city: 'PITTSBURGH', state: 'PA', zip5: '18268', active: 'Y' },
  { custId: 'AAAV', name: 'UT PC', city: 'GARY', state: 'IN', zip5: '75450', active: 'Y' },
  { custId: 'AAA4', name: 'VEHICULA ALIQUET LIBERO LLP', city: 'JACKSONVILLE', state: 'FL', zip5: '81238', active: 'Y' },
  { custId: 'AAAR', name: 'VELIT EU SEM LLP', city: 'NEWPORT NEWS', state: 'VA', zip5: '55708', active: 'Y' },
]);

/**
 * Seed row 3 (AAAD) as stored. Address and account manager keep the seed's
 * mixed case and double spaces, because the seed rows are not normalized.
 */
const AAAD_DETAIL: CustomerResponseFixture = deepFreeze({
  custId: 'AAAD',
  name: "NIBH L'LOR COMPANY",
  addr: 'P.O. Box 103,  9218 Vivamus Avenue',
  city: 'AUBURN',
  state: 'ME',
  zip: '15762-0001',
  corpPhone: '(714)825-5082',
  acctMgr: 'Norman,  Abbot R.',
  acctPhone: '(757)158-0941',
  active: 'Y',
  chgTime: FIXTURE_CHG_TIME,
  chgUser: SYSTEM_USER,
  version: 0,
});

/** Seed row 6 (AAAG) as stored; its name holds literal backslashes. */
const AAAG_DETAIL: CustomerResponseFixture = deepFreeze({
  custId: 'AAAG',
  name: 'URNA \\NUNC\\ COMPANY',
  addr: 'Ap #424-1044 A Road',
  city: 'PITTSBURGH',
  state: 'PA',
  zip: '18268',
  corpPhone: '(766)830-5390',
  acctMgr: 'Buck,  Wallace L.',
  acctPhone: '(633)641-9522',
  active: 'Y',
  chgTime: FIXTURE_CHG_TIME,
  chgUser: SYSTEM_USER,
  version: 0,
});

/** Full records of the two seed rows the e2e specs also use, by custId. */
export const customerDetails: Readonly<Record<string, CustomerResponseFixture>> = deepFreeze({
  AAAD: AAAD_DETAIL,
  AAAG: AAAG_DETAIL,
});

/** The default detail fixture: `NIBH L'LOR COMPANY` (AAAD). */
export const customerDetail: CustomerResponseFixture = AAAD_DETAIL;

/**
 * The stored record for `custId`: a full seed fixture where one exists,
 * otherwise a record derived from the search fixture with fixed test values
 * for the fields a summary lacks, otherwise `undefined` (no such customer).
 */
function detailFor(custId: string): CustomerResponseFixture | undefined {
  if (Object.hasOwn(customerDetails, custId)) {
    return customerDetails[custId];
  }
  const summary = customers.find((customer) => customer.custId === custId);
  if (summary === undefined) {
    return undefined;
  }
  return {
    custId: summary.custId,
    name: summary.name,
    addr: '100 MAIN STREET',
    city: summary.city,
    state: summary.state,
    zip: summary.zip5,
    corpPhone: '(555) 010-0001',
    acctMgr: 'TEST MANAGER',
    acctPhone: '(555) 010-0002',
    active: summary.active,
    chgTime: FIXTURE_CHG_TIME,
    chgUser: SYSTEM_USER,
    version: 0,
  };
}

// ---------------------------------------------------------------------------
// Default handlers
// ---------------------------------------------------------------------------

/**
 * One handler per API route, answering as the backend does for valid input
 * and with its 400 problems for the parameter guards. Paths are relative, so
 * MSW matches them against jsdom's `location`, the origin `setup.ts` resolves
 * relative `fetch` URLs against. Only `/api/session` checks credentials; a test
 * that needs 401 or 403 elsewhere overrides the route with `server.use(...)`.
 * No handler mutates a fixture: every response body is a fresh object.
 */
export const handlers = [
  // Public: the whole catalog as one JSON object.
  http.get('/api/messages', () => HttpResponse.json({ ...catalog })),

  // The signed-in user and roles, or 401 APP0401.
  http.get('/api/session', ({ request }) => {
    const user = principal(request);
    if (user === undefined) {
      return problem(401, 'APP0401', { instance: pathOf(request) });
    }
    return HttpResponse.json({ username: user.username, roles: user.roles });
  }),

  // States: `nameContains` matched case-insensitively anywhere in the name,
  // `sort` = name (default) or code.
  http.get('/api/states', ({ request }) => {
    const url = new URL(request.url);
    const instance = url.pathname;
    // As with the backend's `defaultValue`, an empty `sort` means the default.
    const sort = url.searchParams.get('sort') || 'name';
    if (sort !== 'name' && sort !== 'code') {
      return problem(400, 'APP0400', { args: ['sort'], instance });
    }
    const filter = upper((url.searchParams.get('nameContains') ?? '').trim());
    const rows = states
      .filter((row) => upper(row.name).includes(filter))
      .map((row) => ({ state: row.state, name: row.name }));
    rows.sort((a, b) => (sort === 'name' ? compareText(a.name, b.name) : compareText(a.state, b.state)));
    return HttpResponse.json(rows);
  }),

  // Customer search: prefix filters, exact state, active-only unless
  // includeInactive=true, pages of `size` rows behind a `page:<n>` cursor.
  http.get('/api/customers', ({ request }) => {
    const url = new URL(request.url);
    const instance = url.pathname;
    const query = url.searchParams;
    const name = upper((query.get('name') ?? '').trim());
    const city = upper((query.get('city') ?? '').trim());
    const state = upper((query.get('state') ?? '').trim());
    const includeInactive = query.get('includeInactive') === 'true';

    if (state.length !== 0 && state.length !== 2) {
      return problem(400, 'DEM0007', {
        errors: [{ field: 'state', code: 'DEM0007', message: messageText('DEM0007') }],
        instance,
      });
    }

    // An empty parameter counts as absent, as Spring binds it to null.
    const sizeParam = query.get('size') || null;
    let size = DEFAULT_PAGE_SIZE;
    if (sizeParam !== null) {
      size = /^\d+$/.test(sizeParam) ? Number(sizeParam) : Number.NaN;
      if (!Number.isSafeInteger(size) || size < 1 || size > MAX_PAGE_SIZE) {
        return problem(400, 'APP0400', { args: ['size'], instance });
      }
    }

    const cursor = query.get('cursor') || null;
    let page = 1;
    if (cursor !== null) {
      page = Number(CURSOR_PATTERN.exec(cursor)?.[1]);
      if (!Number.isSafeInteger(page) || page < 2) {
        return problem(400, 'APP0400', { args: ['cursor'], instance });
      }
    }

    // Fixture order is kept: `customers` is already in search order, and a
    // test that serves its own list controls its order the same way.
    const matches = customers.filter(
      (customer) =>
        customer.name.startsWith(name) &&
        customer.city.startsWith(city) &&
        (state.length !== 2 || customer.state === state) &&
        (includeInactive || customer.active === 'Y'),
    );
    const start = (page - 1) * size;
    const items = matches.slice(start, start + size).map((customer) => ({ ...customer }));
    return HttpResponse.json({
      items,
      nextCursor: start + size < matches.length ? `page:${page + 1}` : null,
      limitReached: false,
      notice: matches.length === 0 ? notice('DEM0002') : null,
    });
  }),

  // One customer, or 404 DEM0599.
  http.get<{ custId: string }>('/api/customers/:custId', ({ request, params }) => {
    const instance = pathOf(request);
    const { custId } = params;
    if (!CUST_ID_PATTERN.test(custId)) {
      return problem(400, 'APP0400', { args: ['custId'], instance });
    }
    const detail = detailFor(custId);
    if (detail === undefined) {
      return problem(404, 'DEM0599', { instance });
    }
    return HttpResponse.json({ ...detail });
  }),

  // Review: normalized values and the confirmation notice; no field rule runs
  // here (tests override the route for 422, 502 and stateAccepted cases).
  http.post('/api/customers/review', async ({ request }) => {
    const instance = pathOf(request);
    const body = asRecord(await readJson(request));
    const purpose = body.purpose;
    if (purpose !== 'ADD' && purpose !== 'EDIT') {
      return problem(400, 'APP0400', { args: ['purpose'], instance });
    }
    return HttpResponse.json({
      customer: customerFields(body, purpose === 'ADD'),
      standardized: false,
      notice: notice(purpose === 'ADD' ? 'DEM0009' : 'DEM0000'),
    });
  }),

  // Add: 201 with the first interactive id and version 0.
  http.post('/api/customers', async ({ request }) => {
    const instance = pathOf(request);
    const body = await readJson(request);
    if (!isJsonObject(body)) {
      return problem(400, 'APP0400', { args: ['malformed request body'], instance });
    }
    const created: CustomerResponseFixture = {
      ...customerFields(body, true),
      custId: FIRST_ADDED_ID,
      chgTime: FIXTURE_CHG_TIME,
      chgUser: principal(request)?.username ?? 'sales',
      version: 0,
    };
    return HttpResponse.json(created, {
      status: 201,
      headers: { Location: `/api/customers/${FIRST_ADDED_ID}` },
    });
  }),

  // Update: 200 with the version incremented and the principal as stamp user.
  http.put<{ custId: string }>('/api/customers/:custId', async ({ request, params }) => {
    const instance = pathOf(request);
    const { custId } = params;
    if (!CUST_ID_PATTERN.test(custId)) {
      return problem(400, 'APP0400', { args: ['custId'], instance });
    }
    const body = asRecord(await readJson(request));
    const version = body.version;
    if (typeof version !== 'number' || !Number.isSafeInteger(version)) {
      return problem(400, 'APP0400', { args: ['version'], instance });
    }
    const updated: CustomerResponseFixture = {
      ...customerFields(body, false),
      custId,
      chgTime: FIXTURE_CHG_TIME,
      chgUser: principal(request)?.username ?? 'sales',
      version: version + 1,
    };
    return HttpResponse.json(updated);
  }),
];
