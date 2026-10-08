/**
 * Client route guard: decides which screen the browser shows for the signed-in
 * session's roles.
 *
 * What it replaces. PMTCUSTR has no access control of its own. The caller
 * asserts the mode in the first parameter `pParmType char(1)`
 * (5250_Subfile/PMTCUSTR.SQLRPGLE:76-79), and the program leaves security to
 * whoever calls it: "In a production environment, this would be called from a
 * tested menu or some program that enforced security"
 * (5250_Subfile/PMTCUSTR.SQLRPGLE:230-231). The README expects the general user
 * population to get Inquiry and Sales to get Maintenance
 * (5250_Subfile/README.md:33-40). Here that menu is this guard plus the
 * session roles `AuthProvider` holds.
 *
 * Not a security boundary. The API's `SecurityConfig` is the enforcement
 * point: it answers 401 APP0401 without credentials and 403 APP0403 for an
 * insufficient role on every request, whatever the browser shows. This guard
 * only keeps the UI from offering a screen the server would refuse, so it must
 * never be the only check of anything.
 *
 * Outcomes, in this order:
 * | Session                         | Renders                                                     |
 * |---------------------------------|-------------------------------------------------------------|
 * | signed out                      | `<Navigate to="/sign-in" replace state={{ from }} />`        |
 * | signed in, `hasRole(role)`      | `children`, unchanged                                       |
 * | signed in, role not satisfied   | a static notice with the catalog text of APP0403            |
 *
 * Contract:
 * - **Role hierarchy.** `useAuth().hasRole` already mirrors the server's
 *   `RoleHierarchy` (`MAINTENANCE` implies `INQUIRY`); it is the only role
 *   logic used here. Selection is not a role but the `CustomerPicker`
 *   context, so there is no Selection gate.
 * - **Return location.** The redirect carries `state` of exactly
 *   `{ from: string }`, the guarded path, which `SignInPage` reads to go back
 *   there after a successful sign-in. `replace` keeps the guarded URL out of
 *   the history stack, so Back after sign-in does not bounce through the
 *   redirect again.
 * - **Mode.** The guard passes nothing down. Each screen reads its mode itself
 *   from `useAuth().mode`.
 * - **Notice, not alert.** The insufficient-role notice is static page
 *   content: it carries no `role="alert"` or `role="status"` (`ToastRegion`
 *   always renders exactly one container of each, and a second would make
 *   `getByRole('alert')` ambiguous) and it publishes no toast.
 * - **Message text.** The notice text comes only from the catalog through
 *   `useMessages().format('APP0403')`; the bundle carries no message text.
 *   Until the catalog loads, `format` returns the code itself, which is
 *   acceptable for this brief moment.
 * - **Layer rule.** Imports only `./AuthProvider`,
 *   `../messages/MessageCatalogProvider`, `react` and `react-router-dom`;
 *   never `features/`.
 *
 * Placement. `src/routes.tsx` wraps `/`, `/customers` and `/demo/selection`
 * with this guard; `/sign-in` is never wrapped. It must render below a router,
 * `AuthProvider` and `MessageCatalogProvider`, as `src/App.tsx` arranges.
 *
 * @example
 * ```tsx
 * <Route
 *   path="/customers"
 *   element={
 *     <RequireRole role="INQUIRY">
 *       <CustomerSearchPage />
 *     </RequireRole>
 *   }
 * />
 * ```
 */
import type { ReactNode } from 'react';
import { Navigate, useLocation } from 'react-router-dom';
import { useAuth } from './AuthProvider';
import type { Role } from './AuthProvider';
import { useMessages } from '../messages/MessageCatalogProvider';

/** The route of the sign-in page, where a signed-out visitor is sent. */
const SIGN_IN_PATH = '/sign-in';

/**
 * The navigation state the redirect hands to `SignInPage`: the path the
 * visitor asked for, so a successful sign-in returns there.
 */
export interface SignInRedirectState {
  from: string;
}

/** Props of {@link RequireRole}. */
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
  // Every hook runs unconditionally, before any branch (rules of hooks).
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

  // Signed in without the role, for example an Inquiry user on a Maintenance
  // screen. `screen` is the shared page wrapper of global.css; `access-denied`
  // is this notice's own hook. Static content only: no live-region role and no
  // toast (see the module contract).
  return (
    <main className="screen access-denied">
      <p>{format('APP0403')}</p>
    </main>
  );
}
