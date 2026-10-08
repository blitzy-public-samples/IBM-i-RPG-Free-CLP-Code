/**
 * Tests of the customer display / change / add window:
 * `./CustomerDetailDialog.tsx`, rendered with its real children
 * (`CustomerForm`, `ConfirmationPanel`, `ConflictCompareDialog`) and the real
 * `StatePicker` it nests.
 *
 * What it replaces. MTNCUSTR and its window MTNCUSTD, called by PMTCUSTR with
 * a customer id and a function code:
 * - **Display** (`D`, 5250_Subfile/MTNCUSTR.SQLRPGLE:181-191): ReadRecd,
 *   every field protected, one screen I/O, then the program ends, so every
 *   key MTNCUSTD enables (Enter, CF04, CA05, CA12; MTNCUSTD.DSPF:33-35)
 *   closes the window.
 * - **Edit** (`E`, :195-247): F12 ends, F5 re-reads (a vanished row sends
 *   DEM0599 and clears the fields), F4 prompts, Enter edits and, when the
 *   data is valid, protects the fields and asks for confirmation with
 *   DEM0000. At the confirmation Enter updates, F12 or F5 loop back to a
 *   re-read record (the entries are lost), any other key sends DEM0003.
 * - **Add** (`A`, :251-297): opens cleared with ACTIVE = 'Y'; F5 clears again.
 *   Enter edits and confirms with DEM0009; at the confirmation Enter adds,
 *   F12 refills the cleared fields, any other key sends DEM0003.
 * - **State prompt** (F04Prompt :364-382): PmtState is called with the
 *   program field STATE, which is then copied into SD_STATE whether or not a
 *   code was chosen, so a cancelled prompt puts back the *working State*.
 *   Edit_SD_STATE (:488-500) moves a valid SD_STATE into STATE, and the USPS
 *   variant's Edit_Address (USPS_Address/MTNCUSTR.SQLRPGLE:471-499) runs
 *   after it, so a later failure leaves the working State changed.
 * - **Concurrency** (UpdateRecd :567-607): a stale update sends DEM1002 and
 *   re-reads the record; a row lock sends DEM1001.
 * - **Messages** are the CUSTMSGF texts (5250_Subfile/CRTMSGF.CLLE:12-47),
 *   with the DEM0009 and DEM1002 typos corrected.
 *
 * What is pinned down here (AAP 0.3.8 "Detail flows", 0.8.3
 * `CustomerDetailDialog.test.tsx`):
 * - Display read-only; Enter, F4, F5, F12 and Escape close with no picker and
 *   no reload; F3 shows DEM0003 and the window stays; a missing row DEM0599.
 * - Edit → review → DEM0000 → PUT with `version`; add opens with Active `Y`
 *   → DEM0009 → POST → closes; a 422 highlights and focuses the first field
 *   and shows its message as an alert.
 * - A held Enter repeating while the review is in flight sends no second
 *   request.
 * - The confirmation keys of edit and add, Tab and Shift+Tab at a
 *   confirmation, 409 DEM1001 and 409 DEM1002 (the comparison window).
 * - F5 reloads in edit (fields and version) and clears in add; DEM0599.
 * - The working State in edit (stored `CA`) and in add (blank), read from
 *   the review result (`stateAccepted`) and never from which field failed.
 *
 * Fixtures. The stored customer is seed row AAAD (`customerDetail`) with its
 * State set to `CA`; a `GET /api/customers/:custId` override serves it from
 * {@link served}, so a test can change or remove the stored row between two
 * reads. Review, add and update are answered by the default handlers of
 * `src/test/handlers.ts` (the nine field rules, then the stub address
 * service), except where a test overrides a route with {@link problem} or a
 * fixed body to pin an exact value. Every request is recorded from MSW's
 * `request:start` event, before any handler runs, so the handlers keep
 * answering and the request bodies can be asserted.
 *
 * Evidence. These tests are derived from reading the IBM i source and the
 * plan; they are not executed against the IBM i program and do not establish
 * behavioural equivalence with it.
 *
 * Harness. The providers are mounted in the order `src/App.tsx` uses, with a
 * fresh `QueryClient` per test, and the toast `clear` wired to the key
 * scope's `onBeforeCommand`. A probe reads `useMessages().ready`, so message
 * assertions start only once the catalog has loaded (until then `format`
 * returns the bare code).
 */
import type { ReactNode } from 'react';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
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
import type { CustomerResponse, CustomerUpdateRequest, ReviewRequest } from '../../api/customers';
import { AuthProvider } from '../../auth/AuthProvider';
import { ToastProvider, useToasts } from '../../components/ToastRegion';
import { KeyScopeProvider } from '../../keyboard/KeyScopeProvider';
import { MessageCatalogProvider, useMessages } from '../../messages/MessageCatalogProvider';
import { customerDetail, messageText, problem, users } from '../../test/handlers';
import { server } from '../../test/server';
import { CustomerDetailDialog } from './CustomerDetailDialog';
import type { CustomerDetailDialogProps, DetailMode } from './CustomerDetailDialog';
import { CUSTOMER_FORM_FIELDS } from './CustomerForm';
import type { CustomerFieldName } from './CustomerForm';

// ---------------------------------------------------------------------------
// Fixtures
// ---------------------------------------------------------------------------

/** The nine customer data fields, every one present as a string. */
type FieldValues = Record<CustomerFieldName, string>;

/** The demo user with the MAINTENANCE role: review, add and update require it. */
const MAINTENANCE_USER = (() => {
  const user = users.find((candidate) => candidate.roles.includes('MAINTENANCE'));
  if (user === undefined) {
    throw new Error('The users fixture holds no MAINTENANCE user');
  }
  return user;
})();

/** The stored customer: seed row AAAD with State `CA`, the working State an edit starts from. */
const STORED: CustomerResponse = { ...customerDetail, state: 'CA' };

/** The nine customer data fields of a stored record: the shape of a write body. */
function fieldsOf(record: CustomerResponse): FieldValues {
  return {
    active: record.active,
    name: record.name,
    addr: record.addr,
    city: record.city,
    state: record.state,
    zip: record.zip,
    acctPhone: record.acctPhone,
    acctMgr: record.acctMgr,
    corpPhone: record.corpPhone,
  };
}

/** Every field blank: the form after a vanished row. */
const BLANK: FieldValues = {
  active: '',
  name: '',
  addr: '',
  city: '',
  state: '',
  zip: '',
  acctPhone: '',
  acctMgr: '',
  corpPhone: '',
};

/** The cleared add form: `clear CUSTMAST_ds; ACTIVE = 'Y'` (MTNCUSTR :251-254, :280-282). */
const EMPTY_ADD: FieldValues = { ...BLANK, active: 'Y' };

/**
 * A new customer that passes the nine field rules and that the default stub
 * address service echoes unchanged (uppercase, a known State, a five-digit
 * ZIP, so no ZIP+4 is added): its reviewed values equal what was typed.
 */
const NEW_CUSTOMER: FieldValues = {
  active: 'Y',
  name: 'ACME WIDGETS',
  addr: '100 MAIN STREET',
  city: 'SPRINGFIELD',
  state: 'IL',
  zip: '62701',
  acctPhone: '(217) 555-0100',
  acctMgr: 'JANE DOE',
  corpPhone: '(217) 555-0199',
};

/** The paths the window calls, relative as the SPA calls them. */
const CUSTOMER_PATH = `/api/customers/${STORED.custId}`;
const REVIEW_PATH = '/api/customers/review';
const ADD_PATH = '/api/customers';
const STATES_PATH = '/api/states';

/** Accessible names of the windows: their ScreenHeader title and function line. */
const DIALOG_NAMES: Readonly<Record<DetailMode, string>> = {
  display: 'Customer Master Displaying Customer',
  edit: 'Customer Master Change Customer',
  add: 'Customer Master Add Customer',
};
const STATE_PICKER_NAME = 'USA States';
const CONFLICT_NAME = 'Customer Master Record Changed';

/** Accessible name of the confirmation panel's group. */
const CONFIRM_GROUP_NAME = 'Confirm customer';

/** The test id of {@link CatalogProbe}. */
const CATALOG_PROBE_ID = 'catalog-probe';

/** The visible label of each customer field, from the one label table the form and the panel share. */
function labelOf(field: CustomerFieldName): string {
  const spec = CUSTOMER_FORM_FIELDS.find((candidate) => candidate.field === field);
  if (spec === undefined) {
    throw new Error(`CUSTOMER_FORM_FIELDS has no entry for ${field}`);
  }
  return spec.label;
}

// ---------------------------------------------------------------------------
// The stored customer and the request log
// ---------------------------------------------------------------------------

/**
 * The customer `GET /api/customers/:custId` serves, read at request time, so
 * a test can change it (another user's change) or set it to `null` (a
 * vanished row, 404 DEM0599) between two reads.
 */
let served: CustomerResponse | null = null;

/** One request that reached MSW: method, path and, for a write, its body text. */
interface RecordedRequest {
  method: string;
  path: string;
  body: Promise<string> | null;
}

/** Every `/api` request since the window was rendered, the catalog excepted, in order. */
const traffic: RecordedRequest[] = [];

/**
 * MSW `request:start` listener. It runs before any handler, so the body is
 * cloned while still unread and the handlers keep answering as usual.
 */
function recordRequest({ request }: { request: Request }): void {
  const { pathname } = new URL(request.url);
  if (!pathname.startsWith('/api/') || pathname === '/api/messages') {
    return;
  }
  traffic.push({
    method: request.method,
    path: pathname,
    body: request.method === 'GET' ? null : request.clone().text(),
  });
}

/** The recorded requests of one method and path. */
function sent(method: string, path: string): RecordedRequest[] {
  return traffic.filter((entry) => entry.method === method && entry.path === path);
}

/** The JSON bodies of the recorded requests of one method and path, in order. */
async function bodiesOf<T>(method: string, path: string): Promise<T[]> {
  const texts = await Promise.all(sent(method, path).map((entry) => entry.body ?? Promise.resolve('null')));
  return texts.map((text) => JSON.parse(text) as T);
}

beforeEach(() => {
  // Stored as after a real sign-in: every customer route answers 401 without them.
  setCredentials({ username: MAINTENANCE_USER.username, password: MAINTENANCE_USER.password });
  served = { ...STORED };
  traffic.length = 0;
  server.events.on('request:start', recordRequest);
  server.use(
    http.get<{ custId: string }>('/api/customers/:custId', ({ params }) => {
      const stored = served;
      if (stored === null || stored.custId !== params.custId) {
        return problem(404, 'DEM0599', { instance: `/api/customers/${params.custId}` });
      }
      return HttpResponse.json({ ...stored });
    }),
  );
});

afterEach(() => {
  server.events.removeListener('request:start', recordRequest);
  setCredentials(null);
});

/** A passed review as the API answers it: the values to confirm and the confirmation notice. */
function reviewPassed(purpose: ReviewRequest['purpose'], customer: FieldValues, standardized = false): Response {
  const code = purpose === 'ADD' ? 'DEM0009' : 'DEM0000';
  return HttpResponse.json({ customer, standardized, notice: { code, message: messageText(code) } });
}

/** Overrides the review route with one fixed answer. */
function answerReview(respond: () => Response): void {
  server.use(http.post(REVIEW_PATH, () => respond()));
}

/** Overrides the update route with one fixed answer. */
function answerUpdate(respond: () => Response): void {
  server.use(http.put('/api/customers/:custId', () => respond()));
}

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

/** The window's `onClose`, as a mock. */
type CloseMock = Mock<CustomerDetailDialogProps['onClose']>;

/** What {@link renderDialog} needs: the function, the id (absent in add) and, optionally, the close callback. */
interface RenderOptions {
  mode: DetailMode;
  custId?: string;
  onClose?: CloseMock;
}

/** What {@link renderDialog} returns. */
interface RenderedDialog {
  user: UserEvent;
  onClose: CloseMock;
}

/**
 * Renders `<CustomerDetailDialog open mode custId onClose />` inside the
 * application's providers: a fresh query client (no retries), the message
 * catalog, the toast host, the key scope stack, a router and a MAINTENANCE
 * session. Clears the request log first, so it holds only what the window
 * sends.
 */
function renderDialog({ mode, custId, onClose = vi.fn<CustomerDetailDialogProps['onClose']>() }: RenderOptions): RenderedDialog {
  traffic.length = 0;
  const user = userEvent.setup();
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(
    <QueryClientProvider client={queryClient}>
      <MessageCatalogProvider>
        <ToastProvider>
          <KeyedScreens>
            <MemoryRouter>
              <AuthProvider initialSession={{ username: MAINTENANCE_USER.username, roles: ['MAINTENANCE'] }}>
                <CatalogProbe />
                <CustomerDetailDialog open mode={mode} custId={custId} onClose={onClose} />
              </AuthProvider>
            </MemoryRouter>
          </KeyedScreens>
        </ToastProvider>
      </MessageCatalogProvider>
    </QueryClientProvider>,
  );
  return { user, onClose };
}

/** What {@link openDialog} returns. */
interface OpenedDialog extends RenderedDialog {
  dialog: HTMLElement;
}

/**
 * Renders the window for `mode` (the stored customer in display and edit) and
 * waits until it can be keyed: the window is shown, the catalog has loaded
 * and, in edit and add, Name holds focus.
 */
async function openDialog(mode: DetailMode): Promise<OpenedDialog> {
  const rendered = renderDialog({ mode, custId: mode === 'add' ? undefined : STORED.custId });
  const dialog = await screen.findByRole('dialog', { name: DIALOG_NAMES[mode] });
  await waitFor(() => expect(screen.getByTestId(CATALOG_PROBE_ID)).toHaveAttribute('data-ready', 'true'));
  if (mode !== 'display') {
    await waitFor(() => expect(inputOf(dialog, 'name')).toHaveFocus());
  }
  return { ...rendered, dialog };
}

/** The input of one customer field, by its exact label, inside `scope` (the form or the confirmation panel). */
function inputOf(scope: HTMLElement, field: CustomerFieldName): HTMLInputElement {
  const element = within(scope).getByLabelText(labelOf(field));
  if (!(element instanceof HTMLInputElement)) {
    throw new Error(`The ${labelOf(field)} label does not name an input`);
  }
  return element;
}

/** The nine field values shown in `scope`. */
function valuesOf(scope: HTMLElement): FieldValues {
  const read = (field: CustomerFieldName): string => inputOf(scope, field).value;
  return {
    active: read('active'),
    name: read('name'),
    addr: read('addr'),
    city: read('city'),
    state: read('state'),
    zip: read('zip'),
    acctPhone: read('acctPhone'),
    acctMgr: read('acctMgr'),
    corpPhone: read('corpPhone'),
  };
}

/** Replaces the value of one field by typing, as a user does (the field uppercases as typed). */
async function retype(user: UserEvent, scope: HTMLElement, field: CustomerFieldName, text: string): Promise<void> {
  const input = inputOf(scope, field);
  await user.clear(input);
  if (text !== '') {
    await user.type(input, text);
  }
}

/** Types every field of `values` but Active, which the add form already presets. */
async function fillForm(user: UserEvent, scope: HTMLElement, values: FieldValues): Promise<void> {
  for (const { field } of CUSTOMER_FORM_FIELDS) {
    if (field !== 'active') {
      await retype(user, scope, field, values[field]);
    }
  }
}

/** The confirmation panel, or `null` while the editable form is shown. */
function queryConfirmation(scope: HTMLElement): HTMLElement | null {
  return within(scope).queryByRole('group', { name: CONFIRM_GROUP_NAME });
}

/**
 * Presses Enter on the form and waits for the confirmation the passed review
 * shows: the notice in the status region and the panel holding focus.
 */
async function reviewToConfirmation(user: UserEvent, dialog: HTMLElement, notice: 'DEM0000' | 'DEM0009'): Promise<HTMLElement> {
  await user.keyboard('{Enter}');
  await within(statusRegion()).findByText(messageText(notice));
  const panel = await within(dialog).findByRole('group', { name: CONFIRM_GROUP_NAME });
  await waitFor(() => expect(panel).toHaveFocus());
  return panel;
}

/** Waits until the editable form is back: no confirmation panel and an editable Name. */
async function waitForForm(dialog: HTMLElement): Promise<void> {
  await waitFor(() => expect(queryConfirmation(dialog)).not.toBeInTheDocument());
  expect(inputOf(dialog, 'name')).not.toHaveAttribute('readonly');
}

/** The shared alert region of the toast host: problem details and client-raised errors. */
function alertRegion(): HTMLElement {
  return screen.getByRole('alert');
}

/** The shared status region of the toast host: the DEM0000 and DEM0009 confirmations. */
function statusRegion(): HTMLElement {
  return screen.getByRole('status');
}

/** Opens the State prompt with F4 from the State field and returns the picker window once it is shown. */
async function openStatePrompt(user: UserEvent, dialog: HTMLElement): Promise<HTMLElement> {
  await user.click(inputOf(dialog, 'state'));
  await user.keyboard('{F4}');
  return screen.findByRole('dialog', { name: STATE_PICKER_NAME });
}

/**
 * Opens the State prompt and cancels it with F12, then waits for the picker
 * to close: the State field then shows the working State (F04Prompt copies
 * STATE back into SD_STATE whatever the prompt returned).
 */
async function cancelStatePrompt(user: UserEvent, dialog: HTMLElement): Promise<void> {
  const picker = await openStatePrompt(user, dialog);
  await user.keyboard('{F12}');
  await waitFor(() => expect(picker).not.toBeInTheDocument());
}

/**
 * Opens the State prompt, applies the filter `filter`, keys option 1 on the
 * row named `stateName` and presses Enter, as PMTSTATER returns a code
 * (PMTSTATER.SQLRPGLE:299-304); waits for the picker to close.
 */
async function chooseStateInPrompt(user: UserEvent, dialog: HTMLElement, filter: string, stateName: string): Promise<void> {
  const picker = await openStatePrompt(user, dialog);
  const filterInput = within(picker).getByRole('textbox', { name: 'Name Contains' });
  await waitFor(() => expect(filterInput).toHaveFocus());
  await user.type(filterInput, filter);
  await user.keyboard('{Enter}');
  const option = await within(picker).findByRole('textbox', { name: `Option for ${stateName}` });
  await user.type(option, '1');
  await user.keyboard('{Enter}');
  await waitFor(() => expect(picker).not.toBeInTheDocument());
}

// ---------------------------------------------------------------------------
// Specs
// ---------------------------------------------------------------------------

describe('CustomerDetailDialog', () => {
  it('starts from the fixtures: the stored customer has State CA and the add customer passes every rule', () => {
    expect(STORED.custId).toBe('AAAD');
    expect(STORED.state).toBe('CA');
    expect(STORED.version).toBe(0);
    expect(NEW_CUSTOMER.state).not.toBe(STORED.state);
    expect(CUSTOMER_FORM_FIELDS.map(({ label }) => label)).toContain('Name');
    expect(CUSTOMER_FORM_FIELDS.map(({ label }) => label)).toContain('Account Manager Name');
  });

  // -------------------------------------------------------------------------
  // Display (function code D, MTNCUSTR :181-191)
  // -------------------------------------------------------------------------

  describe('display', () => {
    it('shows the stored customer under "Displaying Customer" with every input read-only', async () => {
      const { dialog } = await openDialog('display');

      expect(within(dialog).getByText('Displaying Customer')).toBeInTheDocument();
      expect(valuesOf(dialog)).toEqual(fieldsOf(STORED));
      expect(within(dialog).getByLabelText('Customer Id')).toHaveValue(STORED.custId);
      const inputs = within(dialog).getAllByRole('textbox');
      // The protected Customer Id plus the nine data fields (ProtectAll).
      expect(inputs).toHaveLength(CUSTOMER_FORM_FIELDS.length + 1);
      for (const input of inputs) {
        expect(input).toHaveAttribute('readonly');
      }
      expect(sent('GET', CUSTOMER_PATH)).toHaveLength(1);
    });

    it.each(['Enter', 'F4', 'F5', 'F12', 'Escape'])(
      'closes once on %s, with no State picker opened and no second read',
      async (key) => {
        const { user, dialog, onClose } = await openDialog('display');
        // Focus on State, where F4 prompts in edit and add: here it closes.
        await user.click(inputOf(dialog, 'state'));

        await user.keyboard(`{${key}}`);

        expect(onClose).toHaveBeenCalledTimes(1);
        expect(onClose).toHaveBeenCalledWith();
        expect(screen.queryByRole('dialog', { name: STATE_PICKER_NAME })).not.toBeInTheDocument();
        expect(sent('GET', CUSTOMER_PATH)).toHaveLength(1);
        expect(sent('GET', STATES_PATH)).toHaveLength(0);
        expect(alertRegion()).toBeEmptyDOMElement();
      },
    );

    it('shows DEM0003 "Key is not active now" for F3 and stays open', async () => {
      const { user, dialog, onClose } = await openDialog('display');

      await user.keyboard('{F3}');

      expect(within(alertRegion()).getByText('Key is not active now')).toBeInTheDocument();
      expect(messageText('DEM0003')).toBe('Key is not active now');
      expect(onClose).not.toHaveBeenCalled();
      expect(dialog).toBeInTheDocument();
      expect(valuesOf(dialog)).toEqual(fieldsOf(STORED));
    });

    it('shows DEM0599 "Customer deleted. Exit & redo search." when the customer no longer exists', async () => {
      served = null;

      const { dialog, onClose } = await openDialog('display');

      expect(within(alertRegion()).getByText('Customer deleted. Exit & redo search.')).toBeInTheDocument();
      expect(messageText('DEM0599')).toBe('Customer deleted. Exit & redo search.');
      expect(valuesOf(dialog)).toEqual(BLANK);
      expect(inputOf(dialog, 'name')).toHaveAttribute('readonly');
      expect(onClose).not.toHaveBeenCalled();
    });
  });

  // -------------------------------------------------------------------------
  // Edit and add flows (MTNCUSTR :195-297)
  // -------------------------------------------------------------------------

  describe('edit and add flows', () => {
    it('edit: reviews the draft, confirms the reviewed values with DEM0000 and sends them with the version read', async () => {
      // The reviewed values differ from the typed ones (normalized and
      // standardized), so the confirmation and the PUT must carry the review's.
      const reviewed: FieldValues = {
        ...fieldsOf(STORED),
        name: 'NIBH LOR HOLDINGS',
        addr: 'P.O. BOX 103 9218 VIVAMUS AVE',
        acctMgr: 'NORMAN, ABBOT R.',
      };
      const saved: CustomerResponse = {
        ...STORED,
        ...reviewed,
        chgTime: '2026-10-05T15:00:00Z',
        chgUser: MAINTENANCE_USER.username,
        version: STORED.version + 1,
      };
      answerReview(() => reviewPassed('EDIT', reviewed, true));
      answerUpdate(() => HttpResponse.json(saved));
      const { user, dialog, onClose } = await openDialog('edit');
      expect(within(dialog).getByText('Change Customer')).toBeInTheDocument();
      expect(valuesOf(dialog)).toEqual(fieldsOf(STORED));

      await retype(user, dialog, 'name', 'nibh lor holdings');
      expect(inputOf(dialog, 'name')).toHaveValue('NIBH LOR HOLDINGS');
      const panel = await reviewToConfirmation(user, dialog, 'DEM0000');

      // The review carried EDIT and the nine fields as shown on the form.
      expect(await bodiesOf<ReviewRequest>('POST', REVIEW_PATH)).toEqual([
        { purpose: 'EDIT', ...fieldsOf(STORED), name: 'NIBH LOR HOLDINGS' },
      ]);
      expect(within(statusRegion()).getByText('Press Enter to update. F12 to Cancel.')).toBeInTheDocument();
      // The confirmation shows the reviewed values, every one protected.
      expect(valuesOf(panel)).toEqual(reviewed);
      for (const input of within(panel).getAllByRole('textbox')) {
        expect(input).toHaveAttribute('readonly');
      }
      expect(within(panel).getByText('Address standardized.')).toBeInTheDocument();
      expect(sent('PUT', CUSTOMER_PATH)).toHaveLength(0);

      await user.keyboard('{Enter}');

      await waitFor(() => expect(onClose).toHaveBeenCalledTimes(1));
      expect(onClose).toHaveBeenCalledWith({ saved });
      expect(await bodiesOf<CustomerUpdateRequest>('PUT', CUSTOMER_PATH)).toEqual([
        { ...reviewed, version: STORED.version },
      ]);
    });

    it('add: opens cleared with Active Y, confirms with DEM0009, adds and closes', async () => {
      const { user, dialog, onClose } = await openDialog('add');
      expect(within(dialog).getByText('Add Customer')).toBeInTheDocument();
      expect(valuesOf(dialog)).toEqual(EMPTY_ADD);
      expect(within(dialog).getByLabelText('Customer Id')).toHaveValue('');
      expect(traffic).toHaveLength(0);

      await fillForm(user, dialog, NEW_CUSTOMER);
      const panel = await reviewToConfirmation(user, dialog, 'DEM0009');

      expect(await bodiesOf<ReviewRequest>('POST', REVIEW_PATH)).toEqual([{ purpose: 'ADD', ...NEW_CUSTOMER }]);
      expect(within(statusRegion()).getByText('Press Enter to add. Press F12 to cancel')).toBeInTheDocument();
      expect(valuesOf(panel)).toEqual(NEW_CUSTOMER);
      for (const input of within(panel).getAllByRole('textbox')) {
        expect(input).toHaveAttribute('readonly');
      }
      expect(sent('POST', ADD_PATH)).toHaveLength(0);

      await user.keyboard('{Enter}');

      await waitFor(() => expect(onClose).toHaveBeenCalledTimes(1));
      expect(onClose).toHaveBeenCalledWith({ added: true });
      expect(await bodiesOf<unknown>('POST', ADD_PATH)).toEqual([NEW_CUSTOMER]);
      expect(sent('PUT', CUSTOMER_PATH)).toHaveLength(0);
    });

    it('a held Enter repeating while the review is in flight sends no second review, PUT or POST', async () => {
      // The review answer is held back until the test releases it.
      let releaseReview: () => void = () => undefined;
      const reviewHeld = new Promise<void>((resolve) => {
        releaseReview = resolve;
      });
      server.use(
        http.post(REVIEW_PATH, async () => {
          await reviewHeld;
          return reviewPassed('EDIT', fieldsOf(STORED));
        }),
      );
      const { user, dialog, onClose } = await openDialog('edit');
      const name = inputOf(dialog, 'name');

      try {
        await user.keyboard('{Enter}');
        await waitFor(() => expect(sent('POST', REVIEW_PATH)).toHaveLength(1));

        // Each repeat is dispatched (and so prevented) like the first Enter;
        // the window's in-flight gate, not the key dispatch, stops a second request.
        for (let repeat = 0; repeat < 3; repeat += 1) {
          expect(fireEvent.keyDown(name, { key: 'Enter', repeat: true })).toBe(false);
        }
        expect(sent('POST', REVIEW_PATH)).toHaveLength(1);
      } finally {
        releaseReview();
      }

      await within(statusRegion()).findByText(messageText('DEM0000'));
      await within(dialog).findByRole('group', { name: CONFIRM_GROUP_NAME });
      expect(await bodiesOf<ReviewRequest>('POST', REVIEW_PATH)).toEqual([{ purpose: 'EDIT', ...fieldsOf(STORED) }]);
      expect(sent('PUT', CUSTOMER_PATH)).toHaveLength(0);
      expect(sent('POST', ADD_PATH)).toHaveLength(0);
      expect(onClose).not.toHaveBeenCalled();
    });

    it.each(['edit', 'add'] as const)('puts the initial focus on Name in %s mode', async (mode) => {
      const { dialog } = await openDialog(mode);

      expect(inputOf(dialog, 'name')).toHaveFocus();
      expect(inputOf(dialog, 'name')).not.toHaveAttribute('readonly');
    });

    it('a review failing at Name highlights and focuses Name and shows "Name: Must not be blank" as an alert', async () => {
      const { user, dialog } = await openDialog('edit');
      await retype(user, dialog, 'name', '');
      // Enter from another field, so the focus move to Name is observable.
      await user.click(inputOf(dialog, 'city'));

      await user.keyboard('{Enter}');

      await within(alertRegion()).findByText('Name: Must not be blank');
      expect(messageText('DEM0502', ['Name'])).toBe('Name: Must not be blank');
      const name = inputOf(dialog, 'name');
      await waitFor(() => expect(name).toHaveFocus());
      expect(name).toHaveAttribute('aria-invalid', 'true');
      expect(name).toHaveAccessibleDescription('Name: Must not be blank');
      for (const field of ['active', 'addr', 'city', 'state', 'zip', 'acctPhone', 'acctMgr', 'corpPhone'] as const) {
        expect(inputOf(dialog, field)).not.toHaveAttribute('aria-invalid');
      }
      expect(queryConfirmation(dialog)).not.toBeInTheDocument();
      expect(sent('PUT', CUSTOMER_PATH)).toHaveLength(0);
    });

    it('a DEM9898 address failure highlights Address, City, State and ZIP and focuses Address', async () => {
      const { user, dialog } = await openDialog('edit');
      // The stub address service does not find a street holding BADADDR.
      await retype(user, dialog, 'addr', 'badaddr 1 main street');
      await user.click(inputOf(dialog, 'zip'));

      await user.keyboard('{Enter}');

      await within(alertRegion()).findByText('USPS: Address Not Found.');
      const addr = inputOf(dialog, 'addr');
      await waitFor(() => expect(addr).toHaveFocus());
      for (const field of ['addr', 'city', 'state', 'zip'] as const) {
        expect(inputOf(dialog, field)).toHaveAttribute('aria-invalid', 'true');
      }
      for (const field of ['active', 'name', 'acctPhone', 'acctMgr', 'corpPhone'] as const) {
        expect(inputOf(dialog, field)).not.toHaveAttribute('aria-invalid');
      }
      expect(queryConfirmation(dialog)).not.toBeInTheDocument();
    });
  });

  // -------------------------------------------------------------------------
  // Keys at the confirmation (MTNCUSTR :221-247, :273-292)
  // -------------------------------------------------------------------------

  describe('keys at the edit confirmation', () => {
    /** Opens the edit window, changes Name and reaches the confirmation. */
    async function confirmEdit(): Promise<OpenedDialog & { panel: HTMLElement }> {
      const opened = await openDialog('edit');
      await retype(opened.user, opened.dialog, 'name', 'new name co');
      const panel = await reviewToConfirmation(opened.user, opened.dialog, 'DEM0000');
      return { ...opened, panel };
    }

    it.each(['F12', 'F5'])('%s re-reads the stored record and shows it on the form, discarding the entries', async (key) => {
      const { user, dialog, onClose } = await confirmEdit();
      // Someone else changed City meanwhile, so the form shows what a re-read returns.
      served = { ...STORED, city: 'BANGOR', version: STORED.version + 1 };

      await user.keyboard(`{${key}}`);

      await waitForForm(dialog);
      expect(sent('GET', CUSTOMER_PATH)).toHaveLength(2);
      await waitFor(() => expect(inputOf(dialog, 'city')).toHaveValue('BANGOR'));
      expect(valuesOf(dialog)).toEqual({ ...fieldsOf(STORED), city: 'BANGOR' });
      expect(inputOf(dialog, 'name')).toHaveFocus();
      expect(alertRegion()).toBeEmptyDOMElement();
      expect(onClose).not.toHaveBeenCalled();
      expect(sent('PUT', CUSTOMER_PATH)).toHaveLength(0);
    });

    it('F4 shows DEM0003 and returns to the editable form with the entries kept, without a re-read', async () => {
      const { user, dialog, onClose } = await confirmEdit();

      await user.keyboard('{F4}');

      await waitForForm(dialog);
      expect(within(alertRegion()).getByText('Key is not active now')).toBeInTheDocument();
      expect(inputOf(dialog, 'name')).toHaveValue('NEW NAME CO');
      expect(sent('GET', CUSTOMER_PATH)).toHaveLength(1);
      expect(sent('GET', STATES_PATH)).toHaveLength(0);
      expect(onClose).not.toHaveBeenCalled();
    });

    it('F3, a key MTNCUSTD does not enable, shows DEM0003 and leaves the confirmation shown', async () => {
      const { user, dialog, panel, onClose } = await confirmEdit();

      await user.keyboard('{F3}');

      expect(within(alertRegion()).getByText('Key is not active now')).toBeInTheDocument();
      expect(queryConfirmation(dialog)).toBe(panel);
      expect(inputOf(panel, 'name')).toHaveValue('NEW NAME CO');
      expect(sent('GET', CUSTOMER_PATH)).toHaveLength(1);
      expect(sent('PUT', CUSTOMER_PATH)).toHaveLength(0);
      expect(onClose).not.toHaveBeenCalled();
    });

    it('Tab and Shift+Tab move focus between the confirmation controls with no message and no change', async () => {
      const { user, dialog, panel } = await confirmEdit();
      const customerId = within(panel).getByLabelText('Customer Id');
      const active = inputOf(panel, 'active');

      await user.tab();
      expect(customerId).toHaveFocus();
      await user.tab();
      expect(active).toHaveFocus();
      await user.tab({ shift: true });
      expect(customerId).toHaveFocus();

      expect(alertRegion()).toBeEmptyDOMElement();
      expect(within(statusRegion()).getByText(messageText('DEM0000'))).toBeInTheDocument();
      expect(queryConfirmation(dialog)).toBe(panel);
      expect(sent('POST', REVIEW_PATH)).toHaveLength(1);
      expect(sent('GET', CUSTOMER_PATH)).toHaveLength(1);
      expect(sent('PUT', CUSTOMER_PATH)).toHaveLength(0);
    });

    it('a 409 DEM1001 row lock shows its message and returns to the editable form with the entries kept', async () => {
      answerUpdate(() => problem(409, 'DEM1001', { instance: CUSTOMER_PATH }));
      const { user, dialog, onClose } = await confirmEdit();

      await user.keyboard('{Enter}');

      await within(alertRegion()).findByText('Customer being updated by another user or job.');
      expect(messageText('DEM1001')).toBe('Customer being updated by another user or job.');
      await waitForForm(dialog);
      expect(inputOf(dialog, 'name')).toHaveValue('NEW NAME CO');
      expect(sent('PUT', CUSTOMER_PATH)).toHaveLength(1);
      expect(sent('GET', CUSTOMER_PATH)).toHaveLength(1);
      expect(onClose).not.toHaveBeenCalled();
    });

    it('a 409 DEM1002 opens the comparison with its text and no toast; Refresh loads the current record', async () => {
      const current: CustomerResponse = {
        ...STORED,
        city: 'PORTLAND',
        chgTime: '2026-10-05T14:10:00Z',
        chgUser: 'other-user',
        version: STORED.version + 1,
      };
      answerUpdate(() => problem(409, 'DEM1002', { current, instance: CUSTOMER_PATH }));
      const { user, dialog, onClose } = await confirmEdit();

      await user.keyboard('{Enter}');

      const compare = await screen.findByRole('dialog', { name: CONFLICT_NAME });
      expect(within(compare).getByText('Someone else changed record. Review data.')).toBeInTheDocument();
      expect(messageText('DEM1002')).toBe('Someone else changed record. Review data.');
      // The comparison carries the DEM1002 text itself: nothing is published.
      expect(alertRegion()).toBeEmptyDOMElement();

      await user.click(within(compare).getByRole('button', { name: 'Refresh' }));

      await waitFor(() => expect(compare).not.toBeInTheDocument());
      await waitForForm(dialog);
      expect(valuesOf(dialog)).toEqual(fieldsOf(current));
      expect(alertRegion()).toBeEmptyDOMElement();
      expect(onClose).not.toHaveBeenCalled();
      // The current record came with the conflict: no re-read was needed.
      expect(sent('GET', CUSTOMER_PATH)).toHaveLength(1);
    });
  });

  describe('keys at the add confirmation', () => {
    /** Opens the add window, keys a valid customer and reaches the confirmation. */
    async function confirmAdd(): Promise<OpenedDialog & { panel: HTMLElement }> {
      const opened = await openDialog('add');
      await fillForm(opened.user, opened.dialog, NEW_CUSTOMER);
      const panel = await reviewToConfirmation(opened.user, opened.dialog, 'DEM0009');
      return { ...opened, panel };
    }

    it('F12 returns to a cleared form with Active Y', async () => {
      const { user, dialog, onClose } = await confirmAdd();

      await user.keyboard('{F12}');

      await waitForForm(dialog);
      expect(valuesOf(dialog)).toEqual(EMPTY_ADD);
      expect(inputOf(dialog, 'name')).toHaveFocus();
      expect(alertRegion()).toBeEmptyDOMElement();
      expect(sent('POST', ADD_PATH)).toHaveLength(0);
      expect(onClose).not.toHaveBeenCalled();
    });

    it.each(['F4', 'F5'])('%s shows DEM0003 and returns to the editable form with the entries kept', async (key) => {
      const { user, dialog, onClose } = await confirmAdd();

      await user.keyboard(`{${key}}`);

      await waitForForm(dialog);
      expect(within(alertRegion()).getByText('Key is not active now')).toBeInTheDocument();
      expect(valuesOf(dialog)).toEqual(NEW_CUSTOMER);
      expect(sent('POST', ADD_PATH)).toHaveLength(0);
      expect(sent('GET', STATES_PATH)).toHaveLength(0);
      expect(onClose).not.toHaveBeenCalled();
    });

    it('F3, a key MTNCUSTD does not enable, shows DEM0003 and leaves the confirmation shown', async () => {
      const { user, dialog, panel, onClose } = await confirmAdd();

      await user.keyboard('{F3}');

      expect(within(alertRegion()).getByText('Key is not active now')).toBeInTheDocument();
      expect(queryConfirmation(dialog)).toBe(panel);
      expect(valuesOf(panel)).toEqual(NEW_CUSTOMER);
      expect(sent('POST', ADD_PATH)).toHaveLength(0);
      expect(onClose).not.toHaveBeenCalled();
    });

    it('Tab and Shift+Tab move focus between the confirmation controls with no message and no change', async () => {
      const { user, dialog, panel } = await confirmAdd();
      const customerId = within(panel).getByLabelText('Customer Id');
      const active = inputOf(panel, 'active');

      await user.tab();
      expect(customerId).toHaveFocus();
      await user.tab();
      expect(active).toHaveFocus();
      await user.tab({ shift: true });
      expect(customerId).toHaveFocus();

      expect(alertRegion()).toBeEmptyDOMElement();
      expect(within(statusRegion()).getByText(messageText('DEM0009'))).toBeInTheDocument();
      expect(queryConfirmation(dialog)).toBe(panel);
      expect(sent('POST', REVIEW_PATH)).toHaveLength(1);
      expect(sent('POST', ADD_PATH)).toHaveLength(0);
    });
  });

  // -------------------------------------------------------------------------
  // F5 on the form (MTNCUSTR :209-216, :284-287)
  // -------------------------------------------------------------------------

  describe('F5 on the form', () => {
    it('edit: re-reads the record, replacing the entries and the version the next update is conditional on', async () => {
      const { user, dialog, onClose } = await openDialog('edit');
      // Another user's change raised the stored version to 4 meanwhile.
      const reloaded: CustomerResponse = { ...STORED, name: 'NIBH RELOADED CO', chgUser: 'other-user', version: 4 };
      served = reloaded;
      const saved: CustomerResponse = { ...reloaded, chgUser: MAINTENANCE_USER.username, version: 5 };
      answerUpdate(() => HttpResponse.json(saved));
      await retype(user, dialog, 'city', 'bangor');

      await user.keyboard('{F5}');

      await waitFor(() => expect(inputOf(dialog, 'name')).toHaveValue('NIBH RELOADED CO'));
      expect(sent('GET', CUSTOMER_PATH)).toHaveLength(2);
      expect(valuesOf(dialog)).toEqual(fieldsOf(reloaded));
      expect(alertRegion()).toBeEmptyDOMElement();

      await user.click(inputOf(dialog, 'name'));
      await reviewToConfirmation(user, dialog, 'DEM0000');
      await user.keyboard('{Enter}');

      await waitFor(() => expect(onClose).toHaveBeenCalledTimes(1));
      expect(onClose).toHaveBeenCalledWith({ saved });
      const [update] = await bodiesOf<CustomerUpdateRequest>('PUT', CUSTOMER_PATH);
      expect(update?.version).toBe(4);
      expect(update?.name).toBe('NIBH RELOADED CO');
    });

    it('edit: a row that vanished shows DEM0599 and clears the fields', async () => {
      const { user, dialog, onClose } = await openDialog('edit');
      served = null;
      await retype(user, dialog, 'city', 'bangor');

      await user.keyboard('{F5}');

      await within(alertRegion()).findByText('Customer deleted. Exit & redo search.');
      await waitFor(() => expect(valuesOf(dialog)).toEqual(BLANK));
      expect(sent('GET', CUSTOMER_PATH)).toHaveLength(2);
      expect(inputOf(dialog, 'name')).not.toHaveAttribute('readonly');
      expect(onClose).not.toHaveBeenCalled();
    });

    it('add: clears the form again to Active Y', async () => {
      const { user, dialog } = await openDialog('add');
      await retype(user, dialog, 'active', 'n');
      await retype(user, dialog, 'name', 'acme');
      await retype(user, dialog, 'city', 'springfield');
      expect(valuesOf(dialog)).toEqual({ ...EMPTY_ADD, active: 'N', name: 'ACME', city: 'SPRINGFIELD' });

      await user.keyboard('{F5}');

      await waitFor(() => expect(valuesOf(dialog)).toEqual(EMPTY_ADD));
      expect(inputOf(dialog, 'name')).toHaveFocus();
      expect(alertRegion()).toBeEmptyDOMElement();
      expect(traffic).toHaveLength(0);
    });
  });

  // -------------------------------------------------------------------------
  // Working State (F04Prompt :364-382, Edit_SD_STATE :488-500)
  // -------------------------------------------------------------------------

  describe('working State in edit (stored CA)', () => {
    it('a prompt selection replaces it: TX chosen, NV typed, a cancelled prompt puts back TX', async () => {
      const { user, dialog } = await openDialog('edit');

      await chooseStateInPrompt(user, dialog, 'tex', 'Texas');

      // The picker closed with the code in the field; F4 runs no edit.
      expect(screen.queryByRole('dialog', { name: STATE_PICKER_NAME })).not.toBeInTheDocument();
      expect(inputOf(dialog, 'state')).toHaveValue('TX');
      await waitFor(() => expect(inputOf(dialog, 'state')).toHaveFocus());
      expect(sent('POST', REVIEW_PATH)).toHaveLength(0);

      await retype(user, dialog, 'state', 'nv');
      expect(inputOf(dialog, 'state')).toHaveValue('NV');
      await cancelStatePrompt(user, dialog);

      expect(inputOf(dialog, 'state')).toHaveValue('TX');
      // Only State is put back: the other entries stay as typed.
      expect(valuesOf(dialog)).toEqual({ ...fieldsOf(STORED), state: 'TX' });
      expect(sent('POST', REVIEW_PATH)).toHaveLength(0);
    });

    it('a review failing after the State rule (blank ZIP) takes its stateAccepted: NV typed, AZ cancelled back to NV', async () => {
      const { user, dialog } = await openDialog('edit');
      await retype(user, dialog, 'state', 'nv');
      await retype(user, dialog, 'zip', '');

      await user.keyboard('{Enter}');

      await within(alertRegion()).findByText('ZIP: Must not be blank');
      await waitFor(() => expect(inputOf(dialog, 'zip')).toHaveFocus());
      expect(inputOf(dialog, 'zip')).toHaveAttribute('aria-invalid', 'true');
      await retype(user, dialog, 'state', 'az');
      await cancelStatePrompt(user, dialog);

      expect(inputOf(dialog, 'state')).toHaveValue('NV');
    });

    it('a review failing at Name, before the State rule, leaves it CA', async () => {
      const { user, dialog } = await openDialog('edit');
      await retype(user, dialog, 'state', 'nv');
      await retype(user, dialog, 'name', '');

      await user.keyboard('{Enter}');

      await within(alertRegion()).findByText('Name: Must not be blank');
      await retype(user, dialog, 'state', 'az');
      await cancelStatePrompt(user, dialog);

      expect(inputOf(dialog, 'state')).toHaveValue('CA');
    });

    it('F5 resets it to the stored State: TX chosen, F5, AZ cancelled back to CA', async () => {
      const { user, dialog } = await openDialog('edit');
      await chooseStateInPrompt(user, dialog, 'tex', 'Texas');
      expect(inputOf(dialog, 'state')).toHaveValue('TX');

      await user.keyboard('{F5}');
      await waitFor(() => expect(inputOf(dialog, 'state')).toHaveValue('CA'));
      expect(sent('GET', CUSTOMER_PATH)).toHaveLength(2);
      await retype(user, dialog, 'state', 'az');
      await cancelStatePrompt(user, dialog);

      expect(inputOf(dialog, 'state')).toHaveValue('CA');
    });

    it('a 502 APP0502 review carrying stateAccepted NV makes it NV: AZ cancelled back to NV', async () => {
      answerReview(() => problem(502, 'APP0502', { stateAccepted: 'NV' }));
      const { user, dialog } = await openDialog('edit');
      await retype(user, dialog, 'state', 'nv');

      await user.keyboard('{Enter}');

      await within(alertRegion()).findByText('Address service is unavailable. Try again later.');
      expect(messageText('APP0502')).toBe('Address service is unavailable. Try again later.');
      await retype(user, dialog, 'state', 'az');
      await cancelStatePrompt(user, dialog);

      expect(inputOf(dialog, 'state')).toHaveValue('NV');
    });

    it('a 422 DEM0503 on State carrying stateAccepted NV (the standardized-State check) makes it NV', async () => {
      answerReview(() =>
        problem(422, 'DEM0503', {
          errors: [{ field: 'state', code: 'DEM0503', message: messageText('DEM0503') }],
          stateAccepted: 'NV',
        }),
      );
      const { user, dialog } = await openDialog('edit');
      await retype(user, dialog, 'state', 'nv');

      await user.keyboard('{Enter}');

      await within(alertRegion()).findByText('State invalid. Can use F4 to prompt.');
      await waitFor(() => expect(inputOf(dialog, 'state')).toHaveFocus());
      expect(inputOf(dialog, 'state')).toHaveAttribute('aria-invalid', 'true');
      await retype(user, dialog, 'state', 'az');
      await cancelStatePrompt(user, dialog);

      expect(inputOf(dialog, 'state')).toHaveValue('NV');
    });

    it('a 422 DEM0503 on State without stateAccepted (the State rule) leaves it CA', async () => {
      const { user, dialog } = await openDialog('edit');
      // ZZ is no STATES code, so the default review fails at the State rule.
      await retype(user, dialog, 'state', 'zz');

      await user.keyboard('{Enter}');

      await within(alertRegion()).findByText('State invalid. Can use F4 to prompt.');
      await waitFor(() => expect(inputOf(dialog, 'state')).toHaveFocus());
      await cancelStatePrompt(user, dialog);

      expect(inputOf(dialog, 'state')).toHaveValue('CA');
    });
  });

  describe('working State in add (blank)', () => {
    it('starts blank: NV typed, a cancelled prompt puts back blank', async () => {
      const { user, dialog } = await openDialog('add');
      await retype(user, dialog, 'state', 'nv');

      await cancelStatePrompt(user, dialog);

      expect(inputOf(dialog, 'state')).toHaveValue('');
      expect(traffic.filter((entry) => entry.path !== STATES_PATH)).toHaveLength(0);
    });

    it('F12 at the add confirmation clears it: IL reviewed, F12, NV cancelled back to blank', async () => {
      const { user, dialog } = await openDialog('add');
      await fillForm(user, dialog, NEW_CUSTOMER);
      await reviewToConfirmation(user, dialog, 'DEM0009');

      await user.keyboard('{F12}');
      await waitForForm(dialog);
      expect(valuesOf(dialog)).toEqual(EMPTY_ADD);
      await retype(user, dialog, 'state', 'nv');
      await cancelStatePrompt(user, dialog);

      expect(inputOf(dialog, 'state')).toHaveValue('');
      expect(sent('POST', ADD_PATH)).toHaveLength(0);
    });
  });
});
