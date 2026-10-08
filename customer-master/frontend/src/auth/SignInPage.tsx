/**
 * SignInPage: the sign-in screen, at route `/sign-in`.
 *
 * Why it exists. The IBM i application has no sign-on of its own: PMTCUSTR's
 * first parameter (`pParmType` I/M/S) asserted the mode, and the program
 * notes that "In a production environment, this would be called from a
 * tested menu or some program that enforced security"
 * [5250_Subfile/PMTCUSTR.SQLRPGLE:76-79,228-231]. The readme gives the general
 * user population Inquiry and Sales Maintenance [5250_Subfile/README.md:33-40].
 * The target enforces those roles in the API, which needs an identity: the
 * user types a username and password here, `AuthProvider.signIn` checks them
 * with HTTP Basic against `GET /api/session`, and the roles that call reports
 * decide Inquiry or Maintenance mode. This page never sends a role or a mode.
 *
 * Flow:
 * - **Redirect target.** `RequireRole` sends a signed-out visitor here with
 *   `state.from`, the path they asked for. A successful sign-in returns there
 *   ({@link readFrom}); with no usable `from` it goes to `/`, the main menu.
 * - **Already signed in.** The page renders `<Navigate>` to that same target,
 *   so the render after the session update and the handler's own `navigate`
 *   can never disagree about where the user lands.
 * - **Submit.** Enter in either field submits the form natively (no keyboard
 *   scope is registered, so `KeyScopeProvider` leaves Enter alone), as does
 *   the "Sign in" button. A submit while this form's attempt is in flight is
 *   ignored. Earlier messages are cleared first, so a repeated failure shows
 *   exactly one alert. The page navigates to `from` only when `signIn`
 *   reports that this attempt stored its session.
 * - **Failure.** Every rejection (401 APP0401 "Sign in required." for bad
 *   credentials, a synthetic DEM9999 when the server is unreachable) of an
 *   attempt this page still owns goes to `useProblemPresenter().present(error)`
 *   unchanged, which publishes the problem's `detail` as the one alert in
 *   `ToastRegion`. This page holds no message text and inspects no status
 *   code. The password is cleared and receives focus, so the user can retype
 *   it straight away.
 * - **Ownership.** Each submit is one attempt with its own `AbortController`.
 *   Leaving the page aborts it, so an abandoned attempt never navigates,
 *   presents or stores anything, and `AuthProvider` drops its answer.
 *
 * Constraints (identity and trust, error model):
 * - Credentials stay in memory: `AuthProvider` hands them to `api/client.ts`
 *   only after the server accepted them. Nothing here touches Web Storage,
 *   cookies or IndexedDB.
 * - Usernames are case-sensitive, so the "User" field is neither uppercased
 *   (no `uppercase` prop, unlike every 5250-derived field) nor trimmed: the
 *   value is sent exactly as typed.
 * - The e2e sign-in fixture finds the inputs by the labels "User" and
 *   "Password" and the button by the name "Sign in". No other label on this
 *   page contains "User" or "Password", and no other button is named
 *   "Sign in"; `ScreenHeader` gets no `user`, so its "Signed in as" line is
 *   not rendered either.
 * - Layer rule: imports `react`, `react-router-dom`, `./AuthProvider`,
 *   `../components/*` and `../errors/useProblemPresenter` only; never
 *   `features/`.
 *
 * Presentation comes from the existing `.sign-in`, `.screen-header*` and
 * `.form-field*` classes of `src/styles/global.css`; the base `button` and
 * `:focus-visible` rules carry the WCAG 2.2 AA token palette. `ToastRegion`
 * is rendered once by `ToastProvider`, never here.
 *
 * Test guidance: render under `QueryClientProvider`, `MessageCatalogProvider`,
 * `ToastProvider`, a router and `AuthProvider` (the order `src/App.tsx`
 * uses), and read the failure through `within(screen.getByRole('alert'))`.
 *
 * @example
 * ```tsx
 * // src/routes.tsx: the one unguarded route.
 * <Route path="/sign-in" element={<SignInPage />} />
 * ```
 */
import { useEffect, useRef, useState } from 'react';
import type { FormEvent } from 'react';
import { Navigate, useLocation, useNavigate } from 'react-router-dom';
import { useAuth } from './AuthProvider';
import { FormField } from '../components/FormField';
import { ScreenHeader } from '../components/ScreenHeader';
import { useToasts } from '../components/ToastRegion';
import { useProblemPresenter } from '../errors/useProblemPresenter';

// ---------------------------------------------------------------------------
// Module constants
// ---------------------------------------------------------------------------

/** Where a sign-in lands when no guarded path asked for it: the main menu. */
const HOME_PATH = '/';

/** This page's own route; never a redirect target, or sign-in would loop here. */
const SIGN_IN_PATH = '/sign-in';

/** `ScreenHeader` id prefix; the header emits `${id}-title` and `${id}-function`. */
const HEADER_ID = 'sign-in';

/**
 * Longest accepted username: the server's limit, which fits the change stamp
 * column `chguser varchar(18)`. A longer name could never authenticate.
 */
const USERNAME_MAX_LENGTH = 18;

/**
 * Longest accepted password. The server sets no length of its own (passwords
 * are BCrypt-encoded); 128 leaves room for generated passwords while still
 * bounding the `Authorization` header.
 */
const PASSWORD_MAX_LENGTH = 128;

/**
 * Visible width of both inputs, in characters. The stylesheet sizes inputs by
 * the `size` attribute; one width keeps the two fields aligned in the narrow
 * sign-in column, and the password still scrolls up to its maximum length.
 */
const FIELD_SIZE = USERNAME_MAX_LENGTH;

// ---------------------------------------------------------------------------
// Pure helpers
// ---------------------------------------------------------------------------

/**
 * The path a successful sign-in returns to, read from the navigation state
 * `RequireRole` attaches (`{ from: location.pathname }`).
 *
 * Returns `state.from` when `state` is a non-null object whose `from` is an
 * in-app path: a string that starts with a single `/` and does not name this
 * page. Otherwise, including a missing or malformed state, returns `/`.
 *
 * - A leading `//` or `/\` is rejected: browsers read either as a
 *   protocol-relative URL, which would leave the application.
 * - The sign-in route is compared without query, hash and trailing slashes,
 *   and case-insensitively, because the router matches `/sign-in/` and
 *   `/SIGN-IN` to the same route; returning there would show this page again.
 *
 * @example readFrom({ from: '/customers' }) // '/customers'
 * @example readFrom({ from: '/sign-in' })   // '/'
 * @example readFrom(null)                   // '/'
 */
function readFrom(state: unknown): string {
  if (typeof state !== 'object' || state === null || !('from' in state)) {
    return HOME_PATH;
  }
  const { from } = state;
  if (typeof from !== 'string' || !from.startsWith('/') || from.startsWith('//') || from.startsWith('/\\')) {
    return HOME_PATH;
  }
  const route = from.replace(/[?#].*$/s, '').replace(/\/+$/, '').toLowerCase();
  return route === SIGN_IN_PATH ? HOME_PATH : from;
}

// ---------------------------------------------------------------------------
// Component
// ---------------------------------------------------------------------------

/**
 * The sign-in form: the screen header ("Customer Master" / "Sign On"), the
 * "User" and "Password" fields and the "Sign in" button. Renders a redirect
 * instead once a session exists.
 *
 * @throws Error (from `useAuth`, `useToasts` or `useProblemPresenter`) when
 *   rendered outside `AuthProvider`, `ToastProvider` or
 *   `MessageCatalogProvider`, and from the router hooks outside a router: a
 *   wiring mistake that must fail loudly
 */
export function SignInPage() {
  // Every hook runs unconditionally, before the signed-in branch (rules of hooks).
  const { status, signIn } = useAuth();
  const navigate = useNavigate();
  const location = useLocation();
  const { present } = useProblemPresenter();
  const { clear } = useToasts();
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const userRef = useRef<HTMLInputElement>(null);
  const passwordRef = useRef<HTMLInputElement>(null);
  // The attempt this form owns, `null` while none is in flight. `submitting`
  // drives the rendering; this ref is read by the handler, where a state value
  // captured by the closure could still be stale for a quick second Enter.
  const attemptRef = useRef<AbortController | null>(null);

  const from = readFrom(location.state);

  // Keyboard-ready on arrival: the cursor starts in "User", as a 5250 sign-on
  // display positions it on the first input field. Nothing is focused when the
  // page renders the redirect, because the input does not exist then.
  useEffect(() => {
    userRef.current?.focus();
  }, []);

  // Leaving the page abandons the attempt in flight. StrictMode's extra
  // mount, unmount and remount aborts nothing: no attempt exists at mount.
  useEffect(
    () => () => {
      attemptRef.current?.abort();
    },
    [],
  );

  /**
   * Tries the typed credentials. Success navigates to {@link readFrom}'s
   * target, replacing `/sign-in` in the history so Back does not return here.
   * Failure clears and focuses the password and presents the problem. An
   * attempt superseded in `AuthProvider` or abandoned by leaving the page
   * does neither.
   */
  async function handleSubmit(event: FormEvent<HTMLFormElement>): Promise<void> {
    event.preventDefault();
    if (attemptRef.current !== null) {
      return;
    }
    const attempt = new AbortController();
    attemptRef.current = attempt;
    clear();
    setSubmitting(true);
    try {
      // Passed exactly as typed: usernames are case-sensitive and never trimmed.
      if (await signIn(username, password, attempt.signal)) {
        void navigate(from, { replace: true });
      }
    } catch (error) {
      if (!attempt.signal.aborted) {
        setPassword('');
        passwordRef.current?.focus();
        present(error);
      }
    } finally {
      if (attemptRef.current === attempt) {
        attemptRef.current = null;
      }
      if (!attempt.signal.aborted) {
        setSubmitting(false);
      }
    }
  }

  if (status === 'signed-in') {
    return <Navigate to={from} replace />;
  }

  return (
    <main className="sign-in">
      <ScreenHeader functionText="Sign On" id={HEADER_ID} />
      <form
        noValidate
        aria-labelledby={`${HEADER_ID}-title ${HEADER_ID}-function`}
        aria-busy={submitting}
        onSubmit={(event) => {
          void handleSubmit(event);
        }}
      >
        <FormField
          id="sign-in-user"
          label="User"
          value={username}
          onChange={setUsername}
          maxLength={USERNAME_MAX_LENGTH}
          size={FIELD_SIZE}
          autoComplete="username"
          inputRef={userRef}
        />
        <FormField
          id="sign-in-password"
          label="Password"
          type="password"
          value={password}
          onChange={setPassword}
          maxLength={PASSWORD_MAX_LENGTH}
          size={FIELD_SIZE}
          autoComplete="current-password"
          inputRef={passwordRef}
        />
        <button type="submit" disabled={submitting}>
          Sign in
        </button>
      </form>
    </main>
  );
}
