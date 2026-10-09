/**
 * AppRoutes: the route table. `/sign-in` is public; `/`, `/customers` and
 * `/demo/selection` are guarded; any other path redirects to `/`, whose guard
 * sends a signed-out visitor to `/sign-in`.
 *
 * Every guarded route passes `role="INQUIRY"` explicitly, so the table shows
 * its guard. `MAINTENANCE` implies `INQUIRY` (the server's `RoleHierarchy`,
 * mirrored by `useAuth().hasRole`), so there is no MAINTENANCE-only route:
 * 2=Edit and F6=Add are offered inside `CustomerSearchPage` by mode, and the
 * API refuses them to an Inquiry user with 403 APP0403, so this table is never
 * a security boundary. Selection is not a role but the `CustomerPicker`
 * context, so `/demo/selection` carries the same `INQUIRY` gate.
 *
 * No path constants are exported: `HomePage`, `AuthProvider` and `SignInPage`
 * are imported here, directly or through `RequireRole`, so importing paths
 * from this module would form an import cycle; they write the same literals.
 * Provider nesting belongs to `src/App.tsx` and nothing is wrapped here, so
 * tests can mount the table under a `MemoryRouter`.
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
 * @throws Error (from the router) when rendered outside a router, and (from
 *   the guarded screens and `RequireRole`) outside `AuthProvider` or the
 *   other providers `src/App.tsx` mounts: a wiring mistake that must fail
 *   loudly
 */
export function AppRoutes() {
  return (
    <Routes>
      <Route path="/sign-in" element={<SignInPage />} />

      <Route
        path="/"
        element={
          <RequireRole role="INQUIRY">
            <HomePage />
          </RequireRole>
        }
      />

      <Route
        path="/customers"
        element={
          <RequireRole role="INQUIRY">
            <CustomerSearchPage />
          </RequireRole>
        }
      />

      <Route
        path="/demo/selection"
        element={
          <RequireRole role="INQUIRY">
            <HostFormDemoPage />
          </RequireRole>
        }
      />

      <Route path="*" element={<Navigate to="/" replace />} />
    </Routes>
  );
}
