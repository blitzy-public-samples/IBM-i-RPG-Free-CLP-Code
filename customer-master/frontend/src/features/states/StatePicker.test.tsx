/**
 * Tests of the USA State prompt window, `./StatePicker.tsx`, and through it
 * the data hook `./useStates.ts`. Their source members are PMTSTATER and
 * PMTSTATED (AAP 0.8.3).
 *
 * Fixtures. Expected pages are read from the default `GET /api/states`
 * handler of `src/test/handlers.ts` through `statesApi.list` before the
 * picker mounts ({@link serverRows}), so the assertions follow the server's
 * order (`customer_sort` collation) without a hand-copied list.
 *
 * Evidence. These tests are derived from reading the IBM i source and the
 * plan; they are not executed against the IBM i program and do not establish
 * behavioural equivalence with it.
 *
 * Harness. The providers are mounted in the order `src/App.tsx` uses, with a
 * fresh `QueryClient` per test. `/api/states` requests are recorded from
 * MSW's `request:start` and `request:end` events, so the default handler
 * keeps answering. A catalog probe lets a test wait until the message catalog
 * has loaded, because until then `format` returns the bare code.
 */
import { StrictMode } from 'react';
import type { ReactNode } from 'react';
import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type { UserEvent } from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
// MSW 3 serves `http` and `HttpResponse` from `msw/http`, as src/test/handlers.ts imports them.
import { http, HttpResponse } from 'msw/http';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { Mock, MockInstance } from 'vitest';
import { setCredentials } from '../../api/client';
import { statesApi } from '../../api/states';
import type { StateResponse, StateSort } from '../../api/states';
import { AuthProvider } from '../../auth/AuthProvider';
import { ToastProvider, useToasts } from '../../components/ToastRegion';
import { KeyScopeProvider } from '../../keyboard/KeyScopeProvider';
import { MessageCatalogProvider, useMessages } from '../../messages/MessageCatalogProvider';
import { messageText, problem, states, users } from '../../test/handlers';
import { server } from '../../test/server';
import { StatePicker } from './StatePicker';
import type { StatePickerProps } from './StatePicker';

/** Rows per page: SFLPAG(0006) of PMTSTATED (:74-75). */
const PAGE_SIZE = 6;

const STATES_PATH = '/api/states';

/** Accessible name of the window: its ScreenHeader title (PMTSTATED:44). */
const DIALOG_NAME = 'USA States';

/** Accessible name of the filter input: its label (PMTSTATED:87). */
const FILTER_NAME = 'Name Contains';

const TABLE_NAME = 'USA states';
const CATALOG_PROBE_ID = 'catalog-probe';
const LOADING_LABEL = 'Loading...';
const NO_MATCH_LABEL = 'No states match.';
const FAILED_LABEL = 'States not loaded.';

/**
 * The MAINTENANCE demo user, as the picker is normally opened from a detail
 * dialog's State field; the states endpoint needs INQUIRY, which MAINTENANCE
 * implies.
 */
const MAINTENANCE_USER = (() => {
  const user = users.find((candidate) => candidate.roles.includes('MAINTENANCE'));
  if (user === undefined) {
    throw new Error('The users fixture holds no MAINTENANCE user');
  }
  return user;
})();

/**
 * The `GET /api/states` requests since the picker was rendered, in order:
 * `stateRequests` as each reached MSW, `endedStateRequests` as MSW finished
 * each exchange. MSW 3.0.2 emits `request:end` only after the matching
 * handler's resolver has returned and its answer has been passed on, so a
 * request in `endedStateRequests` has no resolver left running.
 */
const stateRequests: URL[] = [];
const endedStateRequests: URL[] = [];

function statesRequestUrl(request: Request): URL | undefined {
  const url = new URL(request.url);
  return request.method === 'GET' && url.pathname === STATES_PATH ? url : undefined;
}

function recordStatesRequest({ request }: { request: Request }): void {
  const url = statesRequestUrl(request);
  if (url !== undefined) {
    stateRequests.push(url);
  }
}

function recordStatesRequestEnd({ request }: { request: Request }): void {
  const url = statesRequestUrl(request);
  if (url !== undefined) {
    endedStateRequests.push(url);
  }
}

type StatesQuery = { nameContains: string | null; sort: string | null };

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
  endedStateRequests.length = 0;
  server.events.on('request:start', recordStatesRequest);
  server.events.on('request:end', recordStatesRequestEnd);
});

afterEach(async () => {
  // A hold the test made (`holdStatesAnswer`) is released here if the
  // test did not get to it, and settled either way, before the listeners go
  // and the credentials are forgotten, so no held resolver reaches the next
  // test. Inside `act`, because src/test/setup.ts unmounts the tree only
  // after this hook, so a render the released answer causes stays in `act`.
  const made = holds.splice(0);
  if (made.length > 0) {
    await act(async () => {
      for (const held of made) {
        held.release();
      }
      await Promise.all(made.map((held) => held.settled()));
    });
  }
  server.events.removeListener('request:start', recordStatesRequest);
  server.events.removeListener('request:end', recordStatesRequestEnd);
  setCredentials(null);
});

/** Bridges the toast host and the key scope as `src/App.tsx` does. */
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

type PickerCallbacks = {
  onSelect: Mock<StatePickerProps['onSelect']>;
  onCancel: Mock<StatePickerProps['onCancel']>;
};

/**
 * What {@link renderPicker} returns. `setOpen` re-renders the same providers
 * with another `open` value, as a host closes the window after `onCancel` or
 * opens it again with F4.
 */
type PickerView = PickerCallbacks & { user: UserEvent; setOpen(open: boolean): void };

/**
 * Renders the picker inside the application's providers: a fresh query client
 * (no retries), the message catalog, the toast host, the key scope stack, a
 * router and a MAINTENANCE session. Clears the request log first, so it holds
 * only what the picker sends. With `strict`, the tree is rendered under
 * `<StrictMode>`, as `src/main.tsx` renders the application, so development's
 * extra effect cycle (a simulated unmount and remount) runs too.
 */
function renderPicker({ open = true, strict = false }: { open?: boolean; strict?: boolean } = {}): PickerView {
  stateRequests.length = 0;
  endedStateRequests.length = 0;
  const user = userEvent.setup();
  const onSelect = vi.fn<StatePickerProps['onSelect']>();
  const onCancel = vi.fn<StatePickerProps['onCancel']>();
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const tree = (isOpen: boolean) => {
    const screens = (
      <QueryClientProvider client={queryClient}>
        <MessageCatalogProvider>
          <ToastProvider>
            <KeyedScreens>
              <MemoryRouter>
                <AuthProvider initialSession={{ username: MAINTENANCE_USER.username, roles: ['MAINTENANCE'] }}>
                  <CatalogProbe />
                  <StatePicker open={isOpen} onSelect={onSelect} onCancel={onCancel} />
                </AuthProvider>
              </MemoryRouter>
            </KeyedScreens>
          </ToastProvider>
        </MessageCatalogProvider>
      </QueryClientProvider>
    );
    return strict ? <StrictMode>{screens}</StrictMode> : screens;
  };
  const { rerender } = render(tree(open));
  return { user, onSelect, onCancel, setOpen: (isOpen) => rerender(tree(isOpen)) };
}

async function waitForCatalog(): Promise<void> {
  await waitFor(() => expect(screen.getByTestId(CATALOG_PROBE_ID)).toHaveAttribute('data-ready', 'true'));
}

function dataRows(table: HTMLElement): StateResponse[] {
  const [, ...rows] = within(table).getAllByRole('row');
  return rows.map((row) => {
    const cells = within(row).getAllByRole('cell');
    return { state: cells[1]?.textContent ?? '', name: cells[2]?.textContent ?? '' };
  });
}

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

function rowAt(rows: readonly StateResponse[], index: number): StateResponse {
  const row = rows[index];
  if (row === undefined) {
    throw new Error(`The state list holds no row ${index + 1}`);
  }
  return row;
}

/** The toast host's alert region. */
function alertRegion(): HTMLElement {
  return screen.getByRole('alert');
}

/** The toast host's status region. */
function statusToasts(): HTMLElement {
  return screen.getByRole('status');
}

/**
 * The window's list status line. Fails the test unless it is the window's
 * one live region, polite, atomic and without the toast host's `status` role.
 */
function statusLine(dialog: HTMLElement): HTMLElement {
  const regions = Array.from(dialog.querySelectorAll<HTMLElement>('[aria-live]'));
  expect(regions).toHaveLength(1);
  const region = regions[0];
  if (region === undefined) {
    throw new Error('The picker renders no list status region');
  }
  expect(region).toHaveAttribute('aria-live', 'polite');
  expect(region).toHaveAttribute('aria-atomic', 'true');
  expect(region).not.toHaveAttribute('role');
  return region;
}

/** A `GET /api/states` answer the test holds back: what {@link holdStatesAnswer} returns. */
interface HeldAnswer {
  /** Lets the held resolver return its answer. Calling it again changes nothing. */
  release(): void;
  /**
   * Resolves once the held resolver has returned its answer (or thrown), so
   * nothing of the hold is still running; at once when no request reached it.
   */
  settled(): Promise<void>;
}

/**
 * Every hold the current test made, registered as it is made; `afterEach`
 * releases and settles them, so no held resolver outlives its test, even one
 * whose test failed before releasing it.
 */
const holds: HeldAnswer[] = [];

/**
 * Holds the next `GET /api/states` until `release()` is called, as a slow
 * server would, then answers it with `answer()`. The override is `once`, so
 * every later request reaches the default handler. The resolver records
 * that the request reached it and when it has returned, which `settled`
 * waits for.
 */
function holdStatesAnswer(answer: () => Response): HeldAnswer {
  let open: () => void = () => undefined;
  const gate = new Promise<void>((resolve) => {
    open = resolve;
  });
  let finish: () => void = () => undefined;
  const finished = new Promise<void>((resolve) => {
    finish = resolve;
  });
  let arrived = false;
  server.use(
    http.get(
      STATES_PATH,
      async () => {
        arrived = true;
        try {
          await gate;
          return answer();
        } finally {
          finish();
        }
      },
      { once: true },
    ),
  );
  const held: HeldAnswer = {
    release: () => open(),
    settled: () => (arrived ? finished : Promise.resolve()),
  };
  holds.push(held);
  return held;
}

/**
 * The 500 DEM9999 the API answers when the state lookup itself fails (the
 * catch-all of its error model, AAP 0.4.5): a failure any state-list request
 * can meet, whatever its filter. A blank or short filter is never rejected
 * (AAP 0.7.2: blank means all, a filter matching nothing is an empty list),
 * so this, not a 400 on `nameContains`, is how such a request fails. It names
 * no field, so presenting it publishes its alert and marks nothing.
 */
function serviceFailure(): Response {
  return problem(500, 'DEM9999', { instance: STATES_PATH });
}

/**
 * Releases a held-back failing response and waits, inside `act`, until call
 * `call` of the `statesApi.list` spy has rejected, the held resolver has
 * returned its answer, and React has rendered whatever that rejection caused.
 * The call is an obsolete request, which the hook aborted as it became
 * obsolete, so it rejects with the abort reason (an `AbortError`) and never
 * with the `ApiError` the held answer would have produced. The hook attached
 * its own handler to the call's promise when the request started, before
 * this wait did, so that handler has run by the time the wait ends.
 */
async function failLate(held: HeldAnswer, list: MockInstance<typeof statesApi.list>, call: number): Promise<void> {
  const result = list.mock.results[call];
  if (result === undefined || result.type !== 'return') {
    throw new Error(`statesApi.list call ${call + 1} returned no promise`);
  }
  await act(async () => {
    held.release();
    await expect(result.value).rejects.toHaveProperty('name', 'AbortError');
    await held.settled();
    // One more macrotask, so nothing queued behind the rejection is left over.
    await new Promise((resolve) => setTimeout(resolve, 0));
  });
}

/**
 * Waits, inside `act`, until every call the `statesApi.list` spy has seen so
 * far has settled, either way, and returns the outcomes in call order. The
 * hook attached its own handler to each call's promise when the request
 * started, before this wait did, so whatever that handler did with an outcome
 * (an `onError` call and the alert it publishes) has run, and `act` has
 * flushed the render it caused, by the time the wait ends. Fails the test
 * when a call returned no promise.
 */
async function settleListCalls(
  list: MockInstance<typeof statesApi.list>,
): Promise<Array<PromiseSettledResult<StateResponse[]>>> {
  const calls = list.mock.results.map((result, index) => {
    if (result.type !== 'return') {
      throw new Error(`statesApi.list call ${index + 1} returned no promise`);
    }
    return result.value;
  });
  return act(() => Promise.allSettled(calls));
}

/**
 * The `AbortSignal` the client handed `fetch` for each `GET /api/states`, in
 * call order, read from a spy on the global `fetch` that calls through
 * (restored after the test by `restoreMocks`). The hook's query aborts that
 * signal when its request becomes obsolete, and the browser then stops
 * waiting for the answer.
 */
function statesFetchSignals(): () => Array<AbortSignal | undefined> {
  const spy = vi.spyOn(globalThis, 'fetch');
  return () =>
    spy.mock.calls
      .filter(([input]) => new URL(String(input), window.location.href).pathname === STATES_PATH)
      .map(([, init]) => init?.signal ?? undefined);
}

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

    // The uppercase filter and options are data, never prose or form history;
    // jsdom has no `spellcheck` property, so the attribute is asserted.
    it('turns browser spell checking and autocomplete off on the filter and the option fields', async () => {
      const first = rowAt(await serverRows('', 'name'), 0);

      const { dialog, filter } = await openPicker();

      for (const input of [filter, within(dialog).getByRole('textbox', { name: `Option for ${first.name}` })]) {
        expect(input).toHaveAttribute('spellcheck', 'false');
        expect(input).toHaveAttribute('autocomplete', 'off');
      }
    });
  });

  describe('F7 sort toggle', () => {
    it('F7 toggles the order between name and code, the highlighted column and the F7 legend (PMTSTATER:262-279)', async () => {
      const byName = await serverRows('', 'name');
      const byCode = await serverRows('', 'code');
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
      // SFLEND, PMTSTATED:82-86.
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

    it('each option field has its own id and a <label for> naming it "Option for <Name>"', async () => {
      const byName = await serverRows('', 'name');

      const { user, dialog } = await openPicker();

      /** Checks the option fields of `rows`, the page in view, and returns their ids. */
      function checkOptionLabels(rows: readonly StateResponse[]): string[] {
        return rows.map((row) => {
          const name = `Option for ${row.name}`;
          const option = within(dialog).getByRole('textbox', { name });
          if (!(option instanceof HTMLInputElement)) {
            throw new Error(`The "${name}" textbox is not an <input>`);
          }
          expect(option.id).not.toBe('');
          // Named by its <label for>, not by an aria-label.
          expect(within(dialog).getByLabelText(name)).toBe(option);
          expect(option).not.toHaveAttribute('aria-label');
          const labels = option.labels;
          expect(labels).toHaveLength(1);
          expect(labels?.[0]).toHaveAttribute('for', option.id);
          expect(labels?.[0]).toHaveTextContent(name);
          return option.id;
        });
      }

      const firstPageIds = checkOptionLabels(byName.slice(0, PAGE_SIZE));
      expect(new Set(firstPageIds).size).toBe(PAGE_SIZE);

      // Paging re-labels the same six inputs for the rows now in view.
      await user.keyboard('{PageDown}');
      const secondPageIds = checkOptionLabels(byName.slice(PAGE_SIZE, 2 * PAGE_SIZE));
      expect(new Set([...firstPageIds, ...secondPageIds]).size).toBe(2 * PAGE_SIZE);
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

    it('a rejected option keeps its DEM0004 text as its description after the alert clears; a blank option and Enter drop both', async () => {
      const byName = await serverRows('', 'name');
      // The rejected row is on the last page, the page a blank Enter shows
      // (PMTSTATER:334-341), so it stays in view when that Enter drops the rejection.
      const lastStart = Math.floor((byName.length - 1) / PAGE_SIZE) * PAGE_SIZE;
      const lastPage = byName.slice(lastStart);
      const rejectedRow = rowAt(byName, lastStart + 1);
      const otherRow = rowAt(byName, lastStart);
      const text = messageText('DEM0004', ['X']);
      expect(text).toBe('X is not a valid option at this time.');

      const { user, dialog, table, filter } = await openPicker();

      /** The option field of `row`, which must be on the page in view. */
      function optionFor(row: StateResponse): HTMLElement {
        return within(dialog).getByRole('textbox', { name: `Option for ${row.name}` });
      }

      await user.keyboard('{Enter}');
      await waitFor(() => expect(dataRows(table)).toEqual(lastPage));

      await user.type(optionFor(rejectedRow), 'x');
      await user.keyboard('{Enter}');

      expect(await within(alertRegion()).findByText(text)).toBeInTheDocument();
      const option = optionFor(rejectedRow);
      expect(option).toHaveAttribute('aria-invalid', 'true');
      const descriptionId = option.getAttribute('aria-describedby') ?? '';
      expect(descriptionId).toBe(`${option.id}-error`);
      const description = document.getElementById(descriptionId);
      expect(description?.textContent).toBe(text);
      expect(description).toHaveClass('visually-hidden');
      expect(option).toHaveAccessibleDescription(text);
      expect(optionFor(otherRow)).not.toHaveAttribute('aria-invalid');
      expect(optionFor(otherRow)).not.toHaveAttribute('aria-describedby');

      // A click is the next user action, which clears the alert.
      await user.click(filter);

      expect(alertRegion()).toBeEmptyDOMElement();
      expect(optionFor(rejectedRow)).toHaveAttribute('aria-invalid', 'true');
      expect(optionFor(rejectedRow)).toHaveAttribute('aria-describedby', descriptionId);
      expect(document.getElementById(descriptionId)?.textContent).toBe(text);

      // Rows are keyed by page slot: the description follows the rejected row, not its slot.
      await user.keyboard('{PageUp}');
      await waitFor(() => expect(dataRows(table)).toEqual(byName.slice(lastStart - PAGE_SIZE, lastStart)));
      const described = within(table)
        .getAllByRole('textbox')
        .filter((input) => input.hasAttribute('aria-invalid') || input.hasAttribute('aria-describedby'));
      expect(described).toEqual([]);
      expect(document.getElementById(descriptionId)).toBeNull();
      await user.keyboard('{PageDown}');
      await waitFor(() => expect(dataRows(table)).toEqual(lastPage));
      expect(optionFor(rejectedRow)).toHaveAttribute('aria-describedby', descriptionId);
      expect(optionFor(rejectedRow)).toHaveAccessibleDescription(text);

      await user.clear(optionFor(rejectedRow));
      await user.keyboard('{Enter}');

      await waitFor(() => expect(optionFor(rejectedRow)).not.toHaveAttribute('aria-invalid'));
      expect(optionFor(rejectedRow)).not.toHaveAttribute('aria-describedby');
      expect(document.getElementById(descriptionId)).toBeNull();
      expect(dataRows(table)).toEqual(lastPage);
      expect(alertRegion()).toBeEmptyDOMElement();
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

      await user.keyboard('{PageUp}');
      expect(dataRows(table)).toEqual(byName.slice(0, PAGE_SIZE));
      expect(stateRequests).toHaveLength(1);
    });

    it('a click on plain picker text focuses its key container, where Enter still shows the last six-row page; the container is never a tab stop', async () => {
      const byName = await serverRows('', 'name');
      expect(byName).toHaveLength(58);
      const lastStart = Math.floor((byName.length - 1) / PAGE_SIZE) * PAGE_SIZE;
      expect(lastStart).toBe(54);

      const { user, dialog, table, filter } = await openPicker();
      await waitFor(() => expect(dataRows(table)).toEqual(byName.slice(0, PAGE_SIZE)));
      // The key scope's container: the window's one child, holding the filter, the list and the key bar.
      const container = dialog.firstElementChild;
      if (!(container instanceof HTMLElement)) {
        throw new Error('The picker window renders no content container');
      }
      expect(container).toContainElement(filter);
      expect(container).toContainElement(table);
      // A Name cell holds plain text, no control, so the press focuses its nearest focusable ancestor.
      const nameCell = within(table).getByRole('cell', { name: rowAt(byName, 0).name });
      expect(nameCell.children).toHaveLength(0);

      await user.click(nameCell);

      expect(container).toHaveFocus();
      expect(container).not.toBe(dialog);
      expect(container).toHaveAttribute('tabindex', '-1');

      await user.keyboard('{Enter}');

      await waitFor(() => expect(dataRows(table)).toEqual(byName.slice(lastStart)));
      expect(dataRows(table)).toEqual([54, 55, 56, 57].map((index) => rowAt(byName, index)));
      expect(within(dialog).getByText('Bottom')).toBeInTheDocument();
      expect(within(dialog).queryByText('More...')).not.toBeInTheDocument();
      expect(stateRequests).toHaveLength(1);
      expect(alertRegion()).toBeEmptyDOMElement();
      expect(container).toHaveFocus();

      // Not in the tab order: Tab leaves it for the first stop, the filter,
      // and Shift+Tab from there wraps to the window's last stop, not back to it.
      await user.tab();
      expect(filter).toHaveFocus();
      await user.tab({ shift: true });
      expect(container).not.toHaveFocus();
      expect(filter).not.toHaveFocus();
      expect(dialog).toContainElement(document.activeElement as HTMLElement);
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

  describe('list status', () => {
    it('shows "Loading..." with the table busy while the list loads, then the page summary beside More...', async () => {
      const byName = await serverRows('', 'name');
      expect(byName).toHaveLength(58);
      const held = holdStatesAnswer(() => HttpResponse.json(byName));

      renderPicker();
      const dialog = await screen.findByRole('dialog', { name: DIALOG_NAME });
      const table = within(dialog).getByRole('table', { name: TABLE_NAME });
      const status = statusLine(dialog);

      expect(within(status).getByText(LOADING_LABEL)).toHaveClass('paging-indicator');
      expect(table).toHaveAttribute('aria-busy', 'true');
      expect(dataRows(table)).toEqual([]);
      expect(within(dialog).queryByText(NO_MATCH_LABEL)).not.toBeInTheDocument();
      expect(within(dialog).queryByText('More...')).not.toBeInTheDocument();

      await act(async () => {
        held.release();
        await held.settled();
      });

      await waitFor(() => expect(dataRows(table)).toEqual(byName.slice(0, PAGE_SIZE)));
      expect(table).toHaveAttribute('aria-busy', 'false');
      expect(statusLine(dialog)).toBe(status);
      expect(within(status).getByText('Showing 1 to 6 of 58 states, sorted by Name.')).toHaveClass('visually-hidden');
      expect(within(status).getByText('More...')).toHaveClass('paging-indicator');
      expect(within(status).queryByText(LOADING_LABEL)).not.toBeInTheDocument();
      expect(requestedQueries()).toEqual([{ nameContains: '', sort: 'name' }]);
      expect(statusToasts()).toBeEmptyDOMElement();
    });

    it('a filter matching no state shows "No states match."; F5 then shows nothing and sends no request', async () => {
      expect(await serverRows('ZZZ', 'name')).toEqual([]);

      const { user, dialog, table, filter } = await openPicker();
      const status = statusLine(dialog);

      await user.type(filter, 'zzz');
      await user.keyboard('{Enter}');

      await waitFor(() => expect(within(status).getByText(NO_MATCH_LABEL)).toHaveClass('paging-indicator'));
      expect(table).toHaveAttribute('aria-busy', 'false');
      expect(dataRows(table)).toEqual([]);
      for (const label of [LOADING_LABEL, FAILED_LABEL, 'More...', 'Bottom']) {
        expect(within(status).queryByText(label)).not.toBeInTheDocument();
      }
      expect(requestedQueries()).toEqual([
        { nameContains: '', sort: 'name' },
        { nameContains: 'ZZZ', sort: 'name' },
      ]);
      expect(alertRegion()).toBeEmptyDOMElement();
      expect(statusToasts()).toBeEmptyDOMElement();

      await user.keyboard('{F5}');

      // F5's blank list is intentional (PMTSTATER:257-260), so no label says otherwise.
      expect(statusLine(dialog)).toBe(status);
      expect(status).toBeEmptyDOMElement();
      expect(dataRows(table)).toEqual([]);
      expect(filter).toHaveValue('');
      expect(stateRequests).toHaveLength(2);
    });

    it('a failed load shows one alert with the problem and "States not loaded.", never "No states match."', async () => {
      const text = messageText('DEM9999');
      expect(text).toBe('Program Error! Please contact IT now.');
      server.use(http.get(STATES_PATH, () => problem(500, 'DEM9999', { instance: STATES_PATH })));

      renderPicker();
      const dialog = await screen.findByRole('dialog', { name: DIALOG_NAME });
      const table = within(dialog).getByRole('table', { name: TABLE_NAME });
      const status = statusLine(dialog);

      await waitFor(() => expect(within(status).getByText(FAILED_LABEL)).toHaveClass('paging-indicator'));
      expect(within(alertRegion()).getAllByText(text)).toHaveLength(1);
      expect(alertRegion().children).toHaveLength(1);
      expect(within(status).queryByText(NO_MATCH_LABEL)).not.toBeInTheDocument();
      expect(within(status).queryByText(LOADING_LABEL)).not.toBeInTheDocument();
      // The label is neither the problem text nor a toast.
      expect(within(status).queryByText(text)).not.toBeInTheDocument();
      expect(statusToasts()).toBeEmptyDOMElement();
      expect(table).toHaveAttribute('aria-busy', 'false');
      expect(dataRows(table)).toEqual([]);
      expect(stateRequests).toHaveLength(1);
    });

    it('the page summary follows PageDown, PageUp, Enter on the last page, F7 and each applied filter', async () => {
      const byName = await serverRows('', 'name');
      expect(byName).toHaveLength(58);

      const { user, dialog, filter } = await openPicker();
      const status = statusLine(dialog);
      const summary = (text: string) => within(status).getByText(text);

      expect(summary('Showing 1 to 6 of 58 states, sorted by Name.')).toHaveClass('visually-hidden');

      await user.keyboard('{PageDown}');
      expect(summary('Showing 7 to 12 of 58 states, sorted by Name.')).toBeInTheDocument();
      expect(within(status).getByText('More...')).toBeInTheDocument();

      await user.keyboard('{PageUp}');
      expect(summary('Showing 1 to 6 of 58 states, sorted by Name.')).toBeInTheDocument();

      // Enter with nothing to do: the last page starts at row ((58 - 1) div 6) × 6 + 1 = 55.
      await user.keyboard('{Enter}');
      expect(summary('Showing 55 to 58 of 58 states, sorted by Name.')).toBeInTheDocument();
      expect(within(status).getByText('Bottom')).toBeInTheDocument();

      await user.keyboard('{F7}');
      await waitFor(() => expect(summary('Showing 1 to 6 of 58 states, sorted by Code.')).toBeInTheDocument());
      expect(within(status).getByText('More...')).toBeInTheDocument();

      await user.type(filter, 'car');
      await user.keyboard('{Enter}');
      await waitFor(() => expect(summary('Showing 1 to 2 of 2 states, sorted by Code.')).toBeInTheDocument());
      expect(within(status).getByText('Bottom')).toBeInTheDocument();

      await user.clear(filter);
      await user.type(filter, 'york');
      await user.keyboard('{Enter}');
      await waitFor(() => expect(summary('Showing 1 of 1 state, sorted by Code.')).toBeInTheDocument());
      expect(statusLine(dialog)).toBe(status);
      expect(requestedQueries()).toEqual([
        { nameContains: '', sort: 'name' },
        { nameContains: '', sort: 'code' },
        { nameContains: 'CAR', sort: 'code' },
        { nameContains: 'YORK', sort: 'code' },
      ]);
    });
  });

  describe('obsolete requests', () => {
    it('a request failing after the window closed shows no alert and moves no focus', async () => {
      const held = holdStatesAnswer(serviceFailure);
      const list = vi.spyOn(statesApi, 'list');

      const { user, setOpen, onCancel } = renderPicker();
      const dialog = await screen.findByRole('dialog', { name: DIALOG_NAME });
      await waitForCatalog();
      expect(within(statusLine(dialog)).getByText(LOADING_LABEL)).toBeInTheDocument();
      expect(list).toHaveBeenCalledTimes(1);

      await user.keyboard('{F12}');
      expect(onCancel).toHaveBeenCalledTimes(1);
      setOpen(false);
      await waitFor(() => expect(dialog).not.toBeInTheDocument());
      const focused = document.activeElement;

      await failLate(held, list, 0);

      expect(alertRegion()).toBeEmptyDOMElement();
      expect(document.activeElement).toBe(focused);
      expect(stateRequests).toHaveLength(1);
    });

    it('F5 during a pending request that then fails shows no alert, no label and no filter error', async () => {
      const held = holdStatesAnswer(serviceFailure);
      const list = vi.spyOn(statesApi, 'list');

      const { user } = renderPicker();
      const dialog = await screen.findByRole('dialog', { name: DIALOG_NAME });
      const table = within(dialog).getByRole('table', { name: TABLE_NAME });
      const filter = within(dialog).getByRole('textbox', { name: FILTER_NAME });
      await waitForCatalog();
      await waitFor(() => expect(filter).toHaveFocus());
      const status = statusLine(dialog);
      expect(within(status).getByText(LOADING_LABEL)).toBeInTheDocument();

      await user.keyboard('{F5}');
      expect(status).toBeEmptyDOMElement();
      expect(table).toHaveAttribute('aria-busy', 'false');

      await failLate(held, list, 0);

      expect(alertRegion()).toBeEmptyDOMElement();
      expect(status).toBeEmptyDOMElement();
      expect(filter).not.toHaveAttribute('aria-invalid');
      expect(filter).toHaveFocus();
      expect(dataRows(table)).toEqual([]);
      expect(stateRequests).toHaveLength(1);
    });

    it('a newer Enter replaces a pending request: its late failure shows no alert, marks nothing and moves no focus', async () => {
      const news = await serverRows('NEW', 'name');
      expect(news.map((row) => row.name)).toEqual(['New Hampshire', 'New Jersey', 'New Mexico', 'New York']);

      const { user, dialog, table, filter } = await openPicker();
      const held = holdStatesAnswer(serviceFailure);
      const list = vi.spyOn(statesApi, 'list');

      await user.type(filter, 'car');
      await user.keyboard('{Enter}');
      expect(within(statusLine(dialog)).getByText(LOADING_LABEL)).toBeInTheDocument();
      await waitFor(() => expect(list).toHaveBeenCalledTimes(1));

      await user.clear(filter);
      await user.type(filter, 'new');
      await user.keyboard('{Enter}');
      await waitFor(() => expect(dataRows(table)).toEqual(news));

      // Focus in the newer list, where the old failure must leave it.
      const option = within(dialog).getByRole('textbox', { name: `Option for ${rowAt(news, 3).name}` });
      await user.click(option);
      expect(option).toHaveFocus();

      await failLate(held, list, 0);

      expect(alertRegion()).toBeEmptyDOMElement();
      expect(option).toHaveFocus();
      expect(filter).not.toHaveAttribute('aria-invalid');
      expect(dataRows(table)).toEqual(news);
      expect(within(statusLine(dialog)).getByText('Showing 1 to 4 of 4 states, sorted by Name.')).toBeInTheDocument();
      expect(within(dialog).queryByText(FAILED_LABEL)).not.toBeInTheDocument();
      expect(requestedQueries()).toEqual([
        { nameContains: '', sort: 'name' },
        { nameContains: 'CAR', sort: 'name' },
        { nameContains: 'NEW', sort: 'name' },
      ]);
    });

    it('a reopened window sends its own request, and the closed one failing late shows nothing', async () => {
      const byName = await serverRows('', 'name');
      const held = holdStatesAnswer(serviceFailure);
      const list = vi.spyOn(statesApi, 'list');

      const { user, setOpen } = renderPicker();
      const first = await screen.findByRole('dialog', { name: DIALOG_NAME });
      await waitForCatalog();
      await user.keyboard('{F12}');
      setOpen(false);
      await waitFor(() => expect(first).not.toBeInTheDocument());

      setOpen(true);
      const again = await screen.findByRole('dialog', { name: DIALOG_NAME });
      const table = within(again).getByRole('table', { name: TABLE_NAME });
      await waitFor(() => expect(dataRows(table)).toEqual(byName.slice(0, PAGE_SIZE)));
      expect(requestedQueries()).toEqual([
        { nameContains: '', sort: 'name' },
        { nameContains: '', sort: 'name' },
      ]);

      await failLate(held, list, 0);

      expect(alertRegion()).toBeEmptyDOMElement();
      expect(dataRows(table)).toEqual(byName.slice(0, PAGE_SIZE));
      expect(within(statusLine(again)).getByText('Showing 1 to 6 of 58 states, sorted by Name.')).toBeInTheDocument();
    });

    it('closing the window (F12) while the list request is pending aborts it, and its failing answer shows no alert', async () => {
      const held = holdStatesAnswer(serviceFailure);
      const signals = statesFetchSignals();
      const list = vi.spyOn(statesApi, 'list');

      const { user, setOpen, onCancel } = renderPicker();
      const dialog = await screen.findByRole('dialog', { name: DIALOG_NAME });
      await waitForCatalog();
      await waitFor(() => expect(stateRequests).toHaveLength(1));
      expect(signals()).toHaveLength(1);
      expect(signals()[0]?.aborted).toBe(false);

      await user.keyboard('{F12}');
      expect(onCancel).toHaveBeenCalledTimes(1);
      setOpen(false);
      await waitFor(() => expect(dialog).not.toBeInTheDocument());

      await waitFor(() => expect(signals()[0]?.aborted).toBe(true));
      await failLate(held, list, 0);

      expect(alertRegion()).toBeEmptyDOMElement();
      expect(statusToasts()).toBeEmptyDOMElement();
      expect(stateRequests).toHaveLength(1);
    });

    it('F5 while the list request is pending aborts it: no alert, no filter error and no new request', async () => {
      const held = holdStatesAnswer(serviceFailure);
      const signals = statesFetchSignals();
      const list = vi.spyOn(statesApi, 'list');

      const { user } = renderPicker();
      const dialog = await screen.findByRole('dialog', { name: DIALOG_NAME });
      const table = within(dialog).getByRole('table', { name: TABLE_NAME });
      const filter = within(dialog).getByRole('textbox', { name: FILTER_NAME });
      await waitForCatalog();
      await waitFor(() => expect(filter).toHaveFocus());
      expect(signals()).toHaveLength(1);
      expect(signals()[0]?.aborted).toBe(false);

      await user.keyboard('{F5}');

      await waitFor(() => expect(signals()[0]?.aborted).toBe(true));
      await failLate(held, list, 0);

      expect(alertRegion()).toBeEmptyDOMElement();
      expect(filter).not.toHaveAttribute('aria-invalid');
      expect(dataRows(table)).toEqual([]);
      expect(signals()).toHaveLength(1);
      expect(stateRequests).toHaveLength(1);
    });

    it('under StrictMode the load the simulated unmount aborted shows no alert, and the remount loads the list', async () => {
      const byName = await serverRows('', 'name');
      const signals = statesFetchSignals();
      const list = vi.spyOn(statesApi, 'list');

      renderPicker({ strict: true });
      const dialog = await screen.findByRole('dialog', { name: DIALOG_NAME });
      const table = within(dialog).getByRole('table', { name: TABLE_NAME });
      await waitForCatalog();
      await waitFor(() => expect(dataRows(table)).toEqual(byName.slice(0, PAGE_SIZE)));

      // Development's extra effect cycle aborted the first load; the remount's
      // load answered. Both calls have settled, and the hook has handled both.
      const [aborted, answered, ...further] = await settleListCalls(list);
      expect(further).toEqual([]);
      expect(aborted?.status).toBe('rejected');
      expect(aborted?.status === 'rejected' ? aborted.reason : undefined).toHaveProperty('name', 'AbortError');
      expect(answered).toEqual({ status: 'fulfilled', value: byName });
      // Every exchange that reached MSW has finished, so no resolver is still
      // running. The aborted load's signal aborts in the same commit as its
      // `fetch`, before the request goes out, so it may never reach MSW at
      // all (with MSW 3.0.2 under Node's fetch it does not).
      await waitFor(() => expect(endedStateRequests.map(String)).toEqual(stateRequests.map(String)));
      expect(requestedQueries()).toContainEqual({ nameContains: '', sort: 'name' });

      await waitFor(() =>
        expect(within(statusLine(dialog)).getByText('Showing 1 to 6 of 58 states, sorted by Name.')).toBeInTheDocument(),
      );
      expect(alertRegion()).toBeEmptyDOMElement();
      const sent = signals();
      expect(sent).toHaveLength(2);
      expect(sent[0]?.aborted).toBe(true);
      expect(sent[1]?.aborted).toBe(false);
    });

    it('a current failure is still presented exactly once: one alert and "States not loaded.", the filter not marked', async () => {
      const text = messageText('DEM9999');
      expect(text).toBe('Program Error! Please contact IT now.');

      const { user, dialog, table, filter } = await openPicker();
      server.use(http.get(STATES_PATH, () => serviceFailure(), { once: true }));

      await user.type(filter, 'car');
      await user.keyboard('{Enter}');

      await waitFor(() => expect(within(statusLine(dialog)).getByText(FAILED_LABEL)).toBeInTheDocument());
      expect(within(alertRegion()).getAllByText(text)).toHaveLength(1);
      expect(alertRegion().children).toHaveLength(1);
      expect(filter).not.toHaveAttribute('aria-invalid');
      expect(filter).not.toHaveAccessibleDescription();
      expect(filter).toHaveFocus();
      expect(filter).toHaveValue('CAR');
      expect(dataRows(table)).toEqual([]);
      expect(requestedQueries()).toEqual([
        { nameContains: '', sort: 'name' },
        { nameContains: 'CAR', sort: 'name' },
      ]);
    });

    it('defensive boundary: an 11-character filter set past maxLength is answered 400 APP0400 on nameContains, presented once on the filter', async () => {
      // StateService.list rejects a filter of more than 10 code points; the
      // default handler answers exactly that, so nothing here is overridden.
      const reason = 'nameContains must be at most 10 characters';
      const text = messageText('APP0400', [reason]);
      expect(text).toBe(`Request is not valid: ${reason}`);
      const overLong = 'north carol';
      expect([...overLong]).toHaveLength(11);

      const { user, dialog, table, filter } = await openPicker();
      // SC_NAME is 10A (PMTSTATED:88): the browser's maxLength stops a user at
      // ten characters, so no keyed entry reaches the API's length check.
      expect(filter).toHaveAttribute('maxlength', '10');
      // Defensive boundary only: a scripted value, which maxLength does not
      // limit, goes through the field's own change handler (uppercased as
      // typed), as no user can enter it.
      fireEvent.change(filter, { target: { value: overLong } });
      expect(filter).toHaveValue('NORTH CAROL');

      // Enter on its legend key, focused as a keyboard user reaches it, so
      // focus starts away from the filter and the presenter's move back to it
      // is observable (a mouse press on a legend key keeps focus in the field).
      const enterKey = within(dialog).getByRole('button', { name: 'Enter' });
      await act(async () => {
        enterKey.focus();
      });
      expect(enterKey).toHaveFocus();
      await user.keyboard('{Enter}');

      await waitFor(() => expect(within(statusLine(dialog)).getByText(FAILED_LABEL)).toBeInTheDocument());
      expect(within(alertRegion()).getAllByText(text)).toHaveLength(1);
      expect(alertRegion().children).toHaveLength(1);
      expect(filter).toHaveAttribute('aria-invalid', 'true');
      expect(filter).toHaveAccessibleDescription(text);
      expect(filter).toHaveFocus();
      expect(dataRows(table)).toEqual([]);
      expect(requestedQueries()).toEqual([
        { nameContains: '', sort: 'name' },
        { nameContains: 'NORTH CAROL', sort: 'name' },
      ]);
    });
  });
});
