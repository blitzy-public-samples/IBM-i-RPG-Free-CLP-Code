/**
 * Tests of `./RequireRole.tsx` with the real session of `./AuthProvider.tsx`
 * and the real `./SignInPage.tsx`.
 *
 * Harness. The shared MSW server's default handlers authenticate Basic
 * credentials on every route, the public `/api/messages` included, against the
 * test base's demo `users`. Seeded sessions therefore use those users, so the
 * catalog request their stored credentials accompany is accepted, as the
 * server accepts a signed-in user's credentials on a public route. Each render
 * builds its own `QueryClient`, and the stored credentials are forgotten after
 * every test, so nothing survives from one test to the next. Probes stand in
 * for screens, so these tests depend on nothing under `features/`.
 */
import { useEffect } from 'react';
import type { ReactElement } from 'react';
import { act, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { Link, MemoryRouter, Route, Routes, useLocation } from 'react-router-dom';
import { http } from 'msw/http';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { setCredentials } from '../api/client';
import { isApiError } from '../api/problem';
import { sessionApi } from '../api/session';
import { ToastProvider } from '../components/ToastRegion';
import { MessageCatalogProvider, useMessages } from '../messages/MessageCatalogProvider';
import { basicAuth, messageText, users } from '../test/handlers';
import { server } from '../test/server';
import { AuthProvider, useAuth } from './AuthProvider';
import type { AuthContextValue, AuthSession, Role } from './AuthProvider';
import { RequireRole } from './RequireRole';
import { SignInPage } from './SignInPage';

const SIGN_IN_REQUIRED = 'Sign in required.';

const NOT_AUTHORIZED = 'You are not authorized to perform this action.';

const SIGN_IN_ROUTE = 'Sign-in route';

const NO_FROM = '(none)';

const SESSION_PATH = '/api/session';

const GO_TO_ADMIN = 'Go to admin';

type DemoUser = (typeof users)[number];

/**
 * The test base's demo user holding `role`: `inquiry` for INQUIRY and `sales`
 * for MAINTENANCE.
 *
 * These are the users the default MSW handlers authenticate, rather than the
 * backend's `application-test.yml` users: the stored credentials of a seeded
 * session also accompany the catalog request, and credentials the handlers do
 * not know are answered 401, which would sign the seeded session out.
 *
 * @throws Error when the test base defines no such user
 */
function demoUser(role: Role): DemoUser {
  const user = users.find((candidate) => candidate.roles.includes(role));
  if (user === undefined) {
    throw new Error(`The users fixture holds no ${role} user`);
  }
  return user;
}

/**
 * Signs `role`'s demo user in for the next render: stores its credentials in
 * the API client, which `AuthProvider`'s `initialSession` seam never does, and
 * returns the matching session for that seam.
 */
function seedSession(role: Role): AuthSession {
  const user = demoUser(role);
  setCredentials({ username: user.username, password: user.password });
  return { username: user.username, roles: [...user.roles] };
}

/** Every request that reached MSW in the current test, in order. */
const requests: Request[] = [];

/** MSW `request:start` listener: records the request, leaving the handlers in place. */
function recordRequest({ request }: { request: Request }): void {
  requests.push(request);
}

/** The recorded `GET` requests of `path`, query string ignored. */
function recordedGets(path: string): Request[] {
  return requests.filter((request) => request.method === 'GET' && new URL(request.url).pathname === path);
}

/** A promise and the function that resolves it. */
interface Deferred {
  readonly promise: Promise<void>;
  readonly resolve: () => void;
}

/** A promise the caller resolves; the executor runs synchronously, so `settle` is set before it returns. */
function deferred(): Deferred {
  let settle: (() => void) | undefined;
  const promise = new Promise<void>((resolve) => {
    settle = resolve;
  });
  return { promise, resolve: () => settle?.() };
}

/**
 * Waits inside `act` until `call` settles, either way, and then one macrotask
 * longer, so every continuation queued behind it (`AuthProvider.signIn`, the
 * sign-in page's handler, any state update they make) has run before the test
 * asserts.
 */
async function settle(call: Promise<unknown>): Promise<void> {
  await act(async () => {
    await call.then(
      () => undefined,
      () => undefined,
    );
    await new Promise<void>((resolve) => {
      setTimeout(resolve, 0);
    });
  });
}

/** The handle {@link holdFirstSession} returns. */
interface HeldSession {
  /** Resolves once the held request has reached MSW. */
  readonly arrived: Promise<void>;
  /**
   * Lets the held request go on to the default session handler, which answers
   * it, then waits ({@link settle}) until the `sessionApi.get` call that sent
   * it, and everything queued behind that call, has settled. Calling it again
   * changes nothing.
   */
  readonly release: () => Promise<void>;
}

/** Every hold the current test made; `afterEach` releases them, so no held request outlives its test. */
const holds: HeldSession[] = [];

/**
 * Holds the test's first `GET /api/session` until `release()` is called, as a
 * slow server would, then lets it fall through to the default handler, which
 * authenticates it against the demo `users` like any other request. Every
 * later session request falls through at once.
 *
 * The fall-through relies on MSW 3.0.2's handler loop, which moves on to the
 * next matching handler whenever a resolver returns no response. A
 * pass-through spy on `sessionApi.get` keeps the promise of the first call, the
 * held attempt's, so `release()` can wait for it; `restoreMocks` removes the
 * spy before the next test.
 */
function holdFirstSession(): HeldSession {
  const arrival = deferred();
  const gate = deferred();
  const calls: Array<Promise<unknown>> = [];
  const get = sessionApi.get;
  vi.spyOn(sessionApi, 'get').mockImplementation((credentials) => {
    const call = get.call(sessionApi, credentials);
    calls.push(call);
    return call;
  });
  let taken = false;
  server.use(
    http.get(SESSION_PATH, async () => {
      if (taken) {
        return undefined;
      }
      taken = true;
      arrival.resolve();
      await gate.promise;
      return undefined;
    }),
  );
  const held: HeldSession = {
    arrived: arrival.promise,
    release: async () => {
      gate.resolve();
      const [first] = calls;
      if (first !== undefined) {
        await settle(first);
      }
    },
  };
  holds.push(held);
  return held;
}

beforeEach(() => {
  requests.length = 0;
  server.events.on('request:start', recordRequest);
});

afterEach(async () => {
  // A request a failed test left held is released and settled here, before
  // the credentials are forgotten and the tree is unmounted, so nothing it
  // resolves can reach the next test.
  for (const held of holds.splice(0)) {
    await held.release();
  }
  server.events.removeListener('request:start', recordRequest);
  // The credentials are module state of the API client: forget them, so a
  // sign-in in one test can never authenticate the requests of the next.
  setCredentials(null);
});

/**
 * The guarded screen's stand-in. It reads the mode from the session, as every
 * screen does, and shows it with the search page's header text per mode, plus
 * the signed-in principal.
 */
function ModeProbe() {
  const { mode, username } = useAuth();
  const label = mode === 'MAINTENANCE' ? 'Maintenance' : mode === 'INQUIRY' ? 'Inquiry' : 'none';
  return (
    <>
      <p data-testid="mode">{label}</p>
      <p data-testid="user">{username ?? ''}</p>
    </>
  );
}

/** The `from` path a navigation state carries, narrowed from `unknown`; {@link NO_FROM} when absent. */
function fromOf(state: unknown): string {
  if (typeof state === 'object' && state !== null && 'from' in state && typeof state.from === 'string') {
    return state.from;
  }
  return NO_FROM;
}

/** The sign-in route's stand-in for the guard tests: shows that it was reached and the `from` it received. */
function SignInProbe() {
  const location = useLocation();
  return (
    <>
      <p>{SIGN_IN_ROUTE}</p>
      <p data-testid="from">{fromOf(location.state)}</p>
    </>
  );
}

/**
 * Shows whether the message catalog has loaded, so a render can wait for it:
 * until then `format` returns bare codes, and a load finishing after the
 * test's assertions would update the tree outside `act`.
 */
function CatalogProbe() {
  const { ready } = useMessages();
  return <span data-testid="catalog-ready">{String(ready)}</span>;
}

/**
 * Rendered beside the routes: shows the router's current path, and offers a
 * link to the guarded `/admin`, which a user follows to leave the sign-in page
 * while an attempt is still in flight.
 */
function NavProbe() {
  const { pathname } = useLocation();
  return (
    <>
      <p data-testid="path">{pathname}</p>
      <Link to="/admin">{GO_TO_ADMIN}</Link>
    </>
  );
}

/**
 * Rendered beside the routes: hands every new `useAuth()` value to
 * `onChange`, so a test can call `signIn` and `signOut` the way a screen does
 * and read the resulting status.
 */
function AuthHandle({ onChange }: { onChange: (auth: AuthContextValue) => void }) {
  const auth = useAuth();
  useEffect(() => {
    onChange(auth);
  }, [auth, onChange]);
  return null;
}

/** Options of {@link renderApp}. */
interface RenderAppOptions {
  /** The first location, path and optional query string. */
  path: string;
  /** The session `AuthProvider` starts with; signed out when absent. */
  session?: AuthSession;
  /** The role both guarded routes require; `RequireRole`'s default (INQUIRY) when absent. */
  gateRole?: Role;
  /** What `/sign-in` renders; {@link SignInProbe} when absent. */
  signInElement?: ReactElement;
  /** Rendered inside `AuthProvider` beside the routes, whatever the path; nothing when absent. */
  beside?: ReactElement;
}

/**
 * Renders the application's provider stack in `src/App.tsx`'s order around
 * three routes: `/sign-in`, and `/customers` and `/admin`, both guarded by
 * `RequireRole` around {@link ModeProbe}. Resolves once the message catalog
 * has loaded. No `KeyScopeProvider` is mounted: no auth component registers
 * a key scope, and the sign-in form submits natively.
 */
async function renderApp({ path, session, gateRole, signInElement, beside }: RenderAppOptions): Promise<void> {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const guarded = (
    <RequireRole role={gateRole}>
      <ModeProbe />
    </RequireRole>
  );
  render(
    <QueryClientProvider client={queryClient}>
      <MessageCatalogProvider>
        <CatalogProbe />
        <ToastProvider>
          <MemoryRouter initialEntries={[path]}>
            <AuthProvider initialSession={session}>
              {beside}
              <Routes>
                <Route path="/sign-in" element={signInElement ?? <SignInProbe />} />
                <Route path="/customers" element={guarded} />
                <Route path="/admin" element={guarded} />
              </Routes>
            </AuthProvider>
          </MemoryRouter>
        </ToastProvider>
      </MessageCatalogProvider>
    </QueryClientProvider>,
  );
  await waitFor(() => expect(screen.getByTestId('catalog-ready')).toHaveTextContent('true'));
}

describe('RequireRole', () => {
  it('serves the APP0401 and APP0403 texts these tests assert from the catalog', () => {
    expect(messageText('APP0401')).toBe(SIGN_IN_REQUIRED);
    expect(messageText('APP0403')).toBe(NOT_AUTHORIZED);
  });

  describe('signed out', () => {
    it('redirects to /sign-in with the requested path as from, and never renders the screen', async () => {
      await renderApp({ path: '/customers' });

      expect(await screen.findByText(SIGN_IN_ROUTE)).toBeInTheDocument();
      expect(screen.getByTestId('from')).toHaveTextContent(/^\/customers$/);
      expect(screen.queryByTestId('mode')).not.toBeInTheDocument();
      expect(screen.queryByText(NOT_AUTHORIZED)).not.toBeInTheDocument();
    });
  });

  describe('mode per role', () => {
    it('renders an INQUIRY session in Inquiry mode', async () => {
      const session = seedSession('INQUIRY');
      await renderApp({ path: '/customers', session, gateRole: 'INQUIRY' });

      expect(screen.getByTestId('mode')).toHaveTextContent(/^Inquiry$/);
      expect(screen.getByTestId('user')).toHaveTextContent(session.username);
      expect(screen.queryByText(SIGN_IN_ROUTE)).not.toBeInTheDocument();
    });

    it('renders a MAINTENANCE session in Maintenance mode, MAINTENANCE satisfying the INQUIRY gate', async () => {
      const session = seedSession('MAINTENANCE');
      await renderApp({ path: '/customers', session, gateRole: 'INQUIRY' });

      expect(screen.getByTestId('mode')).toHaveTextContent(/^Maintenance$/);
      expect(screen.getByTestId('user')).toHaveTextContent(session.username);
    });

    it('renders a MAINTENANCE session at a MAINTENANCE gate', async () => {
      await renderApp({ path: '/admin', session: seedSession('MAINTENANCE'), gateRole: 'MAINTENANCE' });

      expect(screen.getByTestId('mode')).toHaveTextContent(/^Maintenance$/);
    });

    it('takes the mode from the session roles, never from the URL', async () => {
      await renderApp({ path: '/customers?mode=M&role=MAINTENANCE', session: seedSession('INQUIRY') });

      expect(screen.getByTestId('mode')).toHaveTextContent(/^Inquiry$/);
    });
  });

  describe('role not satisfied', () => {
    it('shows an INQUIRY session the APP0403 notice instead of a MAINTENANCE screen, without a toast', async () => {
      await renderApp({ path: '/admin', session: seedSession('INQUIRY'), gateRole: 'MAINTENANCE' });

      expect(await screen.findByText(NOT_AUTHORIZED)).toBeInTheDocument();
      expect(screen.queryByTestId('mode')).not.toBeInTheDocument();
      expect(screen.queryByText(SIGN_IN_ROUTE)).not.toBeInTheDocument();
      // Static page content: the toast host's alert region stays empty.
      expect(screen.getByRole('alert')).toBeEmptyDOMElement();
    });

    it('lets a session without a known role pass no gate, the default INQUIRY gate included', async () => {
      // Selection is no role, so a principal the server granted neither role
      // is offered no screen at all.
      await renderApp({ path: '/customers', session: { username: 'nobody', roles: [] } });

      expect(await screen.findByText(NOT_AUTHORIZED)).toBeInTheDocument();
      expect(screen.queryByTestId('mode')).not.toBeInTheDocument();
    });
  });

  describe('sign-in round trip', () => {
    it('signs in on SignInPage and returns to the guarded path in the mode the server reported', async () => {
      const user = userEvent.setup();
      const sales = demoUser('MAINTENANCE');
      await renderApp({ path: '/customers', signInElement: <SignInPage /> });

      await user.type(screen.getByLabelText('User'), sales.username);
      await user.type(screen.getByLabelText('Password'), sales.password);
      await user.click(screen.getByRole('button', { name: 'Sign in' }));

      expect(await screen.findByTestId('mode')).toHaveTextContent(/^Maintenance$/);
      expect(screen.getByTestId('user')).toHaveTextContent(sales.username);
      expect(screen.queryByRole('button', { name: 'Sign in' })).not.toBeInTheDocument();

      const sessionCalls = recordedGets(SESSION_PATH);
      expect(sessionCalls).toHaveLength(1);
      expect(sessionCalls[0]?.headers.get('Authorization')).toBe(basicAuth(sales.username, sales.password));
      expect(sessionCalls[0]?.headers.get('X-Requested-With')).toBe('XMLHttpRequest');
    });

    it('keeps a failed sign-in on the page with APP0401, and a retry still returns to the guarded path', async () => {
      const user = userEvent.setup();
      const sales = demoUser('MAINTENANCE');
      await renderApp({ path: '/customers', signInElement: <SignInPage /> });

      await user.type(screen.getByLabelText('User'), sales.username);
      await user.type(screen.getByLabelText('Password'), 'wrong-password');
      await user.click(screen.getByRole('button', { name: 'Sign in' }));

      expect(await within(screen.getByRole('alert')).findByText(SIGN_IN_REQUIRED)).toBeInTheDocument();
      expect(screen.getByRole('button', { name: 'Sign in' })).toBeInTheDocument();
      const password = screen.getByLabelText('Password');
      expect(password).toHaveValue('');
      expect(password).toHaveFocus();
      expect(screen.queryByTestId('mode')).not.toBeInTheDocument();

      // The 401 signed nobody out and moved nowhere, so the page still holds
      // the guarded path the redirect gave it.
      await user.type(password, sales.password);
      await user.click(screen.getByRole('button', { name: 'Sign in' }));

      expect(await screen.findByTestId('mode')).toHaveTextContent(/^Maintenance$/);
      expect(recordedGets(SESSION_PATH)).toHaveLength(2);
    });
  });

  describe('abandoned and superseded sign-in attempts', () => {
    /**
     * Submits attempt A as `inquiry` with `passwordA` and holds it at the
     * server, follows the link to `/admin` so the first form unmounts, and
     * signs in as `sales` (B) on the new form the MAINTENANCE guard shows,
     * landing in Maintenance mode at `/admin`. A is still pending on return.
     */
    async function supersedeHeldAttempt(passwordA: string): Promise<HeldSession> {
      const user = userEvent.setup();
      const inquiry = demoUser('INQUIRY');
      const sales = demoUser('MAINTENANCE');
      const held = holdFirstSession();
      await renderApp({
        path: '/customers',
        gateRole: 'MAINTENANCE',
        signInElement: <SignInPage />,
        beside: <NavProbe />,
      });

      await user.type(screen.getByLabelText('User'), inquiry.username);
      await user.type(screen.getByLabelText('Password'), passwordA);
      await user.click(screen.getByRole('button', { name: 'Sign in' }));
      await held.arrived;
      expect(screen.getByRole('button', { name: 'Sign in' })).toBeDisabled();

      // Leaving for another guarded path unmounts the first form; the guard
      // sends the visitor to a new, empty one whose `from` is /admin.
      await user.click(screen.getByRole('link', { name: GO_TO_ADMIN }));
      await waitFor(() => expect(screen.getByRole('button', { name: 'Sign in' })).toBeEnabled());
      expect(screen.getByTestId('path')).toHaveTextContent(/^\/sign-in$/);
      expect(screen.getByLabelText('User')).toHaveValue('');

      await user.type(screen.getByLabelText('User'), sales.username);
      await user.type(screen.getByLabelText('Password'), sales.password);
      await user.click(screen.getByRole('button', { name: 'Sign in' }));

      expect(await screen.findByTestId('mode')).toHaveTextContent(/^Maintenance$/);
      expect(screen.getByTestId('user')).toHaveTextContent(sales.username);
      expect(screen.getByTestId('path')).toHaveTextContent(/^\/admin$/);
      return held;
    }

    /** Asserts that B, `sales`, is still signed in at `/admin`, with no alert and its credentials stored. */
    async function expectSalesStillAtAdmin(): Promise<void> {
      const sales = demoUser('MAINTENANCE');
      expect(screen.getByTestId('user')).toHaveTextContent(sales.username);
      expect(screen.getByTestId('mode')).toHaveTextContent(/^Maintenance$/);
      expect(screen.getByTestId('path')).toHaveTextContent(/^\/admin$/);
      expect(screen.queryByRole('button', { name: 'Sign in' })).not.toBeInTheDocument();
      expect(screen.getByRole('alert')).toBeEmptyDOMElement();

      // A later call with the stored credentials still authenticates as B.
      let current: unknown;
      await act(async () => {
        current = await sessionApi.get();
      });
      expect(current).toEqual({ username: sales.username, roles: [...sales.roles] });
    }

    it('keeps the newer sign-in, its route and its credentials when an abandoned attempt succeeds late', async () => {
      const inquiry = demoUser('INQUIRY');
      const held = await supersedeHeldAttempt(inquiry.password);

      await held.release();

      const sessionCalls = recordedGets(SESSION_PATH);
      expect(sessionCalls).toHaveLength(2);
      expect(sessionCalls[0]?.headers.get('Authorization')).toBe(basicAuth(inquiry.username, inquiry.password));
      await expectSalesStillAtAdmin();
    });

    it('neither signs the newer user out nor shows an alert when an abandoned attempt is refused late', async () => {
      const inquiry = demoUser('INQUIRY');
      const held = await supersedeHeldAttempt('wrong-password');

      // A's late 401 refused only its own trial credentials.
      await held.release();

      expect(recordedGets(SESSION_PATH)[0]?.headers.get('Authorization')).toBe(
        basicAuth(inquiry.username, 'wrong-password'),
      );
      await expectSalesStillAtAdmin();
    });

    it('resolves false and stores nothing when a sign-out supersedes a pending attempt', async () => {
      const inquiry = demoUser('INQUIRY');
      const held = holdFirstSession();
      const latest: { auth: AuthContextValue | null } = { auth: null };
      await renderApp({
        path: '/sign-in',
        beside: (
          <AuthHandle
            onChange={(auth) => {
              latest.auth = auth;
            }}
          />
        ),
      });
      const auth = latest.auth;
      if (auth === null) {
        throw new Error('AuthHandle reported no auth context');
      }

      const attempt = auth.signIn(inquiry.username, inquiry.password);
      await held.arrived;
      await act(async () => {
        auth.signOut();
      });
      await held.release();

      await expect(attempt).resolves.toBe(false);
      expect(latest.auth?.status).toBe('signed-out');
      expect(latest.auth?.username).toBeNull();
      expect(screen.getByText(SIGN_IN_ROUTE)).toBeInTheDocument();

      // Nothing was stored: the next call that relies on stored credentials
      // goes out without any and is refused.
      let outcome: unknown;
      await act(async () => {
        outcome = await sessionApi.get().then(
          () => 'resolved',
          (error: unknown) => (isApiError(error) ? error.status : error),
        );
      });
      expect(outcome).toBe(401);
      const sessionCalls = recordedGets(SESSION_PATH);
      expect(sessionCalls).toHaveLength(2);
      expect(sessionCalls[1]?.headers.has('Authorization')).toBe(false);
    });
  });
});
