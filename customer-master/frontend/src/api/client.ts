/**
 * The one fetch wrapper between the SPA and the Customer Master API.
 *
 * Part of the shared component "Error model (client transport)", with
 * `./problem.ts`. Every typed call in `./customers.ts`, `./states.ts`,
 * `./session.ts` and `./messages.ts` goes through {@link request}, and
 * `auth/AuthProvider.tsx` stores the signed-in credentials here with
 * {@link setCredentials} and registers its sign-out with
 * {@link onUnauthorized}.
 *
 * What it replaces. On the 5250, PMTCUSTR called MTNCUSTR and PMTSTATER as
 * programs and passed the customer id, the function code and the state as
 * parameters (5250_Subfile/PMTCUSTR.SQLRPGLE:83-90); a failure came back as a
 * message on the program message queue (SndMsgPgmQ, Service_Pgms/SRV_MSG.RPGLE)
 * or as SQLProblem's escape message (Service_Pgms/SRV_SQL.SQLRPGLE). Here each
 * call is one stateless HTTP request, and each failure is an RFC 9457
 * problem+json body parsed into an {@link ApiError}.
 *
 * What this module does, and nothing else:
 * - Sends `X-Requested-With: XMLHttpRequest` on every call, so the API answers
 *   a 401 without `WWW-Authenticate` and the browser never opens its native
 *   sign-in prompt, plus `Authorization: Basic …` whenever credentials are
 *   known. It never adds a role, a mode, an id or a change stamp to a request.
 * - Parses every non-2xx response into `ApiError {status, problem}`; a body
 *   that is not usable problem+json (an HTML page from a proxy, plain text,
 *   broken JSON) becomes the synthetic DEM9999 problem, and a request that
 *   never reached the server becomes `ApiError(0, DEM9999)`.
 * - On 401, calls the handler `AuthProvider` registered, then rejects.
 *
 * Constraints:
 * - Credentials live in memory only, in this module's variable, never in Web
 *   Storage, IndexedDB or any other browser store, so a reload signs the user
 *   out and nothing outlives the tab. The API sets no session and the server
 *   keeps no per-caller state between requests.
 * - Paths are relative and start with `/api/`, so every call stays on the
 *   page's own origin: nginx forwards `/api` to the `app` service in Compose
 *   and the Vite proxy does so in development. There is no absolute host, no
 *   CORS and no `credentials: 'include'`.
 * - Layer rule: nothing is imported from `components/`, `errors/`,
 *   `features/` or `auth/`. Nothing here renders, touches the DOM, logs or
 *   keeps form or dialog state; the calling feature passes a rejection to
 *   `errors/useProblemPresenter.ts`, which shows it.
 *
 * @example
 * ```ts
 * // A typed call in ./customers.ts:
 * const page = await request<SearchResponse>('/api/customers', {
 *   query: { name: 'NIBH', includeInactive: false, size: 12, cursor: undefined },
 * });
 *
 * // Sign-in, in AuthProvider: try the typed credentials before storing them.
 * const session = await request<SessionResponse>('/api/session', { credentials });
 * setCredentials(credentials);
 * ```
 */
import { ApiError, syntheticProblem } from './problem';
import type { FieldError, Problem } from './problem';

// ---------------------------------------------------------------------------
// Public types
// ---------------------------------------------------------------------------

/** HTTP Basic credentials as the user typed them on the sign-in page. */
export type Credentials = { username: string; password: string };

/** The methods the API serves: reads, review and add, and update. */
export type HttpMethod = 'GET' | 'POST' | 'PUT';

/**
 * Options of one {@link request}.
 *
 * - `method`: `GET` when absent.
 * - `query`: query parameters; an `undefined` value is left out, any other is
 *   sent as `String(value)` (`includeInactive=false`, `size=12`).
 * - `body`: sent as JSON with `Content-Type: application/json` when not
 *   `undefined`. Members whose value is `undefined` drop out, so an absent
 *   `active` on an add stays absent and the server defaults it to `Y`.
 * - `credentials`: used for this call instead of the stored ones, as the
 *   sign-in page does to try credentials before they are stored.
 */
export type RequestOptions = {
  method?: HttpMethod;
  query?: Record<string, string | number | boolean | undefined>;
  body?: unknown;
  credentials?: Credentials;
};

// ---------------------------------------------------------------------------
// Module state: the in-memory credentials and the 401 hook
// ---------------------------------------------------------------------------

/** The signed-in user's credentials; `null` before sign-in and after sign-out. */
let stored: Credentials | null = null;

/** The handler `AuthProvider` registered for 401 responses, if any. */
let unauthorizedHandler: (() => void) | null = null;

/**
 * Stores the credentials every later call sends, or forgets them with `null`
 * (sign-out). They are kept in this module's memory only. A copy is kept, so
 * a caller that later changes its object does not change what is sent.
 */
export function setCredentials(c: Credentials | null): void {
  stored = c === null ? null : { username: c.username, password: c.password };
}

/**
 * Registers the handler called once for every 401 response, before the call
 * rejects; `AuthProvider` clears the credentials and routes to `/sign-in`
 * there. The last registration wins.
 *
 * The returned function unregisters `handler`, but only while it is still the
 * registered one, so a stale unregister (an unmounted provider, or React
 * StrictMode's second effect run) never removes a newer registration.
 *
 * @returns the unregister function, suitable as an effect cleanup
 */
export function onUnauthorized(handler: () => void): () => void {
  unauthorizedHandler = handler;
  return () => {
    if (unauthorizedHandler === handler) {
      unauthorizedHandler = null;
    }
  };
}

// ---------------------------------------------------------------------------
// Wire constants
// ---------------------------------------------------------------------------

/** Every API path starts with this prefix; it keeps each call on the page's origin. */
const API_PREFIX = '/api/';

/** The media type of every API error body (RFC 9457). */
const PROBLEM_JSON = 'application/problem+json';

/** Success bodies are JSON and error bodies problem+json. */
const ACCEPT = `application/json, ${PROBLEM_JSON}`;

/** The problem `type` URN prefix the API uses, completed with the problem's code. */
const PROBLEM_TYPE_PREFIX = 'urn:customer-master:problem:';

// ---------------------------------------------------------------------------
// The request
// ---------------------------------------------------------------------------

/**
 * Sends one API call and resolves with its parsed JSON body.
 *
 * - Resolves `undefined` for a 2xx response with an empty body (204).
 * - Rejects with `ApiError(status, problem)` for every non-2xx response, with
 *   the problem+json body or the synthetic DEM9999 one; on 401 the
 *   {@link onUnauthorized} handler runs first, exactly once.
 * - Rejects with `ApiError(0, DEM9999)` when no response arrives (offline,
 *   DNS, a refused or reset connection), and with `ApiError(status, DEM9999)`
 *   for a 2xx body that is not JSON. The 401 handler runs for neither.
 *
 * `T` is the caller's declared response type, taken from the generated
 * `./schema`; the body is not validated against it.
 *
 * @param path the API path, relative and starting with `/api/`, without a
 *   query string of its own unless `options.query` is empty
 * @param options method, query, JSON body and per-call credentials
 * @throws TypeError (as a rejection) when `path` does not start with `/api/`,
 *   a programming error that never reaches the network
 */
export async function request<T>(path: string, options: RequestOptions = {}): Promise<T> {
  if (!path.startsWith(API_PREFIX)) {
    throw new TypeError(`API paths are relative and start with ${API_PREFIX}: ${path}`);
  }
  const url = withQuery(path, options.query);

  const headers: Record<string, string> = {
    'X-Requested-With': 'XMLHttpRequest',
    Accept: ACCEPT,
  };
  // The public `GET /api/messages` is fetched before sign-in, so with no
  // credentials at all the header is left out rather than sent empty.
  const credentials = options.credentials ?? stored;
  if (credentials !== null) {
    headers.Authorization = `Basic ${basicToken(credentials)}`;
  }
  let body: string | undefined;
  if (options.body !== undefined) {
    headers['Content-Type'] = 'application/json';
    body = JSON.stringify(options.body);
  }

  let response: Response;
  try {
    // The global `fetch` is looked up on every call, never captured in a
    // module constant: the test base replaces `globalThis.fetch` after MSW
    // starts listening, to resolve relative URLs against jsdom's location.
    response = await fetch(url, { method: options.method ?? 'GET', headers, body });
  } catch {
    // No response at all; the server never saw the request, so there is no
    // status, no problem body and no reason to sign the user out.
    throw new ApiError(0, syntheticProblem(0));
  }

  if (response.ok) {
    return readSuccess<T>(response);
  }

  const error = new ApiError(response.status, await readProblem(response));
  if (response.status === 401) {
    notifyUnauthorized();
  }
  throw error;
}

// ---------------------------------------------------------------------------
// Request helpers
// ---------------------------------------------------------------------------

/**
 * `path` with the defined `query` values appended in insertion order, encoded
 * by `URLSearchParams`; `path` alone when none is defined.
 */
function withQuery(path: string, query: RequestOptions['query']): string {
  const params = new URLSearchParams();
  if (query !== undefined) {
    for (const [name, value] of Object.entries(query)) {
      if (value !== undefined) {
        params.append(name, String(value));
      }
    }
  }
  const search = params.toString();
  if (search === '') {
    return path;
  }
  return `${path}${path.includes('?') ? '&' : '?'}${search}`;
}

/**
 * The base64 token of HTTP Basic: the UTF-8 bytes of `username:password`,
 * each turned into one Latin-1 character for `btoa`. Calling `btoa` on the
 * string itself would throw for a non-Latin-1 password; the server decodes
 * the token as UTF-8. The bytes are appended in a loop, not spread into
 * `String.fromCharCode`, so no length limit on arguments applies.
 */
function basicToken({ username, password }: Credentials): string {
  let binary = '';
  for (const byte of new TextEncoder().encode(`${username}:${password}`)) {
    binary += String.fromCharCode(byte);
  }
  return btoa(binary);
}

/** Calls the registered 401 handler, if any, once. */
function notifyUnauthorized(): void {
  const handler = unauthorizedHandler;
  if (handler === null) {
    return;
  }
  try {
    handler();
  } catch {
    // Contained on purpose: the caller must receive the 401's ApiError, the
    // one rejection every feature presents, whatever the sign-out hook does.
  }
}

// ---------------------------------------------------------------------------
// Response helpers
// ---------------------------------------------------------------------------

/**
 * The JSON body of a 2xx response, or `undefined` when it is empty. A body
 * that cannot be read or is not JSON rejects with the synthetic DEM9999
 * problem under the response's status.
 */
async function readSuccess<T>(response: Response): Promise<T> {
  let text: string;
  try {
    text = await response.text();
  } catch {
    throw new ApiError(response.status, syntheticProblem(response.status));
  }
  if (text === '') {
    return undefined as T;
  }
  try {
    return JSON.parse(text) as T;
  } catch {
    throw new ApiError(response.status, syntheticProblem(response.status));
  }
}

/**
 * The problem a non-2xx response carries. Only a body declared
 * `application/problem+json` (media types compare case-insensitively) that
 * parses to an object with a string `code` is a problem; anything else, an
 * HTML error page from a proxy among them, is the synthetic DEM9999 problem
 * under the response's status.
 */
async function readProblem(response: Response): Promise<Problem> {
  const contentType = response.headers.get('Content-Type') ?? '';
  if (!contentType.toLowerCase().includes(PROBLEM_JSON)) {
    return syntheticProblem(response.status);
  }
  let parsed: unknown;
  try {
    parsed = JSON.parse(await response.text());
  } catch {
    return syntheticProblem(response.status);
  }
  if (!isRecord(parsed) || typeof parsed.code !== 'string') {
    return syntheticProblem(response.status);
  }
  return toProblem(parsed, parsed.code, response.status);
}

/**
 * A parsed problem body as a {@link Problem} its consumers can rely on.
 *
 * The members every problem has are completed where the body lacks them:
 * `status` from the response when it is not a number, `type` from the code,
 * and `title` and `detail` as `''`, so the presenter falls back to the
 * catalog text of `code` as it does for a synthetic problem. The optional
 * members are kept only when they have their contract's shape (`args` as
 * strings, `errors` as `{field, code, message}` entries, `current` as an
 * object, the rest as strings); a malformed one is left out rather than
 * handed to a presenter that iterates or focuses it.
 */
function toProblem(body: Record<string, unknown>, code: string, status: number): Problem {
  const problem: Problem = {
    type: typeof body.type === 'string' ? body.type : `${PROBLEM_TYPE_PREFIX}${code}`,
    title: typeof body.title === 'string' ? body.title : '',
    status: typeof body.status === 'number' && Number.isFinite(body.status) ? body.status : status,
    detail: typeof body.detail === 'string' ? body.detail : '',
    code,
  };
  if (typeof body.instance === 'string') {
    problem.instance = body.instance;
  }
  if (Array.isArray(body.args)) {
    problem.args = body.args.map(String);
  }
  if (Array.isArray(body.errors)) {
    const errors = body.errors.filter(isFieldError);
    if (errors.length > 0) {
      problem.errors = errors;
    }
  }
  if (isRecord(body.current)) {
    // The server's CustomerResponse, as the 409 DEM1002 contract defines it.
    problem.current = body.current as Problem['current'];
  }
  if (typeof body.stateAccepted === 'string') {
    problem.stateAccepted = body.stateAccepted;
  }
  if (typeof body.errorId === 'string') {
    problem.errorId = body.errorId;
  }
  return problem;
}

/** Whether `value` is a plain JSON object (not `null`, not an array). */
function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

/** Whether `value` is one `errors` entry: string `field`, `code` and `message`. */
function isFieldError(value: unknown): value is FieldError {
  return (
    isRecord(value) &&
    typeof value.field === 'string' &&
    typeof value.code === 'string' &&
    typeof value.message === 'string'
  );
}
