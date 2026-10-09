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
 *   reaches `/demo/selection`. A route change never leaves focus on the page
 *   body: the menu opens with focus on its first link (after sign-in and on
 *   return from the search page), where Enter keeps its native action, and
 *   the host form opens with focus in "Customer id +".
 * - **Sign-out.** The Sign out button, the F3 key and the F3=Exit legend
 *   button each route to `/sign-in` and end the session: a guarded path
 *   redirects to `/sign-in` again, and a request the application's own API
 *   client sends afterwards carries no `Authorization` header, so no later
 *   request carries the old credentials.
 * - **An earlier identity's 401.** A request sent with `sales`'s stored
 *   credentials and refused only after sign-out and sign-in as `inquiry`
 *   rejects with its 401 but leaves the `inquiry` session, its menu and its
 *   credentials in place; the same 401 for the signed-in user still signs out.
 * - **Wiring.** `App`'s one key listener and scope stack: F3 on the search
 *   page returns to the menu, and that one keypress runs no second handler
 *   (the menu's F3 would sign out). A command key clears the previous message
 *   before its handler publishes the next one, which is the toast reset `App`
 *   wires into `KeyScopeProvider`.
 * - **Offline reads.** While react-query reports the browser offline, a
 *   search, a PageDown and option 5 are still sent; the network failure shows
 *   DEM9999 once, as an alert, and the reconnect sends nothing, so the next
 *   read is the user's own.
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
import { onlineManager } from '@tanstack/react-query';
import { act, cleanup, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type { UserEvent } from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { http, HttpResponse } from 'msw/http';
import type * as ApiClient from './api/client';
import { basicAuth, customerDetail, customers, messageText, problem, users } from './test/handlers';
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

/** The detail fixture's own resource, read with GET and updated with PUT. */
const CUSTOMER_PATH = `/api/customers/${customerDetail.custId}`;

/** A PUT body the API binds: the nine `CustomerFields` of the detail fixture and the version it was read at. */
const CUSTOMER_UPDATE = {
  active: customerDetail.active,
  name: customerDetail.name,
  addr: customerDetail.addr,
  city: customerDetail.city,
  state: customerDetail.state,
  zip: customerDetail.zip,
  acctPhone: customerDetail.acctPhone,
  acctMgr: customerDetail.acctMgr,
  corpPhone: customerDetail.corpPhone,
  version: customerDetail.version,
};

/** The function lines of the guarded screens' headers: the menu, both search modes and the host form. */
const GUARDED_FUNCTION_LINES = ['Main Menu', 'Inquiry', 'Maintenance', 'Order entry'] as const;

/** The rows of one search page: the `size` the search page sends, PMTCUSTR's 12-record subfile page. */
const SEARCH_PAGE_ROWS = 12;

/** The first row of the second active-only page, which PageDown from the first page shows. */
const SECOND_PAGE_FIRST_ROW = (() => {
  const row = customers.filter((candidate) => candidate.active === 'Y')[SEARCH_PAGE_ROWS];
  if (row === undefined) {
    throw new Error('The customers fixture holds no second page of active rows');
  }
  return row;
})();

/** The read option 5 sends for the first active row. */
const FIRST_ACTIVE_ROW_PATH = `${SEARCH_PATH}/${FIRST_ACTIVE_ROW.custId}`;

/** The accessible name of the window option 5 opens: the screen title and the Display header. */
const DISPLAY_DIALOG = 'Customer Master Displaying Customer';

/** The catalog text of DEM9999, which a read that never got an answer shows. */
const PROGRAM_ERROR = messageText('DEM9999');

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

/** A route that holds its next request until `release()`, and the client calls waiting on it. */
interface HeldRoute {
  /** Resolves once the request reached the route. */
  readonly arrived: Promise<void>;
  /** Lets the held request answer; calling it again changes nothing. */
  release(): void;
  /** Registers `call`, a client call waiting on this route, so its rejection is handled whenever it comes; returns it unchanged. */
  holding<T>(call: Promise<T>): Promise<T>;
  /** Resolves once every registered call has settled and the route has answered; never rejects. */
  settled(): Promise<void>;
}

/** Every hold the current test made ({@link holdRoute}); `afterEach` releases and settles each. */
const holds: HeldRoute[] = [];

beforeEach(() => {
  requests.length = 0;
  server.events.on('request:start', recordRequest);
});

afterEach(async () => {
  // A hold a failed test left closed is released and awaited first, while the
  // app and the test's handlers still exist, so neither the held request nor
  // the call waiting on it outlives the test.
  const pending = holds.splice(0);
  for (const held of pending) {
    held.release();
  }
  await Promise.all(pending.map((held) => held.settled()));
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

/**
 * Overrides the next `method path` request of the current test: it waits for
 * `release()`, then is answered with `respond()`; later requests reach the
 * default handlers again.
 */
function holdRoute(method: 'get' | 'put', path: string, respond: () => Response): HeldRoute {
  let reached: () => void = () => undefined;
  const arrived = new Promise<void>((resolve) => {
    reached = resolve;
  });
  let open: () => void = () => undefined;
  const opened = new Promise<void>((resolve) => {
    open = resolve;
  });
  const answers: Array<Promise<void>> = [];
  const calls: Array<Promise<unknown>> = [];
  server.use(
    http[method](
      path,
      async () => {
        let answer: () => void = () => undefined;
        answers.push(
          new Promise<void>((resolve) => {
            answer = resolve;
          }),
        );
        reached();
        try {
          await opened;
          return respond();
        } finally {
          answer();
        }
      },
      { once: true },
    ),
  );
  const route: HeldRoute = {
    arrived,
    release: () => open(),
    holding: (call) => {
      calls.push(Promise.allSettled([call]));
      return call;
    },
    settled: async () => {
      await Promise.all(calls);
      await Promise.all(answers);
    },
  };
  holds.push(route);
  return route;
}

/** The 401 APP0401 problem+json the API answers credentials it does not accept with. */
function refusedCustomer(): Response {
  return problem(401, 'APP0401', { instance: CUSTOMER_PATH });
}

/** `METHOD path` of every request recorded from index `from` of the request log on. */
function sentSince(from: number): string[] {
  return requests.slice(from).map((entry) => `${entry.method} ${entry.path}`);
}

/** Whether the simulated connection is down; the handlers {@link goOffline} installs read it. */
let networkDown = false;

/**
 * Takes the browser offline: react-query's `onlineManager` (a module
 * singleton loaded outside the module reset, so the one the rendered `App`'s
 * client listens to) reports offline, and the search and detail reads fail at
 * the network, as `fetch` does without a connection. Every other request, and
 * every customer read once {@link goOnline} ran, reaches the default handlers.
 */
function goOffline(): void {
  networkDown = true;
  server.use(
    ...[SEARCH_PATH, `${SEARCH_PATH}/:custId`].map((path) =>
      http.get(path, () => (networkDown ? HttpResponse.error() : undefined)),
    ),
  );
  act(() => {
    onlineManager.setOnline(false);
  });
}

/** Brings the connection back and lets react-query see the browser online again. */
async function goOnline(): Promise<void> {
  networkDown = false;
  await act(async () => {
    onlineManager.setOnline(true);
  });
}

/**
 * Reconnects ({@link goOnline}) and asserts that the reconnect sent nothing.
 * A read react-query held back while offline would be sent by the reconnect
 * itself, ahead of the probe that the application's own client sends next,
 * so the probe settling with nothing recorded before it is the condition.
 */
async function expectReconnectSendsNothing(client: typeof ApiClient): Promise<void> {
  const reconnectedAt = requests.length;

  await goOnline();

  await expect(client.request(SESSION_PATH)).resolves.toEqual({
    username: INQUIRY_USER.username,
    roles: INQUIRY_USER.roles,
  });
  expect(sentSince(reconnectedAt)).toEqual([`GET ${SESSION_PATH}`]);
}

/** Signs `inquiry` in from `/customers` and waits for the first page Inquiry loads on open. */
async function openInquirySearch(): Promise<RenderedApp> {
  const rendered = await renderApp('/customers');
  await expectSignInScreen();
  await signIn(rendered.user, INQUIRY_USER);
  expect(await screen.findByRole('textbox', { name: `Option for ${FIRST_ACTIVE_ROW.name}` })).toBeInTheDocument();
  return rendered;
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
        // Sign-in unmounted the focused Sign in button; the menu takes focus
        // onto its first link rather than leaving it on the page body.
        const workWithCustomers = within(menu).getByRole('link', { name: `Work with customers (${modeLabel})` });
        await waitFor(() => expect(workWithCustomers).toHaveFocus());

        await user.click(workWithCustomers);

        await waitFor(() => expect(window.location.pathname).toBe('/customers'));
        expect(await screen.findByText(modeLabel, { selector: 'p' })).toBeInTheDocument();
        expect(screen.queryByRole('navigation', { name: 'Main menu' })).not.toBeInTheDocument();
      },
    );

    it('opens with focus on its first link, where Enter keeps its native action and reaches /customers', async () => {
      const { user } = await renderApp('/');
      await expectSignInScreen();
      await signIn(user, INQUIRY_USER);
      const menu = await findMenu();
      await waitFor(() =>
        expect(within(menu).getByRole('link', { name: 'Work with customers (Inquiry)' })).toHaveFocus(),
      );

      await user.keyboard('{Enter}');

      await waitFor(() => expect(window.location.pathname).toBe('/customers'));
      expect(await screen.findByText('Inquiry', { selector: 'p' })).toBeInTheDocument();
      expect(screen.queryByRole('navigation', { name: 'Main menu' })).not.toBeInTheDocument();
      // Enter is unbound on the menu, so it raised no DEM0003.
      expect(screen.getByRole('alert')).toBeEmptyDOMElement();
    });

    it('"Selection demo (Order entry)" reaches the host form at /demo/selection, with focus in "Customer id +"', async () => {
      const { user } = await renderApp('/');
      await expectSignInScreen();
      await signIn(user, INQUIRY_USER);
      const menu = await findMenu();

      await user.click(within(menu).getByRole('link', { name: 'Selection demo (Order entry)' }));

      await waitFor(() => expect(window.location.pathname).toBe('/demo/selection'));
      expect(await screen.findByText('Order entry', { selector: 'p' })).toBeInTheDocument();
      // The clicked menu link unmounted; the host form puts the cursor on its
      // first input field.
      await waitFor(() => expect(screen.getByLabelText('Customer id +')).toHaveFocus());
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
  // A 401 that answers a request of an earlier identity
  // -------------------------------------------------------------------------

  describe('a stored-credential 401 after a change of identity', () => {
    const salesAuthorization = basicAuth(MAINTENANCE_USER.username, MAINTENANCE_USER.password);
    const inquiryAuthorization = basicAuth(INQUIRY_USER.username, INQUIRY_USER.password);

    it.each([
      ['GET', 'get', (client: typeof ApiClient) => client.request(CUSTOMER_PATH)],
      ['PUT', 'put', (client: typeof ApiClient) => client.request(CUSTOMER_PATH, { method: 'PUT', body: CUSTOMER_UPDATE })],
    ] as const)(
      'a %s sent as sales and refused after sign-out and sign-in as inquiry rejects with its 401 and leaves the inquiry session in place',
      async (method, route, send) => {
        const { user, client } = await renderApp('/');
        await expectSignInScreen();
        await signIn(user, MAINTENANCE_USER);
        await findMenu();
        const held = holdRoute(route, CUSTOMER_PATH, refusedCustomer);
        const call = held.holding(send(client));
        await held.arrived;
        expect(requests.filter((entry) => entry.path === CUSTOMER_PATH)).toEqual([
          { method, path: CUSTOMER_PATH, authorization: salesAuthorization },
        ]);

        await user.click(screen.getByRole('button', { name: 'Sign out' }));
        await expectSignInScreen();
        await signIn(user, INQUIRY_USER);
        await findMenu();
        held.release();

        await expect(call).rejects.toMatchObject({ status: 401, problem: { code: 'APP0401' } });
        expect(window.location.pathname).toBe('/');
        const menu = await findMenu();
        expect(within(menu).getByRole('link', { name: 'Work with customers (Inquiry)' })).toBeInTheDocument();
        expect(screen.getByText(INQUIRY_USER.username)).toBeInTheDocument();
        expect(screen.getByRole('alert')).toBeEmptyDOMElement();
        await expect(client.request(SESSION_PATH)).resolves.toEqual({
          username: INQUIRY_USER.username,
          roles: INQUIRY_USER.roles,
        });
        expect(requests.at(-1)).toEqual({ method: 'GET', path: SESSION_PATH, authorization: inquiryAuthorization });
        expect(window.location.pathname).toBe('/');
      },
    );

    it('the same 401 for the signed-in user still signs out to /sign-in', async () => {
      const { user, client } = await renderApp('/');
      await expectSignInScreen();
      await signIn(user, MAINTENANCE_USER);
      await findMenu();
      const held = holdRoute('get', CUSTOMER_PATH, refusedCustomer);
      const call = held.holding(client.request(CUSTOMER_PATH));
      await held.arrived;
      held.release();

      await expect(call).rejects.toMatchObject({ status: 401, problem: { code: 'APP0401' } });
      await expectSignInScreen();
      await expect(client.request(SESSION_PATH)).rejects.toMatchObject({ status: 401 });
      expect(requests.at(-1)).toEqual({ method: 'GET', path: SESSION_PATH, authorization: null });
      expect(requests.filter((entry) => entry.path === CUSTOMER_PATH)).toEqual([
        { method: 'GET', path: CUSTOMER_PATH, authorization: salesAuthorization },
      ]);
    });
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
      // The search page's focused Name filter unmounted; the menu takes focus
      // onto its first link.
      await waitFor(() =>
        expect(within(menu).getByRole('link', { name: 'Work with customers (Inquiry)' })).toHaveFocus(),
      );
      // The menu's own F3 signs out; it did not run for the same keypress.
      expect(screen.getByText(INQUIRY_USER.username)).toBeInTheDocument();
      expect(window.location.pathname).toBe('/');
    });

    it('a command key clears the previous message before its handler publishes the next one', async () => {
      const { user } = await renderApp('/demo/selection');
      await expectSignInScreen();
      await signIn(user, INQUIRY_USER);
      const hostFunction = await screen.findByText('Order entry', { selector: 'p' });
      await waitFor(() => expect(screen.getByLabelText('Customer id +')).toHaveFocus());
      // A click on plain text takes focus off the "Customer id +" field, so F4
      // there is DEM0005.
      await user.click(hostFunction);
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

  // -------------------------------------------------------------------------
  // Reads while the browser is offline: sent, failed, presented, not replayed
  // -------------------------------------------------------------------------

  describe('offline reads', () => {
    afterEach(() => {
      // `onlineManager` outlives the test. The tree is unmounted first, so its
      // query client stops listening before the browser is put back online,
      // and no read it held can be sent into the next test; the setup file's
      // own cleanup then finds nothing left to unmount.
      cleanup();
      networkDown = false;
      onlineManager.setOnline(true);
    });

    it('a search sent offline shows DEM9999 once, as an alert, and the reconnect searches nothing', async () => {
      expect(PROGRAM_ERROR).toBe('Program Error! Please contact IT now.');
      const { user, client } = await openInquirySearch();
      const alert = screen.getByRole('alert');
      const offlineAt = requests.length;
      goOffline();

      await user.type(screen.getByLabelText('Name starts with:'), 'nib');
      await user.keyboard('{Enter}');

      expect(await within(alert).findByText(PROGRAM_ERROR)).toBeInTheDocument();
      expect(alert.textContent).toBe(PROGRAM_ERROR);
      expect(sentSince(offlineAt)).toEqual([`GET ${SEARCH_PATH}`]);

      await expectReconnectSendsNothing(client);

      expect(alert.textContent).toBe(PROGRAM_ERROR);
    });

    it('a PageDown sent offline shows DEM9999 once, the reconnect loads nothing, and the next PageDown shows page 2', async () => {
      const { user, client } = await openInquirySearch();
      const alert = screen.getByRole('alert');
      const offlineAt = requests.length;
      goOffline();

      await user.keyboard('{PageDown}');

      expect(await within(alert).findByText(PROGRAM_ERROR)).toBeInTheDocument();
      expect(alert.textContent).toBe(PROGRAM_ERROR);
      expect(sentSince(offlineAt)).toEqual([`GET ${SEARCH_PATH}`]);
      // A failed next page leaves the loaded page current.
      expect(screen.getByRole('textbox', { name: `Option for ${FIRST_ACTIVE_ROW.name}` })).toBeInTheDocument();

      await expectReconnectSendsNothing(client);
      const retriedAt = requests.length;

      await user.keyboard('{PageDown}');

      // The user's own retry loads the page that failed, not the one after it.
      expect(
        await screen.findByRole('textbox', { name: `Option for ${SECOND_PAGE_FIRST_ROW.name}` }),
      ).toBeInTheDocument();
      expect(screen.queryByRole('textbox', { name: `Option for ${FIRST_ACTIVE_ROW.name}` })).not.toBeInTheDocument();
      expect(sentSince(retriedAt)).toEqual([`GET ${SEARCH_PATH}`]);
    });

    it('option 5 sent offline shows DEM9999 once and the window on blank fields, and the reconnect reads nothing', async () => {
      const { user, client } = await openInquirySearch();
      const alert = screen.getByRole('alert');
      const offlineAt = requests.length;
      goOffline();

      await user.type(screen.getByRole('textbox', { name: `Option for ${FIRST_ACTIVE_ROW.name}` }), '5');
      await user.keyboard('{Enter}');

      expect(await within(alert).findByText(PROGRAM_ERROR)).toBeInTheDocument();
      expect(alert.textContent).toBe(PROGRAM_ERROR);
      // A failed read still opens the window, on blank fields, as the detail
      // dialog does for any read that fails.
      const dialog = await screen.findByRole('dialog', { name: DISPLAY_DIALOG });
      expect(within(dialog).getByLabelText('Name')).toHaveValue('');
      // StrictMode's simulated remount may send the read a second time, after
      // aborting the first; nothing but that read is sent.
      expect(new Set(sentSince(offlineAt))).toEqual(new Set([`GET ${FIRST_ACTIVE_ROW_PATH}`]));

      await expectReconnectSendsNothing(client);

      expect(screen.getAllByRole('dialog')).toEqual([dialog]);
      expect(within(dialog).getByLabelText('Name')).toHaveValue('');
      expect(alert.textContent).toBe(PROGRAM_ERROR);
    });
  });
});
