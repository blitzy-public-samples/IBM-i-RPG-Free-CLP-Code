/**
 * Tests of the Customer picker: `CustomerPicker` (`./CustomerPicker`), the
 * customer search in Selection mode inside a modal window, rendered with its
 * real collaborators: `CustomerSearchPanel` and the windows it opens, the
 * message catalog, the toast host and the key scope stack.
 *
 * What it replaces. PMTCUSTR called with mode `S` and its return parameter
 * (5250_Subfile/PMTCUSTR.SQLRPGLE, 5250_Subfile/PMTCUSTD.DSPF):
 * - **Init** (:750-767): with `pParmType = 'S'` and a second parameter, the
 *   function line is "Selection", the options line `1=Select 5=Display`, and
 *   only Opt1_OK is set; Maint_OK never is, so 2=Edit and F6=Add are absent
 *   whatever the caller is allowed elsewhere.
 * - **First page** (:243, :252-256): only Inquiry loads on entry; Selection
 *   waits for the first Enter.
 * - **ProcessOption** (:427-445): option 1 moves SF_CUST_H into pCustID,
 *   closes down and returns, so the caller receives exactly one id; option 5
 *   displays; any other option is DEM0004 with the option typed.
 * - **ProcessFunctionKey** (:355-420): F3 and F12 close down and return with
 *   pCustID still cleared (:245-249); F6 is not enabled outside `M` and
 *   answers DEM0003.
 *
 * What is pinned down here (AAP 0.8.3, `CustomerPicker.test.tsx`: "Only
 * options 1 and 5; `onSelect` fires once with the id; F12 and Escape →
 * `onCancel`"; the picker contract of AAP 0.3.8):
 * - the window is named by its header, "Customer Master" and "Selection",
 *   offers `1=Select 5=Display`, and opens with focus in "Name starts with:";
 * - nothing is searched before Enter, and every row then offers only Select
 *   and Display, for a MAINTENANCE user too, with no F6=Add;
 * - option 1, typed or through the row's Select button, calls `onSelect`
 *   once per opening with that row's id;
 * - option 2 is DEM0004 and opens no window; option 5 opens the display
 *   window over the picker;
 * - F3, F12 and Escape call `onCancel` and return no id; F6 is DEM0003;
 * - closed, the picker renders nothing and sends no request; a host that
 *   closes it in its callbacks gets focus back and a fresh search on reopen;
 * - every panel and every opening owns its list: two Selection panels with
 *   the same criteria under one query client each request their own first
 *   page and page independently, and a picker reopened after paging requests
 *   and shows page 1 again.
 *
 * Fixtures. The default `GET /api/customers` handler of `src/test/handlers.ts`
 * serves the 30 seed rows of its `customers` fixture (23 active, 12 per page)
 * and the default `GET /api/customers/:custId` handler their records. Every
 * `/api` request is logged from MSW's `request:start` event in
 * {@link traffic}, so the default handlers keep answering. The message texts
 * are the CUSTMSGF catalog served by the default `/api/messages` handler.
 *
 * Evidence. These tests are derived from reading the IBM i source and the
 * plan; they are not executed against the IBM i program and do not establish
 * behavioural equivalence with it.
 *
 * Harness. The providers are mounted in the order `src/App.tsx` uses, with a
 * fresh `QueryClient` per test and the toast `clear` wired to the key scope's
 * `onBeforeCommand`, under a `MemoryRouter`. The session is the MAINTENANCE
 * demo user, which proves the role adds nothing to Selection. A probe reads
 * `useMessages().ready`, so message assertions start only once the catalog
 * has loaded (until then `format` returns the bare code).
 */
import { useState } from 'react';
import type { ReactElement, ReactNode } from 'react';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type { UserEvent } from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { Mock } from 'vitest';
import { setCredentials } from '../../api/client';
import type { CustomerSummaryResponse } from '../../api/customers';
import { AuthProvider } from '../../auth/AuthProvider';
import { ToastProvider, useToasts } from '../../components/ToastRegion';
import { KeyScopeProvider } from '../../keyboard/KeyScopeProvider';
import { MessageCatalogProvider, useMessages } from '../../messages/MessageCatalogProvider';
import { customers, messageText, users } from '../../test/handlers';
import { server } from '../../test/server';
import { CustomerPicker } from './CustomerPicker';
import type { CustomerPickerProps } from './CustomerPicker';
import { CustomerSearchPanel } from './CustomerSearchPage';

// ---------------------------------------------------------------------------
// Fixtures and constants
// ---------------------------------------------------------------------------

/**
 * The MAINTENANCE demo user (`sales`). Selection is open to every signed-in
 * user; signing in with the widest role shows that the role adds neither
 * option 2 nor F6, as source `S` mode never sets Maint_OK.
 */
const MAINTENANCE_USER = (() => {
  const account = users.find((candidate) => candidate.roles.includes('MAINTENANCE'));
  if (account === undefined) {
    throw new Error('The users fixture holds no MAINTENANCE user');
  }
  return account;
})();

/** Rows per page: SFLPAG 12 of PMTCUSTD, the `size` the panel sends. */
const PAGE_SIZE = 12;

/** The first page of the active-only list, as the default search handler serves it. */
const FIRST_PAGE_ROWS: readonly CustomerSummaryResponse[] = customers
  .filter((row) => row.active === 'Y')
  .slice(0, PAGE_SIZE);

/** The second and last page of the active-only list (11 rows), reached with PageDown. */
const SECOND_PAGE_ROWS: readonly CustomerSummaryResponse[] = customers
  .filter((row) => row.active === 'Y')
  .slice(PAGE_SIZE, 2 * PAGE_SIZE);

/** The seed row of `name`; throws when the fixture holds none. */
function seedRow(name: string): CustomerSummaryResponse {
  const row = customers.find((candidate) => candidate.name === name);
  if (row === undefined) {
    throw new Error(`The customers fixture holds no row named ${name}`);
  }
  return row;
}

/** The seed rows the e2e selection flow also uses: original ids 6 (backslashes) and 3 (apostrophe). */
const URNA = seedRow('URNA \\NUNC\\ COMPANY');
const NIBH = seedRow("NIBH L'LOR COMPANY");

/** A row on the first page of the active-only list. */
const FIRST_ROW = ((): CustomerSummaryResponse => {
  const row = FIRST_PAGE_ROWS[0];
  if (row === undefined) {
    throw new Error('The customers fixture holds no active row');
  }
  return row;
})();

/** Accessible name of the picker window: its ScreenHeader title and function line. */
const PICKER_NAME = 'Customer Master Selection';

/** Accessible name of the display window option 5 opens over the picker. */
const DISPLAY_NAME = 'Customer Master Displaying Customer';

/** Label of the Name criterion (PMTCUSTD SC_NAME), where the cursor starts. */
const NAME_FILTER = 'Name starts with:';

/** The search endpoint, relative as the SPA calls it. */
const SEARCH_PATH = '/api/customers';

/** The test id of {@link CatalogProbe}. */
const CATALOG_PROBE_ID = 'catalog-probe';

// ---------------------------------------------------------------------------
// Request log
// ---------------------------------------------------------------------------

/** One request that reached MSW: its method, path and query parameters. */
interface RecordedRequest {
  method: string;
  path: string;
  params: URLSearchParams;
}

/** Every `/api` request since the test began, the public catalog excepted, in order. */
const traffic: RecordedRequest[] = [];

/** MSW `request:start` listener: runs before any handler, so every default handler keeps answering. */
function recordRequest({ request }: { request: Request }): void {
  const url = new URL(request.url);
  if (url.pathname.startsWith('/api/') && url.pathname !== '/api/messages') {
    traffic.push({ method: request.method, path: url.pathname, params: url.searchParams });
  }
}

/** The recorded searches (`GET /api/customers`) as their query parameters. */
function searches(): Record<string, string | null>[] {
  return traffic
    .filter((entry) => entry.method === 'GET' && entry.path === SEARCH_PATH)
    .map(({ params }) => ({
      name: params.get('name'),
      city: params.get('city'),
      state: params.get('state'),
      includeInactive: params.get('includeInactive'),
      size: params.get('size'),
      cursor: params.get('cursor'),
    }));
}

/** The query of a first page with the given Name and inactive rows excluded, as the panel sends it. */
function firstPageQuery(name = ''): Record<string, string | null> {
  return { name, city: '', state: '', includeInactive: 'false', size: String(PAGE_SIZE), cursor: null };
}

beforeEach(() => {
  traffic.length = 0;
  // Stored as after a real sign-in: every customer route answers 401 without them.
  setCredentials({ username: MAINTENANCE_USER.username, password: MAINTENANCE_USER.password });
  server.events.on('request:start', recordRequest);
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

/** Waits until the message catalog has loaded, so `format` yields texts rather than codes. */
async function waitForCatalog(): Promise<void> {
  await waitFor(() => expect(screen.getByTestId(CATALOG_PROBE_ID)).toHaveAttribute('data-ready', 'true'));
}

/**
 * Renders `ui` inside the application's providers, signed in as the
 * MAINTENANCE user: a fresh query client (no retries), the message catalog,
 * the toast host, the key scope stack, a router and the session. Resolves
 * once the catalog has loaded.
 */
async function renderWithProviders(ui: ReactElement): Promise<UserEvent> {
  const user = userEvent.setup();
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(
    <QueryClientProvider client={queryClient}>
      <MessageCatalogProvider>
        <ToastProvider>
          <KeyedScreens>
            <MemoryRouter>
              <AuthProvider initialSession={{ username: MAINTENANCE_USER.username, roles: [...MAINTENANCE_USER.roles] }}>
                <CatalogProbe />
                {ui}
              </AuthProvider>
            </MemoryRouter>
          </KeyedScreens>
        </ToastProvider>
      </MessageCatalogProvider>
    </QueryClientProvider>,
  );
  await waitForCatalog();
  return user;
}

/** The two picker callbacks of one render, as fresh mocks. */
interface PickerCallbacks {
  onSelect: Mock<CustomerPickerProps['onSelect']>;
  onCancel: Mock<CustomerPickerProps['onCancel']>;
}

/** What {@link openPicker} returns. */
interface OpenPicker extends PickerCallbacks {
  user: UserEvent;
  dialog: HTMLElement;
}

/**
 * Renders the open picker with fresh callbacks that leave it open (so a
 * second selection in the same opening can be attempted), and waits until it
 * is ready to be keyed: the catalog has loaded and the Name filter holds
 * focus.
 */
async function openPicker(initialName?: string): Promise<OpenPicker> {
  const onSelect = vi.fn<CustomerPickerProps['onSelect']>();
  const onCancel = vi.fn<CustomerPickerProps['onCancel']>();
  const user = await renderWithProviders(
    <CustomerPicker open initialName={initialName} onSelect={onSelect} onCancel={onCancel} />,
  );
  const dialog = await screen.findByRole('dialog', { name: PICKER_NAME });
  await waitFor(() => expect(nameFilter(dialog)).toHaveFocus());
  return { user, dialog, onSelect, onCancel };
}

/** Props of {@link HostForm}: spies that see each callback before the host acts on it. */
interface HostFormProps {
  onSelectSeen: (custId: string) => void;
  onCancelSeen: () => void;
}

/**
 * A minimal host, as the "Order entry" demo form hosts the picker: a
 * "Customer id +" field and a prompt button. It closes the picker in both
 * callbacks, as the contract asks; a selection writes the id into the
 * field, a cancel leaves the field as it was.
 */
function HostForm({ onSelectSeen, onCancelSeen }: HostFormProps) {
  const [custId, setCustId] = useState('');
  const [open, setOpen] = useState(false);
  return (
    <div>
      <label htmlFor="host-customer-id">Customer id +</label>
      <input id="host-customer-id" value={custId} readOnly />
      <button type="button" onClick={() => setOpen(true)}>
        Prompt customer
      </button>
      <CustomerPicker
        open={open}
        onSelect={(selected) => {
          onSelectSeen(selected);
          setCustId(selected);
          setOpen(false);
        }}
        onCancel={() => {
          onCancelSeen();
          setOpen(false);
        }}
      />
    </div>
  );
}

/** Accessible names of the two regions {@link TwoSelectionPanels} renders, in order. */
const PANEL_REGIONS = ['First selection', 'Second selection'] as const;

/**
 * Two Selection-mode search panels mounted at the same time under the one
 * query client of {@link renderWithProviders}, as two Customer pickers on one
 * screen would hold them: each in its own named region, with its own header
 * ids. They are not wrapped in `Dialog`, because an open window makes the
 * screen beneath it inert and so only one picker window can be driven at a
 * time. Only the second panel's key scope is topmost, so each panel is driven
 * through its own legend buttons, which run the very handlers its keys run.
 */
function TwoSelectionPanels() {
  return (
    <>
      {PANEL_REGIONS.map((name, index) => (
        <div key={name} role="region" aria-label={name}>
          <CustomerSearchPanel
            mode="selection"
            headerId={`selection-panel-${index}`}
            onSelect={() => undefined}
            onExit={() => undefined}
          />
        </div>
      ))}
    </>
  );
}

// ---------------------------------------------------------------------------
// Queries
// ---------------------------------------------------------------------------

/** The Name criterion of the picker (PMTCUSTD SC_NAME), by its exact label inside the criteria group. */
function nameFilter(dialog: HTMLElement): HTMLInputElement {
  const group = within(dialog).getByRole('group', { name: 'Search criteria' });
  const element = within(group).getByLabelText(NAME_FILTER);
  if (!(element instanceof HTMLInputElement)) {
    throw new Error(`The ${NAME_FILTER} label does not name an input`);
  }
  return element;
}

/** The picker's results table (the PMTCUSTD subfile), named by its caption. */
function resultsTable(dialog: HTMLElement): HTMLElement {
  return within(dialog).getByRole('table', { name: 'Customers' });
}

/** The customer names on the page shown, in list order, read from each row's Opt label "Option for <name>". */
function shownNames(dialog: HTMLElement): string[] {
  return within(resultsTable(dialog))
    .queryAllByRole('textbox')
    .map((input) => {
      const label = input instanceof HTMLInputElement ? input.labels?.[0]?.textContent : undefined;
      return (label ?? '').replace(/^Option for /, '');
    });
}

/** The Opt input of the row showing `name`. */
function optionInput(dialog: HTMLElement, name: string): HTMLInputElement {
  const element = within(resultsTable(dialog)).getByRole('textbox', { name: `Option for ${name}` });
  if (!(element instanceof HTMLInputElement)) {
    throw new Error(`The option of ${name} is not an input`);
  }
  return element;
}

/** The accessible names of the action buttons of the row showing `name`, in order. */
function rowActions(dialog: HTMLElement, name: string): string[] {
  const row = optionInput(dialog, name).closest('tr');
  if (row === null) {
    throw new Error(`The option of ${name} is not in a table row`);
  }
  return within(row)
    .getAllByRole('button')
    .map((button) => button.textContent ?? '');
}

/** The picker's own function-key legend, not that of a window opened over it. */
function pickerKeys(dialog: HTMLElement): HTMLElement {
  const bar = within(dialog)
    .getAllByRole('toolbar', { name: 'Function keys' })
    .find((candidate) => candidate.closest('dialog') === dialog);
  if (bar === undefined) {
    throw new Error('The picker shows no function-key legend');
  }
  return bar;
}

/** One region of {@link TwoSelectionPanels}, by its accessible name. */
function panelRegion(name: (typeof PANEL_REGIONS)[number]): HTMLElement {
  return screen.getByRole('region', { name });
}

/** Presses the legend button `label` of the one search panel inside `region`. */
async function pressLegendKey(user: UserEvent, region: HTMLElement, label: string): Promise<void> {
  const bar = within(region).getByRole('toolbar', { name: 'Function keys' });
  await user.click(within(bar).getByRole('button', { name: label }));
}

/** The shared alert region of the toast host, where DEM0003 and DEM0004 are published. */
function alertRegion(): HTMLElement {
  return screen.getByRole('alert');
}

/** Runs the first search of an opening (Enter) and waits until the page it returns is shown. */
async function searchWithEnter(user: UserEvent, dialog: HTMLElement, expected: readonly string[]): Promise<void> {
  await user.keyboard('{Enter}');
  await waitFor(() => expect(shownNames(dialog)).toEqual(expected));
}

/** Types `text` into the Name filter, then searches with Enter and waits for `expected`. */
async function searchByName(
  user: UserEvent,
  dialog: HTMLElement,
  text: string,
  expected: readonly string[],
): Promise<void> {
  await user.type(nameFilter(dialog), text);
  await searchWithEnter(user, dialog, expected);
}


// ---------------------------------------------------------------------------
// Specs
// ---------------------------------------------------------------------------

describe('CustomerPicker', () => {
  it('starts from the fixtures: a MAINTENANCE user, a full first page, and the two seed rows used below', () => {
    expect(MAINTENANCE_USER.roles).toEqual(['MAINTENANCE']);
    expect(FIRST_PAGE_ROWS).toHaveLength(PAGE_SIZE);
    expect(URNA).toMatchObject({ custId: 'AAAG', active: 'Y' });
    expect(NIBH).toMatchObject({ custId: 'AAAD', active: 'Y' });
    expect(messageText('DEM0003')).toBe('Key is not active now');
    expect(messageText('DEM0004', ['2'])).toBe('2 is not a valid option at this time.');
  });

  // -------------------------------------------------------------------------
  // Opening (Init :750-767, first page :243, :252-256)
  // -------------------------------------------------------------------------

  describe('opening', () => {
    it('is a modal window named "Customer Master" / "Selection", offers "1=Select 5=Display" and opens in "Name starts with:"', async () => {
      const { dialog } = await openPicker();

      expect(dialog).toHaveAttribute('aria-modal', 'true');
      expect(within(dialog).getByRole('heading', { name: 'Customer Master' })).toBeInTheDocument();
      expect(within(dialog).getByText('Selection', { selector: 'p' })).toBeInTheDocument();
      expect(within(dialog).getByText('1=Select 5=Display', { selector: 'p' })).toBeInTheDocument();
      expect(within(dialog).queryByText('2=Edit 5=Display')).not.toBeInTheDocument();
      // The DDS USER keyword: the signed-in user in the header.
      expect(within(dialog).getByText(MAINTENANCE_USER.username)).toBeInTheDocument();
      expect(nameFilter(dialog)).toHaveFocus();
      expect(nameFilter(dialog)).toHaveValue('');
      expect(alertRegion()).toBeEmptyDOMElement();
    });

    it('sends no request before Enter; then every row offers only Select and Display, and F6=Add is not shown', async () => {
      const { user, dialog } = await openPicker();

      // NewSearchCriteria is on at entry; only Inquiry loads at once.
      expect(traffic).toEqual([]);
      expect(shownNames(dialog)).toEqual([]);
      expect(within(dialog).queryByText(/^(More\.\.\.|Bottom)$/)).not.toBeInTheDocument();

      await searchWithEnter(
        user,
        dialog,
        FIRST_PAGE_ROWS.map((row) => row.name),
      );

      expect(searches()).toEqual([firstPageQuery()]);
      expect(within(dialog).getByText('More...')).toBeInTheDocument();
      // Options 1 and 5 only, whatever the role: S mode never sets Maint_OK.
      for (const row of FIRST_PAGE_ROWS) {
        expect(rowActions(dialog, row.name)).toEqual([`Select ${row.name}`, `Display ${row.name}`]);
      }
      expect(within(dialog).queryByRole('button', { name: /^Edit / })).not.toBeInTheDocument();
      expect(within(pickerKeys(dialog)).queryByRole('button', { name: 'F6=Add' })).not.toBeInTheDocument();
      expect(within(pickerKeys(dialog)).getByRole('button', { name: 'F12=Cancel' })).toBeInTheDocument();
    });

    it('presets "Name starts with:" from initialName and still waits for Enter to search it', async () => {
      const { user, dialog } = await openPicker('NIBH');

      expect(nameFilter(dialog)).toHaveValue('NIBH');
      expect(traffic).toEqual([]);

      await searchWithEnter(user, dialog, [NIBH.name]);

      expect(searches()).toEqual([firstPageQuery('NIBH')]);
    });

    it('renders nothing and sends no request while closed', async () => {
      const onSelect = vi.fn<CustomerPickerProps['onSelect']>();
      const onCancel = vi.fn<CustomerPickerProps['onCancel']>();
      await renderWithProviders(<CustomerPicker open={false} onSelect={onSelect} onCancel={onCancel} />);

      expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
      expect(screen.queryByRole('table')).not.toBeInTheDocument();
      expect(screen.queryByLabelText(NAME_FILTER)).not.toBeInTheDocument();
      expect(traffic).toEqual([]);
      expect(onSelect).not.toHaveBeenCalled();
      expect(onCancel).not.toHaveBeenCalled();
    });
  });

  // -------------------------------------------------------------------------
  // Option 1 (ProcessOption :439-445)
  // -------------------------------------------------------------------------

  describe('option 1 (Select)', () => {
    it("typed 1 and Enter call onSelect once with the row's id; a second Enter or Select in the same opening calls nothing", async () => {
      const { user, dialog, onSelect, onCancel } = await openPicker();
      await searchByName(user, dialog, 'URNA', [URNA.name]);

      await user.type(optionInput(dialog, URNA.name), '1');
      await user.keyboard('{Enter}');

      expect(onSelect).toHaveBeenCalledTimes(1);
      expect(onSelect).toHaveBeenCalledWith(URNA.custId);
      // The option is consumed with the selection.
      expect(optionInput(dialog, URNA.name)).toHaveValue('');

      // The host keeps the picker open here, so the same opening is keyed again.
      await user.keyboard('{Enter}');
      await user.click(within(dialog).getByRole('button', { name: `Select ${URNA.name}` }));

      expect(onSelect).toHaveBeenCalledTimes(1);
      expect(onCancel).not.toHaveBeenCalled();
      expect(screen.getAllByRole('dialog')).toEqual([dialog]);
      expect(searches()).toEqual([firstPageQuery('URNA')]);
      expect(alertRegion()).toBeEmptyDOMElement();
    });

    it("the row's Select button calls onSelect once with that row's id", async () => {
      const { user, dialog, onSelect, onCancel } = await openPicker();
      await searchWithEnter(
        user,
        dialog,
        FIRST_PAGE_ROWS.map((row) => row.name),
      );

      await user.click(within(dialog).getByRole('button', { name: `Select ${FIRST_ROW.name}` }));

      expect(onSelect).toHaveBeenCalledTimes(1);
      expect(onSelect).toHaveBeenCalledWith(FIRST_ROW.custId);

      // A second press (a double click) in the same opening returns nothing more.
      await user.click(within(dialog).getByRole('button', { name: `Select ${FIRST_ROW.name}` }));

      expect(onSelect).toHaveBeenCalledTimes(1);
      expect(onCancel).not.toHaveBeenCalled();
      expect(screen.getAllByRole('dialog')).toEqual([dialog]);
    });
  });

  // -------------------------------------------------------------------------
  // Options 2 and 5 (ProcessOption :446-499)
  // -------------------------------------------------------------------------

  describe('options 2 and 5', () => {
    it('option 2 shows DEM0004 "2 is not a valid option at this time.", marks that row and opens no window', async () => {
      const { user, dialog, onSelect, onCancel } = await openPicker();
      await searchWithEnter(
        user,
        dialog,
        FIRST_PAGE_ROWS.map((row) => row.name),
      );
      const option = optionInput(dialog, FIRST_ROW.name);

      await user.type(option, '2');
      await user.keyboard('{Enter}');

      expect(await within(alertRegion()).findByText('2 is not a valid option at this time.')).toBeInTheDocument();
      expect(option).toHaveAttribute('aria-invalid', 'true');
      expect(option).toHaveValue('2');
      await waitFor(() => expect(option).toHaveFocus());
      expect(screen.getAllByRole('dialog')).toEqual([dialog]);
      expect(traffic.filter((entry) => entry.path === `${SEARCH_PATH}/${FIRST_ROW.custId}`)).toEqual([]);
      expect(onSelect).not.toHaveBeenCalled();
      expect(onCancel).not.toHaveBeenCalled();
    });

    it('option 5 opens the display window over the picker; closing it returns to the picker with no id returned', async () => {
      const { user, dialog, onSelect, onCancel } = await openPicker();
      await searchWithEnter(
        user,
        dialog,
        FIRST_PAGE_ROWS.map((row) => row.name),
      );

      await user.type(optionInput(dialog, FIRST_ROW.name), '5');
      await user.keyboard('{Enter}');

      const display = await screen.findByRole('dialog', { name: DISPLAY_NAME });
      await waitFor(() => expect(within(display).getByLabelText('Name')).toHaveValue(FIRST_ROW.name));
      expect(traffic.filter((entry) => entry.method === 'GET' && entry.path === `${SEARCH_PATH}/${FIRST_ROW.custId}`)).toHaveLength(1);

      // Every key the display window enables closes it; Enter from its Name field.
      await user.click(within(display).getByLabelText('Name'));
      await user.keyboard('{Enter}');

      await waitFor(() => expect(display).not.toBeInTheDocument());
      expect(screen.getAllByRole('dialog')).toEqual([dialog]);
      expect(optionInput(dialog, FIRST_ROW.name)).toHaveValue('');
      expect(shownNames(dialog)).toEqual(FIRST_PAGE_ROWS.map((row) => row.name));
      expect(onSelect).not.toHaveBeenCalled();
      expect(onCancel).not.toHaveBeenCalled();
    });
  });

  // -------------------------------------------------------------------------
  // Keys (ProcessFunctionKey :355-420)
  // -------------------------------------------------------------------------

  describe('keys', () => {
    it.each([
      ['F12', '{F12}'],
      ['Escape', '{Escape}'],
      ['F3', '{F3}'],
    ])('%s calls onCancel once and returns no id', async (_key, keys) => {
      const { user, dialog, onSelect, onCancel } = await openPicker();
      await searchByName(user, dialog, 'URNA', [URNA.name]);
      await user.type(optionInput(dialog, URNA.name), '1');

      await user.keyboard(keys);

      expect(onCancel).toHaveBeenCalledTimes(1);
      expect(onCancel).toHaveBeenCalledWith();
      expect(onSelect).not.toHaveBeenCalled();
      expect(searches()).toEqual([firstPageQuery('URNA')]);
      expect(alertRegion()).toBeEmptyDOMElement();
    });

    it('F6, which Selection does not enable, shows DEM0003 "Key is not active now" and opens no window', async () => {
      const { user, dialog, onSelect, onCancel } = await openPicker();

      await user.keyboard('{F6}');

      expect(within(alertRegion()).getByText('Key is not active now')).toBeInTheDocument();
      expect(screen.getAllByRole('dialog')).toEqual([dialog]);
      expect(traffic).toEqual([]);
      expect(onSelect).not.toHaveBeenCalled();
      expect(onCancel).not.toHaveBeenCalled();
    });
  });

  // -------------------------------------------------------------------------
  // In a host (the Selection demo form)
  // -------------------------------------------------------------------------

  describe('in a host form', () => {
    it('the host closes it in each callback: the id fills the field, focus returns to the invoker, a reopened picker starts fresh, Cancel leaves the field', async () => {
      const onSelectSeen = vi.fn<(custId: string) => void>();
      const onCancelSeen = vi.fn<() => void>();
      const user = await renderWithProviders(<HostForm onSelectSeen={onSelectSeen} onCancelSeen={onCancelSeen} />);
      const field = screen.getByLabelText('Customer id +');
      const prompt = screen.getByRole('button', { name: 'Prompt customer' });

      await user.click(prompt);
      const first = await screen.findByRole('dialog', { name: PICKER_NAME });
      await waitFor(() => expect(nameFilter(first)).toHaveFocus());
      await searchByName(user, first, 'URNA', [URNA.name]);
      await user.type(optionInput(first, URNA.name), '1');
      await user.keyboard('{Enter}');

      await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
      expect(onSelectSeen).toHaveBeenCalledTimes(1);
      expect(onSelectSeen).toHaveBeenCalledWith(URNA.custId);
      expect(field).toHaveValue(URNA.custId);
      expect(prompt).toHaveFocus();

      // Reopened: a new opening, as PMTCUSTR ran Init on every call.
      await user.click(prompt);
      const second = await screen.findByRole('dialog', { name: PICKER_NAME });
      await waitFor(() => expect(nameFilter(second)).toHaveFocus());
      expect(nameFilter(second)).toHaveValue('');
      expect(shownNames(second)).toEqual([]);
      expect(searches()).toHaveLength(1);

      await user.keyboard('{F12}');

      await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
      expect(onCancelSeen).toHaveBeenCalledTimes(1);
      expect(onSelectSeen).toHaveBeenCalledTimes(1);
      expect(field).toHaveValue(URNA.custId);
      expect(prompt).toHaveFocus();
    });
  });

  // -------------------------------------------------------------------------
  // List isolation: every panel and every opening owns its list
  // -------------------------------------------------------------------------

  describe('list isolation', () => {
    it('two panels open at once with the same criteria each request, page and keep their own list', async () => {
      const user = await renderWithProviders(<TwoSelectionPanels />);
      const first = panelRegion('First selection');
      const second = panelRegion('Second selection');
      const pageOne = FIRST_PAGE_ROWS.map((row) => row.name);
      const pageTwo = SECOND_PAGE_ROWS.map((row) => row.name);

      await pressLegendKey(user, first, 'Enter');
      await waitFor(() => expect(shownNames(first)).toEqual(pageOne));
      await pressLegendKey(user, second, 'Enter');
      await waitFor(() => expect(shownNames(second)).toEqual(pageOne));

      // Identical criteria, yet each panel's first page is its own request.
      expect(searches()).toEqual([firstPageQuery(), firstPageQuery()]);

      await pressLegendKey(user, first, 'Page Down');
      await waitFor(() => expect(shownNames(first)).toEqual(pageTwo));

      expect(within(first).getByText('Bottom')).toBeInTheDocument();
      // The second panel keeps its page 1 and its "More..." indicator.
      expect(shownNames(second)).toEqual(pageOne);
      expect(within(second).getByText('More...')).toBeInTheDocument();
      expect(within(second).queryByText('Bottom')).not.toBeInTheDocument();
      const afterFirstPageDown = searches();
      expect(afterFirstPageDown).toHaveLength(3);
      expect(afterFirstPageDown[2]?.cursor).toEqual(expect.any(String));

      // The first panel's continuation does not serve the second panel: the
      // second panel's PageDown requests its own page 2, with the same cursor.
      await pressLegendKey(user, second, 'Page Down');
      await waitFor(() => expect(shownNames(second)).toEqual(pageTwo));

      const afterSecondPageDown = searches();
      expect(afterSecondPageDown).toHaveLength(4);
      expect(afterSecondPageDown[3]).toEqual(afterFirstPageDown[2]);
      expect(afterSecondPageDown.filter((query) => query.cursor === null)).toHaveLength(2);
      expect(shownNames(first)).toEqual(pageTwo);
      expect(alertRegion()).toBeEmptyDOMElement();
    });

    it('a picker reopened after paging starts anew: the same criteria request page 1 again and show it', async () => {
      const onSelectSeen = vi.fn<(custId: string) => void>();
      const onCancelSeen = vi.fn<() => void>();
      const user = await renderWithProviders(<HostForm onSelectSeen={onSelectSeen} onCancelSeen={onCancelSeen} />);
      const prompt = screen.getByRole('button', { name: 'Prompt customer' });
      const pageOne = FIRST_PAGE_ROWS.map((row) => row.name);

      await user.click(prompt);
      const first = await screen.findByRole('dialog', { name: PICKER_NAME });
      await waitFor(() => expect(nameFilter(first)).toHaveFocus());
      await searchWithEnter(user, first, pageOne);
      await user.keyboard('{PageDown}');
      await waitFor(() => expect(shownNames(first)).toEqual(SECOND_PAGE_ROWS.map((row) => row.name)));
      await user.keyboard('{F12}');
      await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());

      expect(onCancelSeen).toHaveBeenCalledTimes(1);
      expect(searches()).toHaveLength(2);

      // Reopened with the same (blank) criteria: nothing is shown before Enter,
      // and Enter requests the first page again rather than reusing the pages
      // the closed opening loaded.
      await user.click(prompt);
      const second = await screen.findByRole('dialog', { name: PICKER_NAME });
      await waitFor(() => expect(nameFilter(second)).toHaveFocus());
      expect(shownNames(second)).toEqual([]);
      expect(searches()).toHaveLength(2);

      await searchWithEnter(user, second, pageOne);

      const recorded = searches();
      expect(recorded).toHaveLength(3);
      expect(recorded[0]).toEqual(firstPageQuery());
      expect(recorded[1]?.cursor).toEqual(expect.any(String));
      expect(recorded[2]).toEqual(firstPageQuery());
      expect(within(second).getByText('More...')).toBeInTheDocument();
      expect(onSelectSeen).not.toHaveBeenCalled();
    });
  });
});

