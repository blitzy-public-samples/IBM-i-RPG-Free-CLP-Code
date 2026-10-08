/**
 * The sign-in screen at `/sign-in`. The user types a username and password,
 * `AuthProvider.signIn` checks them against `GET /api/session`, and the roles
 * that call reports decide the mode; this page never sends a role or a mode.
 *
 * - A successful sign-in returns to the path `RequireRole` passed as
 *   `state.from` ({@link readFrom}), or to `/`. Once a session exists the
 *   page renders `<Navigate>` to the same target, so the render after the
 *   session update and the handler's own `navigate` never disagree.
 * - Enter submits the form natively: the page registers no keyboard scope.
 *   Each submit is one attempt with its own `AbortController`; a submit while
 *   it is in flight is ignored, and leaving the page aborts it, so an
 *   abandoned attempt never navigates, presents or stores anything.
 * - A failure of an attempt the page still owns is presented unchanged as the
 *   one alert (earlier messages are cleared first), and the password is
 *   cleared and focused so the user can retype it.
 * - The "User" field is neither uppercased (no `uppercase` prop, unlike every
 *   5250-derived field) nor trimmed: the value is sent exactly as typed. The
 *   server looks the name up case-insensitively and reports its configured
 *   spelling, which the UI then shows.
 * - The e2e sign-in fixture finds the fields by the labels "User" and
 *   "Password" and the button by the name "Sign in", so each stays unique here.
 */
import { useEffect, useRef, useState } from 'react';
import type { FormEvent } from 'react';
import { Navigate, useLocation, useNavigate } from 'react-router-dom';
import { useAuth } from './AuthProvider';
import { FormField } from '../components/FormField';
import { ScreenHeader } from '../components/ScreenHeader';
import { useToasts } from '../components/ToastRegion';
import { useProblemPresenter } from '../errors/useProblemPresenter';

const HOME_PATH = '/';

const SIGN_IN_PATH = '/sign-in';

const HEADER_ID = 'sign-in';

/**
 * Longest accepted username: the server's limit, which fits the change stamp
 * column `chguser varchar(18)`. A longer name could never authenticate.
 */
const USERNAME_MAX_LENGTH = 18;

/**
 * Longest password this page accepts, in characters: this input's own bound,
 * which keeps the `Authorization` header bounded, not the server's limit. The
 * server validates configured passwords to at most 72 UTF-8 bytes, the most
 * BCrypt encodes.
 */
const PASSWORD_MAX_LENGTH = 128;

/**
 * Visible width of both inputs, in characters. The stylesheet sizes inputs by
 * the `size` attribute; one width keeps the two fields aligned in the narrow
 * sign-in column, and the password still scrolls up to its maximum length.
 */
const FIELD_SIZE = USERNAME_MAX_LENGTH;

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

/** The sign-in form, or a redirect once a session exists. */
export function SignInPage() {
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
