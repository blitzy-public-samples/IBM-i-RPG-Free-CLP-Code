/**
 * Tests of the customer search screen: `CustomerSearchPage` (the `/customers`
 * route) and `CustomerSearchPanel` (its body, shared with the Customer
 * picker), both from `./CustomerSearchPage`, rendered with their real
 * collaborators: `useCustomerSearch`, `SearchFilters`, `ResultsTable`, the
 * `CustomerDetailDialog` and `StatePicker` windows they open, the message
 * catalog, the toast host and the key scope stack.
 *
 * What it replaces. PMTCUSTR and its display file PMTCUSTD
 * (5250_Subfile/PMTCUSTR.SQLRPGLE, 5250_Subfile/PMTCUSTD.DSPF), the
 * expanding subfile that searched CUSTMAST:
 * - **Init** (:712-767): the function line Inquiry / Maintenance / Selection
 *   and the options line `5=Display`, `2=Edit 5=Display` or
 *   `1=Select 5=Display`; F6=Add only in Maintenance (BldFkeyText :676-702).
 * - **First page** (:237-256): loaded on entry in Inquiry only; the other
 *   modes wait for Enter (`NewSearchCriteria = *on`, :243).
 * - **Main loop** (:261-307): Enter runs a new search when the criteria
 *   changed or one is pending, otherwise ProcessOption; PageDown loads the
 *   next page, DEM0003 with no list, DEM0006 at the 9,999-row cap.
 * - **Function keys** (:355-420): F3 and F12 leave; F4 prompts from the
 *   State field only (DEM0005 elsewhere) and a changed State clears the
 *   list; F5 clears the criteria, turns inactive rows off and empties the
 *   list; F6 adds (Maintenance) or is DEM0003; F9 toggles inactive rows,
 *   switches its legend and reloads the first page.
 * - **ProcessOption** (:427-515): 1 returns the id (Selection), 2 edits
 *   (Maintenance), 5 displays (every mode), anything else is DEM0004 with the
 *   option in reverse image; with nothing to process the last page loaded
 *   so far is shown (the preserved defect, :506-513).
 * - **Paging** (:532-600) and **filters** (:625-665): 12 rows per page,
 *   DEM0002 for an empty first page, DEM0007 for a State of the wrong length.
 * - **Messages** are the CUSTMSGF texts (5250_Subfile/CRTMSGF.CLLE:12-47),
 *   DEM0007's typo corrected; the PMTCUSTD label "Including Inctives" is
 *   corrected to "Including Inactives".
 * - **Stacked screens.** CustDsp (MTNCUSTR) opened from the list, and its
 *   PmtState prompt over it (5250_Subfile/MTNCUSTR.SQLRPGLE:364-382): only
 *   the window on top is keyed, and returning from the prompt leaves the
 *   detail window and the list as they were.
 *
 * What is pinned down here (AAP 0.3.8 "Modes" and the keyboard table, 0.7.2
 * PMTCUSTD filters, 0.8.3 `CustomerSearchPage.test.tsx`).
 *
 * Fixtures. A `GET /api/customers` override serves the 30 seed rows of the
 * `customers` fixture of `src/test/handlers.ts` in its order: active rows
 * only unless `includeInactive=true` (23 rows: two pages of 12 + 11; all 30:
 * three pages of 12 + 12 + 6), name and city as trimmed prefixes and an
 * exact two-letter State, with the opaque cursors `c1`, `c2`, … and DEM0002
 * on an empty first page. It records every query it answers in
 * {@link searches}. A test that needs another answer (DEM0006, DEM0007)
 * sets {@link answerSearch}. Every other route (the catalog, one customer,
 * the states, review and add) is answered by the default handlers, and every
 * request is logged from MSW's `request:start` event in {@link traffic}.
 *
 * Evidence. These tests are derived from reading the IBM i source and the
 * plan; they are not executed against the IBM i program and do not establish
 * behavioural equivalence with it.
 *
 * Harness. The providers are mounted in the order `src/App.tsx` uses, with a
 * fresh `QueryClient` per test and the toast `clear` wired to the key
 * scope's `onBeforeCommand`, under a `MemoryRouter` at `/customers` whose `/`
 * route renders "Home". A probe reads `useMessages().ready`, so message
 * assertions start only once the catalog has loaded (until then `format`
 * returns the bare code).
 */
import type { ReactElement, ReactNode } from 'react';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type { UserEvent } from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
// MSW 3 serves `http` and `HttpResponse` from its `msw/http` entry point, the
// one src/test/handlers.ts and the other suites import them from.
import { http, HttpResponse } from 'msw/http';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { setCredentials } from '../../api/client';
import type { CustomerSummaryResponse, SearchResponse } from '../../api/customers';
import { AuthProvider } from '../../auth/AuthProvider';
import { ToastProvider, useToasts } from '../../components/ToastRegion';
import { KeyScopeProvider } from '../../keyboard/KeyScopeProvider';
import { MessageCatalogProvider, useMessages } from '../../messages/MessageCatalogProvider';
import { customers, messageText, problem, requestNotValid, users } from '../../test/handlers';
import { server } from '../../test/server';
import { CustomerSearchPage, CustomerSearchPanel } from './CustomerSearchPage';
import type { CustomerSearchPanelProps } from './CustomerSearchPage';

// ---------------------------------------------------------------------------
// Fixtures
// ---------------------------------------------------------------------------

/** A demo user of the `users` fixture: the Compose defaults of `CM_INQUIRY_*` and `CM_MAINTENANCE_*`. */
type Account = (typeof users)[number];

/** The demo user holding `role`: `inquiry` (INQUIRY) or `sales` (MAINTENANCE). */
function accountWith(role: 'INQUIRY' | 'MAINTENANCE'): Account {
  const account = users.find((candidate) => candidate.roles.includes(role));
  if (account === undefined) {
    throw new Error(`The users fixture holds no ${role} user`);
  }
  return account;
}

const INQUIRY_USER = accountWith('INQUIRY');
const MAINTENANCE_USER = accountWith('MAINTENANCE');

/** Rows per page: SFLPAG 12 of PMTCUSTD, the `size` the screen sends. */
const PAGE_SIZE = 12;

/** The seed rows the active-only list shows (ACTIVE = 'Y'), in search order. */
const ACTIVE_ROWS: readonly CustomerSummaryResponse[] = customers.filter((row) => row.active === 'Y');

/** Every seed row, inactive ones included (F9), in search order. */
const ALL_ROWS: readonly CustomerSummaryResponse[] = customers;

/** The names on the 0-based page `page` of `rows`, as the list shows them. */
function pageNames(rows: readonly CustomerSummaryResponse[], page: number): string[] {
  return rows.slice(page * PAGE_SIZE, (page + 1) * PAGE_SIZE).map((row) => row.name);
}

/** The seed row of `name`; throws when the fixture holds none. */
function seedRow(name: string): CustomerSummaryResponse {
  const row = customers.find((candidate) => candidate.name === name);
  if (row === undefined) {
    throw new Error(`The customers fixture holds no row named ${name}`);
  }
  return row;
}

/** The first active row: ALIQUET INC. (AAAK), on the first page of either list. */
const FIRST_ACTIVE = ((): CustomerSummaryResponse => {
  const row = ACTIVE_ROWS[0];
  if (row === undefined) {
    throw new Error('The customers fixture holds no active row');
  }
  return row;
})();

/** An inactive row on the first page of the full list. */
const INACTIVE_ROW = seedRow('ALIQUAM ORNARE LIBERO ASSOCIATES');

/** The seed rows with the apostrophe and the backslashes (original ids 3 and 6). */
const APOSTROPHE_NAME = "NIBH L'LOR COMPANY";
const BACKSLASH_NAME = 'URNA \\NUNC\\ COMPANY';

/**
 * A new customer that passes the nine field rules and that the default stub
 * address service echoes unchanged, as the window's labels name its fields
 * (MTNCUSTD screen order; Active is preset to Y on add).
 */
const NEW_CUSTOMER_ENTRIES: ReadonlyArray<readonly [label: string, value: string]> = [
  ['Name', 'ACME WIDGETS'],
  ['Address', '100 MAIN STREET'],
  ['City', 'SPRINGFIELD'],
  ['State +', 'IL'],
  ['ZIP', '62701'],
  ['Account Manager Phone', '(217) 555-0100'],
  ['Account Manager Name', 'JANE DOE'],
  ['Corporate Phone', '(217) 555-0199'],
];

/** Labels of the three search criteria (PMTCUSTD SC_NAME, SC_CITY, SC_STATE). */
const NAME_FILTER = 'Name starts with:';
const CITY_FILTER = 'City starts with:';
const STATE_FILTER = 'State +';

/** Accessible names of the windows the screen opens: their ScreenHeader title and function line. */
const CHANGE_DIALOG = 'Customer Master Change Customer';
const ADD_DIALOG = 'Customer Master Add Customer';
const DISPLAY_DIALOG = 'Customer Master Displaying Customer';
const STATE_PICKER = 'USA States';

/** The paths the screen and its windows call, relative as the SPA calls them. */
const SEARCH_PATH = '/api/customers';
const ADD_PATH = '/api/customers';
const REVIEW_PATH = '/api/customers/review';
const STATES_PATH = '/api/states';

/** The test id of {@link CatalogProbe}. */
const CATALOG_PROBE_ID = 'catalog-probe';

// ---------------------------------------------------------------------------
// Search requests and the request log
// ---------------------------------------------------------------------------

/** One `GET /api/customers` query as received: each parameter's value, `null` when absent. */
interface SearchQuery {
  name: string | null;
  city: string | null;
  state: string | null;
  includeInactive: string | null;
  size: string | null;
  cursor: string | null;
}

/** The query of a first page with blank criteria and inactive rows excluded, as the screen sends it. */
const FIRST_PAGE: SearchQuery = {
  name: '',
  city: '',
  state: '',
  includeInactive: 'false',
  size: String(PAGE_SIZE),
  cursor: null,
};

/** Every search query the override answered since the screen was rendered, in order. */
const searches: SearchQuery[] = [];

/**
 * The answer of a test that needs one other than the fixture pages; returning
 * `undefined` falls back to {@link fixturePage}. Reset before each test.
 */
let searchAnswer: ((query: SearchQuery) => Response | undefined) | null = null;

/** Sets the search answer of the current test. */
function answerSearch(respond: (query: SearchQuery) => Response | undefined): void {
  searchAnswer = respond;
}

/** The search parameters of a request URL. */
function readQuery(request: Request): SearchQuery {
  const params = new URL(request.url).searchParams;
  return {
    name: params.get('name'),
    city: params.get('city'),
    state: params.get('state'),
    includeInactive: params.get('includeInactive'),
    size: params.get('size'),
    cursor: params.get('cursor'),
  };
}

/** The 0-based page index an opaque cursor `c<n>` stands for, or `null` for any other text. */
function cursorPage(cursor: string): number | null {
  const match = /^c([1-9]\d*)$/.exec(cursor);
  return match?.[1] === undefined ? null : Number(match[1]);
}

/**
 * One page of the seed rows that match `query`, as the API pages them:
 * `size` rows from the page the cursor names, `nextCursor` while rows
 * follow, and notice DEM0002 on an empty first page. A cursor this override
 * never issued is 400 APP0400.
 */
function fixturePage(query: SearchQuery): Response {
  const page = query.cursor === null ? 0 : cursorPage(query.cursor);
  if (page === null) {
    return requestNotValid(SEARCH_PATH, [{ field: 'cursor', reason: 'cursor is not valid' }]);
  }
  const size = Number(query.size ?? PAGE_SIZE);
  const name = (query.name ?? '').trim();
  const city = (query.city ?? '').trim();
  const state = (query.state ?? '').trim();
  const rows = customers.filter(
    (row) =>
      (query.includeInactive === 'true' || row.active === 'Y') &&
      row.name.startsWith(name) &&
      row.city.startsWith(city) &&
      (state === '' || row.state === state),
  );
  const items = rows.slice(page * size, (page + 1) * size).map((row) => ({ ...row }));
  const body: SearchResponse = {
    items,
    nextCursor: (page + 1) * size < rows.length ? `c${page + 1}` : null,
    limitReached: false,
    notice: page === 0 && items.length === 0 ? { code: 'DEM0002', message: messageText('DEM0002') } : null,
  };
  return HttpResponse.json(body);
}

/** One request that reached MSW: its method and path. */
interface RecordedRequest {
  method: string;
  path: string;
}

/** Every `/api` request since the screen was rendered, the catalog excepted, in order. */
const traffic: RecordedRequest[] = [];

/** MSW `request:start` listener: runs before any handler, so every handler keeps answering. */
function recordRequest({ request }: { request: Request }): void {
  const { pathname } = new URL(request.url);
  if (pathname.startsWith('/api/') && pathname !== '/api/messages') {
    traffic.push({ method: request.method, path: pathname });
  }
}

/** The recorded requests of one method and path. */
function sent(method: string, path: string): RecordedRequest[] {
  return traffic.filter((entry) => entry.method === method && entry.path === path);
}

beforeEach(() => {
  searches.length = 0;
  traffic.length = 0;
  searchAnswer = null;
  server.events.on('request:start', recordRequest);
  server.use(
    http.get(SEARCH_PATH, ({ request }) => {
      const query = readQuery(request);
      searches.push(query);
      return searchAnswer?.(query) ?? fixturePage(query);
    }),
  );
});

afterEach(() => {
  server.events.removeListener('request:start', recordRequest);
  setCredentials(null);
});

// ---------------------------------------------------------------------------
// Harness
// ---------------------------------------------------------------------------

/** Wires the toast clear to the key scope's `onBeforeCommand`, as `src/App.tsx` does. */
function KeyedScreens({ children }: { children: ReactNode }) {
  const { clear } = useToasts();
  return <KeyScopeProvider onBeforeCommand={clear}>{children}</KeyScopeProvider>;
}

/**
 * Exposes whether the message catalog has loaded, as `data-ready`, so a test
 * can wait for it. Hidden and without a role, so no query by role matches it.
 */
function CatalogProbe() {
  const { ready } = useMessages();
  return <span hidden data-testid={CATALOG_PROBE_ID} data-ready={ready ? 'true' : 'false'} />;
}

/**
 * Renders `ui` inside the application's providers, signed in as `account`: a
 * fresh query client (no retries), the message catalog, the toast host, the
 * key scope stack, a router at `/customers` and the session. The account's
 * credentials are stored as after a real sign-in, because every customer and
 * state route answers 401 without them. Resolves once the catalog has loaded
 * and the Name filter holds the initial focus.
 */
async function renderWithProviders(account: Account, ui: ReactElement): Promise<UserEvent> {
  setCredentials({ username: account.username, password: account.password });
  const user = userEvent.setup();
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(
    <QueryClientProvider client={queryClient}>
      <MessageCatalogProvider>
        <ToastProvider>
          <KeyedScreens>
            <MemoryRouter initialEntries={['/customers']}>
              <AuthProvider initialSession={{ username: account.username, roles: [...account.roles] }}>
                <CatalogProbe />
                {ui}
              </AuthProvider>
            </MemoryRouter>
          </KeyedScreens>
        </ToastProvider>
      </MessageCatalogProvider>
    </QueryClientProvider>,
  );
  await waitFor(() => expect(screen.getByTestId(CATALOG_PROBE_ID)).toHaveAttribute('data-ready', 'true'));
  await waitFor(() => expect(filterInput(NAME_FILTER)).toHaveFocus());
  return user;
}

/**
 * Renders the `/customers` route for a user of `role` (INQUIRY: Inquiry mode,
 * MAINTENANCE: Maintenance mode); the `/` route, where F3 and F12 lead,
 * renders "Home".
 */
function renderSearchPage(role: 'INQUIRY' | 'MAINTENANCE'): Promise<UserEvent> {
  return renderWithProviders(
    role === 'MAINTENANCE' ? MAINTENANCE_USER : INQUIRY_USER,
    <Routes>
      <Route path="/customers" element={<CustomerSearchPage />} />
      <Route path="/" element={<p>Home</p>} />
    </Routes>,
  );
}

// ---------------------------------------------------------------------------
// Queries
// ---------------------------------------------------------------------------

/** The search criteria group (PMTCUSTD rows 4 and 5). */
function criteriaGroup(): HTMLElement {
  return screen.getByRole('group', { name: 'Search criteria' });
}

/** One filter input of the search criteria, by its exact label. */
function filterInput(label: typeof NAME_FILTER | typeof CITY_FILTER | typeof STATE_FILTER): HTMLInputElement {
  const element = within(criteriaGroup()).getByLabelText(label);
  if (!(element instanceof HTMLInputElement)) {
    throw new Error(`The ${label} label does not name an input`);
  }
  return element;
}

/** The results table (the PMTCUSTD subfile), named by its caption. */
function resultsTable(): HTMLElement {
  return screen.getByRole('table', { name: 'Customers' });
}

/** The customer names on the page shown, in list order, read from each row's Opt label "Option for <name>". */
function shownNames(): string[] {
  return within(resultsTable())
    .queryAllByRole('textbox')
    .map((input) => {
      const label = input instanceof HTMLInputElement ? input.labels?.[0]?.textContent : undefined;
      return (label ?? '').replace(/^Option for /, '');
    });
}

/** Waits until the list shows exactly page `page` of `rows`. */
async function waitForPage(rows: readonly CustomerSummaryResponse[], page: number): Promise<void> {
  await waitFor(() => expect(shownNames()).toEqual(pageNames(rows, page)));
}

/** The Opt input of the row showing `name`. */
function optionInput(name: string): HTMLInputElement {
  const element = within(resultsTable()).getByRole('textbox', { name: `Option for ${name}` });
  if (!(element instanceof HTMLInputElement)) {
    throw new Error(`The option of ${name} is not an input`);
  }
  return element;
}

/** The table row showing `name`. */
function rowOf(name: string): HTMLTableRowElement {
  const row = optionInput(name).closest('tr');
  if (row === null) {
    throw new Error(`The option of ${name} is not in a table row`);
  }
  return row;
}

/** The text of a row's action buttons, in order: the verb and the visually hidden customer name. */
function rowActions(name: string): string[] {
  return within(rowOf(name))
    .getAllByRole('button')
    .map((button) => button.textContent ?? '');
}

/** The search screen's own function-key legend, not the legend of a window opened over it. */
function searchKeys(): HTMLElement {
  const bar = screen
    .getAllByRole('toolbar', { name: 'Function keys' })
    .find((candidate) => candidate.closest('dialog') === null);
  if (bar === undefined) {
    throw new Error('The search screen shows no function-key legend');
  }
  return bar;
}

/** The SFLEND indicator, "More..." or "Bottom"; `null` while the list holds no rows. */
function pagingIndicator(): HTMLElement | null {
  return screen.queryByText(/^(More\.\.\.|Bottom)$/);
}

/** The shared alert region of the toast host: problem details and client-raised errors. */
function alertRegion(): HTMLElement {
  return screen.getByRole('alert');
}

/** The shared status region of the toast host: notices such as DEM0002 and DEM0006. */
function statusRegion(): HTMLElement {
  return screen.getByRole('status');
}

// ---------------------------------------------------------------------------
// Specs
// ---------------------------------------------------------------------------

describe('CustomerSearchPage', () => {
  it('starts from the fixtures: two active pages, three pages with inactive rows, distinct users per role', () => {
    expect(ACTIVE_ROWS).toHaveLength(23);
    expect(ALL_ROWS).toHaveLength(30);
    expect(FIRST_ACTIVE.name).toBe('ALIQUET INC.');
    expect(INACTIVE_ROW.active).toBe('N');
    expect(pageNames(ALL_ROWS, 0)).toContain(INACTIVE_ROW.name);
    expect(INQUIRY_USER.roles).toEqual(['INQUIRY']);
    expect(MAINTENANCE_USER.roles).toEqual(['MAINTENANCE']);
  });

  // -------------------------------------------------------------------------
  // Modes (Init :712-767, first page :237-256, BldFkeyText :676-702)
  // -------------------------------------------------------------------------

  describe('modes', () => {
    it('Inquiry: header "Inquiry", options "5=Display", the first page loads on open without Enter, no F6=Add', async () => {
      await renderSearchPage('INQUIRY');

      await waitForPage(ACTIVE_ROWS, 0);
      expect(screen.getByText('Inquiry', { selector: 'p' })).toBeInTheDocument();
      expect(screen.getByText('5=Display', { selector: 'p' })).toBeInTheDocument();
      expect(screen.queryByText('2=Edit 5=Display')).not.toBeInTheDocument();
      expect(searches).toEqual([FIRST_PAGE]);
      expect(within(searchKeys()).queryByRole('button', { name: 'F6=Add' })).not.toBeInTheDocument();
      expect(within(searchKeys()).getByRole('button', { name: 'F9=Include Inactive' })).toBeInTheDocument();
      // Rows offer Display only: 5 is the one option Inquiry accepts.
      for (const name of pageNames(ACTIVE_ROWS, 0)) {
        expect(rowActions(name)).toEqual([`Display ${name}`]);
      }
    });

    it('Maintenance: header "Maintenance", options "2=Edit 5=Display", F6=Add shown, no request until Enter', async () => {
      const user = await renderSearchPage('MAINTENANCE');

      expect(screen.getByText('Maintenance', { selector: 'p' })).toBeInTheDocument();
      expect(screen.getByText('2=Edit 5=Display', { selector: 'p' })).toBeInTheDocument();
      expect(within(searchKeys()).getByRole('button', { name: 'F6=Add' })).toBeInTheDocument();
      expect(shownNames()).toEqual([]);
      expect(pagingIndicator()).not.toBeInTheDocument();
      expect(searches).toHaveLength(0);

      await user.keyboard('{Enter}');

      await waitForPage(ACTIVE_ROWS, 0);
      expect(searches).toEqual([FIRST_PAGE]);
      // Rows offer Edit and Display, in option-code order.
      for (const name of pageNames(ACTIVE_ROWS, 0)) {
        expect(rowActions(name)).toEqual([`Edit ${name}`, `Display ${name}`]);
      }
    });

    it('F6 in Inquiry shows DEM0003 "Key is not active now" and opens no window', async () => {
      const user = await renderSearchPage('INQUIRY');
      await waitForPage(ACTIVE_ROWS, 0);

      await user.keyboard('{F6}');

      expect(within(alertRegion()).getByText('Key is not active now')).toBeInTheDocument();
      expect(messageText('DEM0003')).toBe('Key is not active now');
      expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
      expect(shownNames()).toEqual(pageNames(ACTIVE_ROWS, 0));
      expect(searches).toHaveLength(1);
    });

    it('F6 in Maintenance opens "Add Customer"; after the add the list is unchanged and no message is shown', async () => {
      const user = await renderSearchPage('MAINTENANCE');
      await user.keyboard('{Enter}');
      await waitForPage(ACTIVE_ROWS, 0);

      await user.keyboard('{F6}');

      const dialog = await screen.findByRole('dialog', { name: ADD_DIALOG });
      await waitFor(() => expect(within(dialog).getByLabelText('Name')).toHaveFocus());
      expect(within(dialog).getByLabelText('Active (Y/N)')).toHaveValue('Y');
      for (const [label, value] of NEW_CUSTOMER_ENTRIES) {
        await user.type(within(dialog).getByLabelText(label), value);
      }
      await user.keyboard('{Enter}');
      await within(statusRegion()).findByText(messageText('DEM0009'));
      const panel = await within(dialog).findByRole('group', { name: 'Confirm customer' });
      await waitFor(() => expect(panel).toHaveFocus());

      await user.keyboard('{Enter}');

      await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
      expect(sent('POST', REVIEW_PATH)).toHaveLength(1);
      expect(sent('POST', ADD_PATH)).toHaveLength(1);
      // PMTCUSTR returns from the add with the list as it was and no message (:397-400).
      expect(shownNames()).toEqual(pageNames(ACTIVE_ROWS, 0));
      expect(searches).toHaveLength(1);
      expect(statusRegion()).toBeEmptyDOMElement();
      expect(alertRegion()).toBeEmptyDOMElement();
    });

    it('option 5 opens the display window; closing it clears the option and leaves the list', async () => {
      const user = await renderSearchPage('INQUIRY');
      await waitForPage(ACTIVE_ROWS, 0);

      await user.type(optionInput(FIRST_ACTIVE.name), '5');
      await user.keyboard('{Enter}');

      const dialog = await screen.findByRole('dialog', { name: DISPLAY_DIALOG });
      expect(within(dialog).getByLabelText('Name')).toHaveValue(FIRST_ACTIVE.name);
      expect(sent('GET', `${SEARCH_PATH}/${FIRST_ACTIVE.custId}`)).toHaveLength(1);
      await user.click(within(dialog).getByLabelText('Name'));
      await user.keyboard('{Enter}');

      await waitFor(() => expect(dialog).not.toBeInTheDocument());
      expect(optionInput(FIRST_ACTIVE.name)).toHaveValue('');
      expect(shownNames()).toEqual(pageNames(ACTIVE_ROWS, 0));
      expect(searches).toHaveLength(1);
    });
  });

  // -------------------------------------------------------------------------
  // Keys and filters (ProcessFunctionKey :355-420, filters :625-665)
  // -------------------------------------------------------------------------

  describe('keys and filters', () => {
    it('F9 switches its legend, shows "Including Inactives" and reloads the first page with includeInactive=true', async () => {
      const user = await renderSearchPage('INQUIRY');
      await waitForPage(ACTIVE_ROWS, 0);
      expect(within(criteriaGroup()).queryByText('Including Inactives')).not.toBeInTheDocument();

      await user.keyboard('{F9}');

      await waitForPage(ALL_ROWS, 0);
      expect(searches).toEqual([FIRST_PAGE, { ...FIRST_PAGE, includeInactive: 'true' }]);
      expect(within(searchKeys()).getByRole('button', { name: 'F9=Exclude Inactive' })).toBeInTheDocument();
      expect(within(searchKeys()).queryByRole('button', { name: 'F9=Include Inactive' })).not.toBeInTheDocument();
      expect(within(criteriaGroup()).getByText('Including Inactives')).toBeInTheDocument();

      await user.keyboard('{F9}');

      await waitForPage(ACTIVE_ROWS, 0);
      expect(searches).toEqual([FIRST_PAGE, { ...FIRST_PAGE, includeInactive: 'true' }, FIRST_PAGE]);
      expect(within(searchKeys()).getByRole('button', { name: 'F9=Include Inactive' })).toBeInTheDocument();
      expect(within(criteriaGroup()).queryByText('Including Inactives')).not.toBeInTheDocument();
    });

    it('F5 clears the criteria, turns inactive rows off and empties the list with no request; the next Enter searches', async () => {
      const user = await renderSearchPage('INQUIRY');
      await waitForPage(ACTIVE_ROWS, 0);
      await user.type(filterInput(NAME_FILTER), 'ali');
      await user.type(filterInput(CITY_FILTER), 'ced');
      await user.type(filterInput(STATE_FILTER), 'ia');
      // F9 searches with the criteria on screen, inactive rows included.
      await user.keyboard('{F9}');
      await waitFor(() => expect(shownNames()).toEqual(['ALIQUET INC.']));
      expect(searches[1]).toEqual({ ...FIRST_PAGE, name: 'ALI', city: 'CED', state: 'IA', includeInactive: 'true' });

      await user.keyboard('{F5}');

      expect(filterInput(NAME_FILTER)).toHaveValue('');
      expect(filterInput(CITY_FILTER)).toHaveValue('');
      expect(filterInput(STATE_FILTER)).toHaveValue('');
      expect(within(criteriaGroup()).queryByText('Including Inactives')).not.toBeInTheDocument();
      expect(within(searchKeys()).getByRole('button', { name: 'F9=Include Inactive' })).toBeInTheDocument();
      expect(shownNames()).toEqual([]);
      expect(pagingIndicator()).not.toBeInTheDocument();
      expect(searches).toHaveLength(2);

      await user.keyboard('{Enter}');

      await waitForPage(ACTIVE_ROWS, 0);
      expect(searches).toHaveLength(3);
      expect(searches[2]).toEqual(FIRST_PAGE);
    });

    it.each([NAME_FILTER, CITY_FILTER] as const)(
      'F4 with focus on "%s" shows DEM0005 "Use F4 only if + is on field" and opens no picker',
      async (label) => {
        const user = await renderSearchPage('INQUIRY');
        await waitForPage(ACTIVE_ROWS, 0);
        await user.click(filterInput(label));

        await user.keyboard('{F4}');

        expect(within(alertRegion()).getByText('Use F4 only if + is on field')).toBeInTheDocument();
        expect(messageText('DEM0005')).toBe('Use F4 only if + is on field');
        expect(screen.queryByRole('dialog', { name: STATE_PICKER })).not.toBeInTheDocument();
        expect(sent('GET', STATES_PATH)).toHaveLength(0);
      },
    );

    it('F4 on State prompts; the chosen code fills the State filter, clears the list, and the next Enter searches it', async () => {
      const user = await renderSearchPage('INQUIRY');
      await waitForPage(ACTIVE_ROWS, 0);
      await user.click(filterInput(STATE_FILTER));

      await user.keyboard('{F4}');

      const picker = await screen.findByRole('dialog', { name: STATE_PICKER });
      const nameContains = within(picker).getByRole('textbox', { name: 'Name Contains' });
      await waitFor(() => expect(nameContains).toHaveFocus());
      await user.type(nameContains, 'cali');
      await user.keyboard('{Enter}');
      const option = await within(picker).findByRole('textbox', { name: 'Option for California' });
      await user.type(option, '1');
      await user.keyboard('{Enter}');

      await waitFor(() => expect(picker).not.toBeInTheDocument());
      expect(filterInput(STATE_FILTER)).toHaveValue('CA');
      await waitFor(() => expect(filterInput(STATE_FILTER)).toHaveFocus());
      // The State differs from the one applied, so the list is cleared (:377-381).
      expect(shownNames()).toEqual([]);
      expect(searches).toHaveLength(1);

      await user.keyboard('{Enter}');

      await waitFor(() => expect(shownNames()).toEqual(['MI TEMPOR LOREM INCORPORATED', 'ORCI IN INDUSTRIES']));
      expect(searches[1]).toEqual({ ...FIRST_PAGE, state: 'CA' });
    });

    it('a one-character State gives 400 DEM0007: the State filter is marked, focused and the message alerted', async () => {
      answerSearch((query) =>
        query.state?.trim().length === 1
          ? problem(400, 'DEM0007', {
              errors: [{ field: 'state', code: 'DEM0007', message: messageText('DEM0007') }],
              instance: SEARCH_PATH,
            })
          : undefined,
      );
      const user = await renderSearchPage('INQUIRY');
      await waitForPage(ACTIVE_ROWS, 0);
      await user.type(filterInput(STATE_FILTER), 'c');
      // Enter from another filter, so the focus move to State is observable.
      await user.click(filterInput(NAME_FILTER));

      await user.keyboard('{Enter}');

      await within(alertRegion()).findByText('State selection field is invalid.');
      expect(messageText('DEM0007')).toBe('State selection field is invalid.');
      expect(searches[1]).toEqual({ ...FIRST_PAGE, state: 'C' });
      const state = filterInput(STATE_FILTER);
      expect(state).toHaveAttribute('aria-invalid', 'true');
      expect(state).toHaveAccessibleDescription('State selection field is invalid.');
      await waitFor(() => expect(state).toHaveFocus());
      expect(filterInput(NAME_FILTER)).not.toHaveAttribute('aria-invalid');
      expect(filterInput(CITY_FILTER)).not.toHaveAttribute('aria-invalid');
    });

    it.each([
      ['x', 'X'],
      ['2', '2'],
    ])(
      'an invalid option %s on a row marks that option and alerts "<option> is not a valid option at this time."',
      async (typed, shown) => {
        const user = await renderSearchPage('INQUIRY');
        await waitForPage(ACTIVE_ROWS, 0);
        const other = pageNames(ACTIVE_ROWS, 0)[1] ?? '';

        await user.type(optionInput(FIRST_ACTIVE.name), typed);
        expect(optionInput(FIRST_ACTIVE.name)).toHaveValue(shown);
        await user.keyboard('{Enter}');

        const text = `${shown} is not a valid option at this time.`;
        expect(within(alertRegion()).getByText(text)).toBeInTheDocument();
        expect(messageText('DEM0004', [shown])).toBe(text);
        expect(optionInput(FIRST_ACTIVE.name)).toHaveAttribute('aria-invalid', 'true');
        await waitFor(() => expect(optionInput(FIRST_ACTIVE.name)).toHaveFocus());
        expect(optionInput(other)).not.toHaveAttribute('aria-invalid');
        expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
        expect(searches).toHaveLength(1);
      },
    );

    it('an empty first page shows the DEM0002 notice "No records match the selection criteria"; Enter searches again', async () => {
      answerSearch(() =>
        HttpResponse.json({
          items: [],
          nextCursor: null,
          limitReached: false,
          notice: { code: 'DEM0002', message: messageText('DEM0002') },
        } satisfies SearchResponse),
      );
      const user = await renderSearchPage('INQUIRY');

      await within(statusRegion()).findByText('No records match the selection criteria');
      expect(shownNames()).toEqual([]);
      expect(pagingIndicator()).not.toBeInTheDocument();
      expect(searches).toEqual([FIRST_PAGE]);

      // DEM0002 sets NewSearchCriteria (:539-541): the same criteria search again.
      await user.keyboard('{Enter}');

      await waitFor(() => expect(searches).toEqual([FIRST_PAGE, FIRST_PAGE]));
    });

    it('a list cut at 9,999 rows shows the DEM0006 notice, and PageDown repeats it with no request', async () => {
      answerSearch(() =>
        HttpResponse.json({
          items: ACTIVE_ROWS.slice(0, PAGE_SIZE).map((row) => ({ ...row })),
          nextCursor: null,
          limitReached: true,
          notice: { code: 'DEM0006', message: messageText('DEM0006') },
        } satisfies SearchResponse),
      );
      const user = await renderSearchPage('INQUIRY');

      const first = await within(statusRegion()).findByText('Too many records. Change the selection criteria.');
      expect(messageText('DEM0006')).toBe('Too many records. Change the selection criteria.');
      await waitForPage(ACTIVE_ROWS, 0);
      expect(pagingIndicator()).toHaveTextContent('Bottom');

      await user.keyboard('{PageDown}');

      // The key cleared the message and the cap sent it again (:287-292): a new toast.
      await waitFor(() =>
        expect(within(statusRegion()).getByText('Too many records. Change the selection criteria.')).not.toBe(first),
      );
      expect(shownNames()).toEqual(pageNames(ACTIVE_ROWS, 0));
      expect(searches).toHaveLength(1);
      expect(alertRegion()).toBeEmptyDOMElement();
    });

    it.each(['F3', 'F12', 'Escape'])('%s leaves the screen for the home page', async (key) => {
      const user = await renderSearchPage('INQUIRY');
      await waitForPage(ACTIVE_ROWS, 0);

      await user.keyboard(`{${key}}`);

      expect(await screen.findByText('Home')).toBeInTheDocument();
      expect(screen.queryByRole('table', { name: 'Customers' })).not.toBeInTheDocument();
    });
  });

  // -------------------------------------------------------------------------
  // Paging and Enter (main loop :261-307, SflFillPage :556-600, ProcessOption :506-513)
  // -------------------------------------------------------------------------

  describe('paging and Enter', () => {
    it('PageDown loads the next page through its cursor, PageUp and a second PageDown need no request, the end reads "Bottom"', async () => {
      const user = await renderSearchPage('INQUIRY');
      await waitForPage(ACTIVE_ROWS, 0);
      // The full list has three pages, so the middle one is followed by more rows.
      await user.keyboard('{F9}');
      await waitForPage(ALL_ROWS, 0);
      expect(pagingIndicator()).toHaveTextContent('More...');
      expect(searches).toHaveLength(2);
      const inactiveFirstPage: SearchQuery = { ...FIRST_PAGE, includeInactive: 'true' };

      await user.keyboard('{PageDown}');

      await waitForPage(ALL_ROWS, 1);
      expect(searches).toEqual([FIRST_PAGE, inactiveFirstPage, { ...inactiveFirstPage, cursor: 'c1' }]);
      expect(pagingIndicator()).toHaveTextContent('More...');

      await user.keyboard('{PageUp}');

      await waitForPage(ALL_ROWS, 0);
      expect(searches).toHaveLength(3);
      expect(pagingIndicator()).toHaveTextContent('More...');

      await user.keyboard('{PageDown}');

      await waitForPage(ALL_ROWS, 1);
      expect(searches).toHaveLength(3);

      await user.keyboard('{PageDown}');

      await waitForPage(ALL_ROWS, 2);
      expect(searches).toHaveLength(4);
      expect(searches[3]).toEqual({ ...inactiveFirstPage, cursor: 'c2' });
      expect(pagingIndicator()).toHaveTextContent('Bottom');

      // At the bottom PageDown keeps the last page, with no request and no message.
      await user.keyboard('{PageDown}');

      expect(shownNames()).toEqual(pageNames(ALL_ROWS, 2));
      expect(searches).toHaveLength(4);
      expect(alertRegion()).toBeEmptyDOMElement();
    });

    it.each(['PageDown', 'PageUp'])('%s with no list (Maintenance before Enter) shows DEM0003', async (key) => {
      const user = await renderSearchPage('MAINTENANCE');

      await user.keyboard(`{${key}}`);

      expect(within(alertRegion()).getByText('Key is not active now')).toBeInTheDocument();
      expect(shownNames()).toEqual([]);
      expect(searches).toHaveLength(0);
    });

    it('Enter with unchanged criteria and no option shows the last page loaded so far, with no request', async () => {
      const user = await renderSearchPage('INQUIRY');
      await waitForPage(ACTIVE_ROWS, 0);
      await user.keyboard('{PageDown}');
      await waitForPage(ACTIVE_ROWS, 1);
      expect(pagingIndicator()).toHaveTextContent('Bottom');
      await user.keyboard('{PageUp}');
      await waitForPage(ACTIVE_ROWS, 0);
      expect(searches).toEqual([FIRST_PAGE, { ...FIRST_PAGE, cursor: 'c1' }]);

      await user.keyboard('{Enter}');

      await waitForPage(ACTIVE_ROWS, 1);
      expect(searches).toHaveLength(2);
      expect(alertRegion()).toBeEmptyDOMElement();
    });

    it('Enter with changed criteria searches anew, ahead of any option', async () => {
      const user = await renderSearchPage('INQUIRY');
      await waitForPage(ACTIVE_ROWS, 0);
      await user.type(optionInput(FIRST_ACTIVE.name), '5');
      await user.type(filterInput(NAME_FILTER), 'nibh');

      await user.keyboard('{Enter}');

      await waitFor(() => expect(shownNames()).toEqual([APOSTROPHE_NAME]));
      expect(searches[1]).toEqual({ ...FIRST_PAGE, name: 'NIBH' });
      expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    });
  });

  // -------------------------------------------------------------------------
  // Rendering (PMTCUSTD SFLCTL and SFL records)
  // -------------------------------------------------------------------------

  describe('rendering', () => {
    it('labels the criteria "Name starts with:", "City starts with:" and "State +" with the PMTCUSTD lengths', async () => {
      await renderSearchPage('INQUIRY');

      expect(filterInput(NAME_FILTER)).toHaveAttribute('maxlength', '13');
      expect(filterInput(CITY_FILTER)).toHaveAttribute('maxlength', '13');
      expect(filterInput(STATE_FILTER)).toHaveAttribute('maxlength', '2');
      expect(within(criteriaGroup()).queryByText('Including Inactives')).not.toBeInTheDocument();
      expect(screen.getByRole('heading', { level: 1, name: 'Customer Master' })).toBeInTheDocument();
      expect(screen.getByText('Type options, press Enter.')).toBeInTheDocument();
      expect(screen.getByText('Demo Corp of America')).toBeInTheDocument();
    });

    it('shows the "Including Inactives" label only while inactive rows are included', async () => {
      const user = await renderSearchPage('INQUIRY');
      await waitForPage(ACTIVE_ROWS, 0);
      expect(screen.queryByText('Including Inactives')).not.toBeInTheDocument();

      await user.keyboard('{F9}');
      await waitForPage(ALL_ROWS, 0);

      expect(within(criteriaGroup()).getByText('Including Inactives')).toBeInTheDocument();
      expect(screen.queryByText(/Inctives/)).not.toBeInTheDocument();
    });

    it('heads the columns Opt, Customer Name, City, St and ZIP with scope="col" and shows ZIP as five characters', async () => {
      await renderSearchPage('INQUIRY');
      await waitForPage(ACTIVE_ROWS, 0);
      const table = resultsTable();

      for (const heading of ['Opt', 'Customer Name', 'City', 'St', 'ZIP']) {
        expect(within(table).getByRole('columnheader', { name: heading })).toHaveAttribute('scope', 'col');
      }
      const cells = within(rowOf(FIRST_ACTIVE.name)).getAllByRole('cell');
      expect(cells.slice(1, 5).map((cell) => cell.textContent)).toEqual([
        FIRST_ACTIVE.name,
        FIRST_ACTIVE.city,
        FIRST_ACTIVE.state,
        FIRST_ACTIVE.zip5,
      ]);
    });

    it('marks inactive rows with row--inactive and a hidden "Inactive", so colour is not the only signal', async () => {
      const user = await renderSearchPage('INQUIRY');
      await waitForPage(ACTIVE_ROWS, 0);
      await user.keyboard('{F9}');
      await waitForPage(ALL_ROWS, 0);

      const inactive = rowOf(INACTIVE_ROW.name);
      expect(inactive).toHaveClass('row--inactive');
      expect(within(inactive).getByText('Inactive')).toHaveClass('visually-hidden');
      const active = rowOf(FIRST_ACTIVE.name);
      expect(active).not.toHaveClass('row--inactive');
      expect(within(active).queryByText('Inactive')).not.toBeInTheDocument();
    });

    it.each([
      ['nibh', APOSTROPHE_NAME],
      ['urna', BACKSLASH_NAME],
    ])('the seed name found by "%s" renders literally: %s', async (typed, name) => {
      const user = await renderSearchPage('INQUIRY');
      await waitForPage(ACTIVE_ROWS, 0);

      await user.type(filterInput(NAME_FILTER), typed);
      await user.keyboard('{Enter}');

      await waitFor(() => expect(shownNames()).toEqual([name]));
      expect(within(resultsTable()).getByRole('cell', { name })).toBeInTheDocument();
      expect(searches[1]).toEqual({ ...FIRST_PAGE, name: typed.toUpperCase() });
    });
  });

  // -------------------------------------------------------------------------
  // Stacked screens (CustDsp over the list, PmtState over CustDsp)
  // -------------------------------------------------------------------------

  describe('stacked screens (Maintenance)', () => {
    it('the State picker over the change window returns its code to the window only; F12 in a reopened picker closes only the picker', async () => {
      const user = await renderSearchPage('MAINTENANCE');
      await user.keyboard('{Enter}');
      await waitForPage(ACTIVE_ROWS, 0);

      // Option 2 + Enter on a row opens the change window over the list.
      await user.type(optionInput(FIRST_ACTIVE.name), '2');
      await user.keyboard('{Enter}');
      const detail = await screen.findByRole('dialog', { name: CHANGE_DIALOG });
      expect(within(detail).getByLabelText('Name')).toHaveValue(FIRST_ACTIVE.name);
      expect(within(detail).getByLabelText('State +')).toHaveValue(FIRST_ACTIVE.state);
      expect(sent('GET', `${SEARCH_PATH}/${FIRST_ACTIVE.custId}`)).toHaveLength(1);

      // F4 on the window's State field opens the picker over it.
      await user.click(within(detail).getByLabelText('State +'));
      await user.keyboard('{F4}');
      const picker = await screen.findByRole('dialog', { name: STATE_PICKER });
      const alabama = await within(picker).findByRole('textbox', { name: 'Option for Alabama' });

      // Option 1 + Enter in the picker: only the picker closes.
      await user.type(alabama, '1');
      await user.keyboard('{Enter}');

      await waitFor(() => expect(picker).not.toBeInTheDocument());
      expect(screen.getByRole('dialog', { name: CHANGE_DIALOG })).toBe(detail);
      const stateField = within(detail).getByLabelText('State +');
      expect(stateField).toHaveValue('AL');
      await waitFor(() => expect(stateField).toHaveFocus());
      expect(sent('POST', REVIEW_PATH)).toHaveLength(0);
      expect(shownNames()).toEqual(pageNames(ACTIVE_ROWS, 0));
      expect(searches).toHaveLength(1);
      expect(screen.queryByText('Home')).not.toBeInTheDocument();

      // F4 again opens a fresh picker; F12 closes it and nothing beneath.
      await user.keyboard('{F4}');
      const again = await screen.findByRole('dialog', { name: STATE_PICKER });
      await user.keyboard('{F12}');

      await waitFor(() => expect(again).not.toBeInTheDocument());
      expect(screen.getByRole('dialog', { name: CHANGE_DIALOG })).toBe(detail);
      expect(within(detail).getByLabelText('State +')).toHaveValue('AL');
      expect(sent('POST', REVIEW_PATH)).toHaveLength(0);
      expect(sent('GET', STATES_PATH)).toHaveLength(2);
      expect(shownNames()).toEqual(pageNames(ACTIVE_ROWS, 0));
      expect(searches).toHaveLength(1);
      expect(screen.queryByText('Home')).not.toBeInTheDocument();
    });
  });
});

// ---------------------------------------------------------------------------
// The panel on its own: Selection mode, as the Customer picker hosts it
// ---------------------------------------------------------------------------

describe('CustomerSearchPanel in Selection mode', () => {
  it('offers 1=Select 5=Display, waits for Enter, returns the id of option 1 once and leaves through onExit', async () => {
    const onSelect = vi.fn<NonNullable<CustomerSearchPanelProps['onSelect']>>();
    const onExit = vi.fn<CustomerSearchPanelProps['onExit']>();
    const user = await renderWithProviders(
      INQUIRY_USER,
      <CustomerSearchPanel mode="selection" initialName="NIBH" onSelect={onSelect} onExit={onExit} />,
    );

    expect(screen.getByText('Selection', { selector: 'p' })).toBeInTheDocument();
    expect(screen.getByText('1=Select 5=Display', { selector: 'p' })).toBeInTheDocument();
    expect(within(searchKeys()).queryByRole('button', { name: 'F6=Add' })).not.toBeInTheDocument();
    expect(filterInput(NAME_FILTER)).toHaveValue('NIBH');
    expect(searches).toHaveLength(0);

    // F6 is not enabled outside Maintenance.
    await user.keyboard('{F6}');
    expect(within(alertRegion()).getByText('Key is not active now')).toBeInTheDocument();

    await user.keyboard('{Enter}');

    await waitFor(() => expect(shownNames()).toEqual([APOSTROPHE_NAME]));
    expect(searches).toEqual([{ ...FIRST_PAGE, name: 'NIBH' }]);
    expect(rowActions(APOSTROPHE_NAME)).toEqual([`Select ${APOSTROPHE_NAME}`, `Display ${APOSTROPHE_NAME}`]);

    await user.type(optionInput(APOSTROPHE_NAME), '1');
    await user.keyboard('{Enter}');

    expect(onSelect).toHaveBeenCalledTimes(1);
    expect(onSelect).toHaveBeenCalledWith(seedRow(APOSTROPHE_NAME).custId);
    expect(onExit).not.toHaveBeenCalled();

    await user.keyboard('{F12}');

    expect(onExit).toHaveBeenCalledTimes(1);
    expect(onSelect).toHaveBeenCalledTimes(1);
  });
});
