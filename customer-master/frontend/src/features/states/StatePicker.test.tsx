/**
 * Tests of the USA State prompt window: `./StatePicker.tsx` and, through it,
 * the data hook `./useStates.ts`.
 *
 * What it replaces. PMTSTATER and its display file PMTSTATED were a "load
 * all" subfile in a 16×40 window that any screen opened with F4 from a State
 * field and that returned the chosen code through its one parameter
 * (5250_Subfile/PMTSTATER.SQLRPGLE:52-54, 5250_Subfile/PMTSTATED.DSPF:39).
 * The picker is that window: `{ open, onSelect(code), onCancel() }` over
 * `GET /api/states?nameContains=&sort=name|code`.
 *
 * What is pinned down here (AAP 0.8.3, `StatePicker.test.tsx`; the source
 * rules restated in AAP 0.3.8 and 0.8.2.6):
 * - **Load all, by name.** One request on open, sorted by name, six rows per
 *   page (`Init` sets SortbyName, PMTSTATER:171-177, 442-448; SFLPAG 6,
 *   PMTSTATED:74-75), with the "Sorted by:" value, the 1=Select legend and
 *   the More.../Bottom indicator (SFLEND(*MORE), PMTSTATED:82-86).
 * - **Filter on Enter.** "Name Contains" (SC_NAME 10A, no CHECK(LC),
 *   PMTSTATED:87-88) is uppercased as typed and sent only when Enter applies
 *   a changed filter (PMTSTATER:196-203, `DESCLike`, :395-400).
 * - **F7** toggles name/code order, the highlighted column and the F7 legend,
 *   and reloads with the filter last applied (PMTSTATER:183-191, 262-279).
 * - **F5** clears the filter and empties the list without a request; the next
 *   Enter searches again (`NewSearchCriteria`, PMTSTATER:257-260).
 * - **Option 1** returns the row's code (PMTSTATER:299-304); any other option
 *   raises DEM0004 with the option typed and marks that row (:311-329).
 * - **Enter with nothing to do** positions the last six-row page and sends
 *   no request (PMTSTATER:334-341).
 * - **F3, F12 and Escape** cancel without a code (PMTSTATER:252-255); a key
 *   PMTSTATED does not enable shows DEM0003 (:281-282).
 *
 * Fixtures. The rows are the 58 STATES rows of 5250_Subfile/States.sql as
 * served by the default `GET /api/states` handler of `src/test/handlers.ts`.
 * Expected pages are read from that same handler through `statesApi.list`
 * before the picker mounts ({@link serverRows}), so the assertions follow the
 * server's order (`customer_sort` collation) without a hand-copied list. The
 * message texts are the CUSTMSGF catalog (5250_Subfile/CRTMSGF.CLLE) served
 * by the default `/api/messages` handler.
 *
 * Evidence. These tests are derived from reading the IBM i source and the
 * plan; they are not executed against the IBM i program and do not establish
 * behavioural equivalence with it.
 *
 * Harness. The providers are mounted in the order `src/App.tsx` uses, with a
 * fresh `QueryClient` per test. Every `/api/states` request is recorded from
 * MSW's `request:start` event, so the default handler keeps answering. A
 * probe reads `useMessages().ready`, so message assertions start only once
 * the catalog has loaded (until then `format` returns the bare code).
 */
import type { ReactNode } from 'react';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type { UserEvent } from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
// MSW 3 serves `http` and `HttpResponse` from its `msw/http` entry point, the
// one src/test/handlers.ts and the other suites import them from.
import { http, HttpResponse } from 'msw/http';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { Mock } from 'vitest';
import { setCredentials } from '../../api/client';
import { statesApi } from '../../api/states';
import type { StateResponse, StateSort } from '../../api/states';
import { AuthProvider } from '../../auth/AuthProvider';
import { ToastProvider, useToasts } from '../../components/ToastRegion';
import { KeyScopeProvider } from '../../keyboard/KeyScopeProvider';
import { MessageCatalogProvider, useMessages } from '../../messages/MessageCatalogProvider';
import { messageText, states, users } from '../../test/handlers';
import { server } from '../../test/server';
import { StatePicker } from './StatePicker';
import type { StatePickerProps } from './StatePicker';

// ---------------------------------------------------------------------------
// Constants
// ---------------------------------------------------------------------------

/** Rows per page: SFLPAG(0006) of PMTSTATED (:74-75). */
const PAGE_SIZE = 6;

/** The endpoint the picker reads, relative as the SPA calls it. */
const STATES_PATH = '/api/states';

/** Accessible name of the window: its ScreenHeader title (PMTSTATED:44). */
const DIALOG_NAME = 'USA States';

/** Accessible name of the filter input: its label (PMTSTATED:87). */
const FILTER_NAME = 'Name Contains';

/** Accessible name of the state table: its caption. */
const TABLE_NAME = 'USA states';

/** The test id of {@link CatalogProbe}. */
const CATALOG_PROBE_ID = 'catalog-probe';

/**
 * The demo user with the MAINTENANCE role, as a State field of the detail
 * dialog is where the picker is normally opened; the states endpoint itself
 * needs INQUIRY, which MAINTENANCE implies.
 */
const MAINTENANCE_USER = (() => {
  const user = users.find((candidate) => candidate.roles.includes('MAINTENANCE'));
  if (user === undefined) {
    throw new Error('The users fixture holds no MAINTENANCE user');
  }
  return user;
})();

// ---------------------------------------------------------------------------
// Request log
// ---------------------------------------------------------------------------

/** Every `GET /api/states` that reached MSW since the picker was rendered, in order. */
const stateRequests: URL[] = [];

/** MSW `request:start` listener: records the state-list requests, ignores every other route. */
function recordStatesRequest({ request }: { request: Request }): void {
  const url = new URL(request.url);
  if (request.method === 'GET' && url.pathname === STATES_PATH) {
    stateRequests.push(url);
  }
}

/** The two query parameters of a recorded request, as the API reads them. */
type StatesQuery = { nameContains: string | null; sort: string | null };

/** The recorded requests reduced to their query parameters. */
function requestedQueries(): StatesQuery[] {
  return stateRequests.map((url) => ({
    nameContains: url.searchParams.get('nameContains'),
    sort: url.searchParams.get('sort'),
  }));
}

beforeEach(() => {
  // Stored as after a real sign-in: the states endpoint answers 401 without them.
  setCredentials({ username: MAINTENANCE_USER.username, password: MAINTENANCE_USER.password });
  stateRequests.length = 0;
  server.events.on('request:start', recordStatesRequest);
});

afterEach(() => {
  server.events.removeListener('request:start', recordStatesRequest);
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
 * can wait for it. Hidden and without a role, so it is neither a live region
 * nor anything a query by role can match.
 */
function CatalogProbe() {
  const { ready } = useMessages();
  return <span hidden data-testid={CATALOG_PROBE_ID} data-ready={ready ? 'true' : 'false'} />;
}

/** The two picker callbacks of one render, as fresh mocks. */
type PickerCallbacks = {
  onSelect: Mock<StatePickerProps['onSelect']>;
  onCancel: Mock<StatePickerProps['onCancel']>;
};

/** What {@link renderPicker} returns. */
type PickerView = PickerCallbacks & { user: UserEvent };

/**
 * Renders the picker inside the application's providers: a fresh query client
 * (no retries), the message catalog, the toast host, the key scope stack, a
 * router and a MAINTENANCE session. Clears the request log first, so it holds
 * only what the picker sends.
 */
function renderPicker({ open = true }: { open?: boolean } = {}): PickerView {
  stateRequests.length = 0;
  const user = userEvent.setup();
  const onSelect = vi.fn<StatePickerProps['onSelect']>();
  const onCancel = vi.fn<StatePickerProps['onCancel']>();
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(
    <QueryClientProvider client={queryClient}>
      <MessageCatalogProvider>
        <ToastProvider>
          <KeyedScreens>
            <MemoryRouter>
              <AuthProvider initialSession={{ username: MAINTENANCE_USER.username, roles: ['MAINTENANCE'] }}>
                <CatalogProbe />
                <StatePicker open={open} onSelect={onSelect} onCancel={onCancel} />
              </AuthProvider>
            </MemoryRouter>
          </KeyedScreens>
        </ToastProvider>
      </MessageCatalogProvider>
    </QueryClientProvider>,
  );
  return { user, onSelect, onCancel };
}

/** Waits until the message catalog has loaded, so `format` yields texts rather than codes. */
async function waitForCatalog(): Promise<void> {
  await waitFor(() => expect(screen.getByTestId(CATALOG_PROBE_ID)).toHaveAttribute('data-ready', 'true'));
}

/** The data rows of the state table as `{state, name}`, header row excluded, in display order. */
function dataRows(table: HTMLElement): StateResponse[] {
  const [, ...rows] = within(table).getAllByRole('row');
  return rows.map((row) => {
    const cells = within(row).getAllByRole('cell');
    return { state: cells[1]?.textContent ?? '', name: cells[2]?.textContent ?? '' };
  });
}

/** What {@link openPicker} returns. */
type OpenPicker = PickerView & { dialog: HTMLElement; table: HTMLElement; filter: HTMLElement };

/**
 * Renders the open picker and waits until it is ready to be keyed: the first
 * rows are shown, the catalog has loaded and the filter holds focus.
 */
async function openPicker(): Promise<OpenPicker> {
  const view = renderPicker();
  const dialog = await screen.findByRole('dialog', { name: DIALOG_NAME });
  const table = within(dialog).getByRole('table', { name: TABLE_NAME });
  await waitFor(() => expect(dataRows(table).length).toBeGreaterThan(0));
  await waitForCatalog();
  const filter = within(dialog).getByRole('textbox', { name: FILTER_NAME });
  await waitFor(() => expect(filter).toHaveFocus());
  return { ...view, dialog, table, filter };
}

/**
 * The rows the default handler serves for `nameContains` in `sort` order:
 * the oracle for expected pages. Call it before rendering, because
 * {@link renderPicker} clears the request log this call also lands in.
 */
function serverRows(nameContains: string, sort: StateSort): Promise<StateResponse[]> {
  return statesApi.list(nameContains, sort);
}

/** The `index`-th (0-based) element of `rows`; fails the test when it is absent. */
function rowAt(rows: readonly StateResponse[], index: number): StateResponse {
  const row = rows[index];
  if (row === undefined) {
    throw new Error(`The state list holds no row ${index + 1}`);
  }
  return row;
}

/** The shared alert region of the toast host, where DEM0003 and DEM0004 are published. */
function alertRegion(): HTMLElement {
  return screen.getByRole('alert');
}

// ---------------------------------------------------------------------------
// Specs
// ---------------------------------------------------------------------------

describe('StatePicker', () => {
  describe('opening', () => {
    it('loads all states once, sorted by name, and shows the first six-row page (PMTSTATER:171-177, PMTSTATED:74-86)', async () => {
      const byName = await serverRows('', 'name');
      expect(byName).toHaveLength(states.length);

      const { dialog, table, filter } = await openPicker();

      expect(dataRows(table)).toEqual(byName.slice(0, PAGE_SIZE));
      expect(requestedQueries()).toEqual([{ nameContains: '', sort: 'name' }]);

      // The sorted column is the highlighted one (scNameHi, PMTSTATER:443-448).
      expect(within(table).getByRole('columnheader', { name: 'Name' })).toHaveAttribute('aria-sort', 'ascending');
      expect(within(table).getByRole('columnheader', { name: 'Code' })).not.toHaveAttribute('aria-sort');

      expect(within(dialog).getByRole('heading', { name: DIALOG_NAME })).toBeInTheDocument();
      expect(within(dialog).getByText('Sorted by: Name')).toBeInTheDocument();
      expect(within(dialog).getByText('1=Select')).toBeInTheDocument();
      expect(within(dialog).getByText('Demo Corp of America')).toBeInTheDocument();
      expect(within(dialog).getByText('More...')).toBeInTheDocument();
      expect(within(dialog).getByRole('button', { name: 'F7=By Code' })).toBeInTheDocument();

      expect(filter).toHaveFocus();
      expect(filter).toHaveValue('');
      expect(alertRegion()).toBeEmptyDOMElement();
    });

    it('renders no window and sends no request while closed', async () => {
      renderPicker({ open: false });
      await waitForCatalog();

      expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
      expect(stateRequests).toEqual([]);
    });
  });

  describe('Name Contains filter', () => {
    it('Enter with a changed filter searches with the entry uppercased as typed (PMTSTATER:196-203, 395-400)', async () => {
      const carolinas = await serverRows('CAR', 'name');
      expect(carolinas.map((row) => row.name)).toEqual(['North Carolina', 'South Carolina']);

      const { user, dialog, table, filter } = await openPicker();

      await user.type(filter, 'car');
      // No CHECK(LC) on SC_NAME (PMTSTATED:88): the entry is uppercased as typed.
      expect(filter).toHaveValue('CAR');
      // Typing alone sends nothing; only Enter applies the filter.
      expect(stateRequests).toHaveLength(1);

      await user.keyboard('{Enter}');

      await waitFor(() => expect(dataRows(table)).toEqual(carolinas));
      expect(requestedQueries()).toEqual([
        { nameContains: '', sort: 'name' },
        { nameContains: 'CAR', sort: 'name' },
      ]);
      expect(within(dialog).getByText('Bottom')).toBeInTheDocument();
      expect(within(dialog).getByText('Sorted by: Name')).toBeInTheDocument();
    });
  });

  describe('F7 sort toggle', () => {
    it('F7 toggles the order between name and code, the highlighted column and the F7 legend (PMTSTATER:262-279)', async () => {
      const byName = await serverRows('', 'name');
      const byCode = await serverRows('', 'code');
      // The two orders differ on the first page, so the toggle is observable.
      expect(byCode.slice(0, PAGE_SIZE)).not.toEqual(byName.slice(0, PAGE_SIZE));

      const { user, dialog, table } = await openPicker();
      const nameHeader = within(table).getByRole('columnheader', { name: 'Name' });
      const codeHeader = within(table).getByRole('columnheader', { name: 'Code' });

      await user.keyboard('{F7}');

      await waitFor(() => expect(dataRows(table)).toEqual(byCode.slice(0, PAGE_SIZE)));
      expect(requestedQueries()).toEqual([
        { nameContains: '', sort: 'name' },
        { nameContains: '', sort: 'code' },
      ]);
      expect(within(dialog).getByText('Sorted by: Code')).toBeInTheDocument();
      expect(codeHeader).toHaveAttribute('aria-sort', 'ascending');
      expect(nameHeader).not.toHaveAttribute('aria-sort');
      expect(within(dialog).getByRole('button', { name: 'F7=By Name' })).toBeInTheDocument();
      expect(within(dialog).queryByRole('button', { name: 'F7=By Code' })).not.toBeInTheDocument();

      await user.keyboard('{F7}');

      await waitFor(() => expect(dataRows(table)).toEqual(byName.slice(0, PAGE_SIZE)));
      expect(requestedQueries()).toEqual([
        { nameContains: '', sort: 'name' },
        { nameContains: '', sort: 'code' },
        { nameContains: '', sort: 'name' },
      ]);
      expect(within(dialog).getByText('Sorted by: Name')).toBeInTheDocument();
      expect(nameHeader).toHaveAttribute('aria-sort', 'ascending');
      expect(codeHeader).not.toHaveAttribute('aria-sort');
      expect(within(dialog).getByRole('button', { name: 'F7=By Code' })).toBeInTheDocument();
    });

    it('F7 reloads with the filter last applied and puts it back into the field (PMTSTATER:183-191)', async () => {
      const carolinasByCode = await serverRows('CAR', 'code');

      const { user, table, filter } = await openPicker();
      await user.type(filter, 'car');
      await user.keyboard('{Enter}');
      await waitFor(() => expect(stateRequests).toHaveLength(2));
      // A further entry that Enter has not applied yet.
      await user.type(filter, 'o');
      expect(filter).toHaveValue('CARO');

      await user.keyboard('{F7}');

      await waitFor(() => expect(dataRows(table)).toEqual(carolinasByCode));
      expect(requestedQueries()).toEqual([
        { nameContains: '', sort: 'name' },
        { nameContains: 'CAR', sort: 'name' },
        { nameContains: 'CAR', sort: 'code' },
      ]);
      expect(filter).toHaveValue('CAR');
    });
  });

  describe('F5 refresh', () => {
    it('F5 clears the filter and empties the list without a request; the next Enter searches again (PMTSTATER:257-260)', async () => {
      const byName = await serverRows('', 'name');

      const { user, dialog, table, filter } = await openPicker();
      await user.type(filter, 'new');

      await user.keyboard('{F5}');

      expect(dataRows(table)).toEqual([]);
      expect(filter).toHaveValue('');
      // No indicator while the subfile holds no records (PMTSTATED:82-86).
      expect(within(dialog).queryByText('More...')).not.toBeInTheDocument();
      expect(within(dialog).queryByText('Bottom')).not.toBeInTheDocument();
      expect(stateRequests).toHaveLength(1);

      // The filter is unchanged ('' again), yet Enter searches: NewSearchCriteria.
      await user.keyboard('{Enter}');

      await waitFor(() => expect(dataRows(table)).toEqual(byName.slice(0, PAGE_SIZE)));
      expect(requestedQueries()).toEqual([
        { nameContains: '', sort: 'name' },
        { nameContains: '', sort: 'name' },
      ]);
    });
  });

  describe('options', () => {
    it("option 1 returns the row's code exactly once (PMTSTATER:299-304)", async () => {
      const first = rowAt(await serverRows('', 'name'), 0);

      const { user, dialog, onSelect, onCancel } = await openPicker();
      const option = within(dialog).getByRole('textbox', { name: `Option for ${first.name}` });

      await user.type(option, '1');
      await user.keyboard('{Enter}');

      expect(onSelect).toHaveBeenCalledTimes(1);
      expect(onSelect).toHaveBeenCalledWith(first.state);
      expect(onCancel).not.toHaveBeenCalled();
      expect(stateRequests).toHaveLength(1);
      expect(alertRegion()).toBeEmptyDOMElement();
    });

    it("the row's Select button returns its code", async () => {
      const second = rowAt(await serverRows('', 'name'), 1);

      const { user, dialog, onSelect, onCancel } = await openPicker();

      await user.click(within(dialog).getByRole('button', { name: `Select ${second.name}` }));

      expect(onSelect).toHaveBeenCalledTimes(1);
      expect(onSelect).toHaveBeenCalledWith(second.state);
      expect(onCancel).not.toHaveBeenCalled();
    });

    it('an invalid option shows DEM0004 with the option as typed and marks only that row (PMTSTATER:311-329)', async () => {
      const second = rowAt(await serverRows('', 'name'), 1);
      const text = messageText('DEM0004', ['X']);
      expect(text).toBe('X is not a valid option at this time.');

      const { user, dialog, table, onSelect, onCancel } = await openPicker();
      const option = within(dialog).getByRole('textbox', { name: `Option for ${second.name}` });

      await user.type(option, 'x');
      // SF_OPT has no CHECK(LC) either: the option is uppercased as typed.
      expect(option).toHaveValue('X');
      await user.keyboard('{Enter}');

      expect(await within(alertRegion()).findByText(text)).toBeInTheDocument();
      expect(option).toHaveAttribute('aria-invalid', 'true');
      const marked = within(table)
        .getAllByRole('textbox')
        .filter((input) => input.getAttribute('aria-invalid') === 'true');
      expect(marked).toEqual([option]);
      // The value stays to be checked again (SFLNXTCHG) and the cursor is on it (DSPATR(PC)).
      expect(option).toHaveValue('X');
      expect(option).toHaveFocus();
      expect(onSelect).not.toHaveBeenCalled();
      expect(onCancel).not.toHaveBeenCalled();
      expect(stateRequests).toHaveLength(1);
    });
  });

  describe('paging', () => {
    it('Enter with unchanged filter and no option positions the last six-row page (PMTSTATER:334-341)', async () => {
      const twenty = states.slice(0, 20);
      server.use(http.get(STATES_PATH, () => HttpResponse.json(twenty)));

      const { user, dialog, table } = await openPicker();
      await waitFor(() => expect(dataRows(table)).toEqual(twenty.slice(0, PAGE_SIZE)));
      expect(within(dialog).getByText('More...')).toBeInTheDocument();

      await user.keyboard('{Enter}');

      // ((20 - 1) div 6) × 6 + 1 = row 19: fixture rows 19 and 20.
      const lastPageStart = Math.floor((twenty.length - 1) / PAGE_SIZE) * PAGE_SIZE;
      expect(lastPageStart + 1).toBe(19);
      expect(dataRows(table)).toEqual([rowAt(twenty, 18), rowAt(twenty, 19)]);
      expect(within(dialog).getByText('Bottom')).toBeInTheDocument();
      expect(within(dialog).queryByText('More...')).not.toBeInTheDocument();
      expect(stateRequests).toHaveLength(1);
      expect(alertRegion()).toBeEmptyDOMElement();
    });

    it('PageDown shows the next six rows and PageUp the previous six, with no request', async () => {
      const byName = await serverRows('', 'name');

      const { user, table } = await openPicker();

      await user.keyboard('{PageDown}');
      expect(dataRows(table)).toEqual(byName.slice(PAGE_SIZE, 2 * PAGE_SIZE));

      await user.keyboard('{PageUp}');
      expect(dataRows(table)).toEqual(byName.slice(0, PAGE_SIZE));

      // PageUp on the first page stays there.
      await user.keyboard('{PageUp}');
      expect(dataRows(table)).toEqual(byName.slice(0, PAGE_SIZE));
      expect(stateRequests).toHaveLength(1);
    });
  });

  describe('keys', () => {
    it.each(['F3', 'F12', 'Escape'])(
      '%s cancels: onCancel once and no code returned (PMTSTATER:252-255)',
      async (key) => {
        const { user, onSelect, onCancel } = await openPicker();

        await user.keyboard(`{${key}}`);

        expect(onCancel).toHaveBeenCalledTimes(1);
        expect(onCancel).toHaveBeenCalledWith();
        expect(onSelect).not.toHaveBeenCalled();
        expect(alertRegion()).toBeEmptyDOMElement();
      },
    );

    it('F4, which PMTSTATED does not enable, shows DEM0003 and changes nothing (PMTSTATER:281-282)', async () => {
      const byName = await serverRows('', 'name');
      expect(messageText('DEM0003')).toBe('Key is not active now');

      const { user, table, filter, onSelect, onCancel } = await openPicker();

      await user.keyboard('{F4}');

      expect(await within(alertRegion()).findByText(messageText('DEM0003'))).toBeInTheDocument();
      expect(dataRows(table)).toEqual(byName.slice(0, PAGE_SIZE));
      expect(filter).toHaveValue('');
      expect(stateRequests).toHaveLength(1);
      expect(onSelect).not.toHaveBeenCalled();
      expect(onCancel).not.toHaveBeenCalled();
    });
  });
});

