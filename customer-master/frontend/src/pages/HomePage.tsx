/**
 * HomePage: the Customer Master main menu, at route `/`.
 *
 * It replaces the calling menu that lived outside the repository. PMTCUSTR
 * insists on a mode parameter and notes that "In a production environment,
 * this would be called from a tested menu or some program that enforced
 * security" [5250_Subfile/PMTCUSTR.SQLRPGLE:228-235]. The readme gives the
 * general user population Inquiry, Sales Maintenance, and Selection to any
 * in-house program that prompts for a customer id
 * [5250_Subfile/README.md:33-40]. Here the security comes from the signed-in
 * session instead: `src/routes.tsx` renders this page under
 * `<RequireRole role="INQUIRY">`, and the mode is read from the roles that
 * `GET /api/session` reported, never from a URL or parameter. This page
 * therefore only offers links:
 *
 * - **Work with customers** opens `/customers`, the search screen
 *   (PMTCUSTR/PMTCUSTD). Its text names the mode the session gives, "Inquiry"
 *   or "Maintenance", the same words the search screen shows in its header.
 *   MAINTENANCE implies INQUIRY, so anything other than MAINTENANCE reads as
 *   Inquiry.
 * - **Selection demo (Order entry)** opens `/demo/selection`, the host form
 *   that embeds the customer picker (PMTCUSTR Selection mode). Selection is a
 *   picker context available to every signed-in user, not a role.
 * - **Sign out**, the F3=Exit legend button and the F3 key all run the same
 *   handler, `AuthProvider`'s `signOut`, which forgets the in-memory
 *   credentials and cached data and routes to `/sign-in` itself.
 *
 * Layout follows the PMTCUSTD screen: the `SH_HDR` header with the function
 * line "Main Menu" and the signed-in user, then the menu, then the `SFT_FKEY`
 * footer, whose underlined, centred "Demo Corp of America" line sits above the
 * blue key legend [5250_Subfile/PMTCUSTD.DSPF:125-132]. Presentation comes
 * only from the existing `.screen` and `.footer-brand` classes of
 * `src/styles/global.css` and the shared components' own classes; base `a`,
 * `button` and `:focus-visible` styles carry the WCAG 2.2 AA palette from
 * `tokens.css`.
 *
 * Keyboard (the keyboard scope contract of `KeyScopeProvider`):
 * - One scope is registered, binding F3 only. Enter is deliberately left
 *   unbound, so on a link or button it keeps its native action: links
 *   navigate and the Sign out button signs out.
 * - Every other function key, F5 and Escape (delivered as F12) included, is
 *   prevented by the provider and answered with the DEM0003 alert, whose text
 *   comes from the message catalog. Preventing F5 means an accidental
 *   reload cannot drop the in-memory credentials. `KeyScopeProvider`'s
 *   `onBeforeCommand` clears earlier messages before each command, so this
 *   page never clears toasts itself.
 * - No focus is moved on mount and no key listener is added here; Tab moves
 *   through the links and buttons as in any web page.
 *
 * Constraints:
 * - Holds no message text: DEM0003 is formatted from the catalog served by
 *   `GET /api/messages`. Before the catalog loads, `format` returns the code.
 * - Does not render `ToastRegion`; `src/App.tsx` renders the one live region.
 * - Uses literal paths. It never imports `../routes`, which imports this file,
 *   so no import cycle can form; it imports nothing from `api/`, `errors/` or
 *   `features/`, and makes no API call.
 */
import { Link } from 'react-router-dom';
import { useAuth } from '../auth/AuthProvider';
import { FunctionKeyBar } from '../components/FunctionKeyBar';
import { ScreenHeader } from '../components/ScreenHeader';
import { useToasts } from '../components/ToastRegion';
import { useFunctionKeys } from '../keyboard/useFunctionKeys';
import { useMessages } from '../messages/MessageCatalogProvider';

/**
 * Renders the main menu for the signed-in user. Rendered only under
 * `RequireRole`, so a session is always present; a missing username or mode
 * degrades to no user line and the Inquiry wording rather than failing.
 */
export function HomePage() {
  const { username, mode, signOut } = useAuth();
  const { publish } = useToasts();
  const { format } = useMessages();

  /**
   * The one exit path, shared by the F3 key, the F3=Exit legend button and
   * the Sign out button so all three behave identically. `signOut` is called
   * with no arguments, so a click event is never passed into it, and it does
   * its own navigation to `/sign-in`.
   */
  const handleSignOut = () => {
    signOut();
  };

  // The menu enables F3 only, as a 5250 menu offered F3=Exit; any other
  // function key shows DEM0003 and changes nothing. A fresh bindings object on
  // each render is safe: the scope reads the latest handlers at keydown time.
  useFunctionKeys(
    { F3: handleSignOut },
    { onUnbound: () => publish({ kind: 'alert', text: format('DEM0003') }) },
  );

  const modeLabel = mode === 'MAINTENANCE' ? 'Maintenance' : 'Inquiry';

  return (
    <main className="screen">
      <ScreenHeader functionText="Main Menu" user={username ?? undefined} id="home" />
      <nav aria-label="Main menu">
        <ul>
          <li>
            <Link to="/customers">Work with customers ({modeLabel})</Link>
          </li>
          <li>
            <Link to="/demo/selection">Selection demo (Order entry)</Link>
          </li>
        </ul>
      </nav>
      <button type="button" onClick={handleSignOut}>
        Sign out
      </button>
      <footer>
        <p className="footer-brand">Demo Corp of America</p>
        <FunctionKeyBar keys={[{ key: 'F3', label: 'F3=Exit', onPress: handleSignOut }]} />
      </footer>
    </main>
  );
}
