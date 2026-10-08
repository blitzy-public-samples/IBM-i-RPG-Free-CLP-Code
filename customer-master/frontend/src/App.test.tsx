/**
 * Smoke integration of the real application shell: `App` (`./App`), rendered
 * as `src/main.tsx` renders it, with its own providers, its `BrowserRouter`,
 * the route table of `./routes` and the production screens behind it:
 * `SignInPage`, `HomePage`, `CustomerSearchPage` and `HostFormDemoPage`.
 *
 * What it replaces. On IBM i a menu called PMTCUSTR with the mode as its first
 * parameter, and the program left security to that caller: "In a production
 * environment, this would be called from a tested menu or some program that
 * enforced security" [5250_Subfile/PMTCUSTR.SQLRPGLE:230-231]. Here the menu
 * is `HomePage`, the security is the sign-in session that `RequireRole`
 * checks on every guarded route, and the mode comes from the roles
 * `GET /api/session` reports [5250_Subfile/README.md:33-40].
 *
 * What is pinned down here, through the real tree only (no test providers):
 * - **Signed out.** `/`, `/customers`, `/demo/selection` and an unknown path
 *   all end at `/sign-in` on the sign-in screen; no guarded screen renders and
 *   nothing but the public message catalog is requested.
 * - **Round trip.** Signing in from a redirected `/customers` returns there in
 *   the mode the session gives ("Inquiry" or "Maintenance"), and from
 *   `/demo/selection` returns to the Order entry host form.
 * - **Menu.** "Work with customers (Inquiry|Maintenance)" names the session's
 *   mode and reaches `/customers` in it; "Selection demo (Order entry)"
 *   reaches `/demo/selection`.
 * - **Sign-out.** The Sign out button, the F3 key and the F3=Exit legend
 *   button each route to `/sign-in` and end the session: a guarded path
 *   redirects to `/sign-in` again, and a request the application's own API
 *   client sends afterwards carries no `Authorization` header, so no later
 *   request carries the old credentials.
 * - **Wiring.** `App`'s one key listener and scope stack: F3 on the search
 *   page returns to the menu, and that one keypress runs no second handler
 *   (the menu's F3 would sign out). A command key clears the previous message
 *   before its handler publishes the next one, which is the toast reset `App`
 *   wires into `KeyScopeProvider`.
 *
 * Isolation. `App` holds module-level state: its `QueryClient` (with the
 * cached catalog and any customer data), the credentials `api/client.ts`
 * stores, and the toast ids of `ToastRegion`. Each test therefore calls
 * `vi.resetModules()` and imports `./App` afresh ({@link renderApp}), so it
 * starts from a new module graph, signed out with an empty cache, whatever an
 * earlier test did or wherever it failed; the tests are order-independent.
 * The reset covers source modules only. React, React Router, react-query and
 * MSW load from `node_modules` outside it, so Testing Library and `App` share
 * one React, and the MSW server imported here is the one `src/test/setup.ts`
 * started. The application's API client is imported in the same graph as
 * `App`, so a probe request goes through the very credentials store the
 * screens use.
 *
 * Harness. `<StrictMode><App /></StrictMode>`, as `src/main.tsx` mounts it,
 * over jsdom's real browser history: each test sets its starting path with
 * `window.history.replaceState` before rendering, assertions read
 * `window.location.pathname`, and `afterEach` puts the history back at `/`.
 * Every request is answered by the default handlers of `src/test/handlers.ts`
 * (demo users `inquiry`/`inquiry-demo` INQUIRY and `sales`/`sales-demo`
 * MAINTENANCE) and recorded from MSW's `request:start` event. Waits are
 * condition-based only.
 *
 * Evidence. These tests are derived from reading the IBM i source and the
 * plan; they are not executed against the IBM i program and do not establish
 * behavioural equivalence with it.
 */
import { StrictMode } from 'react';
import { act, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type { UserEvent } from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type * as ApiClient from './api/client';
import { basicAuth, customers, messageText, users } from './test/handlers';
import type { Role } from './test/handlers';
import { server } from './test/server';

// ---------------------------------------------------------------------------
// Fixtures and constants
// ---------------------------------------------------------------------------

/** A demo user of the shared test base: the credentials its handlers accept. */
type DemoUser = (typeof users)[number];

/**
 * The test base's demo user holding `role`: `inquiry` for INQUIRY and `sales`
 * for MAINTENANCE.
 *
 * @throws Error when the test base defines no such user
 */
function demoUser(role: Role): DemoUser {
  const account = users.find((candidate) => candidate.roles.includes(role));
  if (account === undefined) {
    throw new Error(`The users fixture holds no ${role} user`);
  }
  return account;
}

/** The INQUIRY demo user (`inquiry`). */
const INQUIRY_USER = demoUser('INQUIRY');

/** The MAINTENANCE demo user (`sales`). */
const MAINTENANCE_USER = demoUser('MAINTENANCE');

/** Each role with the word the menu and the search header use for its mode. */
const MODES: [Role, string][] = [
  ['INQUIRY', 'Inquiry'],
  ['MAINTENANCE', 'Maintenance'],
];

/** The first row of the active-only list, which Inquiry mode loads on open. */
const FIRST_ACTIVE_ROW = (() => {
  const row = customers.find((candidate) => candidate.active === 'Y');
  if (row === undefined) {
    throw new Error('The customers fixture holds no active row');
  }
  return row;
})();

/** The only public route, where every signed-out visitor lands. */
const SIGN_IN_PATH = '/sign-in';

/** The public catalog, the one request a signed-out page makes. */
const MESSAGES_PATH = '/api/messages';

/** The session endpoint the sign-in page authenticates against. */
const SESSION_PATH = '/api/session';

/** The search endpoint. */
const SEARCH_PATH = '/api/customers';

/** The function lines of the guarded screens' headers: the menu, both search modes and the host form. */
const GUARDED_FUNCTION_LINES = ['Main Menu', 'Inquiry', 'Maintenance', 'Order entry'] as const;

// ---------------------------------------------------------------------------
// Request log
// ---------------------------------------------------------------------------

/** One request that reached MSW: its method, path and `Authorization` header. */
interface RecordedRequest {
  method: string;
  path: string;
  authorization: string | null;
}

/** Every request since the test began, in order. */
const requests: RecordedRequest[] = [];

/** MSW `request:start` listener: runs before any handler, so every default handler keeps answering. */
function recordRequest({ request }: { request: Request }): void {
  requests.push({
    method: request.method,
    path: new URL(request.url).pathname,
    authorization: request.headers.get('Authorization'),
  });
}

beforeEach(() => {
  requests.length = 0;
  server.events.on('request:start', recordRequest);
});

afterEach(() => {
  server.events.removeListener('request:start', recordRequest);
  window.history.replaceState(null, '', '/');
});

// ---------------------------------------------------------------------------
// Harness
// ---------------------------------------------------------------------------

/** What {@link renderApp} returns. */
interface RenderedApp {
  user: UserEvent;
  /** The API client module of the rendered `App`'s own module graph. */
  client: typeof ApiClient;
}

/**
 * Renders a freshly imported `App` under `StrictMode`, as `src/main.tsx`
 * does, with the browser at `path`. The module registry is reset first, so
 * the query client, the stored credentials and the toast ids are new.
 */
async function renderApp(path: string): Promise<RenderedApp> {
  vi.resetModules();
  const { App } = await import('./App');
  const client = await import('./api/client');
  window.history.replaceState(null, '', path);
  const user = userEvent.setup();
  render(
    <StrictMode>
      <App />
    </StrictMode>,
  );
  return { user, client };
}

/** Moves the browser to `path` as a history traversal would, so the mounted router follows it. */
function visit(path: string): void {
  act(() => {
    window.history.pushState(null, '', path);
    window.dispatchEvent(new PopStateEvent('popstate'));
  });
}

/**
 * Waits until the browser is at `/sign-in` showing the real sign-in screen,
 * and asserts that none of the guarded screens rendered.
 */
async function expectSignInScreen(): Promise<void> {
  await waitFor(() => expect(window.location.pathname).toBe(SIGN_IN_PATH));
  expect(await screen.findByRole('button', { name: 'Sign in' })).toBeInTheDocument();
  expect(screen.getByText('Sign On', { selector: 'p' })).toBeInTheDocument();
  expect(screen.getByLabelText('User')).toBeInTheDocument();
  expect(screen.getByLabelText('Password')).toBeInTheDocument();
  for (const functionLine of GUARDED_FUNCTION_LINES) {
    expect(screen.queryByText(functionLine, { selector: 'p' })).not.toBeInTheDocument();
  }
  expect(screen.queryByRole('navigation', { name: 'Main menu' })).not.toBeInTheDocument();
  expect(screen.queryByRole('table')).not.toBeInTheDocument();
  expect(screen.queryByLabelText('Customer id +')).not.toBeInTheDocument();
}

/** Signs `account` in on the sign-in screen shown, and waits until the application has left it. */
async function signIn(user: UserEvent, account: DemoUser): Promise<void> {
  await user.type(await screen.findByLabelText('User'), account.username);
  await user.type(screen.getByLabelText('Password'), account.password);
  await user.click(screen.getByRole('button', { name: 'Sign in' }));
  await waitFor(() => expect(window.location.pathname).not.toBe(SIGN_IN_PATH));
}

/** The menu's navigation, once the menu is shown. */
async function findMenu(): Promise<HTMLElement> {
  return screen.findByRole('navigation', { name: 'Main menu' });
}

// ---------------------------------------------------------------------------
// Specs
// ---------------------------------------------------------------------------

describe('App', () => {
  it('starts from the fixtures: one demo user per role and an active first row', () => {
    expect(INQUIRY_USER).toMatchObject({ username: 'inquiry', roles: ['INQUIRY'] });
    expect(MAINTENANCE_USER).toMatchObject({ username: 'sales', roles: ['MAINTENANCE'] });
    expect(FIRST_ACTIVE_ROW.active).toBe('Y');
    expect(messageText('DEM0003')).toBe('Key is not active now');
    expect(messageText('DEM0005')).toBe('Use F4 only if + is on field');
  });

  // -------------------------------------------------------------------------
  // Signed out: every guarded or unknown path ends at the sign-in screen
  // -------------------------------------------------------------------------

  describe('signed out', () => {
    it.each(['/', '/customers', '/demo/selection', '/nope'])(
      '%s ends at /sign-in on the sign-in screen, with no guarded screen and only the public catalog requested',
      async (path) => {
        await renderApp(path);

        await expectSignInScreen();

        await waitFor(() => expect(requests.map((entry) => entry.path)).toContain(MESSAGES_PATH));
        expect(requests.filter((entry) => entry.path !== MESSAGES_PATH)).toEqual([]);
        expect(requests.every((entry) => entry.authorization === null)).toBe(true);
      },
    );
  });

  // -------------------------------------------------------------------------
  // Sign-in round trip: back to the path the guard redirected from
  // -------------------------------------------------------------------------

  describe('signing in', () => {
    it.each(MODES)(
      '%s: from a redirected /customers returns to /customers and shows the search page in %s mode',
      async (role, modeLabel) => {
        const account = demoUser(role);
        const { user } = await renderApp('/customers');
        await expectSignInScreen();

        await signIn(user, account);

        expect(window.location.pathname).toBe('/customers');
        expect(await screen.findByText(modeLabel, { selector: 'p' })).toBeInTheDocument();
        expect(screen.getByRole('heading', { name: 'Customer Master' })).toBeInTheDocument();
        expect(screen.getByRole('table', { name: 'Customers' })).toBeInTheDocument();
        expect(screen.getByText(account.username)).toBeInTheDocument();
        const authorization = basicAuth(account.username, account.password);
        expect(requests.filter((entry) => entry.path === SESSION_PATH).map((entry) => entry.authorization)).toEqual([
          authorization,
        ]);
        if (role === 'INQUIRY') {
          // Inquiry loads its first page on entry, with the stored credentials.
          expect(await screen.findByRole('textbox', { name: `Option for ${FIRST_ACTIVE_ROW.name}` })).toBeInTheDocument();
          expect(requests.filter((entry) => entry.path === SEARCH_PATH).map((entry) => entry.authorization)).toEqual([
            authorization,
          ]);
        } else {
          // Maintenance waits for the first Enter.
          expect(requests.filter((entry) => entry.path === SEARCH_PATH)).toEqual([]);
        }
      },
    );

    it('from a redirected /demo/selection returns to the Order entry host form', async () => {
      const { user } = await renderApp('/demo/selection');
      await expectSignInScreen();

      await signIn(user, INQUIRY_USER);

      expect(window.location.pathname).toBe('/demo/selection');
      expect(await screen.findByText('Order entry', { selector: 'p' })).toBeInTheDocument();
      expect(screen.getByLabelText('Customer id +')).toBeInTheDocument();
      expect(screen.getByRole('button', { name: 'Look up customer' })).toBeInTheDocument();
      expect(screen.getByText(INQUIRY_USER.username)).toBeInTheDocument();
    });
  });

  // -------------------------------------------------------------------------
  // The menu (HomePage at /)
  // -------------------------------------------------------------------------

  describe('the menu', () => {
    it.each(MODES)(
      '%s: "Work with customers (%s)" names the mode and reaches /customers in it',
      async (role, modeLabel) => {
        const { user } = await renderApp('/');
        await expectSignInScreen();
        await signIn(user, demoUser(role));

        expect(window.location.pathname).toBe('/');
        const menu = await findMenu();
        expect(screen.getByText('Main Menu', { selector: 'p' })).toBeInTheDocument();
        expect(within(menu).getAllByRole('link').map((link) => link.textContent)).toEqual([
          `Work with customers (${modeLabel})`,
          'Selection demo (Order entry)',
        ]);

        await user.click(within(menu).getByRole('link', { name: `Work with customers (${modeLabel})` }));

        await waitFor(() => expect(window.location.pathname).toBe('/customers'));
        expect(await screen.findByText(modeLabel, { selector: 'p' })).toBeInTheDocument();
        expect(screen.queryByRole('navigation', { name: 'Main menu' })).not.toBeInTheDocument();
      },
    );

    it('"Selection demo (Order entry)" reaches the host form at /demo/selection', async () => {
      const { user } = await renderApp('/');
      await expectSignInScreen();
      await signIn(user, INQUIRY_USER);
      const menu = await findMenu();

      await user.click(within(menu).getByRole('link', { name: 'Selection demo (Order entry)' }));

      await waitFor(() => expect(window.location.pathname).toBe('/demo/selection'));
      expect(await screen.findByText('Order entry', { selector: 'p' })).toBeInTheDocument();
      expect(screen.getByLabelText('Customer id +')).toBeInTheDocument();
      expect(screen.queryByRole('navigation', { name: 'Main menu' })).not.toBeInTheDocument();
    });
  });

  // -------------------------------------------------------------------------
  // Sign-out from the menu
  // -------------------------------------------------------------------------

  describe('signing out from the menu', () => {
    it.each([
      [
        'the Sign out button',
        async (user: UserEvent) => {
          await user.click(screen.getByRole('button', { name: 'Sign out' }));
        },
      ],
      [
        'the F3 key',
        async (user: UserEvent) => {
          await user.keyboard('{F3}');
        },
      ],
      [
        'the F3=Exit legend button',
        async (user: UserEvent) => {
          await user.click(
            within(screen.getByRole('toolbar', { name: 'Function keys' })).getByRole('button', { name: 'F3=Exit' }),
          );
        },
      ],
    ])(
      '%s routes to /sign-in and ends the session: a guarded path redirects again and no later request carries the old credentials',
      async (_how, signOut) => {
        const { user, client } = await renderApp('/');
        await expectSignInScreen();
        await signIn(user, MAINTENANCE_USER);
        await findMenu();
        const oldAuthorization = basicAuth(MAINTENANCE_USER.username, MAINTENANCE_USER.password);
        expect(requests.map((entry) => entry.authorization)).toContain(oldAuthorization);
        const signedOutAt = requests.length;

        await signOut(user);

        await expectSignInScreen();

        // Back to a guarded screen within the same page: the guard sends the
        // visitor to the sign-in screen again, and nothing is searched.
        visit('/customers');
        await expectSignInScreen();

        // The application's own API client no longer holds the credentials: a
        // request it sends now carries no Authorization header and is refused.
        await expect(client.request(SESSION_PATH)).rejects.toMatchObject({ status: 401 });

        const later = requests.slice(signedOutAt);
        expect(later.map((entry) => `${entry.method} ${entry.path}`)).toEqual([`GET ${SESSION_PATH}`]);
        expect(later.map((entry) => entry.authorization)).toEqual([null]);
        expect(screen.getByRole('alert')).toBeEmptyDOMElement();
      },
    );
  });

  // -------------------------------------------------------------------------
  // App wiring only the real tree proves
  // -------------------------------------------------------------------------

  describe('wiring', () => {
    it('one key listener and scope stack: F3 on the search page returns to the menu, and that keypress runs no second handler', async () => {
      const { user } = await renderApp('/customers');
      await expectSignInScreen();
      await signIn(user, INQUIRY_USER);
      expect(await screen.findByRole('textbox', { name: `Option for ${FIRST_ACTIVE_ROW.name}` })).toBeInTheDocument();

      await user.keyboard('{F3}');

      await waitFor(() => expect(window.location.pathname).toBe('/'));
      const menu = await findMenu();
      expect(within(menu).getByRole('link', { name: 'Work with customers (Inquiry)' })).toBeInTheDocument();
      // The menu's own F3 signs out; it did not run for the same keypress.
      expect(screen.getByText(INQUIRY_USER.username)).toBeInTheDocument();
      expect(window.location.pathname).toBe('/');
    });

    it('a command key clears the previous message before its handler publishes the next one', async () => {
      const { user } = await renderApp('/demo/selection');
      await expectSignInScreen();
      await signIn(user, INQUIRY_USER);
      expect(await screen.findByText('Order entry', { selector: 'p' })).toBeInTheDocument();
      // Focus is off the "Customer id +" field, so F4 there is DEM0005.
      expect(document.body).toHaveFocus();
      const alert = screen.getByRole('alert');

      await user.keyboard('{F6}');

      const keyNotActive = await within(alert).findByText(messageText('DEM0003'));

      await user.keyboard('{F4}');

      expect(await within(alert).findByText(messageText('DEM0005'))).toBeInTheDocument();
      // Without the reset the region would hold both messages, DEM0003 first.
      expect(keyNotActive).not.toBeInTheDocument();
      expect(alert.textContent).toBe(messageText('DEM0005'));
      expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    });
  });
});
