/**
 * Tests of `CustomerPicker` with its real collaborators (`CustomerSearchPanel`
 * and the windows it opens, the message catalog, the toast host and the key
 * scope stack), and of its production host, the Order entry form
 * (`HostFormDemoPage`) at `/demo/selection`. Source: PMTCUSTR called with mode
 * `S` (5250_Subfile/PMTCUSTR.SQLRPGLE: Init :750-767, entry :243-256,
 * ProcessOption :427-499, ProcessFunctionKey :355-420). Spec: AAP 0.8.3 and
 * the picker contract of AAP 0.3.8.
 *
 * Evidence. These tests are derived from reading the IBM i source and the
 * plan; they are not executed against the IBM i program and do not establish
 * behavioural equivalence with it.
 *
 * Harness. The providers are mounted in the order `src/App.tsx` uses, with a
 * fresh `QueryClient` per test and the toast `clear` wired to the key scope's
 * `onBeforeCommand`, under a `MemoryRouter`. The host tests render the
 * production `HostFormDemoPage` in a route table whose `/` is a marker
 * standing in for the menu, so the host's exits show as that marker
 * appearing; the picker inside it is the real one, with no test callback
 * between them.
 */
import type { ReactElement, ReactNode } from 'react';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type { UserEvent } from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
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
import { HostFormDemoPage } from '../demo/HostFormDemoPage';
import { CustomerPicker } from './CustomerPicker';
import type { CustomerPickerProps } from './CustomerPicker';
import { CustomerSearchPanel } from './CustomerSearchPage';

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

/** SFLPAG 12 of PMTCUSTD. */
const PAGE_SIZE = 12;

const FIRST_PAGE_ROWS: readonly CustomerSummaryResponse[] = customers
  .filter((row) => row.active === 'Y')
  .slice(0, PAGE_SIZE);

const SECOND_PAGE_ROWS: readonly CustomerSummaryResponse[] = customers
  .filter((row) => row.active === 'Y')
  .slice(PAGE_SIZE, 2 * PAGE_SIZE);

function seedRow(name: string): CustomerSummaryResponse {
  const row = customers.find((candidate) => candidate.name === name);
  if (row === undefined) {
    throw new Error(`The customers fixture holds no row named ${name}`);
  }
  return row;
}

/** Seed rows the e2e flows also use: original ids 6 (backslashes) and 3 (apostrophe). */
const URNA = seedRow('URNA \\NUNC\\ COMPANY');
const NIBH = seedRow("NIBH L'LOR COMPANY");

const FIRST_ROW = ((): CustomerSummaryResponse => {
  const row = FIRST_PAGE_ROWS[0];
  if (row === undefined) {
    throw new Error('The customers fixture holds no active row');
  }
  return row;
})();

const PICKER_NAME = 'Customer Master Selection';

const DISPLAY_NAME = 'Customer Master Displaying Customer';

const NAME_FILTER = 'Name starts with:';

const SEARCH_PATH = '/api/customers';

const CATALOG_PROBE_ID = 'catalog-probe';

const HOST_PATH = '/demo/selection';

const HOST_FUNCTION = 'Order entry';

const CUSTOMER_ID_LABEL = 'Customer id +';

const LOOKUP_BUTTON = 'Look up customer';

const HOME_MARKER = 'Main menu stand-in';

interface RecordedRequest {
  method: string;
  path: string;
  params: URLSearchParams;
}

/** The current test's `/api` requests, `/api/messages` excepted, in order. */
const traffic: RecordedRequest[] = [];

/** MSW `request:start` listener: runs before any handler, so every default handler keeps answering. */
function recordRequest({ request }: { request: Request }): void {
  const url = new URL(request.url);
  if (url.pathname.startsWith('/api/') && url.pathname !== '/api/messages') {
    traffic.push({ method: request.method, path: url.pathname, params: url.searchParams });
  }
}

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

/** The `KeyScopeWithToastReset` of `src/App.tsx`. */
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

/** Options of {@link renderWithProviders}. */
interface RenderOptions {
  /** The router's history, current entry last; the router's own default (`/`) when absent. */
  initialEntries?: string[];
}

/**
 * Renders `ui` inside the providers, signed in as {@link MAINTENANCE_USER}; a
 * `<Routes>` table matches against `initialEntries`. Resolves once the catalog
 * has loaded.
 */
async function renderWithProviders(ui: ReactElement, { initialEntries }: RenderOptions = {}): Promise<UserEvent> {
  const user = userEvent.setup();
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(
    <QueryClientProvider client={queryClient}>
      <MessageCatalogProvider>
        <ToastProvider>
          <KeyedScreens>
            <MemoryRouter initialEntries={initialEntries}>
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

interface PickerCallbacks {
  onSelect: Mock<CustomerPickerProps['onSelect']>;
  onCancel: Mock<CustomerPickerProps['onCancel']>;
}

interface OpenPicker extends PickerCallbacks {
  user: UserEvent;
  dialog: HTMLElement;
}

/**
 * Renders the open picker with callbacks that leave it open, so a second
 * selection in one opening can be attempted; resolves once the catalog has
 * loaded and the Name filter has focus.
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

interface HostFormHandle {
  user: UserEvent;
  field: HTMLInputElement;
  lookup: HTMLElement;
}

/**
 * Renders the production Order entry host form (`HostFormDemoPage`) at
 * {@link HOST_PATH}, in a route table whose `/` shows {@link HOME_MARKER},
 * and resolves once the catalog has loaded and "Customer id +", where the form
 * puts the cursor on open, has focus. The picker it hosts is closed.
 */
async function renderHostForm(): Promise<HostFormHandle> {
  const user = await renderWithProviders(
    <Routes>
      <Route path={HOST_PATH} element={<HostFormDemoPage />} />
      <Route path="/" element={<p>{HOME_MARKER}</p>} />
    </Routes>,
    { initialEntries: [HOST_PATH] },
  );
  const field = screen.getByLabelText(CUSTOMER_ID_LABEL);
  if (!(field instanceof HTMLInputElement)) {
    throw new Error(`The ${CUSTOMER_ID_LABEL} label does not name an input`);
  }
  await waitFor(() => expect(field).toHaveFocus());
  return { user, field, lookup: screen.getByRole('button', { name: LOOKUP_BUTTON }) };
}

/** Takes focus off every control of the host form, as a click on its plain function-line text does. */
async function focusOffHostControls(user: UserEvent): Promise<void> {
  await user.click(screen.getByText(HOST_FUNCTION, { selector: 'p' }));
  expect(document.body).toHaveFocus();
}

/**
 * The host form's legend button `label`. Only for use while the picker is
 * closed, when the host's legend is the one function-key toolbar shown.
 */
function hostLegendKey(label: string): HTMLElement {
  return within(screen.getByRole('toolbar', { name: 'Function keys' })).getByRole('button', { name: label });
}

/** Asserts that the Order entry form is still the screen shown: its function line is there and the menu stand-in is not. */
function expectHostShown(): void {
  expect(screen.getByText(HOST_FUNCTION, { selector: 'p' })).toBeInTheDocument();
  expect(screen.queryByText(HOME_MARKER)).not.toBeInTheDocument();
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

function panelRegion(name: (typeof PANEL_REGIONS)[number]): HTMLElement {
  return screen.getByRole('region', { name });
}

async function pressLegendKey(user: UserEvent, region: HTMLElement, label: string): Promise<void> {
  const bar = within(region).getByRole('toolbar', { name: 'Function keys' });
  await user.click(within(bar).getByRole('button', { name: label }));
}

function alertRegion(): HTMLElement {
  return screen.getByRole('alert');
}

async function searchWithEnter(user: UserEvent, dialog: HTMLElement, expected: readonly string[]): Promise<void> {
  await user.keyboard('{Enter}');
  await waitFor(() => expect(shownNames(dialog)).toEqual(expected));
}

async function searchByName(
  user: UserEvent,
  dialog: HTMLElement,
  text: string,
  expected: readonly string[],
): Promise<void> {
  await user.type(nameFilter(dialog), text);
  await searchWithEnter(user, dialog, expected);
}

describe('CustomerPicker', () => {
  it('starts from the fixtures: a MAINTENANCE user, a full first page, and the two seed rows used below', () => {
    expect(MAINTENANCE_USER.roles).toEqual(['MAINTENANCE']);
    expect(FIRST_PAGE_ROWS).toHaveLength(PAGE_SIZE);
    expect(URNA).toMatchObject({ custId: 'AAAG', active: 'Y' });
    expect(NIBH).toMatchObject({ custId: 'AAAD', active: 'Y' });
    expect(messageText('DEM0003')).toBe('Key is not active now');
    expect(messageText('DEM0004', ['2'])).toBe('2 is not a valid option at this time.');
    expect(messageText('DEM0005')).toBe('Use F4 only if + is on field');
  });

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

  describe('in the Order entry host form', () => {
    it('opens with focus in "Customer id +", a four-character field that uppercases as typed, beside "Look up customer" and the F3, F4 and F12 legend', async () => {
      const { user, field, lookup } = await renderHostForm();

      expect(screen.getByRole('heading', { name: 'Customer Master' })).toBeInTheDocument();
      expectHostShown();
      // The cursor starts on the screen's first input field, not the page body.
      expect(field).toHaveFocus();
      expect(field).toHaveAttribute('maxlength', '4');
      expect(field).toHaveValue('');
      expect(lookup).toHaveAttribute('aria-haspopup', 'dialog');
      expect(
        within(screen.getByRole('toolbar', { name: 'Function keys' }))
          .getAllByRole('button')
          .map((button) => button.textContent),
      ).toEqual(['F3=Exit', 'F4=Prompt+', 'F12=Cancel']);

      await user.type(field, 'aaagx');

      expect(field).toHaveValue('AAAG');
      expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
      expect(traffic).toEqual([]);
    });

    it('"Look up customer" opens the picker; option 1 writes the id into the field and puts focus back in the field, not on the button; a reopened picker starts fresh, and F12 there leaves the field as it was and focus on the button that opened it', async () => {
      const { user, field, lookup } = await renderHostForm();

      await user.click(lookup);
      const first = await screen.findByRole('dialog', { name: PICKER_NAME });
      await waitFor(() => expect(nameFilter(first)).toHaveFocus());
      expect(traffic).toEqual([]);
      await searchByName(user, first, 'URNA', [URNA.name]);
      await user.type(optionInput(first, URNA.name), '1');
      await user.keyboard('{Enter}');

      await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
      // Option 1 returned this row's id, and the host wrote exactly it.
      expect(field).toHaveValue(URNA.custId);
      // Dialog hands focus back to its invoker, the button; the host then
      // moves it on to the field, as a 5250 prompt returned the cursor there.
      await waitFor(() => expect(field).toHaveFocus());
      expect(lookup).not.toHaveFocus();
      expectHostShown();

      // Reopened: a new opening, as PMTCUSTR ran Init on every call.
      await user.click(lookup);
      const second = await screen.findByRole('dialog', { name: PICKER_NAME });
      await waitFor(() => expect(nameFilter(second)).toHaveFocus());
      expect(nameFilter(second)).toHaveValue('');
      expect(shownNames(second)).toEqual([]);
      expect(searches()).toEqual([firstPageQuery('URNA')]);

      await user.keyboard('{F12}');

      await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
      expect(field).toHaveValue(URNA.custId);
      // A cancel returns focus to the invoking control, here the button.
      await waitFor(() => expect(lookup).toHaveFocus());
      expect(field).not.toHaveFocus();
      expectHostShown();
      expect(searches()).toEqual([firstPageQuery('URNA')]);
      expect(alertRegion()).toBeEmptyDOMElement();
    });

    it.each([
      [
        'F4 with focus in "Customer id +"',
        async ({ user }: HostFormHandle) => {
          await user.keyboard('{F4}');
        },
      ],
      [
        'F4 with focus on "Look up customer"',
        async ({ user, lookup }: HostFormHandle) => {
          await user.tab();
          expect(lookup).toHaveFocus();
          await user.keyboard('{F4}');
        },
      ],
      [
        'the F4=Prompt+ legend button with focus in "Customer id +"',
        async ({ user, field }: HostFormHandle) => {
          expect(field).toHaveFocus();
          // The legend button takes no focus on mouse-down, so the field still
          // holds it when the shared F4 handler checks where focus is.
          await user.click(hostLegendKey('F4=Prompt+'));
        },
      ],
    ])('%s opens the picker, which starts in "Name starts with:" with nothing searched', async (_how, prompt) => {
      const host = await renderHostForm();
      await host.user.type(host.field, 'ab');

      await prompt(host);

      const dialog = await screen.findByRole('dialog', { name: PICKER_NAME });
      await waitFor(() => expect(nameFilter(dialog)).toHaveFocus());
      expect(nameFilter(dialog)).toHaveValue('');
      expect(shownNames(dialog)).toEqual([]);
      expect(traffic).toEqual([]);
      expect(host.field).toHaveValue('AB');
      expect(alertRegion()).toBeEmptyDOMElement();
    });

    it.each([
      ['F12', '{F12}'],
      ['Escape', '{Escape}'],
      ['F3', '{F3}'],
    ])('%s in a picker prompted from the field with F4 cancels only the picker: the field keeps its value and gets focus back, and the host form stays', async (_key, keys) => {
      const { user, field } = await renderHostForm();
      await user.type(field, 'ab');
      await user.keyboard('{F4}');
      const dialog = await screen.findByRole('dialog', { name: PICKER_NAME });
      await waitFor(() => expect(nameFilter(dialog)).toHaveFocus());
      await searchByName(user, dialog, 'URNA', [URNA.name]);
      // An option typed but not yet entered selects nothing.
      await user.type(optionInput(dialog, URNA.name), '1');

      await user.keyboard(keys);

      await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
      // No id returned: the field still holds what the user typed.
      expect(field).toHaveValue('AB');
      await waitFor(() => expect(field).toHaveFocus());
      // The picker's scope was topmost, so the host's own exit to `/` did not run.
      expectHostShown();
      expect(searches()).toEqual([firstPageQuery('URNA')]);
      expect(alertRegion()).toBeEmptyDOMElement();
    });

    it.each([
      ['F12', '{F12}'],
      ['Escape', '{Escape}'],
      ['F3', '{F3}'],
    ])('%s in a picker opened by clicking "Look up customer" cancels it and puts focus back on the button, not the field', async (_key, keys) => {
      const { user, field, lookup } = await renderHostForm();
      await user.type(field, 'ab');
      await user.click(lookup);
      const dialog = await screen.findByRole('dialog', { name: PICKER_NAME });
      await waitFor(() => expect(nameFilter(dialog)).toHaveFocus());

      await user.keyboard(keys);

      await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
      expect(field).toHaveValue('AB');
      await waitFor(() => expect(lookup).toHaveFocus());
      expect(field).not.toHaveFocus();
      expectHostShown();
      expect(traffic).toEqual([]);
      expect(alertRegion()).toBeEmptyDOMElement();
    });

    it.each([
      [
        'F4 with focus on "Look up customer"',
        async ({ user, lookup }: HostFormHandle) => {
          await user.tab();
          expect(lookup).toHaveFocus();
          await user.keyboard('{F4}');
        },
      ],
      [
        'the F4=Prompt+ legend button with focus on "Look up customer"',
        async ({ user, lookup }: HostFormHandle) => {
          await user.tab();
          expect(lookup).toHaveFocus();
          await user.click(hostLegendKey('F4=Prompt+'));
        },
      ],
      [
        'a click on "Look up customer" that leaves focus in the field',
        async ({ field, lookup }: HostFormHandle) => {
          expect(field).toHaveFocus();
          // A bare click event moves no focus, as in a browser that does not
          // focus a clicked button, so the picker's Dialog records the field,
          // not the button, as the element focused when it opened.
          fireEvent.click(lookup);
        },
      ],
    ])('a picker opened by %s and cancelled with Escape puts focus back on "Look up customer"', async (_how, open) => {
      const host = await renderHostForm();
      await host.user.type(host.field, 'ab');

      await open(host);

      const dialog = await screen.findByRole('dialog', { name: PICKER_NAME });
      await waitFor(() => expect(nameFilter(dialog)).toHaveFocus());

      await host.user.keyboard('{Escape}');

      await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
      expect(host.field).toHaveValue('AB');
      await waitFor(() => expect(host.lookup).toHaveFocus());
      expect(host.field).not.toHaveFocus();
      expectHostShown();
      expect(traffic).toEqual([]);
      expect(alertRegion()).toBeEmptyDOMElement();
    });

    it('F4, and the F4=Prompt+ button, with focus off the field show DEM0005 "Use F4 only if + is on field" and open nothing', async () => {
      const { user } = await renderHostForm();
      // The form opens with focus in the field; a click on plain text takes it off.
      await focusOffHostControls(user);

      await user.keyboard('{F4}');

      expect(await within(alertRegion()).findByText(messageText('DEM0005'))).toBeInTheDocument();
      expect(screen.queryByRole('dialog')).not.toBeInTheDocument();

      // The click clears the earlier message first, so exactly one DEM0005 shows.
      await user.click(hostLegendKey('F4=Prompt+'));

      await waitFor(() => expect(within(alertRegion()).getAllByText(messageText('DEM0005'))).toHaveLength(1));
      expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
      expect(document.body).toHaveFocus();
      expectHostShown();
      expect(traffic).toEqual([]);
    });

    it.each([
      ['F3', '{F3}'],
      ['F12', '{F12}'],
      ['Escape', '{Escape}'],
    ])('with the picker closed, %s leaves the host form for the menu at /', async (_key, keys) => {
      const { user, field } = await renderHostForm();
      await user.type(field, 'ab');

      await user.keyboard(keys);

      expect(await screen.findByText(HOME_MARKER)).toBeInTheDocument();
      expect(screen.queryByText(HOST_FUNCTION)).not.toBeInTheDocument();
      expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
      expect(traffic).toEqual([]);
      expect(alertRegion()).toBeEmptyDOMElement();
    });

    it('F6, which the host form does not enable, shows DEM0003 "Key is not active now" and changes nothing', async () => {
      const { user, field } = await renderHostForm();
      await user.type(field, 'ab');

      await user.keyboard('{F6}');

      expect(await within(alertRegion()).findByText(messageText('DEM0003'))).toBeInTheDocument();
      expect(field).toHaveValue('AB');
      expect(field).toHaveFocus();
      expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
      expectHostShown();
      expect(traffic).toEqual([]);
    });
  });

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

      expect(searches()).toEqual([firstPageQuery(), firstPageQuery()]);

      await pressLegendKey(user, first, 'Page Down');
      await waitFor(() => expect(shownNames(first)).toEqual(pageTwo));

      expect(within(first).getByText('Bottom')).toBeInTheDocument();
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
      const { user, field, lookup } = await renderHostForm();
      const pageOne = FIRST_PAGE_ROWS.map((row) => row.name);

      await user.click(lookup);
      const first = await screen.findByRole('dialog', { name: PICKER_NAME });
      await waitFor(() => expect(nameFilter(first)).toHaveFocus());
      await searchWithEnter(user, first, pageOne);
      await user.keyboard('{PageDown}');
      await waitFor(() => expect(shownNames(first)).toEqual(SECOND_PAGE_ROWS.map((row) => row.name)));
      await user.keyboard('{F12}');
      await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());

      // Cancelled: no id came back, and the host form is still shown.
      expect(field).toHaveValue('');
      expectHostShown();
      expect(searches()).toHaveLength(2);

      // Reopened with the same (blank) criteria: nothing is shown before Enter,
      // and Enter requests the first page again rather than reusing the pages
      // the closed opening loaded.
      await user.click(lookup);
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
      expect(field).toHaveValue('');
    });
  });
});
