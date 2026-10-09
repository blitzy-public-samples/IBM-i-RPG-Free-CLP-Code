/**
 * Typed call for `GET /api/session`: who the API authenticated, and with
 * which roles. The roles decide the UI mode, which is never taken from a URL,
 * a parameter or anything the browser asserts, unlike the source's
 * caller-asserted mode parameter (5250_Subfile/PMTCUSTR.SQLRPGLE:76-79). The
 * API enforces the roles; the UI only reflects them. A maintenance user is
 * reported with `MAINTENANCE` alone, so consumers mirror its implication of
 * `INQUIRY` ({@link Role}) themselves.
 *
 * This module never stores credentials: `auth/AuthProvider.tsx` passes the
 * candidate credentials of a sign-in to {@link sessionApi.get} and stores them
 * with `setCredentials` only once the call has succeeded.
 */
import { request } from './client';
import type { Credentials } from './client';
import type { components } from './schema';

/** The body of `GET /api/session` 200. */
export type SessionResponse = components['schemas']['SessionResponse'];

/**
 * The two roles the API grants (`customer-master.security.users[].role`):
 * `INQUIRY` for general users (source mode `I`) and `MAINTENANCE` for Sales
 * (source mode `M`), which implies `INQUIRY` on the server (a
 * `RoleHierarchy`). Selection (source mode `S`) is not a role but the
 * `CustomerPicker` context, open to every signed-in user, so it has no member
 * here. These are the values of schema `SessionResponse`'s `roles` enum;
 * consumers compare against these literals.
 */
export type Role = 'INQUIRY' | 'MAINTENANCE';

const SESSION_PATH = '/api/session';

export const sessionApi = {
  /**
   * Reads the signed-in user and roles.
   *
   * @param credentials the credentials for this one call only, as a sign-in
   *   tries them; when absent the credentials stored in `./client` are sent
   * @returns the authenticated principal's name and roles
   * @throws ApiError (as a rejection) 401 APP0401 when the credentials are
   *   missing or wrong, and the client's other `ApiError`s (status 0 when
   *   no response arrives, 500 DEM9999) for any other failure
   */
  get(credentials?: Credentials): Promise<SessionResponse> {
    return request<SessionResponse>(SESSION_PATH, { credentials });
  },
};
