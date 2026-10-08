/**
 * Tests of the client route guard and the mode it lets a screen derive:
 * `./RequireRole.tsx`, with the session of `./AuthProvider.tsx` and the
 * redirect target `./SignInPage.tsx`.
 *
 * What it replaces. PMTCUSTR takes its mode as the caller-asserted first
 * parameter `pParmType` (I, M or S) and leaves security to "a tested menu or
 * some program that enforced security" (5250_Subfile/PMTCUSTR.SQLRPGLE:76-79,
 * 230-231). The README gives the general user population Inquiry, Sales
 * Maintenance, and Selection to any in-house program that needs a customer id
 * (5250_Subfile/README.md:33-40). Here the identity is an HTTP Basic sign-in,
 * the mode comes only from the roles `GET /api/session` reports, MAINTENANCE
 * implies INQUIRY, and Selection is a picker context rather than a role.
 *
 * What is pinned down here:
 * - **Signed out.** A guarded path redirects to `/sign-in`, carrying the
 *   requested path as `state.from`; the guarded screen never renders.
 * - **Mode per role.** An INQUIRY session renders the screen in Inquiry mode,
 *   a MAINTENANCE session in Maintenance mode, and MAINTENANCE satisfies both
 *   gates. The mode ignores anything the URL says.
 * - **Role not satisfied.** The screen is replaced by the APP0403 catalog
 *   text as static content, with no toast; a session without a known role
 *   passes no gate, the default INQUIRY gate included.
 * - **Round trip.** Signing in on the real `SignInPage` returns to the
 *   guarded path in the mode the server reported, sending Basic credentials
 *   and `X-Requested-With`; bad credentials keep the page, clear the password
 *   and show APP0401 as the one alert, and a retry still returns to `from`.
 *
 * Harness. Every request is answered by the shared MSW server
 * (`../test/server`, started by `../test/setup.ts` with
 * `onUnhandledFrame: 'error'`), whose default handlers authenticate Basic
 * credentials on every route, the public `/api/messages` included, against the
 * test base's demo `users`. The seeded sessions therefore use those users, so
 * the catalog request their stored credentials accompany is accepted, exactly
 * as the server accepts a signed-in user's credentials on a public route.
 * Each render builds its own `QueryClient`, and the stored credentials are
 * forgotten after every test, so no catalog, session or credential survives
 * from one test to the next. Screens are stood in for by probes, so these
 * tests depend on nothing under `features/`.
 */
import type { ReactElement } from 'react';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { setCredentials } from '../api/client';
import { ToastProvider } from '../components/ToastRegion';
import { MessageCatalogProvider, useMessages } from '../messages/MessageCatalogProvider';
import { basicAuth, messageText, users } from '../test/handlers';
import { server } from '../test/server';
import { AuthProvider, useAuth } from './AuthProvider';
import type { AuthSession, Role } from './AuthProvider';
import { RequireRole } from './RequireRole';
import { SignInPage } from './SignInPage';

// ---------------------------------------------------------------------------
// Fixtures
// ---------------------------------------------------------------------------

/** APP0401 as the catalog serves it: the alert of a failed sign-in. */
const SIGN_IN_REQUIRED = 'Sign in required.';

/** APP0403 as the catalog serves it: the notice of a role not satisfied. */
const NOT_AUTHORIZED = 'You are not authorized to perform this action.';

/** The text {@link SignInProbe} renders, standing in for the sign-in page. */
const SIGN_IN_ROUTE = 'Sign-in route';

/** What {@link SignInProbe} shows when the navigation carried no `from`. */
const NO_FROM = '(none)';

/** The session endpoint the sign-in page authenticates against. */
const SESSION_PATH = '/api/session';

/** A demo user of the shared test base: the credentials its handlers accept. */
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

// ---------------------------------------------------------------------------
// Request recording
// ---------------------------------------------------------------------------

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

beforeEach(() => {
  requests.length = 0;
  server.events.on('request:start', recordRequest);
});

afterEach(() => {
  server.events.removeListener('request:start', recordRequest);
  // The credentials are module state of the API client: forget them, so a
  // sign-in in one test can never authenticate the requests of the next.
  setCredentials(null);
});

// ---------------------------------------------------------------------------
// Probes
// ---------------------------------------------------------------------------

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

// ---------------------------------------------------------------------------
// Harness
// ---------------------------------------------------------------------------

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
}

/**
 * Renders the application's provider stack in `src/App.tsx`'s order around
 * three routes: `/sign-in`, and `/customers` and `/admin`, both guarded by
 * `RequireRole` around {@link ModeProbe}. Resolves once the message catalog
 * has loaded. No `KeyScopeProvider` is mounted: no auth component registers
 * a key scope, and the sign-in form submits natively.
 */
async function renderApp({ path, session, gateRole, signInElement }: RenderAppOptions): Promise<void> {
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

// ---------------------------------------------------------------------------
// Tests
// ---------------------------------------------------------------------------

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
});
