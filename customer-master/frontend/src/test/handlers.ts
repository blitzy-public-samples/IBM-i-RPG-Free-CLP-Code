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
 * Layering. The fixture and wire types are type-only aliases of the generated
 * `../api/schema` (from `customer-master/openapi/customer-master-api.yaml`),
 * the one source of API types, and are erased at compile time, so this file's
 * only runtime import is `msw/http`. `schema.d.ts` imports nothing, so the
 * tests that import this file form no import cycle with it. `UserFixture`, the
 * demo credentials, is the one shape declared here, because it is test-only
 * data.
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
import type { DefaultBodyType, PathParams } from 'msw';
import type { HttpHandler, HttpResponseResolver } from 'msw/http';
import type { components } from '../api/schema';

// ---------------------------------------------------------------------------
// Fixture types (aliases of the generated OpenAPI schemas in ../api/schema)
// ---------------------------------------------------------------------------

/** The generated OpenAPI component schemas. */
type Schemas = components['schemas'];

/** A role as `GET /api/session` reports it: an element of schema `SessionResponse`'s `roles`. */
export type Role = Schemas['SessionResponse']['roles'][number];

/** One row of `GET /api/states`: alias of schema `StateResponse`. */
export type StateFixture = Schemas['StateResponse'];

/** One search result row: alias of schema `CustomerSummaryResponse`. */
export type CustomerSummaryFixture = Schemas['CustomerSummaryResponse'];

/**
 * The nine editable customer fields of schema `CustomerFields`, as schema
 * `CustomerResponse` carries them: always present, never `null`.
 */
export type CustomerFieldsFixture = Pick<Schemas['CustomerResponse'], keyof Schemas['CustomerFields']>;

/** A stored customer: alias of schema `CustomerResponse`; `chgTime` is an ISO-8601 instant or `null`. */
export type CustomerResponseFixture = Schemas['CustomerResponse'];

/** One entry of a problem's `errors`, the first receiving focus: alias of schema `FieldError`. */
export type FieldErrorFixture = Schemas['FieldError'];

/** The problem+json body: alias of schema `Problem`. */
type ProblemBody = Schemas['Problem'];

/**
 * Optional members of a problem built by {@link problem}, each typed as schema
 * `Problem` declares it:
 *
 * - `args`: substitution values for the catalog text; also sent as `args`.
 * - `instance`: the request path; defaults to `/api/customers/review` when
 *   `stateAccepted` is given, and to `/api` otherwise.
 * - `detail`: overrides the catalog text, e.g. for DEM9898's USPS description.
 * - `errors`: the fields at fault; never empty, and 400 or 422 only.
 * - `current`: 409 DEM1002 only, and required there: the customer as now stored.
 * - `stateAccepted`: 422 and 502 review failures after the State rule passed;
 *   `instance` then defaults to `/api/customers/review`, the only path allowed.
 * - `errorId`: 500 only, a UUID; defaults to {@link DEFAULT_ERROR_ID}.
 *
 * {@link problem} throws when a member breaks this contract.
 */
export type ProblemExtra = Partial<
  Pick<ProblemBody, 'args' | 'instance' | 'detail' | 'errors' | 'current' | 'stateAccepted' | 'errorId'>
>;

/** A demo user: the Compose defaults of `CM_INQUIRY_*` and `CM_MAINTENANCE_*`. */
interface UserFixture {
  username: string;
  password: string;
  roles: Role[];
}

/** A non-error message carried in a success payload: alias of schema `Notice`. */
type NoticeFixture = Schemas['Notice'];

// ---------------------------------------------------------------------------
// Constants
// ---------------------------------------------------------------------------

/** The `chgTime` of every fixture customer and of every write answered here. */
export const FIXTURE_CHG_TIME = '2026-10-05T14:03:09Z';

/** The stamp user of seed and generated rows. */
const SYSTEM_USER = '*SYSTEM*';

/** The first id an interactive add receives on a fresh database. */
const FIRST_ADDED_ID = 'EEEF';

/** The base-36 digits of a customer id in ordinal order: A..Z = 0..25, 0..9 = 26..35. */
const CUST_ID_DIGITS = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789';

/** The ordinal of `9999`, the last customer id (36^4 - 1); no add follows it. */
const MAX_CUST_ID_ORDINAL = 36 ** 4 - 1;

/** Search page size the UI uses, and the API's upper bound. */
const DEFAULT_PAGE_SIZE = 12;
const MAX_PAGE_SIZE = 100;

/** Four characters of the base-36 alphabet, as the API validates path ids. */
const CUST_ID_PATTERN = /^[A-Z0-9]{4}$/;

/** The length of a customer id, in code points, that a `custId` path value must have exactly. */
const CUST_ID_LENGTH = 4;

/** The search's row cap (MAXSFLRECDS): the page that brings the rows served to it ends the list with DEM0006. */
const MAX_SEARCH_ROWS = 9999;

/** The longest search cursor, in UTF-16 units, the API decodes; a longer one is not valid. */
const MAX_CURSOR_LENGTH = 1024;

/** Widths, in code points, of the search entries `name` and `city` (SC_NAME, SC_CITY) and of their LIKE pattern. */
const FILTER_WIDTH = 13;

/** Width, in code points, of the state picker's `nameContains` entry. */
const NAME_CONTAINS_WIDTH = 10;

/** Widths, in code points, of the stored columns: `name`, `city`, `state` and the state table's `name`. */
const NAME_WIDTH = 40;
const CITY_WIDTH = 20;
const STATE_WIDTH = 2;
const STATE_NAME_WIDTH = 30;

/**
 * HTTP reason phrases for the problem `title`: Spring 6.2's
 * `HttpStatus.getReasonPhrase()` for every 4xx and 5xx status that
 * `HttpStatus.resolve` knows, the deprecated 419–421 included, spelled and
 * cased exactly as Spring has them (`Requested range not satisfiable`,
 * `HTTP Version not supported`). A status missing here, such as 499, has no
 * `title`, because the server sets it only for a status Spring resolves.
 */
const REASON_PHRASES: Readonly<Record<number, string>> = {
  400: 'Bad Request',
  401: 'Unauthorized',
  402: 'Payment Required',
  403: 'Forbidden',
  404: 'Not Found',
  405: 'Method Not Allowed',
  406: 'Not Acceptable',
  407: 'Proxy Authentication Required',
  408: 'Request Timeout',
  409: 'Conflict',
  410: 'Gone',
  411: 'Length Required',
  412: 'Precondition Failed',
  413: 'Payload Too Large',
  414: 'URI Too Long',
  415: 'Unsupported Media Type',
  416: 'Requested range not satisfiable',
  417: 'Expectation Failed',
  418: "I'm a teapot",
  419: 'Insufficient Space On Resource',
  420: 'Method Failure',
  421: 'Destination Locked',
  422: 'Unprocessable Entity',
  423: 'Locked',
  424: 'Failed Dependency',
  425: 'Too Early',
  426: 'Upgrade Required',
  428: 'Precondition Required',
  429: 'Too Many Requests',
  431: 'Request Header Fields Too Large',
  451: 'Unavailable For Legal Reasons',
  500: 'Internal Server Error',
  501: 'Not Implemented',
  502: 'Bad Gateway',
  503: 'Service Unavailable',
  504: 'Gateway Timeout',
  505: 'HTTP Version not supported',
  506: 'Variant Also Negotiates',
  507: 'Insufficient Storage',
  508: 'Loop Detected',
  509: 'Bandwidth Limit Exceeded',
  510: 'Not Extended',
  511: 'Network Authentication Required',
};

/**
 * The `errorId` of every 500 {@link problem} builds unless the caller passes
 * its own: a fixed, valid UUID, so a test can assert it. The server draws a
 * random one per failure and writes it to its ERROR log line.
 */
export const DEFAULT_ERROR_ID = '6f1d3c2a-8b4e-4f7a-9c5d-0e2b4a6c8d10';

/** A UUID in its canonical 8-4-4-4-12 hexadecimal form, as schema `Problem` types `errorId`. */
const UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

/** The path of `POST /api/customers/review`, the only source of `stateAccepted`. */
const REVIEW_PATH = '/api/customers/review';

/**
 * The fields of the field rules that run before the State rule (rules 1–4:
 * `active`, `name`, `addr`, `city`); a review failing on one of them never
 * carries `stateAccepted`.
 */
const PRE_STATE_RULE_FIELDS: ReadonlySet<string> = new Set(['active', 'name', 'addr', 'city']);

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

/**
 * The `Authorization` header value for HTTP Basic credentials: `Basic` and the
 * base64 of the UTF-8 bytes of `username:password`, the encoding the server
 * decodes, so non-Latin-1 credentials are encoded rather than rejected.
 */
export function basicAuth(username: string, password: string): string {
  let binary = '';
  for (const byte of new TextEncoder().encode(`${username}:${password}`)) {
    binary += String.fromCharCode(byte);
  }
  return `Basic ${btoa(binary)}`;
}

/**
 * The Compose demo users and their roles. `roles` is exactly what
 * `GET /api/session` returns for each. The passwords are the published,
 * demo-only Compose defaults (overridden through `.env` in any real
 * deployment), not secrets; they are kept verbatim so tests sign in exactly
 * as a user of the demo stack does. As in the server's user store, a username
 * matches in any case and the user is reported by the name configured here.
 */
export const users: readonly UserFixture[] = deepFreeze([
  { username: 'inquiry', password: 'inquiry-demo', roles: ['INQUIRY'] },
  { username: 'sales', password: 'sales-demo', roles: ['MAINTENANCE'] },
]);

// ---------------------------------------------------------------------------
// Security (the server's filter chain: firewall, HTTP Basic, access rules)
// ---------------------------------------------------------------------------

/** Who may call a route: anyone, or a user granted the role or one implying it. */
type Access = 'public' | Role;

/**
 * What a request's `Authorization` header establishes: no credentials (none,
 * or another scheme), credentials the server rejects, or a configured user.
 */
type Authentication =
  | { readonly outcome: 'anonymous' }
  | { readonly outcome: 'rejected' }
  | { readonly outcome: 'authenticated'; readonly user: Schemas['SessionResponse'] };

/** The `WWW-Authenticate` value of a 401 answered to a caller other than the SPA. */
const BASIC_CHALLENGE = 'Basic realm="customer-master"';

/** The standard base64 alphabet in sextet order; the URL-safe `-` and `_` are not in it. */
const BASE64_DIGITS = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/';

/**
 * What the server's request firewall rejects in the raw, still percent-encoded
 * path before any authentication, compared with the path lowercased: `;` and
 * its encoding, an encoded `%`, `.`, CR or LF, an encoded line or paragraph
 * separator (U+2028, U+2029), and an empty segment. It never inspects the
 * query string.
 */
const FIREWALL_REJECTED: readonly string[] = [';', '%3b', '%25', '%2e', '%0d', '%0a', '%e2%80%a8', '%e2%80%a9', '//'];

/** The user each request was admitted as by {@link secured}, read by {@link principal}. */
const principals = new WeakMap<Request, Schemas['SessionResponse']>();

/** `value` without the leading and trailing characters at or below U+0020, as Java's `String.trim()`. */
function javaTrim(value: string): string {
  let start = 0;
  let end = value.length;
  while (start < end && value.charCodeAt(start) <= 0x20) {
    start += 1;
  }
  while (end > start && value.charCodeAt(end - 1) <= 0x20) {
    end -= 1;
  }
  return value.slice(start, end);
}

/**
 * The bytes of `text` decoded as Java's `Base64.getDecoder()` decodes them, or
 * `undefined` where it throws: only {@link BASE64_DIGITS} (no whitespace);
 * padding optional, but where present it completes the final unit (`xx==`,
 * `xxx=`) and ends the text; a final unit of one character is invalid; unused
 * trailing bits are ignored.
 */
function decodeBase64(text: string): Uint8Array | undefined {
  const bytes: number[] = [];
  let bits = 0;
  let sextets = 0;
  let index = 0;
  while (index < text.length) {
    const char = text.charAt(index);
    index += 1;
    if (char === '=') {
      const completes = sextets === 3 || (sextets === 2 && text.charAt(index) === '=');
      if (!completes) {
        return undefined;
      }
      index += sextets === 2 ? 1 : 0;
      break;
    }
    const value = BASE64_DIGITS.indexOf(char);
    if (value < 0) {
      return undefined;
    }
    bits = (bits << 6) | value;
    sextets += 1;
    if (sextets === 4) {
      bytes.push((bits >> 16) & 0xff, (bits >> 8) & 0xff, bits & 0xff);
      bits = 0;
      sextets = 0;
    }
  }
  if (index < text.length || sextets === 1) {
    return undefined;
  }
  if (sextets === 2) {
    bytes.push((bits >> 4) & 0xff);
  } else if (sextets === 3) {
    bytes.push((bits >> 10) & 0xff, (bits >> 2) & 0xff);
  }
  return Uint8Array.from(bytes);
}

/**
 * Authenticates a request as the server's HTTP Basic filter does. The header
 * is trimmed as Java trims; a scheme other than `Basic`, in any case, carries
 * no credentials. Otherwise the token after `Basic` and one separator
 * character is decoded by {@link decodeBase64} and read as UTF-8, a malformed
 * sequence becoming U+FFFD and a BOM kept, then split at its first `:`, since
 * a password may contain one. The username matches {@link users} in any case,
 * the password exactly. A bare `Basic`, an undecodable token, a missing `:`,
 * an unknown user or a wrong password is rejected.
 */
function authenticate(request: Request): Authentication {
  const value = request.headers.get('Authorization');
  if (value === null) {
    return { outcome: 'anonymous' };
  }
  const header = javaTrim(value);
  if (header.slice(0, 5).toLowerCase() !== 'basic') {
    return { outcome: 'anonymous' };
  }
  const bytes = header.length === 5 ? undefined : decodeBase64(header.slice(6));
  if (bytes === undefined) {
    return { outcome: 'rejected' };
  }
  const token = new TextDecoder('utf-8', { ignoreBOM: true }).decode(bytes);
  const colon = token.indexOf(':');
  if (colon < 0) {
    return { outcome: 'rejected' };
  }
  const username = token.slice(0, colon).toLowerCase();
  const password = token.slice(colon + 1);
  const user = users.find((u) => u.username.toLowerCase() === username);
  if (user === undefined || user.password !== password) {
    return { outcome: 'rejected' };
  }
  return { outcome: 'authenticated', user: { username: user.username, roles: [...user.roles] } };
}

/** Whether `roles` grant `role`, `MAINTENANCE` implying `INQUIRY`. */
function grants(roles: readonly Role[], role: Role): boolean {
  return roles.includes(role) || (role === 'INQUIRY' && roles.includes('MAINTENANCE'));
}

/** 401 APP0401, with the Basic challenge unless the request carries `X-Requested-With`. */
function unauthorized(request: Request): Response {
  const response = problem(401, 'APP0401', { instance: pathOf(request) });
  if (!request.headers.has('X-Requested-With')) {
    response.headers.set('WWW-Authenticate', BASIC_CHALLENGE);
  }
  return response;
}

/**
 * Wraps a route's resolver in the server's security filter chain, in its
 * order: the request firewall ({@link FIREWALL_REJECTED}: 400 APP0400 `bad
 * request`), then {@link authenticate} on any credentials supplied, public
 * routes included (rejected: 401 APP0401), then the route's access rule
 * (anonymous on a protected route: 401 APP0401; a user without the role: 403
 * APP0403). Only an admitted request reaches the resolver, so every guard
 * runs before the route parses or validates anything. The admitted user is
 * available to the resolver through {@link principal}.
 */
function secured<Params extends PathParams<keyof Params> = PathParams>(
  access: Access,
  resolver: HttpResponseResolver<Params, DefaultBodyType, undefined>,
): HttpResponseResolver<Params, DefaultBodyType, undefined> {
  return (info) => {
    const { request } = info;
    const instance = pathOf(request);
    const lowered = instance.toLowerCase();
    if (FIREWALL_REJECTED.some((fragment) => lowered.includes(fragment))) {
      return problem(400, 'APP0400', { args: ['bad request'], instance });
    }
    const authentication = authenticate(request);
    if (authentication.outcome === 'rejected') {
      return unauthorized(request);
    }
    if (authentication.outcome === 'anonymous') {
      return access === 'public' ? resolver(info) : unauthorized(request);
    }
    if (access !== 'public' && !grants(authentication.user.roles, access)) {
      return problem(403, 'APP0403', { instance });
    }
    principals.set(request, authentication.user);
    return resolver(info);
  };
}

/**
 * The user a {@link secured} route admitted the request as: the identity of
 * `GET /api/session` and the only source of a write's `chgUser`, as the server
 * stamps its authenticated principal and nothing else.
 *
 * @throws Error when the request was not admitted with credentials, a defect
 *   of the calling handler; there is no fallback user.
 */
function principal(request: Request): Schemas['SessionResponse'] {
  const user = principals.get(request);
  if (user === undefined) {
    throw new Error(`No authenticated principal for ${request.method} ${pathOf(request)}`);
  }
  return user;
}

/**
 * An RFC 9457 `application/problem+json` response in the API's error shape:
 * `type`, `title`, `status`, `instance`, `detail`, `code` and `args`, then
 * `errors`, `current`, `stateAccepted` and `errorId` only where the contract
 * below allows them. `title` is the reason phrase of `status`
 * ({@link REASON_PHRASES}) and is left out for a status Spring does not
 * resolve, as the server does. `detail` defaults to the catalog text of
 * `code` with `extra.args` substituted.
 *
 * The conditional-member contract of the API's error model. A call that
 * breaks it is a programming error in the test and throws an `Error` naming
 * the rule, so no test can stub a problem the API never sends:
 *
 * - `status` is an integer from 400 to 599.
 * - `errors` names at least one field, and only on 400 and 422.
 * - `current` is required on 409 DEM1002 and allowed nowhere else.
 * - `stateAccepted` appears only on a 422 or 502 whose `instance` is
 *   `/api/customers/review` (its default when `extra.instance` is left out;
 *   any other explicit `instance` throws), holds a code of {@link states}
 *   (the State rule passed), and never accompanies a DEM0501, or a DEM0502
 *   whose first error is on `active`, `name`, `addr` or `city`: rules 1–4
 *   run before the State rule. A State-rule DEM0503 on `state` looks the
 *   same as the later standardized-State check, which does carry it, so that
 *   case is the caller's to get right.
 * - `errorId` is a UUID and appears on 500 only; every 500 carries one,
 *   `extra.errorId` or else {@link DEFAULT_ERROR_ID}.
 *
 * @throws Error when `status` or a member of `extra` breaks the contract
 * @example problem(409, 'DEM1002', { current: customerDetail, instance: '/api/customers/AAAD' })
 * @example problem(500, 'DEM9999', { instance: '/api/customers' }) // errorId: DEFAULT_ERROR_ID
 * @example problem(502, 'APP0502', { stateAccepted: 'NV' }) // instance: /api/customers/review
 */
export function problem(status: number, code: string, extra: ProblemExtra = {}) {
  const instance = extra.instance ?? (extra.stateAccepted === undefined ? '/api' : REVIEW_PATH);
  const call = `problem(${status}, '${code}')`;
  if (!Number.isInteger(status) || status < 400 || status > 599) {
    throw new Error(`${call}: a problem is an error response, so its status must be an integer from 400 to 599`);
  }
  if (extra.errors !== undefined) {
    if (extra.errors.length === 0) {
      throw new Error(`${call}: errors must name at least one field; leave it out when none is at fault`);
    }
    if (status !== 400 && status !== 422) {
      throw new Error(`${call}: errors is sent on 400 and 422 only`);
    }
  }
  const stale = status === 409 && code === 'DEM1002';
  if (stale && extra.current === undefined) {
    throw new Error(`${call}: 409 DEM1002 requires current, the customer as now stored`);
  }
  if (!stale && extra.current !== undefined) {
    throw new Error(`${call}: current is sent on 409 DEM1002 only`);
  }
  if (extra.stateAccepted !== undefined) {
    const accepted = extra.stateAccepted;
    if ((status !== 422 && status !== 502) || instance !== REVIEW_PATH) {
      throw new Error(`${call}: stateAccepted is sent on a 422 or 502 of ${REVIEW_PATH} only, not of ${instance}`);
    }
    const failedField = extra.errors?.[0]?.field;
    if (
      code === 'DEM0501' ||
      (code === 'DEM0502' && failedField !== undefined && PRE_STATE_RULE_FIELDS.has(failedField))
    ) {
      throw new Error(`${call}: stateAccepted is absent when the review fails before the State rule`);
    }
    if (!states.some((row) => row.state === accepted)) {
      throw new Error(`${call}: stateAccepted must be a State code that passed the State rule, not '${accepted}'`);
    }
  }
  if (extra.errorId !== undefined) {
    if (status !== 500) {
      throw new Error(`${call}: errorId is sent on 500 only`);
    }
    if (!UUID_PATTERN.test(extra.errorId)) {
      throw new Error(`${call}: errorId must be a UUID, not '${extra.errorId}'`);
    }
  }

  const args = extra.args ?? [];
  const title = REASON_PHRASES[status];
  // Schema `Problem` marks `title` required, yet the server leaves it out for a
  // status Spring does not resolve, so the body types it as optional to match.
  const body: Omit<ProblemBody, 'title'> & Partial<Pick<ProblemBody, 'title'>> = {
    type: `urn:customer-master:problem:${code}`,
    ...(title === undefined ? {} : { title }),
    status,
    instance,
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
  const errorId = extra.errorId ?? (status === 500 ? DEFAULT_ERROR_ID : undefined);
  if (errorId !== undefined) {
    body.errorId = errorId;
  }
  // An explicit Content-Type is kept by HttpResponse.json, which sets
  // application/json only when the caller supplies none.
  return HttpResponse.json(body, {
    status,
    headers: { 'Content-Type': 'application/problem+json' },
  });
}

/** One property or parameter at fault in a {@link requestNotValid} problem. */
export interface RequestViolation {
  /** The JSON property, or the path or query parameter, that `errors[].field` names. */
  readonly field: string;
  /** The fixed English reason, the `{0}` of APP0400, such as `name is too long`. */
  readonly reason: string;
}

/**
 * The APP0400 problem the API sends for a request it cannot bind or accept,
 * built by {@link problem}: `detail` "Request is not valid: <reason>", `args`
 * `[<reason>]`, and `title` the reason phrase of `status`.
 *
 * - A string `cause` is a reason that names no field, such as
 *   `malformed request body`, `unknown property chgUser`, `not acceptable`
 *   (406) or `unsupported media type` (415); the problem has no `errors`.
 * - A violation list names the fields at fault in reporting order, which the
 *   caller sets (the server's order: property path, then constraint).
 *   `detail` and `args` take the first reason, and `errors` holds one APP0400
 *   item per field, from that field's first violation, with the `message`
 *   "Request is not valid: <its reason>". The server names a field on 400
 *   only.
 *
 * @param instance the request path
 * @param cause the reason, or the violations in reporting order
 * @param status 400, or the framework 4xx the failure keeps (404, 405, 406,
 *   415, ...); never 401 or 403, which are APP0401 and APP0403
 * @throws Error when `status` is not such a 4xx, a violation list comes with a
 *   status other than 400, or a reason or field is blank
 * @example requestNotValid('/api/customers', [{ field: 'addr', reason: 'addr is too long' }])
 * @example requestNotValid('/api/customers/review', 'unsupported media type', 415)
 */
export function requestNotValid(
  instance: string,
  cause: string | readonly [RequestViolation, ...RequestViolation[]],
  status = 400,
): Response {
  const call = `requestNotValid(${status})`;
  if (!Number.isInteger(status) || status < 400 || status > 499 || status === 401 || status === 403) {
    throw new Error(`${call}: APP0400 keeps a 4xx status other than 401 and 403`);
  }
  const violations: readonly RequestViolation[] = typeof cause === 'string' ? [] : cause;
  if (violations.length > 0 && status !== 400) {
    throw new Error(`${call}: a reason that names a field is sent on 400 only`);
  }
  const reason = typeof cause === 'string' ? cause : cause[0].reason;
  if (
    reason.trim() === '' ||
    violations.some((violation) => violation.field.trim() === '' || violation.reason.trim() === '')
  ) {
    throw new Error(`${call}: every reason and field must be non-blank`);
  }
  // One item per field, its first violation, as the server keeps them; the
  // field the detail names is therefore errors[0].
  const byField = new Map<string, RequestViolation>();
  for (const violation of violations) {
    if (!byField.has(violation.field)) {
      byField.set(violation.field, violation);
    }
  }
  const errors: FieldErrorFixture[] = [...byField.values()].map((violation) => ({
    field: violation.field,
    code: 'APP0400',
    message: messageText('APP0400', [violation.reason]),
  }));
  return problem(status, 'APP0400', {
    args: [reason],
    instance,
    errors: errors.length === 0 ? undefined : errors,
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

/**
 * The code points Java's `Character.isWhitespace(int)` accepts, which the
 * server's `String.strip()` and `stripTrailing()` remove: U+0009–U+000D,
 * U+001C–U+001F, the space, and the Unicode space, line and paragraph
 * separators other than the no-break spaces. U+00A0, U+2007, U+202F, U+FEFF,
 * U+0085, U+180E and U+200B are not whitespace and are kept. Listed literally,
 * because JavaScript's `trim()` and `\s` use a different set.
 */
const JAVA_WHITESPACE: ReadonlySet<number> = new Set([
  0x0009, 0x000a, 0x000b, 0x000c, 0x000d,
  0x001c, 0x001d, 0x001e, 0x001f,
  0x0020,
  0x1680,
  0x2000, 0x2001, 0x2002, 0x2003, 0x2004, 0x2005, 0x2006,
  0x2008, 0x2009, 0x200a,
  0x2028, 0x2029,
  0x205f,
  0x3000,
]);

/** Whether a code point is whitespace to Java's `Character.isWhitespace(int)`. */
function isJavaWhitespace(codePoint: number): boolean {
  return JAVA_WHITESPACE.has(codePoint);
}

/**
 * Java's `String.stripTrailing()`: removes trailing code points that
 * {@link isJavaWhitespace} accepts. A surrogate pair, or an unpaired
 * surrogate, is never whitespace and is kept intact.
 */
function javaStripTrailing(value: string): string {
  let end = 0;
  let index = 0;
  for (const ch of value) {
    index += ch.length;
    const codePoint = ch.codePointAt(0);
    if (codePoint === undefined || !isJavaWhitespace(codePoint)) {
      end = index;
    }
  }
  return value.slice(0, end);
}

/** Java's `String.strip()`: removes {@link isJavaWhitespace} code points from both ends. */
function javaStrip(value: string): string {
  let start = 0;
  for (const ch of value) {
    const codePoint = ch.codePointAt(0);
    if (codePoint === undefined || !isJavaWhitespace(codePoint)) {
      break;
    }
    start += ch.length;
  }
  return javaStripTrailing(value.slice(start));
}

/**
 * The server's `TextNormalizer.field`, applied to customer data fields:
 * trailing Java whitespace removed, leading characters kept as typed, then
 * {@link upper}; `null` or absent reads as `''`.
 */
function normalizeField(value: string | null | undefined): string {
  return value === undefined || value === null ? '' : upper(javaStripTrailing(value));
}

/**
 * The server's `TextNormalizer.filter`, applied to search and picker filters:
 * Java whitespace removed from both ends, then {@link upper}; `null` or absent
 * reads as `''`.
 */
function normalizeFilter(value: string | null | undefined): string {
  return value === undefined || value === null ? '' : upper(javaStrip(value));
}

/** The path a problem's `instance` names. */
function pathOf(request: Request): string {
  return new URL(request.url).pathname;
}

/** A success-payload notice with its catalog text. */
function notice(code: string): NoticeFixture {
  return { code, message: messageText(code) };
}

/**
 * Orders two texts as the database's `customer_sort` collation does, the
 * order of every search, keyset comparison and state list: negative when `a`
 * sorts first, positive when `b` does, 0 only for identical texts. ICU's
 * root order decides, with the collation's digit tailoring applied at the
 * primary level ({@link primaryForm}) and combining marks weighed in the
 * order given ({@link collationInput}); texts equal at all three ICU levels
 * are ordered by code point ({@link compareCodePoints}), as PostgreSQL breaks
 * ties for a deterministic collation. {@link ROOT_COLLATOR_OPTIONS} describes
 * the method and what it does not model.
 */
function compareText(a: string, b: string): number {
  if (a === b) {
    return 0;
  }
  return (
    PRIMARY_COLLATOR.compare(primaryForm(a), primaryForm(b)) ||
    FULL_COLLATOR.compare(collationInput(a), collationInput(b)) ||
    compareCodePoints(a, b)
  );
}

/**
 * The nine customer fields of a bound request body, normalized as the
 * server's maintenance service does before its rules: each by
 * {@link normalizeField}, so one absent or `null` reads as `''`. With
 * `defaultActive` (add, and review for ADD), an `active` that is absent or
 * `null` becomes `Y`; a value that is present is normalized as given.
 */
function customerFields(text: TextFields, defaultActive: boolean): CustomerFieldsFixture {
  const field = (key: keyof CustomerFieldsFixture): string => normalizeField(text.get(key));
  return {
    name: field('name'),
    addr: field('addr'),
    city: field('city'),
    state: field('state'),
    zip: field('zip'),
    corpPhone: field('corpPhone'),
    acctMgr: field('acctMgr'),
    acctPhone: field('acctPhone'),
    active: defaultActive && !text.has('active') ? 'Y' : field('active'),
  };
}

// ---------------------------------------------------------------------------
// Write requests (the server's media type check, JSON binding and bean validation)
// ---------------------------------------------------------------------------

/** The APP0400 reason of a body the server cannot read as the request's JSON object. */
const MALFORMED_BODY = 'malformed request body';

/**
 * The nine text properties of every write request, in schema order, each with
 * its `@Size(max)` in UTF-16 code units (the DDS field lengths).
 */
const TEXT_PROPERTIES: readonly (readonly [keyof CustomerFieldsFixture, number])[] = [
  ['name', 40],
  ['addr', 40],
  ['city', 20],
  ['state', 2],
  ['zip', 10],
  ['corpPhone', 20],
  ['acctMgr', 40],
  ['acctPhone', 20],
  ['active', 1],
];

/**
 * How a request property binds: a text field (a JSON string or `null`), the
 * review's `purpose` (exactly `ADD` or `EDIT`, or `null`) or the update's
 * `version` (an integer within Java's `long`, or `null`).
 */
type PropertyKind = 'text' | 'purpose' | 'version';

/** A write request's properties by name; any other name is an unknown property. */
type RequestShape = ReadonlyMap<string, PropertyKind>;

/** The nine text properties plus `extra`, the one property a request adds to them. */
function requestShape(extra?: readonly [string, PropertyKind]): RequestShape {
  const shape = new Map<string, PropertyKind>(TEXT_PROPERTIES.map(([name]): [string, PropertyKind] => [name, 'text']));
  if (extra !== undefined) {
    shape.set(extra[0], extra[1]);
  }
  return shape;
}

/** `POST /api/customers`: schema `CustomerFields`. */
const ADD_REQUEST = requestShape();

/** `POST /api/customers/review`: schema `ReviewRequest`, `purpose` and the nine fields. */
const REVIEW_REQUEST = requestShape(['purpose', 'purpose']);

/** `PUT /api/customers/{custId}`: schema `CustomerUpdateRequest`, the nine fields and `version`. */
const UPDATE_REQUEST = requestShape(['version', 'version']);

/** The review purposes, matched exactly: `add` or `ADD ` is refused. */
type Purpose = 'ADD' | 'EDIT';

/** Java's `Long.MIN_VALUE` and `Long.MAX_VALUE`, the range of `version`. */
const JAVA_LONG_MIN = -9223372036854775808n;
const JAVA_LONG_MAX = 9223372036854775807n;

/** The text properties a body sent as strings, by name; one absent or `null` is left out. */
type TextFields = ReadonlyMap<keyof CustomerFieldsFixture, string>;

/**
 * A write request body as the server binds it; `purpose` and `version` are
 * `null` when absent, `null` or not in the request's shape.
 */
interface BoundBody {
  readonly text: TextFields;
  readonly purpose: Purpose | null;
  readonly version: bigint | null;
}

/** A request read for its endpoint, or the response that refuses it. */
type ReadResult<T> = { readonly ok: true; readonly value: T } | { readonly ok: false; readonly response: Response };

/** The bean-validation violation of a review without `purpose`. */
const PURPOSE_REQUIRED: RequestViolation = { field: 'purpose', reason: 'purpose is required' };

/** The bean-validation violation of an update without `version`. */
const VERSION_REQUIRED: RequestViolation = { field: 'version', reason: 'version is required' };

/** The method-validation violation of an update whose path `custId` is not a customer id. */
const CUST_ID_INVALID: RequestViolation = { field: 'custId', reason: 'custId has an invalid format' };

/**
 * Ends binding with the APP0400 cause the server reports: a reason that names
 * no field (malformed body, unknown property) or a property's violation.
 */
class BindingFailure extends Error {
  readonly failure: string | RequestViolation;

  constructor(failure: string | RequestViolation) {
    super(typeof failure === 'string' ? failure : failure.reason);
    this.failure = failure;
  }
}

/**
 * Whether a `Content-Type` is the `application/json` the write endpoints
 * consume: the type and subtype before any `;`, trimmed as Java trims, in any
 * case; parameters such as `charset` are allowed. Absent, `text/plain`,
 * `application/*` or `application/merge-patch+json` is not.
 */
function isJsonMediaType(contentType: string | null): boolean {
  return contentType !== null && javaTrim(contentType.split(';', 1)[0] ?? '').toLowerCase() === 'application/json';
}

/**
 * The byte length of the longest prefix of `bytes` that is well-formed UTF-8:
 * no stray continuation byte, truncated sequence, overlong form, surrogate or
 * code point above U+10FFFF.
 */
function wellFormedUtf8Length(bytes: Uint8Array): number {
  let index = 0;
  while (index < bytes.length) {
    const lead = bytes[index] ?? 0;
    let length = 1;
    let low = 0x80;
    let high = 0xbf;
    if (lead >= 0xc2 && lead <= 0xdf) {
      length = 2;
    } else if (lead >= 0xe0 && lead <= 0xef) {
      length = 3;
      low = lead === 0xe0 ? 0xa0 : 0x80;
      high = lead === 0xed ? 0x9f : 0xbf;
    } else if (lead >= 0xf0 && lead <= 0xf4) {
      length = 4;
      low = lead === 0xf0 ? 0x90 : 0x80;
      high = lead === 0xf4 ? 0x8f : 0xbf;
    } else if (lead >= 0x80) {
      return index;
    }
    for (let offset = 1; offset < length; offset += 1) {
      const next = bytes[index + offset];
      if (next === undefined || next < (offset === 1 ? low : 0x80) || next > (offset === 1 ? high : 0xbf)) {
        return index;
      }
    }
    index += length;
  }
  return index;
}

/** The first token of a JSON value; a string's content is read only when it is needed. */
type JsonToken =
  | { readonly type: 'int'; readonly lexeme: string }
  | { readonly type: 'string' | 'float' | 'true' | 'false' | 'null' | 'object' | 'array' };

/** One object member: its name and its value's first token. */
interface JsonMember {
  readonly name: string;
  readonly value: JsonToken;
}

/** The single-character JSON escapes and the characters they stand for. */
const JSON_ESCAPES: Readonly<Record<string, string>> = {
  '"': '"',
  '\\': '\\',
  '/': '/',
  b: '\b',
  f: '\f',
  n: '\n',
  r: '\r',
  t: '\t',
};

/**
 * Characters that may not follow `true`, `false` or `null` directly: those
 * Java's `Character.isJavaIdentifierPart` accepts, as `truex` or `null1` is
 * one unrecognized token to the server's parser.
 */
const JAVA_IDENTIFIER_PART = /[\p{L}\p{Nl}\p{Nd}\p{Mn}\p{Mc}\p{Pc}\p{Sc}\p{Cf}\u007f-\u009f]/u;

/**
 * Reads a request body as the server's streaming JSON parser does in strict
 * mode, one token at a time, so the first problem in document order decides
 * the answer. Whitespace is space, tab, CR and LF only; names and strings are
 * double-quoted with the standard escapes and no raw control character;
 * numbers have no leading zero, `+`, `NaN` or bare `.`; there are no comments
 * and no trailing commas. Reading a member reads its name and its value's
 * first token, a number or literal in full but a string only up to its
 * opening quote: its content is decoded when the value is used or skipped.
 * Every syntax error, and running out of text (or of its well-formed UTF-8
 * prefix) where more is due, throws {@link BindingFailure} with
 * {@link MALFORMED_BODY}.
 */
class JsonReader {
  private readonly text: string;
  private readonly truncated: boolean;
  private index = 0;
  private pendingString = false;

  /**
   * @param text the body's well-formed UTF-8 prefix, decoded (a leading BOM removed)
   * @param truncated whether bytes that are not well-formed UTF-8 follow `text`
   */
  constructor(text: string, truncated: boolean) {
    this.text = text;
    this.truncated = truncated;
  }

  /** The first token of the next value. */
  readValue(): JsonToken {
    this.skipWhitespace();
    const char = this.text.charAt(this.index);
    if (char === '"') {
      this.index += 1;
      this.pendingString = true;
      return { type: 'string' };
    }
    if (char === '{' || char === '[') {
      this.index += 1;
      return { type: char === '{' ? 'object' : 'array' };
    }
    if (char === 't' || char === 'f' || char === 'n') {
      const word = char === 't' ? 'true' : char === 'f' ? 'false' : 'null';
      this.readLiteral(word);
      return { type: word };
    }
    if (char === '-' || (char >= '0' && char <= '9')) {
      return this.readNumber();
    }
    return this.malformed();
  }

  /**
   * The next member of the object being read, or `undefined` at its closing
   * brace; `first` for the member right after the opening brace.
   */
  readMember(first: boolean): JsonMember | undefined {
    this.skipWhitespace();
    if (this.text.charAt(this.index) === '}') {
      this.index += 1;
      return undefined;
    }
    if (!first) {
      this.expect(',');
      this.skipWhitespace();
    }
    this.expect('"');
    this.pendingString = true;
    const name = this.readString();
    this.skipWhitespace();
    this.expect(':');
    return { name, value: this.readValue() };
  }

  /** The content of the string whose first token was read last. */
  readString(): string {
    this.pendingString = false;
    let result = '';
    let start = this.index;
    for (;;) {
      const code = this.code();
      if (code === undefined || code < 0x20) {
        return this.malformed();
      }
      if (code === 0x22 || code === 0x5c) {
        result += this.text.slice(start, this.index);
        this.index += 1;
        if (code === 0x22) {
          return result;
        }
        result += this.readEscape();
        start = this.index;
      } else {
        this.index += 1;
      }
    }
  }

  /** Reads the rest of a value whose first token is `token`: nested members, elements and strings included. */
  skip(token: JsonToken): void {
    if (token.type === 'string') {
      this.readString();
    } else if (token.type === 'object') {
      for (let member = this.readMember(true); member !== undefined; member = this.readMember(false)) {
        this.skip(member.value);
      }
    } else if (token.type === 'array') {
      for (let element = this.readElement(true); element !== undefined; element = this.readElement(false)) {
        this.skip(element);
      }
    }
  }

  /** Requires that only whitespace follows the root value, as the server refuses trailing tokens. */
  end(): void {
    this.skipWhitespace();
    if (this.index < this.text.length || this.truncated) {
      this.malformed();
    }
  }

  /** Fails the read: the body is malformed. */
  malformed(): never {
    throw new BindingFailure(MALFORMED_BODY);
  }

  /** The next array element's first token, or `undefined` at its closing bracket. */
  private readElement(first: boolean): JsonToken | undefined {
    this.skipWhitespace();
    if (this.text.charAt(this.index) === ']') {
      this.index += 1;
      return undefined;
    }
    if (!first) {
      this.expect(',');
    }
    return this.readValue();
  }

  /** The UTF-16 code unit at the read position, or `undefined` at the end of the text. */
  private code(): number | undefined {
    return this.index < this.text.length ? this.text.charCodeAt(this.index) : undefined;
  }

  /** Skips JSON whitespace, after the content of a string whose first token was read and not used. */
  private skipWhitespace(): void {
    if (this.pendingString) {
      this.readString();
    }
    for (let code = this.code(); code === 0x20 || code === 0x09 || code === 0x0a || code === 0x0d; code = this.code()) {
      this.index += 1;
    }
  }

  /** Consumes `char`, or fails. */
  private expect(char: string): void {
    if (this.text.charAt(this.index) !== char) {
      this.malformed();
    }
    this.index += 1;
  }

  /** The character an escape after its backslash stands for: a single-character escape or `\uXXXX`. */
  private readEscape(): string {
    const char = this.text.charAt(this.index);
    this.index += 1;
    if (char === 'u') {
      const hex = this.text.slice(this.index, this.index + 4);
      if (!/^[0-9A-Fa-f]{4}$/.test(hex)) {
        return this.malformed();
      }
      this.index += 4;
      return String.fromCharCode(Number.parseInt(hex, 16));
    }
    const escaped = Object.hasOwn(JSON_ESCAPES, char) ? JSON_ESCAPES[char] : undefined;
    return escaped ?? this.malformed();
  }

  /**
   * Reads `word`. The end of the text may follow it; a character Java counts
   * as part of an identifier may not, nor bytes that are not well-formed UTF-8.
   */
  private readLiteral(word: string): void {
    if (!this.text.startsWith(word, this.index)) {
      this.malformed();
    }
    this.index += word.length;
    const next = this.text.codePointAt(this.index);
    if (next === undefined) {
      if (this.truncated) {
        this.malformed();
      }
      return;
    }
    if (next >= 0x30 && next !== 0x5d && next !== 0x7d && JAVA_IDENTIFIER_PART.test(String.fromCodePoint(next))) {
      this.malformed();
    }
  }

  /**
   * Reads a number: `-`, then `0` or a digit sequence not starting with `0`,
   * then an optional fraction and exponent, each with at least one digit.
   * Whatever follows ends it and is read as the next token.
   */
  private readNumber(): JsonToken {
    const start = this.index;
    const digits = (): number => {
      const from = this.index;
      for (let code = this.code(); code !== undefined && code >= 0x30 && code <= 0x39; code = this.code()) {
        this.index += 1;
      }
      return this.index - from;
    };
    if (this.text.charAt(this.index) === '-') {
      this.index += 1;
    }
    const leadingZero = this.text.charAt(this.index) === '0';
    const integerDigits = digits();
    if (integerDigits === 0 || (leadingZero && integerDigits > 1)) {
      return this.malformed();
    }
    let float = false;
    if (this.text.charAt(this.index) === '.') {
      this.index += 1;
      float = true;
      if (digits() === 0) {
        return this.malformed();
      }
    }
    const exponent = this.text.charAt(this.index);
    if (exponent === 'e' || exponent === 'E') {
      this.index += 1;
      float = true;
      const sign = this.text.charAt(this.index);
      if (sign === '+' || sign === '-') {
        this.index += 1;
      }
      if (digits() === 0) {
        return this.malformed();
      }
    }
    return float ? { type: 'float' } : { type: 'int', lexeme: this.text.slice(start, this.index) };
  }
}

/**
 * A member name as the server writes it into a reason: `parameter` when
 * blank, otherwise its first 64 code points with each ISO control as `?`.
 */
function safePropertyName(name: string): string {
  if (isJavaBlank(name)) {
    return 'parameter';
  }
  return [...name]
    .slice(0, 64)
    .map((char) => {
      const codePoint = char.codePointAt(0) ?? 0;
      return codePoint <= 0x1f || (codePoint >= 0x7f && codePoint <= 0x9f) ? '?' : char;
    })
    .join('');
}

/** Whether Java's `String.isBlank()` holds: empty, or only {@link isJavaWhitespace} code points. */
function isJavaBlank(value: string): boolean {
  return javaStrip(value) === '';
}

/**
 * The value of property `name` of kind `kind` from its first token, reading a
 * string's content; a token of the wrong type fails at that token, the rest of
 * the value unread, as `<name> has an invalid value` on `name`.
 */
function bindValue(reader: JsonReader, name: string, kind: PropertyKind, token: JsonToken): string | bigint | null {
  if (token.type === 'null') {
    return null;
  }
  if (token.type === 'string') {
    // The server reads the text before it judges it, so a bad escape in a
    // purpose or version string is a malformed body, not an invalid value.
    const value = reader.readString();
    if (kind === 'text' || (kind === 'purpose' && (value === 'ADD' || value === 'EDIT'))) {
      return value;
    }
  } else if (token.type === 'int' && kind === 'version') {
    const value = BigInt(token.lexeme);
    // An integer beyond a Java long fails as a number the parser cannot
    // convert, which the server reports as a malformed body.
    return value < JAVA_LONG_MIN || value > JAVA_LONG_MAX ? reader.malformed() : value;
  }
  throw new BindingFailure({ field: name, reason: `${name} has an invalid value` });
}

/**
 * Binds a request body to `shape` as the server's JSON binding of a record
 * does. The root must be an object. Its members are read in document order:
 * a property of the shape is bound at once ({@link bindValue}); the value of
 * an unknown member is read through and the first unknown name kept, then
 * reported as `unknown property <name>` once the object ends, or as soon as
 * every property of the shape has been seen (the record is built then): after
 * the next member's first token, and for any later unknown member right after
 * its value's first token. Only then are trailing tokens refused. A property
 * given twice keeps its last value.
 *
 * @throws BindingFailure with the server's reason
 */
function bindBody(reader: JsonReader, shape: RequestShape): BoundBody {
  if (reader.readValue().type !== 'object') {
    reader.malformed();
  }
  const values = new Map<string, string | bigint | null>();
  let unknown: string | undefined;
  let built = false;
  let member = reader.readMember(true);
  while (member !== undefined) {
    const kind = shape.get(member.name);
    if (kind === undefined) {
      if (built) {
        throw new BindingFailure(`unknown property ${safePropertyName(member.name)}`);
      }
      reader.skip(member.value);
      unknown ??= member.name;
      member = reader.readMember(false);
      continue;
    }
    values.set(member.name, bindValue(reader, member.name, kind, member.value));
    const completes: boolean = !built && values.size === shape.size;
    built ||= completes;
    member = reader.readMember(false);
    if (completes && unknown !== undefined) {
      throw new BindingFailure(`unknown property ${safePropertyName(unknown)}`);
    }
  }
  if (unknown !== undefined) {
    throw new BindingFailure(`unknown property ${safePropertyName(unknown)}`);
  }
  reader.end();
  const text = new Map<keyof CustomerFieldsFixture, string>();
  for (const [name] of TEXT_PROPERTIES) {
    const value = values.get(name);
    if (typeof value === 'string') {
      text.set(name, value);
    }
  }
  const purpose = values.get('purpose');
  const version = values.get('version');
  return {
    text,
    purpose: purpose === 'ADD' || purpose === 'EDIT' ? purpose : null,
    version: typeof version === 'bigint' ? version : null,
  };
}

/**
 * Reads a write request's body as the server does before validating it: 415
 * APP0400 `unsupported media type` unless {@link isJsonMediaType}; then the
 * raw bytes, decoded as UTF-8 up to the first byte that is not well-formed
 * (a leading BOM is skipped), bound by {@link bindBody}; a failure is 400
 * APP0400 with its reason, naming the property on `errors` when it has one.
 */
async function bindWriteRequest(request: Request, shape: RequestShape): Promise<ReadResult<BoundBody>> {
  const instance = pathOf(request);
  if (!isJsonMediaType(request.headers.get('Content-Type'))) {
    return { ok: false, response: requestNotValid(instance, 'unsupported media type', 415) };
  }
  const bytes = new Uint8Array(await request.arrayBuffer());
  const wellFormed = wellFormedUtf8Length(bytes);
  // TextDecoder removes a leading BOM, as the server's parser skips one.
  const text = new TextDecoder('utf-8').decode(bytes.subarray(0, wellFormed));
  const reader = new JsonReader(text, wellFormed < bytes.length);
  try {
    return { ok: true, value: bindBody(reader, shape) };
  } catch (error) {
    if (!(error instanceof BindingFailure)) {
      throw error;
    }
    const { failure } = error;
    return { ok: false, response: requestNotValid(instance, typeof failure === 'string' ? failure : [failure]) };
  }
}

/**
 * The server's bean-validation violations of a bound body, all at once: per
 * text property `<name> is too long` beyond its width (UTF-16 code units),
 * then `<name> contains a character that cannot be stored` for a U+0000, plus
 * `missing`, the required properties left out. Ordered by property name in
 * Java's `String` order, then by constraint; {@link requestNotValid} keeps
 * each property's first.
 */
function bodyViolations(text: TextFields, missing: readonly RequestViolation[]): RequestViolation[] {
  const violations: RequestViolation[] = [];
  for (const [name, width] of TEXT_PROPERTIES) {
    const value = text.get(name);
    if (value === undefined) {
      continue;
    }
    if (value.length > width) {
      violations.push({ field: name, reason: `${name} is too long` });
    }
    if (value.includes('\u0000')) {
      violations.push({ field: name, reason: `${name} contains a character that cannot be stored` });
    }
  }
  violations.push(...missing);
  // A stable sort keeps each property's violations in constraint order.
  return violations.sort((a, b) => (a.field < b.field ? -1 : a.field > b.field ? 1 : 0));
}

/** A refused request: 400 APP0400 for `violations`, in reporting order. */
function refused(request: Request, violations: readonly [RequestViolation, ...RequestViolation[]]): ReadResult<never> {
  return { ok: false, response: requestNotValid(pathOf(request), violations) };
}

/** An add request (schema `CustomerFields`), bound and validated. */
async function readAddRequest(request: Request): Promise<ReadResult<TextFields>> {
  const bound = await bindWriteRequest(request, ADD_REQUEST);
  if (!bound.ok) {
    return bound;
  }
  const [first, ...rest] = bodyViolations(bound.value.text, []);
  return first === undefined ? { ok: true, value: bound.value.text } : refused(request, [first, ...rest]);
}

/** A review request (schema `ReviewRequest`), bound and validated; `purpose` is required. */
async function readReviewRequest(
  request: Request,
): Promise<ReadResult<{ readonly purpose: Purpose; readonly text: TextFields }>> {
  const bound = await bindWriteRequest(request, REVIEW_REQUEST);
  if (!bound.ok) {
    return bound;
  }
  const { purpose, text } = bound.value;
  const [first, ...rest] = bodyViolations(text, purpose === null ? [PURPOSE_REQUIRED] : []);
  if (first !== undefined || purpose === null) {
    // A missing purpose is itself a violation, so it is `first` when it is the only one.
    return refused(request, [first ?? PURPOSE_REQUIRED, ...rest]);
  }
  return { ok: true, value: { purpose, text } };
}

/**
 * An update request (schema `CustomerUpdateRequest`) for path id `custId`,
 * bound and validated; `version` is required. A body the server cannot bind
 * is refused first; otherwise an invalid path id (not a customer id, per
 * {@link isCustIdPath}) is reported before the body's violations.
 */
async function readUpdateRequest(
  request: Request,
  custId: string,
): Promise<ReadResult<{ readonly version: bigint; readonly text: TextFields }>> {
  const bound = await bindWriteRequest(request, UPDATE_REQUEST);
  if (!bound.ok) {
    return bound;
  }
  const { version, text } = bound.value;
  const path = isCustIdPath(custId) ? [] : [CUST_ID_INVALID];
  const [first, ...rest] = [...path, ...bodyViolations(text, version === null ? [VERSION_REQUIRED] : [])];
  if (first !== undefined || version === null) {
    // A missing version is itself a violation, so it is `first` when it is the only one.
    return refused(request, [first ?? VERSION_REQUIRED, ...rest]);
  }
  return { ok: true, value: { version, text } };
}

// ---------------------------------------------------------------------------
// Field rules and address review (the server's validator and the stub address service)
// ---------------------------------------------------------------------------

/** The State rule's place in source order; a review failing at a later rule carries `stateAccepted`. */
const STATE_RULE = 5;

/** A field rule's failure: its place in source order (1–9), the field it names, its catalog code and arguments. */
interface RuleFailure {
  readonly rule: number;
  readonly field: keyof CustomerFieldsFixture;
  readonly code: string;
  readonly args: readonly string[];
}

/** Whether a normalized value is a STATES code, as the server's state lookup answers; a blank value never is. */
function isStateCode(code: string): boolean {
  return !isJavaBlank(code) && states.some((row) => normalizeField(row.state) === normalizeField(code));
}

/**
 * The first failure of the nine field rules over normalized fields, in source
 * order, or `undefined` when all pass: 1 `active` is `Y` or `N` (DEM0501);
 * 2–4 `name`, `addr`, `city` not blank (DEM0502); 5 `state` a STATES code
 * (DEM0503, no args); 6–9 `zip`, `acctPhone`, `acctMgr`, `corpPhone` not
 * blank. Blank is Java's `isBlank()`: a no-break space is not blank, an em
 * space is.
 */
function firstRuleFailure(fields: CustomerFieldsFixture): RuleFailure | undefined {
  const required = (rule: number, field: keyof CustomerFieldsFixture, label: string): RuleFailure | undefined =>
    isJavaBlank(fields[field]) ? { rule, field, code: 'DEM0502', args: [label] } : undefined;
  if (fields.active !== 'Y' && fields.active !== 'N') {
    return { rule: 1, field: 'active', code: 'DEM0501', args: ['Active Status'] };
  }
  return (
    required(2, 'name', 'Name') ??
    required(3, 'addr', 'Address') ??
    required(4, 'city', 'City') ??
    (isStateCode(fields.state) ? undefined : { rule: STATE_RULE, field: 'state', code: 'DEM0503', args: [] }) ??
    required(6, 'zip', 'ZIP') ??
    required(7, 'acctPhone', 'Account Manager Phone') ??
    required(8, 'acctMgr', 'Account Manager Name') ??
    required(9, 'corpPhone', 'Corporate Phone')
  );
}

/**
 * The 422 problem of a field-rule failure, its one `errors` item on the
 * field; `stateAccepted` only from a review whose State rule had passed.
 */
function ruleProblem(instance: string, failure: RuleFailure, stateAccepted?: string): Response {
  return problem(422, failure.code, {
    args: [...failure.args],
    instance,
    errors: [{ field: failure.field, code: failure.code, message: messageText(failure.code, failure.args) }],
    stateAccepted,
  });
}

/** An address request as the server sends it to the address service (`zip4` is always blank). */
interface AddressRequest {
  readonly address1: string;
  readonly address2: string;
  readonly city: string;
  readonly state: string;
  readonly zip5: string;
}

/** The address service's answer: the standardized address, or blank fields with the USPS error description. */
interface AddressResult {
  readonly address1: string;
  readonly address2: string;
  readonly city: string;
  readonly state: string;
  readonly zip5: string;
  readonly zip4: string;
  readonly errorDescription: string;
}

/** One stub fixture: the request it matches and the standardized address it returns. */
interface StubAddressFixture {
  readonly description: string;
  readonly input: Pick<AddressRequest, 'address2' | 'city' | 'state' | 'zip5'>;
  readonly output: Omit<AddressResult, 'errorDescription'>;
}

/** The marker that makes the stub answer `Address Not Found.` for any address line containing it. */
const BAD_ADDRESS_MARKER = 'BADADDR';

/**
 * The stub address service's fixtures, copied literally from the backend's
 * `address-validation/src/main/resources/stub/usps-stub-fixtures.json`.
 */
const STUB_ADDRESS_FIXTURES: readonly StubAddressFixture[] = deepFreeze([
  {
    description: 'Primary e2e fixture: street with ZIP+4 (NH is the first NEW state in either picker sort)',
    input: { address2: '41 QUARRY HILL ROAD', city: 'GRANITE FALLS', state: 'NH', zip5: '03999' },
    output: { address1: '', address2: '41 QUARRY HILL RD', city: 'GRANITE FALLS', state: 'NH', zip5: '03999', zip4: '2210' },
  },
  {
    description: 'Harness calls 1-2: street and city given, no ZIP',
    input: { address2: '8 ELMWOOD DRIVE', city: 'OLD HAVEN', state: 'CT', zip5: '' },
    output: { address1: '', address2: '8 ELMWOOD DR', city: 'OLD HAVEN', state: 'CT', zip5: '06399', zip4: '1234' },
  },
  {
    description: 'Harness call 5: city blank, ZIP given, suite in the street line',
    input: { address2: '8 ELMWOOD DRIVE, SUITE 2', city: '', state: 'CT', zip5: '06399' },
    output: { address1: 'STE 2', address2: '8 ELMWOOD DR', city: 'OLD HAVEN', state: 'CT', zip5: '06399', zip4: '1234' },
  },
  {
    description: 'Harness call 6: lowercase input, matched after uppercasing',
    input: { address2: '300 WEST AMBER STREET', city: 'PASO VERDE', state: 'CA', zip5: '' },
    output: { address1: '', address2: '300 W AMBER ST', city: 'PASO VERDE', state: 'CA', zip5: '91999', zip4: '0042' },
  },
  {
    description: 'Harness call 7: long returned city (23 characters), no ZIP+4',
    input: { address2: '6802 MESA GRANDE BLVD NW', city: 'LOS RANCHOS', state: 'NM', zip5: '87999' },
    output: { address1: '', address2: '6802 MESA GRANDE BLVD NW', city: 'LOS RANCHOS DE ALBARADO', state: 'NM', zip5: '87999', zip4: '' },
  },
  {
    description: 'Harness call 8: long city name',
    input: { address2: '13 SENDERO VERDE', city: 'RANCHO SANTA LUCIA', state: 'CA', zip5: '92999' },
    output: { address1: '', address2: '13 SENDERO VERDE', city: 'RANCHO SANTA LUCIA', state: 'CA', zip5: '92999', zip4: '1300' },
  },
  {
    description: 'No ZIP+4 returned, reachable through the UI',
    input: { address2: '77 HARBORVIEW LANE', city: 'PORT ELLISON', state: 'NY', zip5: '10999' },
    output: { address1: '', address2: '77 HARBORVIEW LN', city: 'PORT ELLISON', state: 'NY', zip5: '10999', zip4: '' },
  },
  {
    description: '30-character key: first 30 characters of the 38-character street 4400 SOUTHEAST LAKEVIEW TERRACE APT 12',
    input: { address2: '4400 SOUTHEAST LAKEVIEW TERRAC', city: 'CEDAR BLUFF', state: 'OR', zip5: '97999' },
    output: { address1: '', address2: '4400 SE LAKEVIEW TER', city: 'CEDAR BLUFF', state: 'OR', zip5: '97999', zip4: '5512' },
  },
  {
    description: 'Street with ZIP+4, reachable through the UI',
    input: { address2: '15 ORCHARD PLACE', city: 'MAPLE CROSSING', state: 'NJ', zip5: '08999' },
    output: { address1: '', address2: '15 ORCHARD PL', city: 'MAPLE CROSSING', state: 'NJ', zip5: '08999', zip4: '3101' },
  },
]);

/** The first `width` code points of `value`, as the server's address mapping cuts a component. */
function cutCodePoints(value: string, width: number): string {
  const codePoints = [...value];
  return codePoints.length <= width ? value : codePoints.slice(0, width).join('');
}

/** A request component as the stub compares it: Java `strip()`, then {@link upper}. */
function stubKey(value: string): string {
  return upper(javaStrip(value));
}

/**
 * The stub address service, the default the server runs with: an address line
 * containing `BADADDR` (in any case) is not found (error -2147219401, clsAMS,
 * `Address Not Found.`); a request whose stripped, uppercased `address2`,
 * `city`, `state` and `zip5` equal a fixture's input returns its output, ZIP+4
 * included; any other request is echoed stripped and uppercased, with a blank
 * `zip4`.
 */
function stubAddressService(request: AddressRequest): AddressResult {
  if (upper(request.address1).includes(BAD_ADDRESS_MARKER) || upper(request.address2).includes(BAD_ADDRESS_MARKER)) {
    return { address1: '', address2: '', city: '', state: '', zip5: '', zip4: '', errorDescription: 'Address Not Found.' };
  }
  const address2 = stubKey(request.address2);
  const city = stubKey(request.city);
  const state = stubKey(request.state);
  const zip5 = stubKey(request.zip5);
  const hit = STUB_ADDRESS_FIXTURES.find(
    ({ input }) =>
      stubKey(input.address2) === address2 &&
      stubKey(input.city) === city &&
      stubKey(input.state) === state &&
      stubKey(input.zip5) === zip5,
  );
  if (hit !== undefined) {
    return { ...hit.output, errorDescription: '' };
  }
  return { address1: stubKey(request.address1), address2, city, state, zip5, zip4: '', errorDescription: '' };
}

/**
 * Review's address step, once the nine rules passed: the server's Edit_Address
 * mapping over {@link stubAddressService}. The request carries the first 30
 * code points of the trimmed street as `address2`, the city (30), the state
 * (2) and the first five of the ZIP. A result without a city is 422 DEM9898
 * with the USPS description on `addr`, `city`, `state` and `zip`; a
 * standardized state outside STATES is 422 DEM0503 on `state`; both carry
 * `stateAccepted`, the normalized input state. Otherwise 200 with the fields
 * and the standardized address (`addr` the returned street, the city cut to
 * 20, the state, the ZIP as `zip5-zip4` or `zip5`), `standardized: true` and
 * the confirmation notice, DEM0009 for ADD and DEM0000 for EDIT.
 */
function reviewAddress(instance: string, purpose: Purpose, fields: CustomerFieldsFixture): Response {
  const stateAccepted = fields.state;
  const result = stubAddressService({
    address1: '',
    address2: cutCodePoints(normalizeFilter(fields.addr), 30),
    city: cutCodePoints(normalizeFilter(fields.city), 30),
    state: cutCodePoints(normalizeFilter(fields.state), 2),
    zip5: cutCodePoints(normalizeFilter(fields.zip), 5),
  });
  if (isJavaBlank(result.city)) {
    const args = [result.errorDescription];
    const message = messageText('DEM9898', args);
    return problem(422, 'DEM9898', {
      args,
      instance,
      errors: ['addr', 'city', 'state', 'zip'].map((field) => ({ field, code: 'DEM9898', message })),
      stateAccepted,
    });
  }
  const zip5 = normalizeField(result.zip5);
  const zip4 = normalizeField(result.zip4);
  const customer: CustomerFieldsFixture = {
    ...fields,
    addr: normalizeField(result.address2),
    city: normalizeField(cutCodePoints(result.city, 20)),
    state: normalizeField(result.state),
    zip: isJavaBlank(zip4) ? zip5 : `${zip5}-${zip4}`,
  };
  if (!isStateCode(customer.state)) {
    return problem(422, 'DEM0503', {
      instance,
      errors: [{ field: 'state', code: 'DEM0503', message: messageText('DEM0503') }],
      stateAccepted,
    });
  }
  return HttpResponse.json({
    customer,
    standardized: true,
    notice: notice(purpose === 'ADD' ? 'DEM0009' : 'DEM0000'),
  });
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
 * The stored record of a seed customer: a copy of its full fixture where one
 * exists (AAAD, AAAG), otherwise a record derived from its search fixture with
 * fixed test values for the fields a summary lacks.
 */
function seedRow(summary: CustomerSummaryFixture): CustomerResponseFixture {
  const detail = Object.hasOwn(customerDetails, summary.custId) ? customerDetails[summary.custId] : undefined;
  if (detail !== undefined) {
    return { ...detail };
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
// Customer store (per test: the seed rows plus the test's own writes)
// ---------------------------------------------------------------------------

/** The outcome of {@link insertCustomer}: the stored row, or no id left after `9999` (503 APP0503). */
type InsertResult = { status: 'inserted'; row: CustomerResponseFixture } | { status: 'exhausted' };

/**
 * The outcome of {@link updateCustomer}: the stored row, no row with that id
 * (404 DEM0599), or a version other than the stored one, with the row as
 * stored (409 DEM1002 `current`).
 */
type UpdateResult =
  | { status: 'updated'; row: CustomerResponseFixture }
  | { status: 'missing' }
  | { status: 'stale'; current: CustomerResponseFixture };

/**
 * The stored customers by id: copies of the frozen seed fixtures, which stay
 * unchanged, plus this test's adds and updates. Each row is frozen and read
 * out as a copy, so no caller can change the store except through a write.
 */
const store = new Map<string, Readonly<CustomerResponseFixture>>();

/** The ordinal of the id the next add receives, or `undefined` once `9999` was issued. */
let nextIdOrdinal: number | undefined;

/** The base-36 ordinal of a customer id ({@link CUST_ID_DIGITS}, most significant first). */
function idOrdinal(custId: string): number {
  let ordinal = 0;
  for (const digit of custId) {
    ordinal = ordinal * CUST_ID_DIGITS.length + CUST_ID_DIGITS.indexOf(digit);
  }
  return ordinal;
}

/** The 4-character customer id of an ordinal from 0 (`AAAA`) to {@link MAX_CUST_ID_ORDINAL} (`9999`). */
function idOf(ordinal: number): string {
  let id = '';
  let rest = ordinal;
  for (let position = 0; position < 4; position += 1) {
    id = CUST_ID_DIGITS.charAt(rest % CUST_ID_DIGITS.length) + id;
    rest = Math.floor(rest / CUST_ID_DIGITS.length);
  }
  return id;
}

/** A stored row of the nine fields, in the API's member order, with the stamp and version. */
function storedRow(custId: string, fields: CustomerFieldsFixture, chgUser: string, version: number): CustomerResponseFixture {
  return {
    custId,
    name: fields.name,
    addr: fields.addr,
    city: fields.city,
    state: fields.state,
    zip: fields.zip,
    corpPhone: fields.corpPhone,
    acctMgr: fields.acctMgr,
    acctPhone: fields.acctPhone,
    active: fields.active,
    chgTime: FIXTURE_CHG_TIME,
    chgUser,
    version,
  };
}

/**
 * Restores the customer store to the 30 seed rows and the id sequence to
 * `EEEF`, the state of a fresh database. Every handler's `reset()` runs it,
 * and MSW calls that on each current handler in `server.resetHandlers()`, so
 * the reset `setup.ts` performs after each test also discards that test's
 * writes; a test may call it directly as well.
 */
export function resetHandlerState(): void {
  store.clear();
  for (const summary of customers) {
    store.set(summary.custId, Object.freeze(seedRow(summary)));
  }
  nextIdOrdinal = idOrdinal(FIRST_ADDED_ID);
}

/** A copy of the stored row of `custId`, or `undefined` when no customer has that id. */
function storedCustomer(custId: string): CustomerResponseFixture | undefined {
  const row = store.get(custId);
  return row === undefined ? undefined : { ...row };
}

/**
 * Every stored customer, inactive ones included, as a search row, in the
 * search's order (`ORDER BY name, city, state, custid` under the database
 * collation, {@link compareSearchKeys}), so ids follow allocation order with
 * letters before digits. `zip5` is the first five code points of the stored
 * ZIP.
 */
function storedSummaries(): CustomerSummaryFixture[] {
  return [...store.values()]
    .sort(compareSearchKeys)
    .map((row) => ({
      custId: row.custId,
      name: row.name,
      city: row.city,
      state: row.state,
      zip5: [...row.zip].slice(0, 5).join(''),
      active: row.active,
    }));
}

/**
 * Adds a customer as the server's add does once its rules passed: the next id
 * of the sequence from `EEEF` (base-36 successor), version 0, `chgUser`
 * `user`. The id is consumed only here, so a rejected add leaves no gap.
 */
function insertCustomer(fields: CustomerFieldsFixture, user: string): InsertResult {
  const ordinal = nextIdOrdinal;
  if (ordinal === undefined) {
    return { status: 'exhausted' };
  }
  nextIdOrdinal = ordinal < MAX_CUST_ID_ORDINAL ? ordinal + 1 : undefined;
  const row = storedRow(idOf(ordinal), fields, user, 0);
  store.set(row.custId, Object.freeze(row));
  return { status: 'inserted', row: { ...row } };
}

/**
 * Updates a customer as the server's versioned update does: only when
 * `version` equals the stored version, storing the fields with `chgUser`
 * `user` and the version incremented. `version` is compared exactly: a
 * `bigint` carries any Java `long` without loss, and a `number` must be the
 * integer itself (a non-integral number never matches).
 */
function updateCustomer(
  custId: string,
  fields: CustomerFieldsFixture,
  version: bigint | number,
  user: string,
): UpdateResult {
  const stored = store.get(custId);
  if (stored === undefined) {
    return { status: 'missing' };
  }
  const current = typeof version === 'bigint' ? BigInt(stored.version) === version : stored.version === version;
  if (!current) {
    return { status: 'stale', current: { ...stored } };
  }
  const row = storedRow(custId, fields, user, stored.version + 1);
  store.set(custId, Object.freeze(row));
  return { status: 'updated', row: { ...row } };
}

/**
 * Makes each handler's `reset()`, which MSW calls on every current handler in
 * `server.resetHandlers()`, also run {@link resetHandlerState}, keeping MSW's
 * own reset of the handler's resolution state. The handlers stay
 * `HttpHandler`s.
 */
function resettingStore(list: HttpHandler[]): HttpHandler[] {
  for (const handler of list) {
    const resetResolution = handler.reset.bind(handler);
    handler.reset = () => {
      resetResolution();
      resetHandlerState();
    };
  }
  return list;
}

resetHandlerState();

// ---------------------------------------------------------------------------
// Query parameters (the API's query-string decoding and parameter binding)
// ---------------------------------------------------------------------------

/** The decoded query parameters of a request: each name with its values, in request order. */
type QueryValues = ReadonlyMap<string, readonly string[]>;

/** The lower-case texts the API binds to `true` and to `false` for a boolean parameter. */
const TRUE_TEXTS: ReadonlySet<string> = new Set(['true', 'on', 'yes', '1']);
const FALSE_TEXTS: ReadonlySet<string> = new Set(['false', 'off', 'no', '0']);

/** The range of a Java `int`, the type of `size`. */
const INT_MIN = -(2 ** 31);
const INT_MAX = 2 ** 31 - 1;

/** The prefixes after which `Integer.decode`, and so the API's `size`, reads hexadecimal digits. */
const HEX_PREFIXES: readonly string[] = ['0x', '0X', '#'];

/**
 * One name or value of the query string decoded as the API's servlet
 * container decodes it: `+` is a space, `%XX` a byte, and the bytes are
 * read as UTF-8, a malformed sequence becoming U+FFFD and a BOM kept.
 *
 * @returns the text, or `undefined` for a `%` without two hex digits after
 *   it, for which the container skips the whole parameter
 */
function decodeQueryPart(part: string): string | undefined {
  const encoder = new TextEncoder();
  const bytes: number[] = [];
  let index = 0;
  while (index < part.length) {
    const char = part.charAt(index);
    if (char === '%') {
      const hex = part.slice(index + 1, index + 3);
      if (!/^[0-9A-Fa-f]{2}$/.test(hex)) {
        return undefined;
      }
      bytes.push(Number.parseInt(hex, 16));
      index += 3;
    } else if (char === '+') {
      bytes.push(0x20);
      index += 1;
    } else {
      const text = String.fromCodePoint(part.codePointAt(index) ?? 0);
      bytes.push(...encoder.encode(text));
      index += text.length;
    }
  }
  return new TextDecoder('utf-8', { ignoreBOM: true }).decode(Uint8Array.from(bytes));
}

/**
 * The query parameters of `url` as the API's servlet container parses the
 * query string: pairs split at `&`, each split at its first `=` (a pair
 * without one has an empty value), name and value decoded by
 * {@link decodeQueryPart}. An empty pair, an empty name, or a pair that does
 * not decode is skipped. Names are case-sensitive.
 */
function queryValues(url: URL): QueryValues {
  const values = new Map<string, string[]>();
  for (const pair of url.search.slice(1).split('&')) {
    const equals = pair.indexOf('=');
    const name = decodeQueryPart(equals < 0 ? pair : pair.slice(0, equals));
    const value = decodeQueryPart(equals < 0 ? '' : pair.slice(equals + 1));
    if (name === undefined || value === undefined || name === '') {
      continue;
    }
    const list = values.get(name);
    if (list === undefined) {
      values.set(name, [value]);
    } else {
      list.push(value);
    }
  }
  return values;
}

/**
 * A text parameter as the API binds it to a `String`: `null` when absent;
 * several values are joined with `,` in request order (`state=n&state=` is
 * `n,`).
 */
function textParam(query: QueryValues, name: string): string | null {
  const values = query.get(name);
  return values === undefined ? null : values.join(',');
}

/**
 * `includeInactive` as the API binds its `boolean`, default `false`: absent,
 * or one empty value, is `false`. Otherwise the first value, trimmed as
 * Java's `String.trim()` and lower-cased, must be one of {@link TRUE_TEXTS}
 * or {@link FALSE_TEXTS}; anything else, an empty first value among several
 * included, does not bind.
 */
function bindIncludeInactive(query: QueryValues): Bound<boolean> {
  const values = query.get('includeInactive') ?? [];
  const first = values[0];
  if (first === undefined || (values.length === 1 && first === '')) {
    return { ok: true, value: false };
  }
  const text = javaTrim(first).toLowerCase();
  if (TRUE_TEXTS.has(text) || FALSE_TEXTS.has(text)) {
    return { ok: true, value: TRUE_TEXTS.has(text) };
  }
  return { ok: false, violation: { field: 'includeInactive', reason: 'includeInactive has an invalid value' } };
}

/**
 * The digit value of one UTF-16 unit in `radix`, as Java's
 * `Character.digit(char, radix)`: a Unicode decimal digit of any script
 * (they come in runs of ten from zero), or a Latin letter, ASCII or
 * fullwidth, standing for 10 to 35; -1 when it is no digit below `radix`.
 */
function javaDigit(unit: number, radix: number): number {
  const isDecimal = (code: number): boolean => /^\p{Nd}$/u.test(String.fromCharCode(code));
  let value = -1;
  if (isDecimal(unit)) {
    let zero = unit;
    while (zero > 0 && isDecimal(zero - 1)) {
      zero -= 1;
    }
    value = (unit - zero) % 10;
  } else if (unit >= 0x41 && unit <= 0x5a) {
    value = unit - 0x41 + 10;
  } else if (unit >= 0x61 && unit <= 0x7a) {
    value = unit - 0x61 + 10;
  } else if (unit >= 0xff21 && unit <= 0xff3a) {
    value = unit - 0xff21 + 10;
  } else if (unit >= 0xff41 && unit <= 0xff5a) {
    value = unit - 0xff41 + 10;
  }
  return value < radix ? value : -1;
}

/**
 * The value of a run of {@link javaDigit} digits in `radix`, read unit by
 * unit as Java's `Integer.parseInt` reads them (a supplementary digit is
 * two non-digits); `undefined` when the run is empty or holds a non-digit.
 * Values beyond any `int` saturate at 2^32.
 */
function javaDigits(digits: string, radix: number): number | undefined {
  if (digits === '') {
    return undefined;
  }
  let magnitude = 0;
  for (let index = 0; index < digits.length; index += 1) {
    const digit = javaDigit(digits.charCodeAt(index), radix);
    if (digit < 0) {
      return undefined;
    }
    magnitude = Math.min(magnitude * radix + digit, 2 ** 32);
  }
  return magnitude;
}

/**
 * `text` as Spring's number parsing reads an `Integer`: with `0x`, `0X` or
 * `#` after an optional `-`, hexadecimal digits follow, as
 * `Integer.decode` (no further sign); otherwise an optional `+` or `-` and
 * decimal digits, as `Integer.valueOf` (`010` is ten).
 *
 * @returns the value, or `undefined` for any other text or a value outside
 *   the `int` range
 */
function javaInteger(text: string): number | undefined {
  const signLength = text.startsWith('-') ? 1 : 0;
  const hexPrefix = HEX_PREFIXES.find((prefix) => text.startsWith(prefix, signLength));
  const negative = text.startsWith('-');
  let magnitude: number | undefined;
  if (hexPrefix === undefined) {
    magnitude = javaDigits(negative || text.startsWith('+') ? text.slice(1) : text, 10);
  } else {
    magnitude = javaDigits(text.slice(signLength + hexPrefix.length), 16);
  }
  if (magnitude === undefined) {
    return undefined;
  }
  const value = negative ? -magnitude : magnitude;
  return value >= INT_MIN && value <= INT_MAX ? value : undefined;
}

/**
 * `size` as the API binds its `Integer`: absent, or one value with no
 * character other than Java whitespace, is `null` (the default page size);
 * among several values the first counts, and only an empty one is `null`.
 * Otherwise every Java whitespace character is removed, wherever it stands
 * (`5 5` is 55), and the rest must be a {@link javaInteger}.
 */
function bindSize(query: QueryValues): Bound<number | null> {
  const values = query.get('size') ?? [];
  const first = values[0];
  if (first === undefined || (values.length === 1 ? isJavaBlank(first) : first === '')) {
    return { ok: true, value: null };
  }
  const value = javaInteger([...first].filter((char) => !isJavaWhitespace(char.codePointAt(0) ?? 0)).join(''));
  return value === undefined
    ? { ok: false, violation: { field: 'size', reason: 'size has an invalid value' } }
    : { ok: true, value };
}

/**
 * The violation of a filter entry, as the API checks it before any
 * normalization: more than `width` code points as received (blanks
 * included), else a U+0000; `undefined` for an absent or acceptable entry.
 */
function entryViolation(field: string, value: string | null, width: number): RequestViolation | undefined {
  if (value === null) {
    return undefined;
  }
  if (codePointLength(value) > width) {
    return { field, reason: `${field} must be at most ${width} characters` };
  }
  return value.includes('\u0000') ? { field, reason: `${field} must not contain U+0000` } : undefined;
}

/** Whether a `custId` path value is a customer id: exactly {@link CUST_ID_LENGTH} code points of {@link CUST_ID_PATTERN}. */
function isCustIdPath(value: string): boolean {
  return codePointLength(value) === CUST_ID_LENGTH && CUST_ID_PATTERN.test(value);
}

// ---------------------------------------------------------------------------
// Database collation (customer_sort: ICU root order with the digit tailoring)
// ---------------------------------------------------------------------------

/**
 * The options of both collators below, which compare in ICU's root order as
 * the database's `customer_sort` does. `en` has no CLDR tailoring, so it is
 * the root order; `und` would resolve to the process's default locale, which
 * could carry one. Punctuation and spaces are not ignored and digit runs are
 * not compared numerically, as in PostgreSQL's ICU collations.
 *
 * The database defines `customer_sort` as the ICU root locale `und` with the
 * tailoring rule `&[before 1]α<0<1<2<3<4<5<6<7<8<9` (V1__create_collation.sql),
 * not the reorder locale `und-u-kr-latn-digit`. The rule gives the ten ASCII
 * digits, in that order, primary weights just below α, so they sort after
 * every Latin letter and before Greek. Every other character keeps its root
 * weights, including the non-ASCII digits and the characters that decompose
 * to digits by compatibility (`０ ١ ½ ① ²`), which still sort before the Latin
 * letters. PostgreSQL leaves ICU's normalization mode off, and orders texts
 * that ICU finds equal by code point.
 *
 * {@link compareText} lets ICU itself decide, with three adjustments that
 * reproduce the database: the digit tailoring at the primary level
 * ({@link primaryForm}), ICU's weighing of combining marks in the order given
 * ({@link collationInput}), and the code-point tie-break
 * ({@link compareCodePoints}). They touch only ASCII digits, characters
 * primary-equal to α and combining marks that canonical reordering would
 * move; every other comparison is ICU's own.
 *
 * Not modelled:
 *
 * - Differences between this process's ICU and the database's (ICU 76.1 in
 *   the postgres:18.6 image). A character that only one of them knows (new
 *   in a later Unicode version), or whose root weights changed between them
 *   (some Han and Tangut ideographs), can sort differently.
 * - A contraction that the database's ICU completes past combining marks out
 *   of canonical order, such as `и`, two marks in descending combining class,
 *   then the breve that makes it `й`; the same for `و` and the hamza above,
 *   or the Tibetan vowel sign aa followed by a lower-class mark and then the
 *   vowel sign u. The blocker that keeps those marks in their order also
 *   stops the match.
 */
const ROOT_COLLATOR_OPTIONS: Intl.CollatorOptions = { usage: 'sort', ignorePunctuation: false, numeric: false };

/** The root order at the primary level: base characters only, accents and case ignored. */
const PRIMARY_COLLATOR = new Intl.Collator('en', { ...ROOT_COLLATOR_OPTIONS, sensitivity: 'base' });

/** The root order at all three levels: base characters, then accents, then case. */
const FULL_COLLATOR = new Intl.Collator('en', { ...ROOT_COLLATOR_OPTIONS, sensitivity: 'variant' });

/** α (U+03B1), the reset of the database's tailoring rule; {@link primaryForm} marks the tailored digits with it. */
const TAILORING_RESET = '\u03b1';

/** The combining grapheme joiner (U+034F): ICU ignores it at every level, and canonical reordering never moves a mark across it. */
const REORDER_BLOCKER = '\u034f';

/** At most this many entries are kept by each collation memo; a memo starts afresh when it is full. */
const COLLATION_MEMO_LIMIT = 4096;

/** {@link collationInput} and {@link primaryForm} results by text: the fixture names, cities and states are compared on every search. */
const collationInputMemo = new Map<string, string>();
const primaryFormMemo = new Map<string, string>();

/** Whether a code point beyond ASCII is primary-equal to {@link TAILORING_RESET}, by code point. */
const resetPrimaryMemo = new Map<number, boolean>();

/** `memo`'s entry for `key`, built by `build` and kept when it is absent. */
function remember<K, V>(memo: Map<K, V>, key: K, build: () => V): V {
  const known = memo.get(key);
  if (known !== undefined) {
    return known;
  }
  const value = build();
  if (memo.size >= COLLATION_MEMO_LIMIT) {
    memo.clear();
  }
  memo.set(key, value);
  return value;
}

/**
 * Whether canonical decomposition of `previous` followed by `next` moves a
 * combining mark across their boundary: the decomposition of `next` starts
 * with a mark whose canonical combining class is above 0 and below that of
 * the mark ending the decomposition of `previous`, so the pair is not in FCD
 * form.
 */
function reordersCanonically(previous: string, next: string): boolean {
  // No character below U+0300 decomposes to a leading combining mark.
  if (next.charCodeAt(0) < 0x300) {
    return false;
  }
  return (previous + next).normalize('NFD') !== previous.normalize('NFD') + next.normalize('NFD');
}

/**
 * `text` as the database's ICU weighs it. With normalization mode off, ICU
 * weighs the characters in the order given, while `Intl.Collator` reorders
 * combining marks canonically first; the two differ only at a boundary
 * {@link reordersCanonically} reports. A {@link REORDER_BLOCKER} is inserted
 * there, so the collators keep the order given and weigh nothing more; it
 * also ends any contraction that the database's ICU would complete past it
 * (see {@link ROOT_COLLATOR_OPTIONS}).
 */
function collationInput(text: string): string {
  return remember(collationInputMemo, text, () => {
    let input = '';
    let previous: string | undefined;
    for (const char of text) {
      if (previous !== undefined && reordersCanonically(previous, char)) {
        input += REORDER_BLOCKER;
      }
      input += char;
      previous = char;
    }
    return input;
  });
}

/** Whether {@link primaryForm} puts {@link TAILORING_RESET} before `char`: an ASCII digit, or a character primary-equal to α. */
function takesTailoringMark(char: string): boolean {
  const codePoint = char.codePointAt(0) ?? 0;
  if (codePoint < 0x80) {
    return codePoint >= 0x30 && codePoint <= 0x39;
  }
  return remember(resetPrimaryMemo, codePoint, () => PRIMARY_COLLATOR.compare(char, TAILORING_RESET) === 0);
}

/**
 * `text` as {@link PRIMARY_COLLATOR} compares it for the database's primary
 * level: its {@link collationInput} with α before each ASCII digit and before
 * each character primary-equal to α. Root weighs every digit below every
 * letter, so the pair (α, digit) sorts after every primary below α, before α
 * itself (now the pair (α, α)) and before every primary above α, in digit
 * order: the tailored position. No unmarked character's root primaries start
 * with α's (checked over every code point), so no pair is a prefix of another
 * weight and whole texts keep this order. Accents and case need no
 * adjustment: the tailored digits take the common secondary and tertiary
 * weights, as root's digits do.
 */
function primaryForm(text: string): string {
  return remember(primaryFormMemo, text, () => {
    let form = '';
    for (const char of collationInput(text)) {
      if (takesTailoringMark(char)) {
        form += TAILORING_RESET;
      }
      form += char;
    }
    return form;
  });
}

/**
 * The code-point order of two texts, a proper prefix first: PostgreSQL's
 * tie-break for a deterministic collation, `strcmp` of their UTF-8, which
 * orders as code points do (UTF-16 units do not, beyond U+FFFF).
 */
function compareCodePoints(a: string, b: string): number {
  const left = [...a];
  const right = [...b];
  const shared = Math.min(left.length, right.length);
  for (let index = 0; index < shared; index += 1) {
    const difference = (left[index]?.codePointAt(0) ?? 0) - (right[index]?.codePointAt(0) ?? 0);
    if (difference !== 0) {
      return difference;
    }
  }
  return left.length - right.length;
}

/** A `char(n)` value as PostgreSQL compares it: without its trailing blanks (U+0020). */
function bpchar(value: string): string {
  let end = value.length;
  while (end > 0 && value.charCodeAt(end - 1) === 0x20) {
    end -= 1;
  }
  return value.slice(0, end);
}

/** The sort keys of a search row or of a cursor position. */
interface SearchKeys {
  readonly name: string;
  readonly city: string;
  readonly state: string;
  readonly custId: string;
}

/**
 * The search order, `ORDER BY name, city, state, custid`, which is also the
 * keyset row comparison `(name, city, state, custid) > (...)`: each column
 * under {@link compareText}; `state` and `custid` are `char(n)`, so their
 * trailing blanks do not count.
 */
function compareSearchKeys(a: SearchKeys, b: SearchKeys): number {
  return (
    compareText(a.name, b.name) ||
    compareText(a.city, b.city) ||
    compareText(bpchar(a.state), bpchar(b.state)) ||
    compareText(bpchar(a.custId), bpchar(b.custId))
  );
}

// ---------------------------------------------------------------------------
// LIKE matching (PostgreSQL LIKE over blank-padded values, as the API queries)
// ---------------------------------------------------------------------------

/** One element of a LIKE pattern: a literal code point, `_` (exactly one code point) or `%` (any run, empty included). */
type LikeToken = { readonly kind: 'literal'; readonly char: string } | { readonly kind: 'one' } | { readonly kind: 'any' };

/** `value` right-padded with blanks, or cut, to `width` code points, as PostgreSQL's `rpad(value, width)`. */
function rpad(value: string, width: number): string {
  const chars = [...value];
  return chars.length >= width ? chars.slice(0, width).join('') : value + ' '.repeat(width - chars.length);
}

/** `text` with every `\` doubled, so PostgreSQL's default LIKE escape matches it literally, as Db2 (which has none) did. */
function escapeBackslashes(text: string): string {
  return text.replaceAll('\\', '\\\\');
}

/**
 * The tokens of a LIKE pattern under PostgreSQL's default escape `\`, which
 * makes the next code point literal; `%`, `_` and every other code point
 * stand for themselves as described by {@link LikeToken}.
 *
 * @throws Error when the pattern ends in a lone `\`, which PostgreSQL
 *   rejects; the patterns built here double every `\`, so none does
 */
function likeTokens(pattern: string): LikeToken[] {
  const chars = [...pattern];
  const tokens: LikeToken[] = [];
  for (let index = 0; index < chars.length; index += 1) {
    const char = chars[index];
    if (char === '\\') {
      index += 1;
      const escaped = chars[index];
      if (escaped === undefined) {
        throw new Error(`LIKE pattern must not end with escape character: ${JSON.stringify(pattern)}`);
      }
      tokens.push({ kind: 'literal', char: escaped });
    } else if (char === '%') {
      tokens.push({ kind: 'any' });
    } else if (char === '_') {
      tokens.push({ kind: 'one' });
    } else if (char !== undefined) {
      tokens.push({ kind: 'literal', char });
    }
  }
  return tokens;
}

/**
 * Whether `value LIKE pattern` holds in PostgreSQL: the whole value must
 * match, code point by code point and case-sensitively (the collation is
 * deterministic, so LIKE compares characters exactly).
 */
function likeMatches(value: string, pattern: string): boolean {
  const chars = [...value];
  const tokens = likeTokens(pattern);
  let charIndex = 0;
  let tokenIndex = 0;
  // The last `%` seen and the first character it has not yet absorbed.
  let anyToken = -1;
  let anyResume = 0;
  while (charIndex < chars.length) {
    const token = tokens[tokenIndex];
    if (token !== undefined && token.kind === 'any') {
      anyToken = tokenIndex;
      anyResume = charIndex;
      tokenIndex += 1;
    } else if (token !== undefined && (token.kind === 'one' || token.char === chars[charIndex])) {
      charIndex += 1;
      tokenIndex += 1;
    } else if (anyToken >= 0) {
      anyResume += 1;
      charIndex = anyResume;
      tokenIndex = anyToken + 1;
    } else {
      return false;
    }
  }
  while (tokens[tokenIndex]?.kind === 'any') {
    tokenIndex += 1;
  }
  return tokenIndex === tokens.length;
}

/**
 * The LIKE pattern of a customer `name` or `city` filter, built as the
 * source builds `%trim(SC_NAME) + '%'` into a `varchar(13)`: the normalized
 * filter plus `%`, cut to its first {@link FILTER_WIDTH} code points (so a
 * 13-character filter keeps no wildcard), then every `\` doubled. User `%`
 * and `_` stay wildcards. It is matched against the value padded to the
 * column width ({@link rpad}), where a `_` can match a pad blank.
 */
function customerPattern(filter: string): string {
  return escapeBackslashes([...`${filter}%`].slice(0, FILTER_WIDTH).join(''));
}

/**
 * The LIKE pattern of the state picker's normalized `nameContains`, as
 * PMTSTATER's `DESCLike`: `%%` when blank, otherwise the filter with every
 * `\` doubled between two `%`. It is matched against `rpad(upper(name), 30)`.
 */
function statePattern(filter: string): string {
  return filter === '' ? '%%' : `%${escapeBackslashes(filter)}%`;
}

// ---------------------------------------------------------------------------
// Search cursor (base64url JSON keyset position, as the API issues and checks it)
// ---------------------------------------------------------------------------

/** The outcome of reading one request input: its value, or the violation the API reports for it. */
type Bound<T> = { readonly ok: true; readonly value: T } | { readonly ok: false; readonly violation: RequestViolation };

/** Where a search page starts: after the row with these sort keys, with `served` rows already served. */
interface CursorPosition extends SearchKeys {
  readonly served: number;
}

/** A JSON number as written: its lexeme, and whether it has neither a fraction nor an exponent. */
interface JsonNumber {
  readonly lexeme: string;
  readonly integral: boolean;
}

/** A JSON value read by {@link readStrictJson}; an object is a map, so a repeated key cannot pass unseen. */
type JsonValue = null | boolean | string | JsonNumber | JsonValue[] | Map<string, JsonValue>;

/** The one violation every cursor failure reports; it never says which check failed. */
const CURSOR_INVALID: RequestViolation = { field: 'cursor', reason: 'cursor is not valid' };

/** The properties of a cursor object, exactly these and in the order the API writes them. */
const CURSOR_KEYS: readonly string[] = ['name', 'city', 'state', 'custid', 'served'];

/** The characters JSON allows between tokens. */
const JSON_WHITESPACE = ' \t\n\r';

/** The single-character escapes of a JSON string and the characters they stand for. */
const STRICT_JSON_ESCAPES: ReadonlyMap<string, string> = new Map([
  ['"', '"'], ['\\', '\\'], ['/', '/'], ['b', '\b'], ['f', '\f'], ['n', '\n'], ['r', '\r'], ['t', '\t'],
]);

/** The escapes the API's JSON writer uses for these characters; other controls below U+0020 become `\u00XX`. */
const JSON_OUTPUT_ESCAPES: ReadonlyMap<string, string> = new Map([
  ['"', '\\"'], ['\\', '\\\\'], ['\b', '\\b'], ['\t', '\\t'], ['\n', '\\n'], ['\f', '\\f'], ['\r', '\\r'],
]);

/** A JSON number token: optional minus, integer part without leading zeros, optional fraction and exponent. */
const JSON_NUMBER = /^-?(?:0|[1-9][0-9]*)(\.[0-9]+)?([eE][+-]?[0-9]+)?/;

/**
 * Reads `text` as one strict JSON value (RFC 8259, as the API's reader is
 * configured): no comments, single quotes, unquoted names, trailing commas,
 * leading zeros or raw control characters; whitespace only around tokens;
 * nothing but whitespace after the value; and no key given twice in any
 * object. `\u` escapes are taken as UTF-16 units, an unpaired surrogate
 * included.
 *
 * @returns the value, or `undefined` when `text` breaks any of these rules
 */
function readStrictJson(text: string): JsonValue | undefined {
  let index = 0;
  const invalid = (): never => {
    throw new SyntaxError(`not strict JSON at offset ${index}`);
  };
  const skipWhitespace = (): void => {
    while (index < text.length && JSON_WHITESPACE.includes(text.charAt(index))) {
      index += 1;
    }
  };
  const consume = (token: string): void => {
    if (!text.startsWith(token, index)) {
      invalid();
    }
    index += token.length;
  };
  const readString = (): string => {
    consume('"');
    let result = '';
    for (;;) {
      if (index >= text.length) {
        invalid();
      }
      const char = text.charAt(index);
      index += 1;
      if (char === '"') {
        return result;
      }
      if (char === '\\') {
        const escape = text.charAt(index);
        index += 1;
        const simple = STRICT_JSON_ESCAPES.get(escape);
        if (simple !== undefined) {
          result += simple;
        } else if (escape === 'u' && /^[0-9A-Fa-f]{4}$/.test(text.slice(index, index + 4))) {
          result += String.fromCharCode(Number.parseInt(text.slice(index, index + 4), 16));
          index += 4;
        } else {
          invalid();
        }
      } else if (char.charCodeAt(0) < 0x20) {
        invalid();
      } else {
        result += char;
      }
    }
  };
  const readNumber = (): JsonNumber => {
    const match = JSON_NUMBER.exec(text.slice(index));
    if (match === null) {
      return invalid();
    }
    index += match[0].length;
    return { lexeme: match[0], integral: match[1] === undefined && match[2] === undefined };
  };
  const readValue = (): JsonValue => {
    skipWhitespace();
    const char = text.charAt(index);
    if (char === '{') {
      consume('{');
      const members = new Map<string, JsonValue>();
      skipWhitespace();
      if (text.charAt(index) === '}') {
        index += 1;
        return members;
      }
      for (;;) {
        skipWhitespace();
        const key = readString();
        if (members.has(key)) {
          invalid();
        }
        skipWhitespace();
        consume(':');
        members.set(key, readValue());
        skipWhitespace();
        if (text.charAt(index) !== ',') {
          consume('}');
          return members;
        }
        index += 1;
      }
    }
    if (char === '[') {
      consume('[');
      const elements: JsonValue[] = [];
      skipWhitespace();
      if (text.charAt(index) === ']') {
        index += 1;
        return elements;
      }
      for (;;) {
        elements.push(readValue());
        skipWhitespace();
        if (text.charAt(index) !== ',') {
          consume(']');
          return elements;
        }
        index += 1;
      }
    }
    if (char === '"') {
      return readString();
    }
    if (char === 't' || char === 'f' || char === 'n') {
      const literal = char === 't' ? 'true' : char === 'f' ? 'false' : 'null';
      consume(literal);
      return literal === 'null' ? null : literal === 'true';
    }
    return readNumber();
  };
  try {
    const value = readValue();
    skipWhitespace();
    return index === text.length ? value : undefined;
  } catch {
    // A syntax error, or nesting too deep to follow, is simply not strict JSON.
    return undefined;
  }
}

/** The number of code points of `text`, the unit of every width the API checks. */
function codePointLength(text: string): number {
  return [...text].length;
}

/**
 * `text` as the database receives it from the API: each unpaired surrogate,
 * which a JSON `\u` escape can produce, becomes `?`, as Java's UTF-8 encoder
 * replaces it.
 */
function databaseText(text: string): string {
  return text.replace(/[\uD800-\uDBFF](?![\uDC00-\uDFFF])|(?<![\uD800-\uDBFF])[\uDC00-\uDFFF]/g, '?');
}

/**
 * The JSON a cursor's bytes carry: UTF-8 (a malformed sequence makes the
 * cursor invalid; one leading BOM is skipped) read by {@link readStrictJson},
 * or `undefined`.
 */
function cursorJson(bytes: Uint8Array): JsonValue | undefined {
  let text: string;
  try {
    text = new TextDecoder('utf-8', { fatal: true }).decode(bytes);
  } catch {
    return undefined;
  }
  return readStrictJson(text);
}

/** A cursor key: a JSON string of at most `width` code points without U+0000, or `undefined`. */
function cursorText(value: JsonValue | undefined, width: number): string | undefined {
  return typeof value === 'string' && codePointLength(value) <= width && !value.includes('\u0000') ? value : undefined;
}

/** The cursor's `served`: a JSON integer from 0 to {@link MAX_SEARCH_ROWS} - 1 (`-0` is 0), or `undefined`. */
function cursorServed(value: JsonValue | undefined): number | undefined {
  if (typeof value !== 'object' || value === null || Array.isArray(value) || value instanceof Map || !value.integral) {
    return undefined;
  }
  const served = BigInt(value.lexeme);
  return served >= 0n && served < BigInt(MAX_SEARCH_ROWS) ? Number(served) : undefined;
}

/**
 * Decodes a search cursor as the API does: a blank one (absent, empty or
 * Java whitespace only) is the first page. Otherwise it is valid only with
 * every check passing: at most {@link MAX_CURSOR_LENGTH} UTF-16 units;
 * base64url (`A-Z a-z 0-9 - _`; padding optional, but where present it
 * completes the last unit and ends the text; a last unit of one character
 * is invalid; unused trailing bits are ignored); UTF-8 bytes, a leading BOM
 * skipped; {@link readStrictJson}; an object with exactly the keys of
 * {@link CURSOR_KEYS}; `name`, `city`, `state` and `custid` strings of at most
 * 40, 20, 2 and 4 code points without U+0000, `custid` of `[A-Z0-9]{4}`; and
 * `served` per {@link cursorServed}. Any failure is APP0400 on `cursor`.
 */
function decodeCursor(cursor: string | null): Bound<CursorPosition | null> {
  if (cursor === null || isJavaBlank(cursor)) {
    return { ok: true, value: null };
  }
  const invalid: Bound<CursorPosition | null> = { ok: false, violation: CURSOR_INVALID };
  if (cursor.length > MAX_CURSOR_LENGTH || /[+/]/.test(cursor)) {
    return invalid;
  }
  // The URL-safe alphabet differs from the standard one only in `-` and `_`.
  const bytes = decodeBase64(cursor.replaceAll('-', '+').replaceAll('_', '/'));
  if (bytes === undefined) {
    return invalid;
  }
  const json = cursorJson(bytes);
  if (!(json instanceof Map) || json.size !== CURSOR_KEYS.length || !CURSOR_KEYS.every((key) => json.has(key))) {
    return invalid;
  }
  const name = cursorText(json.get('name'), NAME_WIDTH);
  const city = cursorText(json.get('city'), CITY_WIDTH);
  const state = cursorText(json.get('state'), STATE_WIDTH);
  const custId = cursorText(json.get('custid'), CUST_ID_LENGTH);
  const served = cursorServed(json.get('served'));
  if (
    name === undefined ||
    city === undefined ||
    state === undefined ||
    custId === undefined ||
    !CUST_ID_PATTERN.test(custId) ||
    served === undefined
  ) {
    return invalid;
  }
  return {
    ok: true,
    value: { name: databaseText(name), city: databaseText(city), state: databaseText(state), custId, served },
  };
}

/** `value` as a JSON string, escaped as the API's JSON writer escapes it. */
function jsonText(value: string): string {
  let result = '"';
  for (const char of value) {
    const code = char.codePointAt(0) ?? 0;
    const escape = JSON_OUTPUT_ESCAPES.get(char);
    if (escape !== undefined) {
      result += escape;
    } else if (code < 0x20) {
      result += `\\u${code.toString(16).toUpperCase().padStart(4, '0')}`;
    } else {
      result += char;
    }
  }
  return `${result}"`;
}

/**
 * The cursor of the page after `last`, as the API writes it: base64url
 * without padding over the UTF-8 of the compact JSON
 * `{"name":…,"city":…,"state":…,"custid":…,"served":n}` holding the sort
 * keys of `last`, the last row returned, and the rows served so far.
 */
function encodeCursor(last: SearchKeys, served: number): string {
  const json =
    `{"name":${jsonText(last.name)},"city":${jsonText(last.city)},"state":${jsonText(last.state)},` +
    `"custid":${jsonText(last.custId)},"served":${served}}`;
  let binary = '';
  for (const byte of new TextEncoder().encode(json)) {
    binary += String.fromCharCode(byte);
  }
  return btoa(binary).replaceAll('+', '-').replaceAll('/', '_').replace(/=+$/, '');
}

/** The normalized search filters: `name` and `city` entries, a 2-character `state` or `''`, and the F9 toggle. */
interface SearchFilters {
  readonly name: string;
  readonly city: string;
  readonly state: string;
  readonly includeInactive: boolean;
}

/**
 * Whether a row passes the search's predicates, as the API's query applies
 * them: `rpad(name, 40) LIKE` and `rpad(city, 20) LIKE` the
 * {@link customerPattern} of a non-blank entry, the exact `char(2)` state
 * of a non-blank state (an unknown code matches nothing), and `active = 'Y'`
 * unless inactive customers are included.
 */
function matchesSearch(row: CustomerSummaryFixture, filters: SearchFilters): boolean {
  return (
    (filters.name === '' || likeMatches(rpad(row.name, NAME_WIDTH), customerPattern(filters.name))) &&
    (filters.city === '' || likeMatches(rpad(row.city, CITY_WIDTH), customerPattern(filters.city))) &&
    (filters.state === '' || bpchar(row.state) === bpchar(filters.state)) &&
    (filters.includeInactive || row.active === 'Y')
  );
}

/**
 * One search page from `matches`, the matching rows in search order, as the
 * API pages them. The page starts after `position` (the first page when
 * `null`) and holds at most `min(size, 9999 - served)` rows; one more row is
 * looked ahead. When the rows served reach {@link MAX_SEARCH_ROWS}, the page
 * ends the list: `limitReached`, notice DEM0006, no `nextCursor`. Otherwise
 * `nextCursor` encodes the last row returned when the look-ahead row exists,
 * and DEM0002 is the notice only of a first page that matched nothing.
 */
function searchPage(
  matches: readonly CustomerSummaryFixture[],
  position: CursorPosition | null,
  size: number,
): Schemas['SearchResponse'] {
  const served = position?.served ?? 0;
  const pageLimit = Math.min(size, MAX_SEARCH_ROWS - served);
  const candidates = position === null ? matches : matches.filter((row) => compareSearchKeys(row, position) > 0);
  const items = candidates.slice(0, pageLimit).map((row) => ({ ...row }));
  const servedAfter = served + items.length;
  if (servedAfter >= MAX_SEARCH_ROWS) {
    return { items, nextCursor: null, limitReached: true, notice: notice('DEM0006') };
  }
  const last = items.at(-1);
  return {
    items,
    nextCursor: candidates.length > pageLimit && last !== undefined ? encodeCursor(last, servedAfter) : null,
    limitReached: false,
    notice: items.length === 0 && position === null ? notice('DEM0002') : null,
  };
}

// ---------------------------------------------------------------------------
// Default handlers
// ---------------------------------------------------------------------------

/**
 * One handler per API route, answering as the backend does for valid input
 * and with its 400 problems for the parameter guards. The writes bind and
 * validate their JSON bodies (415 and 400 APP0400), run the nine field rules
 * (422) and, for review only, the default stub address service, as the
 * backend does. Paths are relative, so
 * MSW matches them against jsdom's `location`, the origin `setup.ts` resolves
 * relative `fetch` URLs against.
 *
 * Every route runs behind {@link secured}, the server's security rules:
 * `/api/messages` is public, the reads need INQUIRY, review and the writes
 * MAINTENANCE (which implies INQUIRY), and credentials supplied anywhere are
 * checked, so a test gets 401 APP0401 or 403 APP0403 by signing in as the user
 * it needs. Customers live in the store: an add or update persists, and every
 * read sees it, until `server.resetHandlers()` (or {@link resetHandlerState})
 * restores the seed rows and the id sequence. No handler mutates a fixture,
 * and every response body is a fresh object.
 */
export const handlers = resettingStore([
  // Public: the whole catalog as one JSON object.
  http.get('/api/messages', secured('public', () => HttpResponse.json({ ...catalog }))),

  // The signed-in user and roles, or 401 APP0401.
  http.get('/api/session', secured('INQUIRY', ({ request }) => {
    const user = principal(request);
    return HttpResponse.json({ username: user.username, roles: user.roles });
  })),

  // States: `nameContains` (at most 10 characters, no U+0000) matched as
  // LIKE '%<filter>%' against the padded uppercase name, so `%` and `_` are
  // wildcards; `sort` = name (absent or empty too) or code, exactly.
  http.get('/api/states', secured('INQUIRY', ({ request }) => {
    const url = new URL(request.url);
    const instance = url.pathname;
    const query = queryValues(url);
    const nameContains = textParam(query, 'nameContains');
    const sortParam = textParam(query, 'sort');
    const filterViolation = entryViolation('nameContains', nameContains, NAME_CONTAINS_WIDTH);
    if (filterViolation !== undefined) {
      return requestNotValid(instance, [filterViolation]);
    }
    // As with the backend's `defaultValue`, an empty `sort` means the default;
    // the value is neither trimmed nor case-folded.
    const sort = sortParam === null || sortParam === '' ? 'name' : sortParam;
    if (sort !== 'name' && sort !== 'code') {
      return requestNotValid(instance, [{ field: 'sort', reason: 'sort must be name or code' }]);
    }
    // rpad(upper(name), 30) LIKE the pattern: PostgreSQL's upper() maps each
    // character fully, as toUpperCase() does, and the state names are ASCII.
    const pattern = statePattern(normalizeFilter(nameContains));
    const rows = states
      .filter((row) => likeMatches(rpad(row.name.toUpperCase(), STATE_NAME_WIDTH), pattern))
      .map((row) => ({ state: row.state, name: row.name }));
    rows.sort((a, b) => (sort === 'name' ? compareText(a.name, b.name) : compareText(bpchar(a.state), bpchar(b.state))));
    return HttpResponse.json(rows);
  })),

  // Customer search: LIKE prefix filters over the padded name and city,
  // exact state, active-only unless includeInactive, keyset pages of `size`
  // rows behind the API's base64url cursor, capped at 9,999 rows (DEM0006).
  http.get('/api/customers', secured('INQUIRY', ({ request }) => {
    const url = new URL(request.url);
    const instance = url.pathname;
    const query = queryValues(url);

    // Binding, in the controller's parameter order (name, city, state,
    // includeInactive, size, cursor); the first parameter that does not
    // bind is reported.
    const name = textParam(query, 'name');
    const city = textParam(query, 'city');
    const state = textParam(query, 'state');
    const includeInactive = bindIncludeInactive(query);
    if (!includeInactive.ok) {
      return requestNotValid(instance, [includeInactive.violation]);
    }
    const size = bindSize(query);
    if (!size.ok) {
      return requestNotValid(instance, [size.violation]);
    }

    // The search service's checks, in its order: size, name, city, state,
    // cursor; widths count code points of the entry as received.
    const pageSize = size.value ?? DEFAULT_PAGE_SIZE;
    if (pageSize < 1 || pageSize > MAX_PAGE_SIZE) {
      return requestNotValid(instance, [{ field: 'size', reason: `size must be between 1 and ${MAX_PAGE_SIZE}` }]);
    }
    const filterViolation = entryViolation('name', name, FILTER_WIDTH) ?? entryViolation('city', city, FILTER_WIDTH);
    if (filterViolation !== undefined) {
      return requestNotValid(instance, [filterViolation]);
    }
    // A blank state selects every state; any trimmed length but 2 is DEM0007,
    // which takes precedence over U+0000 (a lone U+0000 is DEM0007).
    const stateFilter = normalizeFilter(state);
    const stateLength = codePointLength(stateFilter);
    if (stateLength !== 0 && stateLength !== STATE_WIDTH) {
      return problem(400, 'DEM0007', {
        errors: [{ field: 'state', code: 'DEM0007', message: messageText('DEM0007') }],
        instance,
      });
    }
    if (state !== null && state.includes('\u0000')) {
      return requestNotValid(instance, [{ field: 'state', reason: 'state must not contain U+0000' }]);
    }
    const position = decodeCursor(textParam(query, 'cursor'));
    if (!position.ok) {
      return requestNotValid(instance, [position.violation]);
    }

    // The store's rows, this test's writes included, in search order (name,
    // city, state, custId); a test that serves its own list sets its order.
    const filters: SearchFilters = {
      name: normalizeFilter(name),
      city: normalizeFilter(city),
      state: stateFilter,
      includeInactive: includeInactive.value,
    };
    const matches = storedSummaries().filter((customer) => matchesSearch(customer, filters));
    return HttpResponse.json(searchPage(matches, position.value, pageSize));
  })),

  // One customer, or 404 DEM0599. The path value, as decoded, must be
  // exactly four characters of A-Z and 0-9.
  http.get<{ custId: string }>('/api/customers/:custId', secured('INQUIRY', ({ request, params }) => {
    const instance = pathOf(request);
    const { custId } = params;
    if (!isCustIdPath(custId)) {
      return requestNotValid(instance, [{ field: 'custId', reason: 'custId has an invalid format' }]);
    }
    const detail = storedCustomer(custId);
    if (detail === undefined) {
      return problem(404, 'DEM0599', { instance });
    }
    return HttpResponse.json({ ...detail });
  })),

  // Review: 415/400 APP0400 for a body the server refuses; the nine field
  // rules in source order (the first failure is 422; after the State rule
  // with `stateAccepted`); then the stub address service: the standardized
  // values with the confirmation notice, or 422 DEM9898 / DEM0503 with
  // `stateAccepted`. Tests override the route for 502 APP0502.
  http.post('/api/customers/review', secured('MAINTENANCE', async ({ request }) => {
    const instance = pathOf(request);
    const read = await readReviewRequest(request);
    if (!read.ok) {
      return read.response;
    }
    const { purpose, text } = read.value;
    const fields = customerFields(text, purpose === 'ADD');
    const failure = firstRuleFailure(fields);
    if (failure !== undefined) {
      return ruleProblem(instance, failure, failure.rule > STATE_RULE ? fields.state : undefined);
    }
    return reviewAddress(instance, purpose, fields);
  })),

  // Add: 415/400 APP0400 for a body the server refuses; the nine field rules
  // (422, no address standardization, no id consumed); then 201 with the next
  // id from EEEF, version 0, the principal as stamp user and a relative
  // Location; 503 APP0503 once 9999 has been issued.
  http.post('/api/customers', secured('MAINTENANCE', async ({ request }) => {
    const instance = pathOf(request);
    const read = await readAddRequest(request);
    if (!read.ok) {
      return read.response;
    }
    const fields = customerFields(read.value, true);
    const failure = firstRuleFailure(fields);
    if (failure !== undefined) {
      return ruleProblem(instance, failure);
    }
    const created = insertCustomer(fields, principal(request).username);
    if (created.status === 'exhausted') {
      return problem(503, 'APP0503', { instance });
    }
    return HttpResponse.json(created.row, {
      status: 201,
      headers: { Location: `/api/customers/${created.row.custId}` },
    });
  })),

  // Update: 415/400 APP0400 for a body the server refuses, an invalid path id
  // reported before the body's violations; the nine field rules (422, before
  // existence and version, no address standardization); then 200 with the
  // version incremented and the principal as stamp user, 404 DEM0599 for an
  // absent id, 409 DEM1002 with `current` for a stale version.
  http.put<{ custId: string }>('/api/customers/:custId', secured('MAINTENANCE', async ({ request, params }) => {
    const instance = pathOf(request);
    const { custId } = params;
    const read = await readUpdateRequest(request, custId);
    if (!read.ok) {
      return read.response;
    }
    const { version, text } = read.value;
    const fields = customerFields(text, false);
    const failure = firstRuleFailure(fields);
    if (failure !== undefined) {
      return ruleProblem(instance, failure);
    }
    const updated = updateCustomer(custId, fields, version, principal(request).username);
    if (updated.status === 'missing') {
      return problem(404, 'DEM0599', { instance });
    }
    if (updated.status === 'stale') {
      return problem(409, 'DEM1002', { current: updated.current, instance });
    }
    return HttpResponse.json(updated.row);
  })),
]);
