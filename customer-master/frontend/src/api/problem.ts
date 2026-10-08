/**
 * The browser half of the API's RFC 9457 error model: the `Problem` body, the
 * `ApiError` every failed call rejects with, and two small helpers.
 *
 * Part of the shared component "Error model (client transport)", with
 * `./client.ts`, the one fetch wrapper. `client.ts` parses every non-2xx
 * response into `new ApiError(status, problem)`; the feature that made the
 * call passes the error to `errors/useProblemPresenter.ts`, which shows the
 * problem's `detail` and highlights the fields its `errors` name.
 *
 * What it replaces. On the 5250 a failed edit sent a CUSTMSGF message id plus
 * its substitution data to the program message queue (SndMsgPgmQ over
 * QMHSNDPM, Service_Pgms/SRV_MSG.RPGLE), and an unexpected SQL condition ended
 * the program with an escape message carrying the SQLSTATE, the SQL text and
 * a dump (SQLProblem, Service_Pgms/SRV_SQL.SQLRPGLE). Here both arrive as data:
 * `code` is the CUSTMSGF id of 5250_Subfile/CRTMSGF.CLLE (DEM0000..DEM9999) or
 * one of the APP keys, `args` is the message data, `detail` the substituted
 * text, and no body ever carries SQL text, a SQLSTATE or a stack trace.
 *
 * Constraints:
 * - Types come only from the generated `./schema` (the OpenAPI snapshot), as
 *   type-only imports, so this module has no runtime dependency at all.
 * - Layer rule: nothing is imported from `components/`, `errors/`,
 *   `features/` or `auth/`. Nothing here renders, touches the DOM or keeps
 *   state.
 * - No message text. The bundle carries none; every text comes from the
 *   server, in `detail` or from `GET /api/messages`. Codes such as `DEM9999`
 *   are catalog keys, not texts.
 *
 * @example
 * ```ts
 * try {
 *   await request('/api/customers/review', { method: 'POST', body });
 * } catch (error) {
 *   if (isApiError(error) && error.status === 422) {
 *     const [first] = fieldErrors(error.problem); // first entry receives focus
 *   }
 * }
 * ```
 */
import type { components } from './schema';

/** The generated OpenAPI schema of an error body. */
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
 * - `status`: the HTTP status; `0` in a synthetic problem for a request that
 *   never reached the server.
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
 * `status` is the HTTP status of the response; `0` means the request never
 * reached the server (a network failure). `message` is the problem's
 * `detail`, or its `code` when the detail is empty, so a stray rejection that
 * reaches a log or a test failure still names the catalog key.
 *
 * @example
 * ```ts
 * throw new ApiError(response.status, problem);
 * throw new ApiError(0, syntheticProblem(0)); // fetch itself rejected
 * ```
 */
export class ApiError extends Error {
  /** The HTTP status of the response; `0` when no response arrived. */
  readonly status: number;

  /** The parsed problem+json body, or the synthetic DEM9999 stand-in. */
  readonly problem: Problem;

  /**
   * @param status the HTTP status of the response, or `0` for a request that
   *   never reached the server
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
 * The DEM9999 problem `client.ts` uses when a response carries no usable
 * problem+json body (an HTML error page from a proxy, plain text, unparsable
 * JSON) and, with status `0`, when the request never reached the server.
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
