/**
 * Client route guard: decides which screen the browser shows for the signed-in
 * session's roles.
 *
 * Not a security boundary. PMTCUSTR leaves security to its caller
 * (5250_Subfile/PMTCUSTR.SQLRPGLE:230-231); the API's `SecurityConfig` answers
 * 401 APP0401 without credentials and 403 APP0403 for an insufficient role on
 * every request. This guard only keeps the UI from offering a screen the
 * server would refuse, so it must never be the only check of anything.
 *
 * Outcomes, in this order:
 * - Signed out: a redirect to `/sign-in` with `replace` and `state` of exactly
 *   `{ from: string }`, the guarded path, which `SignInPage` reads to return
 *   there. `replace` keeps the guarded URL out of history, so Back after
 *   sign-in does not bounce through the redirect.
 * - `hasRole(role)`: `children`, unchanged. `hasRole` already mirrors the
 *   server's `RoleHierarchy` (`MAINTENANCE` implies `INQUIRY`); Selection is
 *   not a role, so there is no Selection gate.
 * - Otherwise: a static notice with the catalog text of APP0403, with no
 *   `role="alert"` or `role="status"` (`ToastRegion` renders exactly one
 *   container of each) and no toast.
 */
import type { ReactNode } from 'react';
import { Navigate, useLocation } from 'react-router-dom';
import { useAuth } from './AuthProvider';
import type { Role } from './AuthProvider';
import { useMessages } from '../messages/MessageCatalogProvider';

const SIGN_IN_PATH = '/sign-in';

/**
 * The navigation state the redirect hands to `SignInPage`: the path the
 * visitor asked for, so a successful sign-in returns there.
 */
export interface SignInRedirectState {
  from: string;
}

export interface RequireRoleProps {
  /**
   * The role the guarded screen needs; defaults to `INQUIRY`, which every
   * signed-in user with a known role satisfies (`MAINTENANCE` implies it).
   */
  role?: Role;
  /** The screen shown when the session satisfies {@link RequireRoleProps.role}. */
  children: ReactNode;
}

/**
 * Renders `children` for a session that satisfies `role`, redirects a
 * signed-out visitor to `/sign-in` with `state.from` set to the requested
 * path, and shows the APP0403 catalog text to a signed-in user without the
 * role.
 *
 * @throws Error (from `useAuth` or `useMessages`) when rendered outside
 *   `AuthProvider` or `MessageCatalogProvider`, a wiring mistake; and from
 *   `useLocation` outside a router
 */
export function RequireRole({ role = 'INQUIRY', children }: RequireRoleProps) {
  const { status, hasRole } = useAuth();
  const location = useLocation();
  const { format } = useMessages();

  if (status === 'signed-out') {
    const state: SignInRedirectState = { from: location.pathname };
    return <Navigate to={SIGN_IN_PATH} replace state={state} />;
  }

  if (hasRole(role)) {
    return <>{children}</>;
  }

  // `screen` is the shared page wrapper of global.css; `access-denied` is this
  // notice's own hook.
  return (
    <main className="screen access-denied">
      <p>{format('APP0403')}</p>
    </main>
  );
}
