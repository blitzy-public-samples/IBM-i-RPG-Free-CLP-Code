/**
 * HomePage: the Customer Master main menu at `/`, replacing the calling menu
 * that lived outside the repository [5250_Subfile/README.md:33-40]. It links
 * to `/customers`, labelled with the mode the session's roles give (Inquiry or
 * Maintenance, never one taken from a URL or parameter), and to
 * `/demo/selection`, the Selection host form, and offers Sign out.
 *
 * One exit path: F3, the F3=Exit legend button and Sign out all call
 * `AuthProvider`'s `signOut`, which forgets the credentials and cached data
 * and navigates to `/sign-in` itself.
 *
 * Keyboard: one scope binds F3 only. Enter is deliberately unbound, so on a
 * link or button it keeps its native action. Every other function key, F5 and
 * Escape (delivered as F12) included, is prevented by the provider and
 * answered with DEM0003 from the message catalog, so an accidental F5 cannot
 * reload the page and drop the in-memory credentials.
 *
 * Focus on open: whenever the menu is shown (after sign-in, or on return from
 * the search page or the Selection host form), focus moves to the first menu
 * link, "Work with customers (…)", so the new screen is announced and Enter
 * opens the search page at once. The element focused on the screen just left
 * has unmounted, so focus would otherwise fall to the page body.
 *
 * Paths are literal: this file never imports `../routes`, which imports it,
 * so no import cycle can form.
 */
import { useEffect, useRef } from 'react';
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
  const firstLinkRef = useRef<HTMLAnchorElement>(null);

  // The cursor on open: the first menu link. This effect only moves focus;
  // the key scope never does.
  useEffect(() => {
    firstLinkRef.current?.focus();
  }, []);

  /**
   * The one exit handler, shared by F3, F3=Exit and Sign out. Calling
   * `signOut` with no arguments keeps a click event out of it.
   */
  const handleSignOut = () => {
    signOut();
  };

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
            <Link to="/customers" ref={firstLinkRef}>Work with customers ({modeLabel})</Link>
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
