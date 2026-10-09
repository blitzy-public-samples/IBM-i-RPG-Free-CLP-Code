/**
 * The browser half of the API's RFC 9457 error model, used with `./client.ts`:
 * the `Problem` body, the `ApiError` a failed call rejects with, and three
 * helpers. `code` is a catalog key (a CUSTMSGF id or an APP key); the bundle
 * carries no message text.
 */
import type { components } from './schema';

type ProblemSchema = components['schemas']['Problem'];

/**
 * One field at fault in a problem's `errors`: alias of the schema
 * `FieldError`, whose members are exactly these.
 *
 * - `field`: the JSON property name (`name`, `addr`, `city`, `state`, `zip`,
 *   `corpPhone`, `acctMgr`, `acctPhone`, `active`; `version` on update,
 *   `purpose` on review) or the path or query parameter at fault (`custId`;
 *   the search filters `name`, `city`, `state`; `nameContains` and `sort` on
 *   the state list). It is passed through unchanged, never translated.
 * - `code`: the catalog key, for example `DEM0502`.
 * - `message`: the catalog text with its arguments substituted.
 */
export type FieldError = components['schemas']['FieldError'];

/**
 * An `application/problem+json` error body, as the API sends it and as
 * {@link syntheticProblem} stands in for it.
 *
 * Always present:
 * - `type`: `urn:customer-master:problem:<code>`.
 * - `title`: the HTTP reason phrase of `status`; empty in a synthetic problem.
 * - `status`: the HTTP status; `0` in a synthetic problem when no HTTP
 *   response was received, in which case whether the server received or
 *   processed the request is unknown.
 * - `detail`: the catalog text of `code` with `args` substituted; empty in a
 *   synthetic problem, whose text the presenter takes from the catalog.
 * - `code`: the catalog key, such as `DEM1002` or `APP0400`.
 *
 * Present only in the cases named:
 * - `instance`: the request path.
 * - `args`: the substitution values, as strings. The API always sends the
 *   member, possibly empty; it is optional here because a synthetic problem
 *   has none.
 * - `errors`: the fields at fault, in server order; the first receives focus.
 * - `current`: 409 DEM1002 only, the customer as now stored, with `version`.
 * - `stateAccepted`: 422 and 502 failures of `POST /api/customers/review`
 *   only, the normalized State when the State rule passed before the failure;
 *   the detail dialog's working State reads it.
 * - `errorId`: 500 only, the correlation id of the server's ERROR log line.
 */
// `args` keeps the schema's `string[]` (ProblemFactory renders every argument
// as text) and is made optional so a synthetic problem, which has no arguments,
// is a valid Problem; every other member is the schema's own, required or
// optional as the OpenAPI contract declares it.
export type Problem = Omit<ProblemSchema, 'args'> & Partial<Pick<ProblemSchema, 'args'>>;

/**
 * The rejection of every failed API call: the HTTP status and the problem the
 * response carried, or a {@link syntheticProblem} when it carried none.
 *
 * `status` is the HTTP status of the response; `0` means no HTTP response was
 * received (a network failure), so whether the server received or processed
 * the request, a write included, is unknown. `message` is the problem's
 * `detail`, or its `code` when the detail is empty, so a stray rejection that
 * reaches a log or a test failure still names the catalog key.
 */
export class ApiError extends Error {
  readonly status: number;
  readonly problem: Problem;

  /**
   * @param status the HTTP status of the response, or `0` when no HTTP
   *   response was received
   * @param problem the problem the response carried, or a synthetic one
   */
  constructor(status: number, problem: Problem) {
    super(problem.detail || problem.code);
    this.name = 'ApiError';
    this.status = status;
    this.problem = problem;
  }
}

/**
 * Whether a caught value is an {@link ApiError}, narrowing it so `status` and
 * `problem` can be read. Anything else (a programming error, a thrown string)
 * is not an API failure, and the presenter treats it as DEM9999.
 */
export function isApiError(e: unknown): e is ApiError {
  return e instanceof ApiError;
}

/**
 * The fields a problem names, in server order, so the first entry is the one
 * that receives focus; an empty array when it names none (401, 403, 404, 409,
 * 500, 502, 503 and the requests that fail before a field is read).
 */
export function fieldErrors(problem: Problem): FieldError[] {
  return problem.errors ?? [];
}

/**
 * The DEM9999 problem `client.ts` uses when an error response carries no
 * usable problem+json body (an HTML error page from a proxy, plain text,
 * unparsable JSON) or a 2xx body cannot be read as JSON, and, with status
 * `0`, when no HTTP response was received.
 *
 * `title` and `detail` are empty because the bundle carries no message text:
 * `errors/useProblemPresenter.ts` shows the catalog text of `code` instead,
 * as served by `GET /api/messages`. Each call returns a new object, so no
 * caller can change another's problem.
 *
 * @param status the HTTP status of the response, or `0` for a network failure
 */
export function syntheticProblem(status: number): Problem {
  return {
    code: 'DEM9999',
    status,
    type: 'urn:customer-master:problem:DEM9999',
    title: '',
    detail: '',
  };
}
