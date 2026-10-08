/**
 * AppRoutes: the route table of the Customer Master SPA.
 *
 * What it replaces. On IBM i a screen was reached by a program call: a menu
 * called PMTCUSTR with the mode as its first parameter (`I`, `M` or `S`), and
 * the program itself leaves access control to that caller: "In a production
 * environment, this would be called from a tested menu or some program that
 * enforced security" [5250_Subfile/PMTCUSTR.SQLRPGLE:228-231]. The readme
 * expects the general user population to get Inquiry, Sales Maintenance, and
 * any in-house program that needs a customer id to use Selection
 * [5250_Subfile/README.md:33-40]. Here each program call becomes a route plus
 * the authenticated session: the menu is `HomePage`, the security is
 * `RequireRole` over the roles `GET /api/session` reported, and the mode is
 * read by each screen from `useAuth()`, never from a URL or a parameter.
 *
 *   Path               Screen                 Source it stands for
 *   /sign-in           SignInPage (public)    none: the sign-on the source lacks
 *   /                  HomePage               the calling menu (outside the repository)
 *   /customers         CustomerSearchPage     PMTCUSTR / PMTCUSTD, mode I or M
 *   /demo/selection    HostFormDemoPage       a program calling PMTCUSTR with mode S
 *   anything else      redirect to `/`        none
 *
 * Guard policy:
 * - **One minimum role.** Every guarded route asks for `INQUIRY`, passed
 *   explicitly so the table shows its guard. `MAINTENANCE` implies `INQUIRY`
 *   (the server's `RoleHierarchy`, mirrored by `useAuth().hasRole`), so both
 *   roles reach every screen. There is no MAINTENANCE-only route: 2=Edit and
 *   F6=Add are offered inside `CustomerSearchPage` by mode, and the API
 *   refuses them to an Inquiry user with 403 APP0403 whatever the browser
 *   shows. This table is therefore never a security boundary.
 * - **Selection is not a role.** It is the `CustomerPicker` context, open to
 *   every signed-in user, so `/demo/selection` carries the same `INQUIRY`
 *   gate as the menu.
 * - **Signed out.** `RequireRole` sends the visitor to `/sign-in` with the
 *   requested path as `state.from`; `SignInPage` returns there after a
 *   successful sign-in. An unknown path first returns to `/`, whose guard
 *   then does the same.
 *
 * Constraints:
 * - Provider nesting belongs to `src/App.tsx`, which renders `<AppRoutes />`
 *   below `BrowserRouter` and `AuthProvider` (and the query client, message
 *   catalog, toast host and key scope providers the screens need). Nothing is
 *   wrapped here, so tests can mount this table under a `MemoryRouter`.
 * - Routes render synchronously: no `lazy` and no `Suspense`. The bundle is
 *   small, and tests assert the rendered screen without waiting on a chunk.
 * - No path constants are exported from here. `HomePage`, `AuthProvider` and
 *   `SignInPage` write their paths literally, because importing them from
 *   this module would form an import cycle (this module imports those
 *   screens). The literals below are the same strings.
 * - Nothing is imported from `api/`: routing never calls the API.
 * - `nginx.conf` answers `/sign-in`, `/customers` and `/demo/selection` with
 *   `index.html`, so deep links and reloads reach this table.
 *
 * @example
 * ```tsx
 * // src/App.tsx
 * <BrowserRouter>
 *   <AuthProvider>
 *     <AppRoutes />
 *   </AuthProvider>
 * </BrowserRouter>
 * ```
 */
import { Navigate, Route, Routes } from 'react-router-dom';
import { RequireRole } from './auth/RequireRole';
import { SignInPage } from './auth/SignInPage';
import { CustomerSearchPage } from './features/customers/CustomerSearchPage';
import { HostFormDemoPage } from './features/demo/HostFormDemoPage';
import { HomePage } from './pages/HomePage';

/**
 * Renders the screen for the current location: the public sign-in page, the
 * three guarded screens, or a redirect to the menu for any other path.
 *
 * A pure render with no hooks of its own; the router, session and screen
 * providers come from the caller (see the module contract).
 *
 * @throws Error (from the router) when rendered outside a router, and (from
 *   the guarded screens and `RequireRole`) outside `AuthProvider` or the
 *   other providers `src/App.tsx` mounts: a wiring mistake that must fail
 *   loudly
 */
export function AppRoutes() {
  return (
    <Routes>
      {/* The only public screen: credentials are typed here and held in memory by AuthProvider. */}
      <Route path="/sign-in" element={<SignInPage />} />

      {/* The main menu, replacing the calling menu that enforced security. */}
      <Route
        path="/"
        element={
          <RequireRole role="INQUIRY">
            <HomePage />
          </RequireRole>
        }
      />

      {/* PMTCUSTR / PMTCUSTD: Inquiry or Maintenance mode, chosen by the page from the session roles. */}
      <Route
        path="/customers"
        element={
          <RequireRole role="INQUIRY">
            <CustomerSearchPage />
          </RequireRole>
        }
      />

      {/* Selection mode (UC-05): the Order entry host form embedding the customer picker. */}
      <Route
        path="/demo/selection"
        element={
          <RequireRole role="INQUIRY">
            <HostFormDemoPage />
          </RequireRole>
        }
      />

      {/* Any other client path returns to the menu, whose guard sends a signed-out visitor to /sign-in. */}
      <Route path="*" element={<Navigate to="/" replace />} />
    </Routes>
  );
}
