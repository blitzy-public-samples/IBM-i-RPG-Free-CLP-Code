/**
 * Typed client for `GET /api/session`: who the API authenticated, and with
 * which roles.
 *
 * What it replaces. On the 5250 the screen mode was the first PMTCUSTR
 * parameter, `pParmType char(1)` (5250_Subfile/PMTCUSTR.SQLRPGLE:76-79), which
 * any caller could assert; the program itself notes that "In a production
 * environment, this would be called from a tested menu or some program that
 * enforced security" (5250_Subfile/PMTCUSTR.SQLRPGLE:230-231). The signed-in
 * user reached the screen headers through the DDS `USER` keyword
 * (5250_Subfile/MTNCUSTD.DSPF:49). Here both come from the server: the user
 * name is the authenticated principal, and the roles decide the UI mode,
 * which is never taken from a URL, a parameter or anything the browser
 * asserts. The API enforces the roles; the UI only reflects them.
 *
 * Contract (OpenAPI operation `getSession`):
 * - 200 `{ username, roles }`, where `roles` is `["INQUIRY"]` or
 *   `["MAINTENANCE"]`. `MAINTENANCE` implies `INQUIRY` on the server (a
 *   `RoleHierarchy`), so a maintenance user is reported with the one role and
 *   consumers mirror the implication themselves.
 * - 401 APP0401 without credentials or with bad ones, rejected by
 *   `./client.ts` as `ApiError`. The client sends `X-Requested-With`, so the
 *   API omits `WWW-Authenticate` and the browser never opens its own prompt.
 *
 * Constraints:
 * - Layer rule: the only imports are `request` and `Credentials` from
 *   `./client` and type-only aliases of the generated `./schema`. Nothing is
 *   imported from `components/`, `errors/`, `features/` or `auth/`.
 * - No state. This module never stores credentials; `auth/AuthProvider.tsx`
 *   passes the candidate credentials of a sign-in to {@link sessionApi.get}
 *   and stores them with `setCredentials` only once the call has succeeded.
 * - The path is relative, so the call stays on the page's own origin.
 *
 * @example
 * ```ts
 * // Sign-in in AuthProvider: try the typed credentials before storing them.
 * const session = await sessionApi.get({ username, password });
 * setCredentials({ username, password });
 *
 * // Later calls, e.g. a refresh of the signed-in user, use the stored ones.
 * const current = await sessionApi.get();
 * const maintenance = current.roles.includes('MAINTENANCE');
 * ```
 */
import { request } from './client';
import type { Credentials } from './client';
import type { components } from './schema';

/** The body of `GET /api/session` 200: alias of schema `SessionResponse`. */
export type SessionResponse = components['schemas']['SessionResponse'];

/**
 * The two roles the API grants (`customer-master.security.users[].role`):
 * `INQUIRY` for general users (source mode `I`) and `MAINTENANCE` for Sales
 * (source mode `M`), which implies `INQUIRY`. Selection (source mode `S`) is
 * not a role but the `CustomerPicker` context, open to every signed-in user,
 * so it has no member here. These are the values of schema
 * `SessionResponse`'s `roles` enum; consumers compare against these literals.
 */
export type Role = 'INQUIRY' | 'MAINTENANCE';

/** The session endpoint, relative to the page's origin. */
const SESSION_PATH = '/api/session';

/** Calls of the session endpoint. */
export const sessionApi = {
  /**
   * Reads the signed-in user and roles.
   *
   * @param credentials the credentials to authenticate this one call with, as
   *   the sign-in page does to try a username and password before they are
   *   stored; when absent the credentials stored in `./client` are sent
   * @returns the authenticated principal's name and roles
   * @throws ApiError (as a rejection) 401 APP0401 when the credentials are
   *   missing or wrong, and the client's other `ApiError`s (status 0 when
   *   no response arrives, 500 DEM9999) for any other failure
   */
  get(credentials?: Credentials): Promise<SessionResponse> {
    return request<SessionResponse>(SESSION_PATH, { credentials });
  },
};
