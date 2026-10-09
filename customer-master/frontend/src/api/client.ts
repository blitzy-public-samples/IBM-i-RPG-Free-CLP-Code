/**
 * The one fetch wrapper between the SPA and the Customer Master API. Every
 * typed call in `./customers.ts`, `./states.ts`, `./session.ts` and
 * `./messages.ts` goes through {@link request}; `auth/AuthProvider.tsx` stores
 * the signed-in credentials with {@link setCredentials} and registers its
 * sign-out with {@link onUnauthorized}.
 *
 * - Every call sends `X-Requested-With: XMLHttpRequest`, so the API answers a
 *   401 without `WWW-Authenticate` and the browser never opens its native
 *   sign-in prompt, plus `Authorization: Basic …` whenever credentials are
 *   known. No role, mode, id or change stamp is ever added.
 * - Credentials live in this module's memory only, never in Web Storage,
 *   IndexedDB or any other browser store, so a reload signs the user out.
 * - Paths are relative and start with `/api/`, so every call stays on the
 *   page's own origin: no absolute host, no CORS, no `credentials: 'include'`.
 * - Nothing here renders, touches the DOM, logs or keeps form or dialog
 *   state, and nothing is imported from `components/`, `errors/`, `features/`
 *   or `auth/`; the calling feature presents a rejection.
 */
import { ApiError, syntheticProblem } from './problem';
import type { FieldError, Problem } from './problem';

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
 * - `signal`: abandons a read whose result nobody needs any more, such as a
 *   query that a newer search replaced, a reset cleared or an unmounted
 *   owner left behind; {@link request} says how such a call rejects. GET
 *   only: a write is never cancelled, so a `signal` with `POST` or `PUT` is
 *   refused.
 */
export type RequestOptions = {
  method?: HttpMethod;
  query?: Record<string, string | number | boolean | undefined>;
  body?: unknown;
  credentials?: Credentials;
  signal?: AbortSignal;
};

/**
 * What the {@link onUnauthorized} handler learns about the refused call.
 *
 * - `perCallCredentials`: `true` when the call carried its own
 *   `RequestOptions.credentials`, as a sign-in trial does, so the 401 refused
 *   only those candidate credentials and nothing stored; `false` when it
 *   carried the stored credentials or none at all.
 * - `superseded`: `true` when {@link setCredentials} ran after the call was
 *   sent, so the 401 refused an identity, or the absence of one, that is no
 *   longer the stored state, and says nothing about the current credentials.
 */
export type UnauthorizedInfo = { perCallCredentials: boolean; superseded: boolean };

/** The signed-in user's credentials; `null` before sign-in and after sign-out. */
let stored: Credentials | null = null;

/**
 * Counts every {@link setCredentials} call. A call records it when it reads
 * the stored credentials, so a 401 can tell whether it answers the stored
 * state still in place or one that a sign-out or a new sign-in replaced.
 */
let credentialEpoch = 0;

/** The handler `AuthProvider` registered for 401 responses, if any. */
let unauthorizedHandler: ((info: UnauthorizedInfo) => void) | null = null;

/**
 * Stores the credentials every later call sends, or forgets them with `null`
 * (sign-out). A copy is kept, so a caller that later changes its object does
 * not change what is sent. Every call, storing or clearing, supersedes the
 * calls already sent, whose 401 then reports `superseded`.
 */
export function setCredentials(c: Credentials | null): void {
  stored = c === null ? null : { username: c.username, password: c.password };
  credentialEpoch += 1;
}

/**
 * Registers the handler called once for every 401 response, before the call
 * rejects; `AuthProvider` clears the credentials and routes to `/sign-in`
 * there. The last registration wins.
 *
 * The handler receives an {@link UnauthorizedInfo}. When either of its
 * members is `true`, the 401 refused a sign-in trial or an earlier identity,
 * never the credentials stored now, so the handler can leave the signed-in
 * session and the stored credentials alone.
 *
 * The returned function unregisters `handler`, but only while it is still the
 * registered one, so a stale unregister (an unmounted provider, or React
 * StrictMode's second effect run) never removes a newer registration.
 *
 * @returns the unregister function, suitable as an effect cleanup
 */
export function onUnauthorized(handler: (info: UnauthorizedInfo) => void): () => void {
  unauthorizedHandler = handler;
  return () => {
    if (unauthorizedHandler === handler) {
      unauthorizedHandler = null;
    }
  };
}

const API_PREFIX = '/api/';

const PROBLEM_JSON = 'application/problem+json';

const ACCEPT = `application/json, ${PROBLEM_JSON}`;

const PROBLEM_TYPE_PREFIX = 'urn:customer-master:problem:';

/**
 * Sends one API call and resolves with its parsed JSON body.
 *
 * - Resolves `undefined` for a 2xx response with an empty body (204).
 * - Rejects with `ApiError(status, problem)` for every non-2xx response, with
 *   the problem+json body or the synthetic DEM9999 one; on 401 the
 *   {@link onUnauthorized} handler runs first, exactly once, with
 *   `perCallCredentials` telling whether `options.credentials` was given and
 *   `superseded` whether {@link setCredentials} ran since the call was sent.
 * - Rejects with `ApiError(0, DEM9999)` when no HTTP response arrives
 *   (offline, DNS, a refused or reset connection, a response lost after the
 *   server answered): whether the server received or executed the request is
 *   unknown, so a POST or PUT may have committed. Rejects with
 *   `ApiError(status, DEM9999)` for a 2xx body that is not JSON. The 401
 *   handler runs for neither, and nothing is retried.
 * - Rejects with `options.signal.reason` (a `DOMException` named
 *   `AbortError` unless the caller gave another reason) when that signal
 *   abandoned the call before its answer arrived (before the call or while
 *   it was pending) or while a 2xx body was read, never with an `ApiError`,
 *   so no presenter shows it. A non-2xx answer that arrived always rejects
 *   with its `ApiError`, even when the signal aborts while its body is read
 *   (the synthetic DEM9999 then, as the body is lost) or inside the 401
 *   handler. The 401 handler runs only when a 401 answer had arrived. An
 *   abort frees the browser's connection; the server may still finish the
 *   request.
 *
 * `T` is the caller's declared response type, taken from the generated
 * `./schema`; the body is not validated against it.
 *
 * @param path the API path, relative and starting with `/api/`, without a
 *   query string of its own unless `options.query` is empty
 * @param options method, query, JSON body, per-call credentials and, for a
 *   GET, the abort signal
 * @throws TypeError (as a rejection) when `path` does not start with `/api/`,
 *   or when `options.signal` is given with `POST` or `PUT`: programming
 *   errors that never reach the network
 */
export async function request<T>(path: string, options: RequestOptions = {}): Promise<T> {
  if (!path.startsWith(API_PREFIX)) {
    throw new TypeError(`API paths are relative and start with ${API_PREFIX}: ${path}`);
  }
  const method = options.method ?? 'GET';
  const { signal } = options;
  if (signal !== undefined && method !== 'GET') {
    // A write whose request was sent may already have committed, so stopping
    // to wait for its answer would only hide the outcome from the user.
    throw new TypeError(`Only a GET can be aborted; ${method} ${path} must not carry a signal`);
  }
  const url = withQuery(path, options.query);

  const headers: Record<string, string> = {
    'X-Requested-With': 'XMLHttpRequest',
    Accept: ACCEPT,
  };
  // The public `GET /api/messages` is fetched before sign-in, so with no
  // credentials at all the header is left out rather than sent empty.
  const credentials = options.credentials ?? stored;
  const dispatchEpoch = credentialEpoch;
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
    response = await fetch(url, { method, headers, body, signal });
  } catch {
    if (signal?.aborted === true) {
      // The caller abandoned the read: reject with its own reason, never an
      // ApiError, so nothing is presented and nobody is signed out.
      throw signal.reason;
    }
    // No status arrived, so the server's outcome is unknown (a POST or PUT
    // may have committed) and no authentication decision was received: the
    // 401 handler does not run, and nothing here retries.
    throw new ApiError(0, syntheticProblem(0));
  }

  if (response.ok) {
    return readSuccess<T>(response, signal);
  }

  // A non-2xx answer arrived, so its ApiError is the rejection whatever the
  // signal does from here on: a body whose read the abort cut short becomes
  // the synthetic DEM9999 under this status (readProblem), and a 401 handler
  // that aborts this very signal (AuthProvider's sign-out cancels the user's
  // queries) still leaves the caller the 401's ApiError to present.
  const error = new ApiError(response.status, await readProblem(response));
  if (response.status === 401) {
    // The server's authentication decision did arrive, so it stands even
    // when the caller abandoned the call while its body was being read.
    notifyUnauthorized({
      perCallCredentials: options.credentials !== undefined,
      superseded: dispatchEpoch !== credentialEpoch,
    });
  }
  throw error;
}

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

function notifyUnauthorized(info: UnauthorizedInfo): void {
  const handler = unauthorizedHandler;
  if (handler === null) {
    return;
  }
  try {
    handler(info);
  } catch {
    // Contained on purpose: the caller must receive the 401's ApiError, the
    // one rejection every feature presents, whatever the sign-out hook does.
  }
}

/**
 * The JSON body of a 2xx response, or `undefined` when it is empty. A body
 * that cannot be read or is not JSON rejects with the synthetic DEM9999
 * problem under the response's status; a body whose read failed because
 * `signal` aborted rejects with the signal's reason instead.
 */
async function readSuccess<T>(response: Response, signal: AbortSignal | undefined): Promise<T> {
  let text: string;
  try {
    text = await response.text();
  } catch {
    if (signal?.aborted === true) {
      throw signal.reason;
    }
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
