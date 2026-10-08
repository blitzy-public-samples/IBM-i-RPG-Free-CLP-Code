/**
 * Tests of `CustomerSearchPage` (the `/customers` route) and its body
 * `CustomerSearchPanel` (shared with the Customer picker), with their real
 * collaborators: `useCustomerSearch`, `SearchFilters`, `ResultsTable`,
 * `CustomerDetailDialog`, `StatePicker`, the message catalog, the toast host
 * and the key scope stack.
 *
 * Source. PMTCUSTR (5250_Subfile/PMTCUSTR.SQLRPGLE): Init :712-767, first
 * page :237-256, main loop :261-307, function keys :355-420, ProcessOption
 * :427-515, paging :532-600, filters :625-665, BldFkeyText :676-702; PMTCUSTD;
 * the windows stacked over the list (5250_Subfile/MTNCUSTR.SQLRPGLE:364-382);
 * CUSTMSGF (5250_Subfile/CRTMSGF.CLLE:12-47), DEM0007's typo corrected. The
 * label "Including Inctives" is corrected to "Including Inactives"; Enter with
 * nothing to process still shows the last page loaded so far, a preserved
 * source defect (:506-513).
 *
 * Evidence. These tests are derived from reading the IBM i source and the
 * plan; they are not executed against the IBM i program and do not establish
 * behavioural equivalence with it.
 *
 * Fixtures. The search override serves the `customers` rows of
 * `src/test/handlers.ts` itself, in their order: active rows only unless
 * `includeInactive=true`, trimmed name and city prefixes, an exact State,
 * cursors `c1`, `c2`, … and DEM0002 on an empty first page. Expectations are
 * computed from that fixture ({@link pageNames}), never from the code under
 * test: the 23 active rows page as 12 + 11, all 30 as 12 + 12 + 6.
 *
 * Harness. Providers are mounted in `src/App.tsx` order; message assertions
 * wait for a probe of the catalog, before which `format` returns the bare code.
 * A held answer ({@link heldAnswer}) is released by waiting for evidence,
 * never for a time: MSW request events, settled `customersApi.search` calls,
 * an idle query cache that has notified its observers, and a `useIsFetching()`
 * probe. `afterEach` releases every hold a failed test left closed.
 */
import { StrictMode } from 'react';
import type { ReactElement, ReactNode } from 'react';
import { act, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type { UserEvent } from '@testing-library/user-event';
import { QueryClient, QueryClientProvider, notifyManager, useIsFetching } from '@tanstack/react-query';
import { http, HttpResponse } from 'msw/http';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { setCredentials } from '../../api/client';
import { customersApi } from '../../api/customers';
import type { CustomerSummaryResponse, SearchResponse } from '../../api/customers';
import { AuthProvider, useAuth } from '../../auth/AuthProvider';
import { RequireRole } from '../../auth/RequireRole';
import { ToastProvider, useToasts } from '../../components/ToastRegion';
import { KeyScopeProvider } from '../../keyboard/KeyScopeProvider';
import { MessageCatalogProvider, useMessages } from '../../messages/MessageCatalogProvider';
import { customers, messageText, problem, requestNotValid, users } from '../../test/handlers';
import { server } from '../../test/server';
import { CustomerSearchPage, CustomerSearchPanel } from './CustomerSearchPage';
import type { CustomerSearchPanelProps } from './CustomerSearchPage';

/** A `users` fixture account: one of the Compose-default demo users. */
type Account = (typeof users)[number];

function accountWith(role: 'INQUIRY' | 'MAINTENANCE'): Account {
  const account = users.find((candidate) => candidate.roles.includes(role));
  if (account === undefined) {
    throw new Error(`The users fixture holds no ${role} user`);
  }
  return account;
}

const INQUIRY_USER = accountWith('INQUIRY');
const MAINTENANCE_USER = accountWith('MAINTENANCE');

/** PMTCUSTD's SFLPAG, the `size` the screen sends. */
const PAGE_SIZE = 12;

const ACTIVE_ROWS: readonly CustomerSummaryResponse[] = customers.filter((row) => row.active === 'Y');

const ALL_ROWS: readonly CustomerSummaryResponse[] = customers;

function pageNames(rows: readonly CustomerSummaryResponse[], page: number): string[] {
  return rows.slice(page * PAGE_SIZE, (page + 1) * PAGE_SIZE).map((row) => row.name);
}

function seedRow(name: string): CustomerSummaryResponse {
  const row = customers.find((candidate) => candidate.name === name);
  if (row === undefined) {
    throw new Error(`The customers fixture holds no row named ${name}`);
  }
  return row;
}

/** ALIQUET INC. (AAAK), on the first page of either list. */
const FIRST_ACTIVE = ((): CustomerSummaryResponse => {
  const row = ACTIVE_ROWS[0];
  if (row === undefined) {
    throw new Error('The customers fixture holds no active row');
  }
  return row;
})();

const INACTIVE_ROW = seedRow('ALIQUAM ORNARE LIBERO ASSOCIATES');

/** The special-character seed names of original ids 3 and 6. */
const APOSTROPHE_NAME = "NIBH L'LOR COMPANY";
const BACKSLASH_NAME = 'URNA \\NUNC\\ COMPANY';

/** Passes the nine field rules, and the stub address service echoes it unchanged. */
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

const NAME_FILTER = 'Name starts with:';
const CITY_FILTER = 'City starts with:';
const STATE_FILTER = 'State +';

const CHANGE_DIALOG = 'Customer Master Change Customer';
const ADD_DIALOG = 'Customer Master Add Customer';
const DISPLAY_DIALOG = 'Customer Master Displaying Customer';
const STATE_PICKER = 'USA States';

const SEARCH_PATH = '/api/customers';
const ADD_PATH = '/api/customers';
const REVIEW_PATH = '/api/customers/review';
const STATES_PATH = '/api/states';

const CATALOG_PROBE_ID = 'catalog-probe';

const SESSION_PROBE_ID = 'session-probe';

const QUERY_PROBE_ID = 'query-probe';

const SIGN_IN_ROUTE = 'Sign-in route';

interface SearchQuery {
  name: string | null;
  city: string | null;
  state: string | null;
  includeInactive: string | null;
  size: string | null;
  cursor: string | null;
}

const FIRST_PAGE: SearchQuery = {
  name: '',
  city: '',
  state: '',
  includeInactive: 'false',
  size: String(PAGE_SIZE),
  cursor: null,
};

/**
 * Every search query received in the current test, in order; a test's own
 * search handler, registered over the suite's override, records here too.
 */
const searches: SearchQuery[] = [];

/** A test's own search answer; `undefined` falls back to {@link fixturePage}. */
let searchAnswer: ((query: SearchQuery) => Response | undefined) | null = null;

function answerSearch(respond: (query: SearchQuery) => Response | undefined): void {
  searchAnswer = respond;
}

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

function cursorPage(cursor: string): number | null {
  const match = /^c([1-9]\d*)$/.exec(cursor);
  return match?.[1] === undefined ? null : Number(match[1]);
}

/** One page of the matching seed rows; a cursor this override never issued is 400 APP0400. */
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

interface RecordedRequest {
  method: string;
  path: string;
}

/** Every `/api` request of the current test except the catalog's, in order. */
const traffic: RecordedRequest[] = [];

/** MSW `request:start` listener: runs before any handler, so every handler keeps answering. */
function recordRequest({ request }: { request: Request }): void {
  const { pathname } = new URL(request.url);
  if (pathname.startsWith('/api/') && pathname !== '/api/messages') {
    traffic.push({ method: request.method, path: pathname });
  }
}

function sent(method: string, path: string): RecordedRequest[] {
  return traffic.filter((entry) => entry.method === method && entry.path === path);
}

interface Deferred {
  readonly promise: Promise<void>;
  readonly resolve: () => void;
}

/** The promise executor runs synchronously, so `resolve` works as soon as this returns. */
function deferred(): Deferred {
  let settle: () => void = () => undefined;
  const promise = new Promise<void>((resolve) => {
    settle = resolve;
  });
  return { promise, resolve: () => settle() };
}

/**
 * Every search request MSW received in the current test, by request id, each
 * settled once MSW has run its handlers. MSW 3.0.2 emits `request:match` once
 * a handler has answered and `request:end` only once the interceptor has
 * accepted that answer, which it can refuse for a request the client aborted
 * meanwhile; such a request gets no `request:end`, so either event closes the
 * record.
 */
const searchRequests = new Map<string, Deferred>();

function startSearchRequest({ requestId, request }: { requestId: string; request: Request }): void {
  if (request.method === 'GET' && new URL(request.url).pathname === SEARCH_PATH) {
    searchRequests.set(requestId, deferred());
  }
}

function endSearchRequest({ requestId }: { requestId: string }): void {
  searchRequests.get(requestId)?.resolve();
}

const searchCalls: Array<Promise<SearchResponse>> = [];

/**
 * Installs a pass-through spy on `customersApi.search`, the call the list's
 * query function awaits, that keeps the promise of every call in
 * {@link searchCalls}; `restoreMocks` removes it before the next test.
 */
function trackSearchCalls(): void {
  const search = customersApi.search;
  vi.spyOn(customersApi, 'search').mockImplementation((params, signal) => {
    const call = search.call(customersApi, params, signal);
    searchCalls.push(call);
    return call;
  });
}

interface TrackedQueries {
  /**
   * Resolves once no query is fetching and the cache has delivered every
   * change to its subscribers, the screen's observers among them; at once
   * when that already holds.
   */
  idle(): Promise<void>;
}

/**
 * TanStack Query calls a plain cache subscriber during each change, but hands
 * the screen's observers (subscribed through `notifyManager.batchCalls`) their
 * notifications later, in the batch the change scheduled. One plain and one
 * batched subscriber count the changes made and delivered; while the counts
 * agree, no notification is still on its way to an observer.
 */
function trackQueries(client: QueryClient): TrackedQueries {
  const cache = client.getQueryCache();
  let made = 0;
  let delivered = 0;
  const waiting: Array<() => void> = [];
  const isIdle = (): boolean => client.isFetching() === 0 && delivered === made;
  cache.subscribe(() => {
    made += 1;
  });
  cache.subscribe(
    notifyManager.batchCalls(() => {
      delivered += 1;
      if (isIdle()) {
        for (const resolve of waiting.splice(0)) {
          resolve();
        }
      }
    }),
  );
  return {
    idle: () =>
      isIdle()
        ? Promise.resolve()
        : new Promise<void>((resolve) => {
            waiting.push(resolve);
          }),
  };
}

let trackedQueries: TrackedQueries | null = null;

/**
 * Waits until the current test's searches have settled, so an assertion that
 * a late answer changed nothing can follow at once. Inside `act`: MSW has run
 * the handlers of every search request ({@link searchRequests}), every
 * `customersApi.search` call has settled either way (an aborted call's query
 * function has then run its `catch`), and the query client is idle with every
 * change delivered; `act` then renders what they caused, effects and focus
 * moves included. Last, {@link QueryProbe} shows no fetch in flight. Each step
 * waits for an event the step before makes certain.
 */
async function settleSearches(): Promise<void> {
  await act(async () => {
    await Promise.all(Array.from(searchRequests.values(), (entry) => entry.promise));
    await Promise.allSettled(searchCalls);
    await trackedQueries?.idle();
  });
  if (trackedQueries !== null) {
    await waitFor(() => expect(screen.getByTestId(QUERY_PROBE_ID)).toHaveAttribute('data-fetching', '0'));
  }
}

/** A search answer held back until the test releases it, so the screen can be observed while the request is pending. */
interface HeldAnswer {
  /** Settles once the hold is opened, by {@link HeldAnswer.open} or {@link HeldAnswer.release}. */
  readonly released: Promise<void>;
  /** Lets every held request be answered; calling it again changes nothing. */
  readonly open: () => void;
  /** Opens the hold, then waits until the searches have settled ({@link settleSearches}); calling it again only waits again. */
  readonly release: () => Promise<void>;
}

const heldAnswers: HeldAnswer[] = [];

/** A closed hold, registered in {@link heldAnswers} so that no handler stays suspended once its test has ended. */
function heldAnswer(): HeldAnswer {
  const gate = deferred();
  const held: HeldAnswer = {
    released: gate.promise,
    open: gate.resolve,
    release: async () => {
      gate.resolve();
      await settleSearches();
    },
  };
  heldAnswers.push(held);
  return held;
}

beforeEach(() => {
  searches.length = 0;
  traffic.length = 0;
  searchAnswer = null;
  searchRequests.clear();
  searchCalls.length = 0;
  trackedQueries = null;
  server.events.on('request:start', recordRequest);
  server.events.on('request:start', startSearchRequest);
  server.events.on('request:match', endSearchRequest);
  server.events.on('request:end', endSearchRequest);
  trackSearchCalls();
  server.use(
    http.get(SEARCH_PATH, ({ request }) => {
      const query = readQuery(request);
      searches.push(query);
      return searchAnswer?.(query) ?? fixturePage(query);
    }),
  );
});

afterEach(async () => {
  // First every gate opens, then the searches settle while the tree is still
  // mounted (src/test/setup.ts unmounts it after this hook), so nothing a late
  // answer causes reaches the next test.
  const pending = heldAnswers.splice(0);
  for (const held of pending) {
    held.open();
  }
  for (const held of pending) {
    await held.release();
  }
  server.events.removeListener('request:start', recordRequest);
  server.events.removeListener('request:start', startSearchRequest);
  server.events.removeListener('request:match', endSearchRequest);
  server.events.removeListener('request:end', endSearchRequest);
  setCredentials(null);
});

/** As in `src/App.tsx`. */
function KeyedScreens({ children }: { children: ReactNode }) {
  const { clear } = useToasts();
  return <KeyScopeProvider onBeforeCommand={clear}>{children}</KeyScopeProvider>;
}

/** Hidden and without a role, like the other probes, so no query by role matches it. */
function CatalogProbe() {
  const { ready } = useMessages();
  return <span hidden data-testid={CATALOG_PROBE_ID} data-ready={ready ? 'true' : 'false'} />;
}

function SessionProbe() {
  const { status } = useAuth();
  return <span hidden data-testid={SESSION_PROBE_ID} data-status={status} />;
}

function QueryProbe() {
  const fetching = useIsFetching();
  return <span hidden data-testid={QUERY_PROBE_ID} data-fetching={String(fetching)} />;
}

/**
 * Renders `ui` signed in as `account`. The credentials are stored as after a
 * real sign-in, because every customer and state route answers 401 without
 * them. Resolves once the catalog has loaded and the Name filter holds the
 * initial focus. `strict` renders under `<StrictMode>`, as `src/main.tsx`
 * does, so development's extra effect cycle (a simulated unmount and remount)
 * runs too.
 */
async function renderWithProviders(account: Account, ui: ReactElement, { strict = false } = {}): Promise<UserEvent> {
  setCredentials({ username: account.username, password: account.password });
  const user = userEvent.setup();
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  trackedQueries = trackQueries(queryClient);
  const screens = (
    <QueryClientProvider client={queryClient}>
      <MessageCatalogProvider>
        <ToastProvider>
          <KeyedScreens>
            <MemoryRouter initialEntries={['/customers']}>
              <AuthProvider initialSession={{ username: account.username, roles: [...account.roles] }}>
                <CatalogProbe />
                <QueryProbe />
                {ui}
              </AuthProvider>
            </MemoryRouter>
          </KeyedScreens>
        </ToastProvider>
      </MessageCatalogProvider>
    </QueryClientProvider>
  );
  render(strict ? <StrictMode>{screens}</StrictMode> : screens);
  await waitFor(() => expect(screen.getByTestId(CATALOG_PROBE_ID)).toHaveAttribute('data-ready', 'true'));
  await waitFor(() => expect(filterInput(NAME_FILTER)).toHaveFocus());
  return user;
}

/** F3 and F12 lead to the `/` route, "Home". */
function renderSearchPage(role: 'INQUIRY' | 'MAINTENANCE'): Promise<UserEvent> {
  return renderWithProviders(
    role === 'MAINTENANCE' ? MAINTENANCE_USER : INQUIRY_USER,
    <Routes>
      <Route path="/customers" element={<CustomerSearchPage />} />
      <Route path="/" element={<p>Home</p>} />
    </Routes>,
  );
}

function criteriaGroup(): HTMLElement {
  return screen.getByRole('group', { name: 'Search criteria' });
}

function filterInput(label: typeof NAME_FILTER | typeof CITY_FILTER | typeof STATE_FILTER): HTMLInputElement {
  const element = within(criteriaGroup()).getByLabelText(label);
  if (!(element instanceof HTMLInputElement)) {
    throw new Error(`The ${label} label does not name an input`);
  }
  return element;
}

function resultsTable(): HTMLElement {
  return screen.getByRole('table', { name: 'Customers' });
}

function shownNames(): string[] {
  return within(resultsTable())
    .queryAllByRole('textbox')
    .map((input) => {
      const label = input instanceof HTMLInputElement ? input.labels?.[0]?.textContent : undefined;
      return (label ?? '').replace(/^Option for /, '');
    });
}

async function waitForPage(rows: readonly CustomerSummaryResponse[], page: number): Promise<void> {
  await waitFor(() => expect(shownNames()).toEqual(pageNames(rows, page)));
}

function optionInput(name: string): HTMLInputElement {
  const element = within(resultsTable()).getByRole('textbox', { name: `Option for ${name}` });
  if (!(element instanceof HTMLInputElement)) {
    throw new Error(`The option of ${name} is not an input`);
  }
  return element;
}

function rowOf(name: string): HTMLTableRowElement {
  const row = optionInput(name).closest('tr');
  if (row === null) {
    throw new Error(`The option of ${name} is not in a table row`);
  }
  return row;
}

/** Each action button's text: its verb and the visually hidden customer name. */
function rowActions(name: string): string[] {
  return within(rowOf(name))
    .getAllByRole('button')
    .map((button) => button.textContent ?? '');
}

/** The search screen's legend, not that of a window opened over it. */
function searchKeys(): HTMLElement {
  const bar = screen
    .getAllByRole('toolbar', { name: 'Function keys' })
    .find((candidate) => candidate.closest('dialog') === null);
  if (bar === undefined) {
    throw new Error('The search screen shows no function-key legend');
  }
  return bar;
}

/** The SFLEND indicator; `null` while the list holds no rows. */
function pagingIndicator(): HTMLElement | null {
  return screen.queryByText(/^(More\.\.\.|Bottom)$/);
}

function alertRegion(): HTMLElement {
  return screen.getByRole('alert');
}

function statusRegion(): HTMLElement {
  return screen.getByRole('status');
}

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

  // Init :712-767, first page :237-256, BldFkeyText :676-702
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

  // ProcessFunctionKey :355-420, filters :625-665
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

  // Main loop :261-307, SflFillPage :556-600, ProcessOption :506-513
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

  // Every key stays live, the latest navigation decides the page shown, and focus follows to the page asked for.
  describe('paging while a next page loads', () => {
    const INCLUDING_INACTIVE: SearchQuery = { ...FIRST_PAGE, includeInactive: 'true' };

    function firstNameOn(rows: readonly CustomerSummaryResponse[], page: number): string {
      const name = pageNames(rows, page)[0];
      if (name === undefined) {
        throw new Error(`Page ${page + 1} of the fixture rows is empty`);
      }
      return name;
    }

    /** Holds the page requested with `cursor` until the returned {@link HeldAnswer.release} is called. */
    function holdContinuation(cursor: string): () => Promise<void> {
      const held = heldAnswer();
      server.use(
        http.get(SEARCH_PATH, async ({ request }) => {
          const query = readQuery(request);
          searches.push(query);
          if (query.cursor === cursor) {
            await held.released;
          }
          return fixturePage(query);
        }),
      );
      return held.release;
    }

    async function showSecondOfThreePages(): Promise<UserEvent> {
      const user = await renderSearchPage('INQUIRY');
      await waitForPage(ACTIVE_ROWS, 0);
      await user.keyboard('{F9}');
      await waitForPage(ALL_ROWS, 0);
      await user.keyboard('{PageDown}');
      await waitForPage(ALL_ROWS, 1);
      await user.click(optionInput(firstNameOn(ALL_ROWS, 1)));
      expect(searches).toEqual([FIRST_PAGE, INCLUDING_INACTIVE, { ...INCLUDING_INACTIVE, cursor: 'c1' }]);
      return user;
    }

    it('PageDown from an option field: when the next page arrives its first option has focus, and 5 + Enter displays that customer', async () => {
      const release = holdContinuation('c1');
      const user = await renderSearchPage('INQUIRY');
      await waitForPage(ACTIVE_ROWS, 0);
      await user.click(optionInput(FIRST_ACTIVE.name));
      const target = seedRow(firstNameOn(ACTIVE_ROWS, 1));

      await user.keyboard('{PageDown}');
      await waitFor(() => expect(searches.at(-1)).toEqual({ ...FIRST_PAGE, cursor: 'c1' }));
      expect(shownNames()).toEqual(pageNames(ACTIVE_ROWS, 0));
      await release();

      await waitForPage(ACTIVE_ROWS, 1);
      await waitFor(() => expect(optionInput(target.name)).toHaveFocus());
      // Focus is on a live option field, so typing reaches it and Enter is still a command.
      await user.keyboard('5');
      expect(optionInput(target.name)).toHaveValue('5');
      await user.keyboard('{Enter}');

      const dialog = await screen.findByRole('dialog', { name: DISPLAY_DIALOG });
      expect(within(dialog).getByLabelText('Name')).toHaveValue(target.name);
      expect(sent('GET', `${SEARCH_PATH}/${target.custId}`)).toHaveLength(1);
      expect(searches).toHaveLength(2);
    });

    it('PageUp while the next page loads shows the previous page; the late page neither replaces it nor moves focus, and PageDown reaches it with no request', async () => {
      const release = holdContinuation('c2');
      const user = await showSecondOfThreePages();

      await user.keyboard('{PageDown}');
      await waitFor(() => expect(searches.at(-1)).toEqual({ ...INCLUDING_INACTIVE, cursor: 'c2' }));
      await user.keyboard('{PageUp}');

      await waitForPage(ALL_ROWS, 0);
      const firstOfPageOne = optionInput(firstNameOn(ALL_ROWS, 0));
      await waitFor(() => expect(firstOfPageOne).toHaveFocus());

      await release();

      expect(shownNames()).toEqual(pageNames(ALL_ROWS, 0));
      expect(firstOfPageOne).toHaveFocus();
      expect(searches).toHaveLength(4);

      // The late page was kept with the loaded pages (the cursor stack).
      await user.keyboard('{PageDown}');
      await waitForPage(ALL_ROWS, 1);
      await user.keyboard('{PageDown}');
      await waitForPage(ALL_ROWS, 2);
      await waitFor(() => expect(optionInput(firstNameOn(ALL_ROWS, 2))).toHaveFocus());
      expect(searches).toHaveLength(4);
      expect(alertRegion()).toBeEmptyDOMElement();
    });

    it('Enter with no option while the next page loads shows the last page loaded so far, and the late page does not replace it', async () => {
      const release = holdContinuation('c2');
      const user = await showSecondOfThreePages();
      const firstOfPageTwo = optionInput(firstNameOn(ALL_ROWS, 1));

      await user.keyboard('{PageDown}');
      await waitFor(() => expect(searches.at(-1)).toEqual({ ...INCLUDING_INACTIVE, cursor: 'c2' }));
      await user.keyboard('{Enter}');

      expect(shownNames()).toEqual(pageNames(ALL_ROWS, 1));

      await release();

      expect(shownNames()).toEqual(pageNames(ALL_ROWS, 1));
      expect(firstOfPageTwo).toHaveFocus();
      expect(searches).toHaveLength(4);
      expect(screen.queryByRole('dialog')).not.toBeInTheDocument();

      await user.keyboard('{PageDown}');

      await waitForPage(ALL_ROWS, 2);
      expect(searches).toHaveLength(4);
    });

    it('PageDown after PageUp while the next page loads moves to the loaded page; a PageDown at the deepest page then shows the late page with no second request', async () => {
      const release = holdContinuation('c2');
      const user = await showSecondOfThreePages();

      await user.keyboard('{PageDown}');
      await waitFor(() => expect(searches.at(-1)).toEqual({ ...INCLUDING_INACTIVE, cursor: 'c2' }));
      await user.keyboard('{PageUp}');
      await waitForPage(ALL_ROWS, 0);

      await user.keyboard('{PageDown}');

      await waitForPage(ALL_ROWS, 1);
      await waitFor(() => expect(optionInput(firstNameOn(ALL_ROWS, 1))).toHaveFocus());
      expect(searches).toHaveLength(4);

      await user.keyboard('{PageDown}');
      expect(shownNames()).toEqual(pageNames(ALL_ROWS, 1));

      await release();

      await waitForPage(ALL_ROWS, 2);
      await waitFor(() => expect(optionInput(firstNameOn(ALL_ROWS, 2))).toHaveFocus());
      expect(searches).toHaveLength(4);
      expect(alertRegion()).toBeEmptyDOMElement();
    });
  });

  // No 5250 counterpart: the subfile cursor closed with its program.
  describe('aborting obsolete requests', () => {
    interface FetchedSearch {
      url: URL;
      signal: AbortSignal | undefined;
    }

    /**
     * The searches handed to `fetch`, read from a pass-through spy. The list's
     * query aborts a search's signal once that search is obsolete, and the
     * browser then stops waiting for its answer.
     */
    function searchFetches(): () => FetchedSearch[] {
      const spy = vi.spyOn(globalThis, 'fetch');
      return () =>
        spy.mock.calls.flatMap(([input, init]) => {
          const url = new URL(String(input), window.location.href);
          return url.pathname === SEARCH_PATH ? [{ url, signal: init?.signal ?? undefined }] : [];
        });
    }

    interface HeldSearches {
      held(): number;
      release(): Promise<void>;
    }

    /** Holds each search `matches` selects until `release`, then answers it with `answer`; its gate is a {@link heldAnswer}. */
    function holdSearches(
      matches: (query: SearchQuery) => boolean,
      answer: (query: SearchQuery) => Response,
    ): HeldSearches {
      const gate = heldAnswer();
      let held = 0;
      server.use(
        http.get(SEARCH_PATH, async ({ request }) => {
          const query = readQuery(request);
          searches.push(query);
          if (!matches(query)) {
            return fixturePage(query);
          }
          held += 1;
          await gate.released;
          return answer(query);
        }),
      );
      return { held: () => held, release: gate.release };
    }

    /** Presenting this answer would publish an alert. */
    function serverFailure(): Response {
      return problem(500, 'DEM9999', { instance: SEARCH_PATH });
    }

    it('a new Enter search with other criteria aborts the pending one; the new rows show and the old answer publishes nothing', async () => {
      // The held search matches nothing, so its answer would carry the DEM0002 notice.
      const route = holdSearches((query) => query.name === 'ZZZ', fixturePage);
      const fetched = searchFetches();
      const user = await renderSearchPage('MAINTENANCE');
      await user.type(filterInput(NAME_FILTER), 'zzz');
      await user.keyboard('{Enter}');
      await waitFor(() => expect(route.held()).toBe(1));
      expect(fetched()[0]?.signal?.aborted).toBe(false);

      await user.clear(filterInput(NAME_FILTER));
      await user.type(filterInput(NAME_FILTER), 'ali');
      await user.keyboard('{Enter}');

      const aliRows = ACTIVE_ROWS.filter((row) => row.name.startsWith('ALI'));
      expect(aliRows.length).toBeGreaterThan(0);
      await waitForPage(aliRows, 0);
      await waitFor(() => expect(fetched()[0]?.signal?.aborted).toBe(true));
      expect(fetched()[1]?.signal?.aborted).toBe(false);

      await route.release();

      expect(shownNames()).toEqual(pageNames(aliRows, 0));
      expect(alertRegion()).toBeEmptyDOMElement();
      expect(statusRegion()).toBeEmptyDOMElement();
      expect(searches).toEqual([
        { ...FIRST_PAGE, name: 'ZZZ' },
        { ...FIRST_PAGE, name: 'ALI' },
      ]);
    });

    it('F5 while a search is pending aborts it; its failing answer shows no alert and the list stays empty', async () => {
      const route = holdSearches((query) => query.name === 'ALI', serverFailure);
      const fetched = searchFetches();
      const user = await renderSearchPage('MAINTENANCE');
      await user.type(filterInput(NAME_FILTER), 'ali');
      await user.keyboard('{Enter}');
      await waitFor(() => expect(route.held()).toBe(1));
      expect(fetched()[0]?.signal?.aborted).toBe(false);

      await user.keyboard('{F5}');

      await waitFor(() => expect(fetched()[0]?.signal?.aborted).toBe(true));
      await route.release();

      expect(alertRegion()).toBeEmptyDOMElement();
      expect(shownNames()).toEqual([]);
      expect(pagingIndicator()).not.toBeInTheDocument();
      expect(fetched()).toHaveLength(1);
      expect(searches).toEqual([{ ...FIRST_PAGE, name: 'ALI' }]);
    });

    it('leaving the screen (F3) while its first page loads aborts that search; its failing answer shows no alert', async () => {
      const route = holdSearches((query) => query.cursor === null, serverFailure);
      const fetched = searchFetches();
      const user = await renderSearchPage('INQUIRY');
      await waitFor(() => expect(route.held()).toBe(1));
      expect(fetched()[0]?.signal?.aborted).toBe(false);

      await user.keyboard('{F3}');

      expect(await screen.findByText('Home')).toBeInTheDocument();
      await waitFor(() => expect(fetched()[0]?.signal?.aborted).toBe(true));
      await route.release();

      expect(alertRegion()).toBeEmptyDOMElement();
      expect(fetched()).toHaveLength(1);
      expect(searches).toEqual([FIRST_PAGE]);
    });

    it('PageUp while the next page loads keeps that load: it is not aborted, and PageDown later shows its page with no request', async () => {
      const route = holdSearches((query) => query.cursor === 'c2', fixturePage);
      const fetched = searchFetches();
      const continuation = (): FetchedSearch | undefined =>
        fetched().find((entry) => entry.url.searchParams.get('cursor') === 'c2');
      const user = await renderSearchPage('INQUIRY');
      await waitForPage(ACTIVE_ROWS, 0);
      await user.keyboard('{F9}');
      await waitForPage(ALL_ROWS, 0);
      await user.keyboard('{PageDown}');
      await waitForPage(ALL_ROWS, 1);

      await user.keyboard('{PageDown}');
      await waitFor(() => expect(route.held()).toBe(1));
      await user.keyboard('{PageUp}');
      await waitForPage(ALL_ROWS, 0);

      expect(continuation()?.signal?.aborted).toBe(false);
      await route.release();
      expect(continuation()?.signal?.aborted).toBe(false);
      expect(shownNames()).toEqual(pageNames(ALL_ROWS, 0));

      await user.keyboard('{PageDown}');
      await waitForPage(ALL_ROWS, 1);
      await user.keyboard('{PageDown}');
      await waitForPage(ALL_ROWS, 2);

      const includingInactive: SearchQuery = { ...FIRST_PAGE, includeInactive: 'true' };
      expect(searches).toEqual([
        FIRST_PAGE,
        includingInactive,
        { ...includingInactive, cursor: 'c1' },
        { ...includingInactive, cursor: 'c2' },
      ]);
      // The first list, replaced by F9, was complete before; no search of the current list was aborted.
      expect(fetched().map((entry) => entry.signal?.aborted)).toEqual([false, false, false, false]);
      expect(alertRegion()).toBeEmptyDOMElement();
    });

    it('under StrictMode the first page the simulated unmount aborted shows no alert, and the remount loads the list', async () => {
      const fetched = searchFetches();
      await renderWithProviders(
        INQUIRY_USER,
        <Routes>
          <Route path="/customers" element={<CustomerSearchPage />} />
          <Route path="/" element={<p>Home</p>} />
        </Routes>,
        { strict: true },
      );

      await waitForPage(ACTIVE_ROWS, 0);
      // Both searches, the aborted one included, have been answered and their
      // calls settled, and the idle query is on screen.
      await settleSearches();

      expect(alertRegion()).toBeEmptyDOMElement();
      expect(statusRegion()).toBeEmptyDOMElement();
      // Development's extra effect cycle aborted the first request; the remount's answered.
      expect(fetched().map((entry) => entry.signal?.aborted)).toEqual([true, false]);
    });

    it('a 401 to the stored credentials signs out; the sign-out aborts that search, and its APP0401 alert still shows', async () => {
      // The stored credentials stopped working on the server (a changed password).
      answerSearch(() => problem(401, 'APP0401', { instance: SEARCH_PATH }));
      const fetched = searchFetches();
      // Guarded as `src/routes.tsx` guards it, so the signed-out session
      // replaces the screen at once and nothing reloads its list.
      const user = await renderWithProviders(
        MAINTENANCE_USER,
        <>
          <SessionProbe />
          <Routes>
            <Route
              path="/customers"
              element={
                <RequireRole role="INQUIRY">
                  <CustomerSearchPage />
                </RequireRole>
              }
            />
            <Route path="/sign-in" element={<p>{SIGN_IN_ROUTE}</p>} />
            <Route path="/" element={<p>Home</p>} />
          </Routes>
        </>,
      );
      expect(screen.getByTestId(SESSION_PROBE_ID)).toHaveAttribute('data-status', 'signed-in');

      await user.keyboard('{Enter}');

      expect(await screen.findByText(SIGN_IN_ROUTE)).toBeInTheDocument();
      expect(screen.getByTestId(SESSION_PROBE_ID)).toHaveAttribute('data-status', 'signed-out');
      // Its sign-out cancelled the user's queries, which aborted the refused search's own signal.
      expect(fetched().map((entry) => entry.signal?.aborted)).toEqual([true]);
      // The 401 did arrive, so it is presented, not swallowed as an abort.
      expect(messageText('APP0401')).toBe('Sign in required.');
      await waitFor(() => expect(within(alertRegion()).getByText(messageText('APP0401'))).toBeInTheDocument());
      expect(searches).toEqual([FIRST_PAGE]);
    });
  });

  // PMTCUSTD SFLCTL and SFL records
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

  // CustDsp over the list, PmtState over CustDsp
  describe('stacked screens (Maintenance)', () => {
    it('the State picker over the change window returns its code to the window only; F12 in a reopened picker closes only the picker', async () => {
      const user = await renderSearchPage('MAINTENANCE');
      await user.keyboard('{Enter}');
      await waitForPage(ACTIVE_ROWS, 0);

      await user.type(optionInput(FIRST_ACTIVE.name), '2');
      await user.keyboard('{Enter}');
      const detail = await screen.findByRole('dialog', { name: CHANGE_DIALOG });
      expect(within(detail).getByLabelText('Name')).toHaveValue(FIRST_ACTIVE.name);
      expect(within(detail).getByLabelText('State +')).toHaveValue(FIRST_ACTIVE.state);
      expect(sent('GET', `${SEARCH_PATH}/${FIRST_ACTIVE.custId}`)).toHaveLength(1);

      await user.click(within(detail).getByLabelText('State +'));
      await user.keyboard('{F4}');
      const picker = await screen.findByRole('dialog', { name: STATE_PICKER });
      const alabama = await within(picker).findByRole('textbox', { name: 'Option for Alabama' });

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

  // CustDsp's update, then ReadByKey + UpdSflRecd :454-457
  describe('saved edit (Maintenance)', () => {
    /** Neither the first nor the last row of page 2 of the active list. */
    const EDITED_INDEX = 3;

    /** ORNARE PLACERAT INSTITUTE (AAA1), MADISON WI 56718. */
    const EDITED = ((): CustomerSummaryResponse => {
      const row = ACTIVE_ROWS[PAGE_SIZE + EDITED_INDEX];
      if (row === undefined) {
        throw new Error('Page 2 of the active rows holds no row at the edited place');
      }
      return row;
    })();

    /** Sorts ahead of every seed name, so a re-sorted list would move the row to page 1. */
    const NEW_NAME = 'AAA RENAMED HOLDINGS';

    /**
     * The stub address service standardizes this address to "15 ORCHARD PL",
     * MAPLE CROSSING NJ 08999-3101, so the stored ZIP carries a ZIP+4 that the
     * list must cut to its first five characters.
     */
    const CHANGES: ReadonlyArray<readonly [label: string, value: string]> = [
      ['Active (Y/N)', 'N'],
      ['Name', NEW_NAME],
      ['Address', '15 ORCHARD PLACE'],
      ['City', 'MAPLE CROSSING'],
      ['State +', 'NJ'],
      ['ZIP', '08999'],
    ];

    function expectSavedRow(): void {
      const row = rowOf(NEW_NAME);
      expect(
        within(row)
          .getAllByRole('cell')
          .slice(1, 5)
          .map((cell) => cell.textContent),
      ).toEqual([`${NEW_NAME} Inactive`, 'MAPLE CROSSING', 'NJ', '08999']);
      expect(row).toHaveClass('row--inactive');
      expect(within(row).getByText('Inactive')).toHaveClass('visually-hidden');
    }

    it('option 2 on page 2, review and save: the PUT result replaces that row in place, with no search and no re-sort', async () => {
      const customerPath = `${SEARCH_PATH}/${EDITED.custId}`;
      // Each PUT body as sent, read by a handler that answers nothing, so the
      // default update handler still validates, stores and answers it.
      const updates: unknown[] = [];
      server.use(
        http.put(customerPath, async ({ request }) => {
          updates.push(await request.clone().json());
          return undefined;
        }),
      );
      const user = await renderSearchPage('MAINTENANCE');
      await user.keyboard('{Enter}');
      await waitForPage(ACTIVE_ROWS, 0);
      await user.keyboard('{PageDown}');
      await waitForPage(ACTIVE_ROWS, 1);
      const pageTwo = pageNames(ACTIVE_ROWS, 1);
      expect(pageTwo).toHaveLength(11);
      expect(pageTwo[EDITED_INDEX]).toBe(EDITED.name);
      expect(EDITED.active).toBe('Y');
      expect(searches).toEqual([FIRST_PAGE, { ...FIRST_PAGE, cursor: 'c1' }]);

      await user.type(optionInput(EDITED.name), '2');
      await user.keyboard('{Enter}');
      const dialog = await screen.findByRole('dialog', { name: CHANGE_DIALOG });
      await waitFor(() => expect(within(dialog).getByLabelText('Name')).toHaveValue(EDITED.name));
      expect(within(dialog).getByLabelText('Customer Id')).toHaveValue(EDITED.custId);
      await waitFor(() => expect(within(dialog).getByLabelText('Name')).toHaveFocus());
      for (const [label, value] of CHANGES) {
        const input = within(dialog).getByLabelText(label);
        await user.clear(input);
        await user.type(input, value);
      }

      await user.keyboard('{Enter}');
      await within(statusRegion()).findByText(messageText('DEM0000'));
      const panel = await within(dialog).findByRole('group', { name: 'Confirm customer' });
      await waitFor(() => expect(panel).toHaveFocus());
      expect(within(panel).getByLabelText('Address')).toHaveValue('15 ORCHARD PL');
      expect(within(panel).getByLabelText('ZIP')).toHaveValue('08999-3101');
      expect(sent('PUT', customerPath)).toHaveLength(0);

      await user.keyboard('{Enter}');

      await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
      expect(sent('POST', REVIEW_PATH)).toHaveLength(1);
      expect(sent('PUT', customerPath)).toHaveLength(1);
      expect(updates).toEqual([
        {
          name: NEW_NAME,
          addr: '15 ORCHARD PL',
          city: 'MAPLE CROSSING',
          state: 'NJ',
          zip: '08999-3101',
          corpPhone: '(555) 010-0001',
          acctMgr: 'TEST MANAGER',
          acctPhone: '(555) 010-0002',
          active: 'N',
          version: 0,
        },
      ]);
      // The saved row replaces the edited one where it stood, red now that it
      // is inactive, although it no longer matches the active-only criteria or
      // the list's name order.
      const replaced = pageTwo.map((name, index) => (index === EDITED_INDEX ? NEW_NAME : name));
      await waitFor(() => expect(shownNames()).toEqual(replaced));
      expectSavedRow();
      expect(optionInput(NEW_NAME)).toHaveValue('');
      // No search ran: the suite's override serves the seed rows, so a reload would have restored the old row.
      expect(searches).toEqual([FIRST_PAGE, { ...FIRST_PAGE, cursor: 'c1' }]);
      expect(traffic).toEqual([
        { method: 'GET', path: SEARCH_PATH },
        { method: 'GET', path: SEARCH_PATH },
        { method: 'GET', path: customerPath },
        { method: 'POST', path: REVIEW_PATH },
        { method: 'PUT', path: customerPath },
      ]);
      expect(listStatus()).toHaveTextContent('Page 2, rows 13 to 23. Bottom');
      expect(statusRegion()).toBeEmptyDOMElement();
      expect(alertRegion()).toBeEmptyDOMElement();

      // The replacement belongs to the loaded list: PageUp and PageDown keep it, with no request.
      await user.keyboard('{PageUp}');

      await waitForPage(ACTIVE_ROWS, 0);

      await user.keyboard('{PageDown}');

      await waitFor(() => expect(shownNames()).toEqual(replaced));
      expectSavedRow();
      expect(listStatus()).toHaveTextContent('Page 2, rows 13 to 23. Bottom');
      expect(searches).toHaveLength(2);
      expect(traffic).toHaveLength(5);
    });
  });
});

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

/** Answers the queries `holds` selects only once `held` is released. */
function holdSearches(holds: (query: SearchQuery) => boolean, held: HeldAnswer): void {
  server.use(
    http.get(SEARCH_PATH, async ({ request }) => {
      const query = readQuery(request);
      searches.push(query);
      if (holds(query)) {
        await held.released;
      }
      return fixturePage(query);
    }),
  );
}

/** The list's live region: the one `aria-live="polite"` element without a role, so never the toast host's `status` region. */
function listStatus(): HTMLElement {
  const regions = Array.from(document.querySelectorAll<HTMLElement>('[aria-live="polite"]')).filter(
    (element) => !element.hasAttribute('role'),
  );
  const [region, ...others] = regions;
  if (region === undefined || others.length > 0) {
    throw new Error(`Expected one list status region, found ${regions.length}`);
  }
  return region;
}

// What assistive technology learns while a page loads, once it has loaded, and after a rejected option's alert clears.
describe('CustomerSearchPanel list status and option errors', () => {
  it('Maintenance Enter: "Searching..." and aria-busy while the first page loads, then the page summary with "More..."', async () => {
    const held = heldAnswer();
    holdSearches((query) => query.cursor === null, held);
    const user = await renderSearchPage('MAINTENANCE');
    // The empty region stands for ERASE(SFL): no list has been loaded.
    expect(listStatus()).toHaveAttribute('aria-atomic', 'true');
    expect(listStatus()).toBeEmptyDOMElement();
    expect(resultsTable()).not.toHaveAttribute('aria-busy');

    await user.keyboard('{Enter}');

    const pending = await within(listStatus()).findByText('Searching...');
    expect(pending).toHaveClass('paging-indicator');
    expect(pending).not.toHaveClass('visually-hidden');
    expect(resultsTable()).toHaveAttribute('aria-busy', 'true');
    expect(shownNames()).toEqual([]);
    expect(pagingIndicator()).not.toBeInTheDocument();
    // A screen label, never a message: neither toast region carries it.
    expect(statusRegion()).toBeEmptyDOMElement();
    expect(alertRegion()).toBeEmptyDOMElement();

    await held.release();

    await waitForPage(ACTIVE_ROWS, 0);
    await waitFor(() => expect(within(listStatus()).queryByText('Searching...')).not.toBeInTheDocument());
    expect(resultsTable()).not.toHaveAttribute('aria-busy');
    expect(listStatus()).toHaveTextContent('Page 1, rows 1 to 12. More...');
    expect(within(listStatus()).getByText('Page 1, rows 1 to 12.')).toHaveClass('visually-hidden');
    expect(pagingIndicator()).toHaveClass('paging-indicator');
    expect(pagingIndicator()).toHaveTextContent('More...');
    expect(statusRegion()).toBeEmptyDOMElement();
    expect(searches).toEqual([FIRST_PAGE]);
  });

  it('PageDown: "Loading next page..." and aria-busy while the next page loads, then the summary of page 2 with "Bottom"', async () => {
    const held = heldAnswer();
    holdSearches((query) => query.cursor !== null, held);
    const user = await renderSearchPage('INQUIRY');
    await waitForPage(ACTIVE_ROWS, 0);
    expect(listStatus()).toHaveTextContent('Page 1, rows 1 to 12. More...');

    await user.keyboard('{PageDown}');

    const pending = await within(listStatus()).findByText('Loading next page...');
    expect(pending).toHaveClass('paging-indicator');
    expect(pending).not.toHaveClass('visually-hidden');
    expect(within(listStatus()).queryByText('Searching...')).not.toBeInTheDocument();
    expect(resultsTable()).toHaveAttribute('aria-busy', 'true');
    expect(shownNames()).toEqual(pageNames(ACTIVE_ROWS, 0));
    expect(pagingIndicator()).toHaveTextContent('More...');
    expect(statusRegion()).toBeEmptyDOMElement();

    await held.release();

    await waitForPage(ACTIVE_ROWS, 1);
    await waitFor(() => expect(within(listStatus()).queryByText('Loading next page...')).not.toBeInTheDocument());
    expect(resultsTable()).not.toHaveAttribute('aria-busy');
    expect(listStatus()).toHaveTextContent('Page 2, rows 13 to 23. Bottom');
    expect(pagingIndicator()).toHaveTextContent('Bottom');
    expect(searches).toEqual([FIRST_PAGE, { ...FIRST_PAGE, cursor: 'c1' }]);

    await user.keyboard('{PageUp}');

    await waitForPage(ACTIVE_ROWS, 0);
    expect(listStatus()).toHaveTextContent('Page 1, rows 1 to 12. More...');
    expect(searches).toHaveLength(2);
  });

  it('"Loading next page..." and aria-busy show only while the deepest loaded page is shown: PageUp drops them, PageDown back restores them', async () => {
    const held = heldAnswer();
    holdSearches((query) => query.cursor === 'c2', held);
    const includingInactive: SearchQuery = { ...FIRST_PAGE, includeInactive: 'true' };
    const user = await renderSearchPage('INQUIRY');
    await waitForPage(ACTIVE_ROWS, 0);
    await user.keyboard('{F9}');
    await waitForPage(ALL_ROWS, 0);
    await user.keyboard('{PageDown}');
    await waitForPage(ALL_ROWS, 1);
    expect(listStatus()).toHaveTextContent('Page 2, rows 13 to 24. More...');

    await user.keyboard('{PageDown}');

    await within(listStatus()).findByText('Loading next page...');
    expect(resultsTable()).toHaveAttribute('aria-busy', 'true');
    expect(shownNames()).toEqual(pageNames(ALL_ROWS, 1));

    // Page 1's next page is already loaded: the pending load cannot change the page shown.
    await user.keyboard('{PageUp}');

    await waitForPage(ALL_ROWS, 0);
    expect(within(listStatus()).queryByText('Loading next page...')).not.toBeInTheDocument();
    expect(resultsTable()).not.toHaveAttribute('aria-busy');
    expect(listStatus()).toHaveTextContent('Page 1, rows 1 to 12. More...');

    // Back at the deepest loaded page, whose next page is still loading.
    await user.keyboard('{PageDown}');

    await waitForPage(ALL_ROWS, 1);
    expect(within(listStatus()).getByText('Loading next page...')).toHaveClass('paging-indicator');
    expect(resultsTable()).toHaveAttribute('aria-busy', 'true');
    expect(within(listStatus()).getByText('Page 2, rows 13 to 24.')).toHaveClass('visually-hidden');
    expect(pagingIndicator()).toHaveTextContent('More...');
    expect(searches).toHaveLength(4);

    await held.release();

    await waitFor(() => expect(within(listStatus()).queryByText('Loading next page...')).not.toBeInTheDocument());
    expect(resultsTable()).not.toHaveAttribute('aria-busy');
    // The PageDown that moved is the newer navigation, so page 2 stays shown;
    // the late page is kept and the next PageDown shows it with no request.
    expect(shownNames()).toEqual(pageNames(ALL_ROWS, 1));
    await user.keyboard('{PageDown}');

    await waitForPage(ALL_ROWS, 2);
    expect(listStatus()).toHaveTextContent('Page 3, rows 25 to 30. Bottom');
    expect(resultsTable()).not.toHaveAttribute('aria-busy');
    expect(searches).toEqual([
      FIRST_PAGE,
      includingInactive,
      { ...includingInactive, cursor: 'c1' },
      { ...includingInactive, cursor: 'c2' },
    ]);
    expect(statusRegion()).toBeEmptyDOMElement();
    expect(alertRegion()).toBeEmptyDOMElement();
  });

  it('a rejected option keeps its DEM0004 text as its description after the alert clears; a blank option and Enter drop both', async () => {
    const user = await renderSearchPage('INQUIRY');
    await waitForPage(ACTIVE_ROWS, 0);
    const text = messageText('DEM0004', ['9']);
    expect(text).toBe('9 is not a valid option at this time.');
    const other = pageNames(ACTIVE_ROWS, 0)[1] ?? '';

    await user.type(optionInput(FIRST_ACTIVE.name), '9');
    await user.keyboard('{Enter}');

    expect(within(alertRegion()).getByText(text)).toBeInTheDocument();
    const option = optionInput(FIRST_ACTIVE.name);
    expect(option).toHaveAttribute('aria-invalid', 'true');
    const descriptionId = option.getAttribute('aria-describedby') ?? '';
    expect(descriptionId).toBe(`${option.id}-error`);
    const description = document.getElementById(descriptionId);
    expect(description?.textContent).toBe(text);
    expect(description).toHaveClass('visually-hidden');
    expect(option).toHaveAccessibleDescription(text);
    expect(optionInput(other)).not.toHaveAttribute('aria-invalid');
    expect(optionInput(other)).not.toHaveAttribute('aria-describedby');

    // A click is the next user action, which clears the alert.
    await user.click(filterInput(NAME_FILTER));

    expect(alertRegion()).toBeEmptyDOMElement();
    expect(optionInput(FIRST_ACTIVE.name)).toHaveAttribute('aria-invalid', 'true');
    expect(optionInput(FIRST_ACTIVE.name)).toHaveAttribute('aria-describedby', descriptionId);
    expect(document.getElementById(descriptionId)?.textContent).toBe(text);

    await user.clear(optionInput(FIRST_ACTIVE.name));
    await user.keyboard('{Enter}');

    await waitFor(() => expect(optionInput(FIRST_ACTIVE.name)).not.toHaveAttribute('aria-invalid'));
    expect(optionInput(FIRST_ACTIVE.name)).not.toHaveAttribute('aria-describedby');
    expect(document.getElementById(descriptionId)).toBeNull();
    expect(alertRegion()).toBeEmptyDOMElement();
    expect(searches).toHaveLength(1);
  });
});
