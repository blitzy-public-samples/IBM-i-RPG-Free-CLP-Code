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
 * - The field contract: the display, edit and add forms and both
 *   confirmations show Customer Id (length 4), then the nine fields in screen
 *   order, each named by its `<label for>` and limited by `maxlength` to its
 *   DDS length, against {@link FIELD_CONTRACT}, which is written out here
 *   rather than read from the form's table; an editable field takes no
 *   character past its length.
 * - Display read-only; Enter, F4, F5, F12 and Escape close with no picker and
 *   no reload; F3 shows DEM0003 and the window stays; a missing row DEM0599.
 * - Closing the window while its opening read is pending aborts that read,
 *   and its answer shows no alert; under StrictMode the read the simulated
 *   unmount aborts shows none either, and the remount's read opens the
 *   record.
 * - Edit → review → DEM0000 → PUT with `version`; add opens with Active `Y`
 *   → DEM0009 → POST → closes; a 422 highlights and focuses the first field
 *   and shows its message as an alert.
 * - A held Enter repeating while the review is in flight sends no second
 *   request.
 * - The confirmation keys of edit and add, Tab and Shift+Tab at a
 *   confirmation, 409 DEM1001 and 409 DEM1002 (the comparison window).
 * - F5 reloads in edit (fields and version) and clears in add; DEM0599.
 * - While a review, reload or save is pending the form is read-only, typing
 *   changes nothing and the key bar disables the keys that do nothing then
 *   (F12 on the form still closes); the response then lands on the values
 *   it was sent for, and focus stays inside the window.
 * - The "Last Change … by …" stamp of a record a user changed stays on the
 *   window from the form to the edit confirmation; a `*SYSTEM*` record and
 *   add show none.
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
import { subscribe, unsubscribe } from 'node:diagnostics_channel';
import type { Socket } from 'node:net';
import { StrictMode } from 'react';
import type { ReactNode } from 'react';
import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type { UserEvent } from '@testing-library/user-event';
import { QueryClient, QueryClientProvider, notifyManager } from '@tanstack/react-query';
// MSW 3 serves `http` and `HttpResponse` from its `msw/http` entry point, the
// one src/test/handlers.ts and the other suites import them from.
import { http, HttpResponse } from 'msw/http';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { Mock, MockInstance } from 'vitest';
import { setCredentials } from '../../api/client';
import { customersApi } from '../../api/customers';
import type { CustomerResponse, CustomerUpdateRequest, ReviewRequest } from '../../api/customers';
import { AuthProvider } from '../../auth/AuthProvider';
import { ToastProvider, useToasts } from '../../components/ToastRegion';
import { KeyScopeProvider } from '../../keyboard/KeyScopeProvider';
import { MessageCatalogProvider, useMessages } from '../../messages/MessageCatalogProvider';
import { customerDetail, messageText, problem, users } from '../../test/handlers';
import { server } from '../../test/server';
import { CustomerDetailDialog } from './CustomerDetailDialog';
import type { CustomerDetailDialogProps, DetailMode } from './CustomerDetailDialog';
import type { CustomerFieldName } from './CustomerForm';
import { formatChangeStamp } from './formatChangeStamp';

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

/** One customer data field as the window must show it: its JSON name, its visible label and its length. */
interface FieldContract {
  readonly field: CustomerFieldName;
  readonly label: string;
  /** The DDS field length, which the input enforces as its `maxlength`. */
  readonly width: number;
}

/**
 * The nine customer data fields in MTNCUSTD screen order (Active, Name,
 * Address, City, State, ZIP, Account Manager Phone, Account Manager Name,
 * Corporate Phone), each with its label and DDS length
 * (5250_Subfile/MTNCUSTD.DSPF:61-125). Active is the one-character Y/N text
 * input "Active (Y/N)"; "State +" keeps the 5250 "+" of the field F4 prompts.
 * Written out here rather than read from the form's field table, so a
 * reordered, renamed, missing or resized field fails these specs instead of
 * moving with the component.
 */
const FIELD_CONTRACT: readonly FieldContract[] = [
  { field: 'active', label: 'Active (Y/N)', width: 1 },
  { field: 'name', label: 'Name', width: 40 },
  { field: 'addr', label: 'Address', width: 40 },
  { field: 'city', label: 'City', width: 20 },
  { field: 'state', label: 'State +', width: 2 },
  { field: 'zip', label: 'ZIP', width: 10 },
  { field: 'acctPhone', label: 'Account Manager Phone', width: 20 },
  { field: 'acctMgr', label: 'Account Manager Name', width: 40 },
  { field: 'corpPhone', label: 'Corporate Phone', width: 20 },
];

/** The protected Customer Id ahead of the nine fields: SD_CUSTID, four base-36 characters. */
const CUSTOMER_ID_CONTRACT = { label: 'Customer Id', width: 4 } as const;

/** The visible label of each customer field, from {@link FIELD_CONTRACT}. */
function labelOf(field: CustomerFieldName): string {
  const spec = FIELD_CONTRACT.find((candidate) => candidate.field === field);
  if (spec === undefined) {
    throw new Error(`FIELD_CONTRACT has no entry for ${field}`);
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

// ---------------------------------------------------------------------------
// Held responses
// ---------------------------------------------------------------------------

/**
 * A response held back until released: an MSW resolver answers through
 * {@link Hold.answer}, so its request stays pending until the test, or the
 * teardown, releases the hold. {@link hold} registers every hold as it is
 * created, and the top-level `afterEach` drains them ({@link releaseHolds}):
 * it releases every hold, including one a resolver creates only after the
 * drain began, which is born released, and waits until every request has
 * settled. A test that fails before its own release, or before its request
 * even reached MSW, therefore leaves no resolver waiting and no teardown
 * hung.
 */
interface Hold {
  /** Lets every resolver waiting on this hold answer; calling it again changes nothing. */
  readonly release: () => void;
  /**
   * Run by an MSW resolver: waits until the hold is released, then answers
   * with `respond()`. The run is tracked until it has returned.
   */
  readonly answer: (respond: () => Response) => Promise<Response>;
  /** Resolves once every resolver run that waited on this hold so far has returned, either way; at once when none came. */
  readonly settled: () => Promise<void>;
}

/** Every hold made since the last drain, in creation order. */
const holds: Hold[] = [];

/** Every resolver run that waited on a hold since the last drain, in arrival order. */
const heldAnswers: Promise<Response>[] = [];

/**
 * Whether the holds are being drained: set when {@link releaseHolds} begins
 * and kept until the next test's `beforeEach`, so every hold created from the
 * start of the drain on is born released.
 */
let draining = false;

/**
 * The pass-through spy on the global `fetch` installed before each test and
 * restored before the next by `restoreMocks`: every request the test made,
 * with the promise each call returned.
 */
let fetchCalls: MockInstance<typeof globalThis.fetch> | undefined;

/** The promise of every `fetch` the current test made, in call order. */
function fetchesMade(): Promise<Response>[] {
  return (fetchCalls?.mock.results ?? []).flatMap((result) => (result.type === 'return' ? [result.value] : []));
}

/**
 * A {@link Hold}, registered for the teardown. It starts closed, except once
 * a drain has begun ({@link draining}): a resolver whose request reached MSW
 * only after the teardown started, because its test failed first, then gets
 * a hold that is already released and answers at once, instead of waiting on
 * a hold the drain has already passed over.
 */
function hold(): Hold {
  let open: () => void = () => undefined;
  const released = new Promise<void>((resolve) => {
    open = resolve;
  });
  if (draining) {
    open();
  }
  const answers: Promise<Response>[] = [];
  const created: Hold = {
    release: () => {
      open();
    },
    answer: (respond) => {
      const answered = released.then(respond);
      answers.push(answered);
      heldAnswers.push(answered);
      return answered;
    },
    settled: async () => {
      await Promise.allSettled(answers);
    },
  };
  holds.push(created);
  return created;
}

/**
 * Drains the holds. From its start every hold is released: those already
 * made, and every one a resolver makes later, until the next test begins
 * ({@link draining}). It waits, inside `act`, until each resolver that waited
 * on a hold has returned its answer and every `fetch` the test made has
 * settled, either way, and again for as long as new resolver runs or fetches
 * appeared meanwhile, so a request whose resolver creates its hold only
 * after the drain began is settled too. Calling it again is harmless. `act`
 * flushes every React update their continuations queued before the tree is
 * unmounted.
 */
async function releaseHolds(): Promise<void> {
  await act(async () => {
    draining = true;
    let awaited: number;
    do {
      for (const held of holds) {
        held.release();
      }
      const pending = [...heldAnswers, ...fetchesMade()];
      awaited = pending.length;
      await Promise.allSettled(pending);
    } while (heldAnswers.length + fetchesMade().length !== awaited);
  });
  holds.length = 0;
  heldAnswers.length = 0;
}

// ---------------------------------------------------------------------------
// Connections
// ---------------------------------------------------------------------------
//
// Closing the window aborts its pending read, and the undici that Node 24
// bundles (7.x) then opens a new connection to the origin with nothing to
// send on it. MSW's socket interception passes a connection that carries no
// request through to the real network, where `localhost:3000` refuses it
// later; a request the next test sends on that pooled connection meanwhile
// fails with ECONNREFUSED instead of reaching its handler. The connections
// are observed through the diagnostics channels undici publishes, and the
// teardown closes every one that carried no request
// ({@link closeIdleConnections}).

/** Connection attempts undici began in the current test that have neither connected nor failed yet. */
let connecting = 0;

/** Every socket undici connected or wrote a request on in the current test, mapped to whether it carried one. */
const connectedSockets = new Map<Socket, boolean>();

/** Waits for {@link connecting} to reach zero. */
const connectWaiters: Array<() => void> = [];

/** `undici:client:beforeConnect`: an attempt begins. */
function onBeforeConnect(): void {
  connecting += 1;
}

/** `undici:client:connectError`, and the end of `undici:client:connected`: an attempt has settled. */
function onConnectSettled(): void {
  connecting = Math.max(0, connecting - 1);
  if (connecting === 0) {
    for (const wake of connectWaiters.splice(0)) {
      wake();
    }
  }
}

/** `undici:client:connected`: the attempt's socket, which has carried no request yet. */
function onConnected(message: unknown): void {
  const { socket } = message as { socket: Socket };
  connectedSockets.set(socket, connectedSockets.get(socket) ?? false);
  onConnectSettled();
}

/** `undici:client:sendHeaders`: a request was written on the socket. */
function onSendHeaders(message: unknown): void {
  const { socket } = message as { socket: Socket };
  connectedSockets.set(socket, true);
}

/** The undici diagnostics channels the connection tracking listens on, with their listeners. */
const CONNECTION_CHANNELS: ReadonlyArray<readonly [string, (message: unknown) => void]> = [
  ['undici:client:beforeConnect', onBeforeConnect],
  ['undici:client:connected', onConnected],
  ['undici:client:connectError', onConnectSettled],
  ['undici:client:sendHeaders', onSendHeaders],
];

/**
 * Waits until every connection attempt of the current test has connected or
 * failed, then destroys each connected socket that never carried a request
 * and waits for it to close; again while that started more attempts. Runs
 * once every `fetch` of the test has settled, so no request is cut short.
 */
async function closeIdleConnections(): Promise<void> {
  for (;;) {
    if (connecting > 0) {
      await new Promise<void>((resolve) => {
        connectWaiters.push(resolve);
      });
    }
    const idle = [...connectedSockets].filter(([socket, carried]) => !carried && !socket.destroyed).map(([socket]) => socket);
    if (idle.length === 0) {
      return;
    }
    await Promise.all(
      idle.map(
        (socket) =>
          new Promise<void>((resolve) => {
            socket.once('close', () => resolve());
            socket.destroy();
          }),
      ),
    );
  }
}

beforeEach(() => {
  // The previous test's drain is over: this test's holds start closed.
  draining = false;
  for (const [channel, listener] of CONNECTION_CHANNELS) {
    subscribe(channel, listener);
  }
  fetchCalls = vi.spyOn(globalThis, 'fetch');
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

afterEach(async () => {
  // A response a failed test left held is released and settled here, while
  // the tree is still mounted, the credentials still set and the test's
  // handlers still in place (src/test/setup.ts unmounts and resets them after
  // this hook), so nothing it resolves can reach the next test.
  await releaseHolds();
  await closeIdleConnections();
  for (const [channel, listener] of CONNECTION_CHANNELS) {
    unsubscribe(channel, listener);
  }
  connectedSockets.clear();
  connecting = 0;
  fetchCalls = undefined;
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

/**
 * What {@link renderDialog} needs: the function, the id (absent in add) and,
 * optionally, the close callback and `strict`, which renders the tree under
 * `<StrictMode>` as `src/main.tsx` renders the application.
 */
interface RenderOptions {
  mode: DetailMode;
  custId?: string;
  onClose?: CloseMock;
  strict?: boolean;
}

/** What {@link renderDialog} returns. */
interface RenderedDialog {
  user: UserEvent;
  onClose: CloseMock;
  /** The test's query client, to wait until no read is in flight. */
  queryClient: QueryClient;
  /** Renders again with `open` set, as the caller does: false closes the window, true opens it afresh. */
  setOpen: (open: boolean) => void;
}

/**
 * Renders `<CustomerDetailDialog open mode custId onClose />` inside the
 * application's providers: a fresh query client (no retries), the message
 * catalog, the toast host, the key scope stack, a router and a MAINTENANCE
 * session. Clears the request log first, so it holds only what the window
 * sends. With `strict`, development's extra effect cycle (a simulated unmount
 * and remount) runs too.
 */
function renderDialog({
  mode,
  custId,
  onClose = vi.fn<CustomerDetailDialogProps['onClose']>(),
  strict = false,
}: RenderOptions): RenderedDialog {
  traffic.length = 0;
  const user = userEvent.setup();
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const screens = (open: boolean) => {
    const tree = (
      <QueryClientProvider client={queryClient}>
        <MessageCatalogProvider>
          <ToastProvider>
            <KeyedScreens>
              <MemoryRouter>
                <AuthProvider initialSession={{ username: MAINTENANCE_USER.username, roles: ['MAINTENANCE'] }}>
                  <CatalogProbe />
                  <CustomerDetailDialog open={open} mode={mode} custId={custId} onClose={onClose} />
                </AuthProvider>
              </MemoryRouter>
            </KeyedScreens>
          </ToastProvider>
        </MessageCatalogProvider>
      </QueryClientProvider>
    );
    return strict ? <StrictMode>{tree}</StrictMode> : tree;
  };
  const { rerender } = render(screens(true));
  return {
    user,
    onClose,
    queryClient,
    setOpen: (open) => {
      rerender(screens(open));
    },
  };
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
  for (const { field } of FIELD_CONTRACT) {
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
    // The field oracle holds each of the nine fields once, in the order of the
    // value fixtures, and nine distinct labels, so the exact label query for
    // "Name" resolves only the customer name, never "Account Manager Name".
    expect(FIELD_CONTRACT.map(({ field }) => field)).toEqual(Object.keys(BLANK));
    expect(new Set(FIELD_CONTRACT.map(({ label }) => label)).size).toBe(9);
  });

  // -------------------------------------------------------------------------
  // Held responses: the teardown's drain
  // -------------------------------------------------------------------------

  describe('held responses', () => {
    it('a hold its resolver creates only after the drain began is born released, so the drain settles that request', async () => {
      // The request reaches MSW, but its resolver makes its hold only once
      // `arrive` is called: the order a test leaves behind when it fails
      // after sending a request and before the request's resolver ran.
      let arrive: () => void = () => undefined;
      const arrival = new Promise<void>((resolve) => {
        arrive = resolve;
      });
      const created: Hold[] = [];
      server.use(
        http.get<{ custId: string }>('/api/customers/:custId', async () => {
          await arrival;
          const read = hold();
          created.push(read);
          return read.answer(() => HttpResponse.json({ ...STORED }));
        }),
      );

      const read = customersApi.get(STORED.custId);
      try {
        await waitFor(() => expect(sent('GET', CUSTOMER_PATH)).toHaveLength(1));
        expect(created).toHaveLength(0);

        const drained = releaseHolds();
        arrive();
        await drained;
      } finally {
        // Should an assertion above fail first, the resolver still goes on to
        // its hold, which the teardown then releases; a second call is a no-op.
        arrive();
      }

      expect(created).toHaveLength(1);
      await expect(read).resolves.toEqual(STORED);
    });
  });

  // -------------------------------------------------------------------------
  // Field contract (MTNCUSTD.DSPF:61-125): screen order, labels, lengths
  // -------------------------------------------------------------------------

  describe('field contract', () => {
    /** One textbox as the window shows it: the text of its `<label for>` and its `maxlength`. */
    interface ShownField {
      label: string | null;
      maxLength: string | null;
    }

    /**
     * Every textbox inside `scope`, in DOM order, as its label and length. The
     * label is read only from a `<label>` whose `for` names the input's id, so
     * a field labelled any other way shows `null`.
     */
    function shownFields(scope: HTMLElement): ShownField[] {
      return within(scope)
        .getAllByRole('textbox')
        .map((input) => {
          const labels = input instanceof HTMLInputElement && input.labels !== null ? Array.from(input.labels) : [];
          const [forLabel, ...others] = labels.filter((label) => input.id !== '' && label.htmlFor === input.id);
          return {
            label: forLabel !== undefined && others.length === 0 ? forLabel.textContent : null,
            maxLength: input.getAttribute('maxlength'),
          };
        });
    }

    /** What every phase must show: the Customer Id, then the nine fields of {@link FIELD_CONTRACT}, in that order. */
    const EXPECTED_FIELDS: readonly ShownField[] = [
      { label: CUSTOMER_ID_CONTRACT.label, maxLength: String(CUSTOMER_ID_CONTRACT.width) },
      ...FIELD_CONTRACT.map(({ label, width }) => ({ label, maxLength: String(width) })),
    ];

    it.each(['display', 'edit', 'add'] as const)(
      'the %s form shows Customer Id, then the nine fields in screen order, each labelled and limited to its length',
      async (mode) => {
        const { dialog } = await openDialog(mode);

        expect(shownFields(dialog)).toEqual(EXPECTED_FIELDS);
        expect(within(dialog).getByLabelText(CUSTOMER_ID_CONTRACT.label)).toHaveAttribute('readonly');
        for (const { field } of FIELD_CONTRACT) {
          if (mode === 'display') {
            expect(inputOf(dialog, field)).toHaveAttribute('readonly');
          } else {
            expect(inputOf(dialog, field)).not.toHaveAttribute('readonly');
          }
        }
      },
    );

    it('the edit confirmation shows the same ten fields, labels and lengths, every one protected', async () => {
      const { user, dialog } = await openDialog('edit');
      await retype(user, dialog, 'name', 'contract name co');

      const panel = await reviewToConfirmation(user, dialog, 'DEM0000');

      expect(shownFields(panel)).toEqual(EXPECTED_FIELDS);
      for (const input of within(panel).getAllByRole('textbox')) {
        expect(input).toHaveAttribute('readonly');
      }
    });

    it('the add confirmation shows the same ten fields, labels and lengths, every one protected', async () => {
      const { user, dialog } = await openDialog('add');
      await fillForm(user, dialog, NEW_CUSTOMER);

      const panel = await reviewToConfirmation(user, dialog, 'DEM0009');

      expect(shownFields(panel)).toEqual(EXPECTED_FIELDS);
      for (const input of within(panel).getAllByRole('textbox')) {
        expect(input).toHaveAttribute('readonly');
      }
    });

    it('each editable field keeps exactly its length: one character more is not taken', async () => {
      const { user, dialog } = await openDialog('edit');

      for (const { field, width } of FIELD_CONTRACT) {
        const input = inputOf(dialog, field);
        await user.clear(input);
        await user.type(input, 'x'.repeat(width + 1));
        // Uppercased as typed, which keeps the length, and stopped at the width.
        expect(input).toHaveValue('X'.repeat(width));
      }
      expect(traffic).toHaveLength(1);
      expect(sent('GET', CUSTOMER_PATH)).toHaveLength(1);
    });
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
      expect(inputs).toHaveLength(1 + 9);
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

    describe('the opening read, when the window closes before it answers', () => {
      /** What {@link renderWhileReading} returns: the rendered window and its held reads. */
      interface ReadingDialog extends RenderedDialog {
        /** The reads held so far, in arrival order, each by its own registered {@link Hold}. */
        held: readonly Hold[];
        /**
         * Lets the held read at `index` answer 404 DEM0599 and waits until it
         * has settled with `inFlight` reads still fetching ({@link settleReads}).
         */
        release: (index: number, inFlight: number) => Promise<void>;
      }

      /** The pass-through spy on `customersApi.get` of the current test: every read of a customer, in call order. */
      let customerGets: MockInstance<typeof customersApi.get>;

      beforeEach(() => {
        customerGets = vi.spyOn(customersApi, 'get');
      });

      /**
       * What read `index` of the stored customer settles through: its `fetch`,
       * and the `customersApi.get` call the window's query function awaits.
       * That function awaited the call before anyone else, so its own handling
       * of the outcome (the alert it presents, or withholds) has run once an
       * await of the call here resumes.
       */
      function readPromises(index: number): Promise<unknown>[] {
        const calls = fetchCalls?.mock.calls ?? [];
        const results = fetchCalls?.mock.results ?? [];
        const fetches = calls.flatMap(([input], call) => {
          const result = results[call];
          const isRead = new URL(String(input), window.location.href).pathname === CUSTOMER_PATH;
          return isRead && result?.type === 'return' ? [result.value] : [];
        });
        const fetched = fetches[index];
        const read = customerGets.mock.results[index];
        if (fetched === undefined || read?.type !== 'return') {
          throw new Error(`No read #${index} of ${CUSTOMER_PATH} was made`);
        }
        return [fetched, read.value];
      }

      /**
       * Resolves once the query cache shows exactly `inFlight` fetching
       * queries, and only after react-query has handed that change to its
       * observers: the resolve is queued on react-query's own notification
       * queue, behind the observers' notifications, so the window has been
       * told to render the settled read by then.
       */
      function queriesSettled(queryClient: QueryClient, inFlight: number): Promise<void> {
        return new Promise((resolve) => {
          const cache = queryClient.getQueryCache();
          let unsubscribe: () => void = () => undefined;
          const check = (): void => {
            if (queryClient.isFetching() === inFlight) {
              unsubscribe();
              notifyManager.schedule(resolve);
            }
          };
          unsubscribe = cache.subscribe(check);
          check();
        });
      }

      /**
       * Releases `read`, when given, and waits inside the same `act` until the
       * reads at `indexes` have settled: the held resolver has returned its
       * answer, and each read's `fetch` and client call have settled, either
       * way ({@link readPromises}); then until `inFlight` queries are fetching
       * ({@link queriesSettled}). A read whose window closed was aborted, so
       * its fetch rejected before the release, while its resolver returns only
       * after it. `act` flushes every React update these continuations
       * queued, so an empty alert region afterwards is observed, not raced.
       */
      async function settleReads(queryClient: QueryClient, inFlight: number, indexes: readonly number[], read?: Hold): Promise<void> {
        await act(async () => {
          read?.release();
          await Promise.allSettled([read?.settled(), ...indexes.flatMap(readPromises)]);
          await queriesSettled(queryClient, inFlight);
        });
      }

      /**
       * Holds every `GET /api/customers/:custId` until released, then answers
       * it 404 DEM0599, as for a row deleted meanwhile. Renders the window and
       * waits until the catalog has loaded and the opening read is held, with
       * no window shown yet.
       */
      async function renderWhileReading(mode: 'display' | 'edit'): Promise<ReadingDialog> {
        const held: Hold[] = [];
        server.use(
          http.get<{ custId: string }>('/api/customers/:custId', ({ params }) => {
            const read = hold();
            held.push(read);
            return read.answer(() => problem(404, 'DEM0599', { instance: `/api/customers/${params.custId}` }));
          }),
        );
        const rendered = renderDialog({ mode, custId: STORED.custId });
        await waitFor(() => expect(screen.getByTestId(CATALOG_PROBE_ID)).toHaveAttribute('data-ready', 'true'));
        await waitFor(() => expect(held).toHaveLength(1));
        expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
        const release = async (index: number, inFlight: number): Promise<void> => {
          const read = held[index];
          if (read === undefined) {
            throw new Error(`No read #${index} is held`);
          }
          await settleReads(rendered.queryClient, inFlight, [index], read);
        };
        return { ...rendered, held, release };
      }

      it.each([
        ['display', 'Escape'],
        ['edit', 'F12'],
      ] as const)('%s: a read answering DEM0599 after %s closed the window shows no alert', async (mode, key) => {
        const { user, onClose, setOpen, release } = await renderWhileReading(mode);

        await user.keyboard(`{${key}}`);
        expect(onClose).toHaveBeenCalledTimes(1);
        expect(onClose).toHaveBeenCalledWith();
        setOpen(false);
        await release(0, 0);

        expect(alertRegion()).toBeEmptyDOMElement();
        expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
        expect(sent('GET', CUSTOMER_PATH)).toHaveLength(1);
      });

      /**
       * The `AbortSignal` the client handed `fetch` for each read of the
       * stored customer, in call order, from a spy on the global `fetch`
       * that calls through (restored after the test by `restoreMocks`).
       */
      function customerReadSignals(): () => Array<AbortSignal | undefined> {
        const spy = vi.spyOn(globalThis, 'fetch');
        return () =>
          spy.mock.calls
            .filter(([input]) => new URL(String(input), window.location.href).pathname === CUSTOMER_PATH)
            .map(([, init]) => init?.signal ?? undefined);
      }

      it('edit: F12 while the opening read is pending aborts it, and the DEM0599 it would answer shows no alert', async () => {
        const signals = customerReadSignals();
        const { user, onClose, setOpen, release } = await renderWhileReading('edit');
        expect(signals()).toHaveLength(1);
        expect(signals()[0]?.aborted).toBe(false);

        await user.keyboard('{F12}');
        expect(onClose).toHaveBeenCalledTimes(1);
        setOpen(false);

        await waitFor(() => expect(signals()[0]?.aborted).toBe(true));
        await release(0, 0);

        expect(alertRegion()).toBeEmptyDOMElement();
        expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
        expect(signals()).toHaveLength(1);
        expect(sent('GET', CUSTOMER_PATH)).toHaveLength(1);
      });

      it('under StrictMode the read the simulated unmount aborted shows no alert, and the remount opens the record', async () => {
        const signals = customerReadSignals();
        const { queryClient } = renderDialog({ mode: 'display', custId: STORED.custId, strict: true });

        const dialog = await screen.findByRole('dialog', { name: DIALOG_NAMES.display });
        await waitFor(() => expect(screen.getByTestId(CATALOG_PROBE_ID)).toHaveAttribute('data-ready', 'true'));
        // Both reads: the one the simulated unmount aborted and the remount's.
        await settleReads(queryClient, 0, [0, 1]);

        await waitFor(() => expect(valuesOf(dialog)).toEqual(fieldsOf(STORED)));
        expect(alertRegion()).toBeEmptyDOMElement();
        // Development's extra effect cycle aborted the first read; the remount's read answered.
        expect(signals().map((signal) => signal?.aborted)).toEqual([true, false]);
      });

      it('a read answering DEM0599 while the window stays open shows the alert and opens on blank fields', async () => {
        const { onClose, release } = await renderWhileReading('display');

        await release(0, 0);

        await waitFor(() => expect(within(alertRegion()).getAllByText('Customer deleted. Exit & redo search.')).toHaveLength(1));
        const dialog = await screen.findByRole('dialog', { name: DIALOG_NAMES.display });
        expect(valuesOf(dialog)).toEqual(BLANK);
        expect(onClose).not.toHaveBeenCalled();
      });

      it('a reopening while the closed window still reads sends its own read, and only that read alerts', async () => {
        const { user, onClose, setOpen, held, release } = await renderWhileReading('display');

        await user.keyboard('{Escape}');
        setOpen(false);
        setOpen(true);
        await waitFor(() => expect(held).toHaveLength(2));
        expect(sent('GET', CUSTOMER_PATH)).toHaveLength(2);

        // The closed window's read answers first: nothing is shown for it.
        await release(0, 1);
        expect(alertRegion()).toBeEmptyDOMElement();
        expect(screen.queryByRole('dialog')).not.toBeInTheDocument();

        // The open window's read answers: its DEM0599, once, and the blank window.
        await release(1, 0);
        await waitFor(() => expect(within(alertRegion()).getAllByText('Customer deleted. Exit & redo search.')).toHaveLength(1));
        const dialog = await screen.findByRole('dialog', { name: DIALOG_NAMES.display });
        expect(valuesOf(dialog)).toEqual(BLANK);
        expect(onClose).toHaveBeenCalledTimes(1);
      });
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
        acctMgr: 'NORMAN,  ABBOT R.',
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
      const review = hold();
      server.use(http.post(REVIEW_PATH, () => review.answer(() => reviewPassed('EDIT', fieldsOf(STORED)))));
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
        review.release();
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

    // A stale update (UpdateRecd :593-599). After the window read the record,
    // another user changed City and raised the stored version to 1.

    /** The customer as now stored: another user's City PORTLAND under version 1. */
    const CONCURRENT: CustomerResponse = {
      ...STORED,
      city: 'PORTLAND',
      chgTime: '2026-10-05T14:10:00Z',
      chgUser: 'other-user',
      version: STORED.version + 1,
    };

    /**
     * The first review's answer to the Name {@link confirmEdit} types: Name
     * NEW NAME CO, with Account Manager normalized (uppercased, its interior
     * blanks kept) and Address standardized, so it differs from the stored
     * record in those three fields. The rejected PUT sends exactly these values.
     */
    const FIRST_REVIEWED: FieldValues = {
      ...fieldsOf(STORED),
      name: 'NEW NAME CO',
      addr: 'P.O. BOX 103 9218 VIVAMUS AVE',
      acctMgr: 'NORMAN,  ABBOT R.',
    };

    /** The record the API answers a successful save with: `values` under the next version, stamped by the principal. */
    function savedAs(values: FieldValues): CustomerResponse {
      return {
        ...CONCURRENT,
        ...values,
        chgTime: '2026-10-05T15:00:00Z',
        chgUser: MAINTENANCE_USER.username,
        version: CONCURRENT.version + 1,
      };
    }

    /**
     * Stores {@link CONCURRENT} as the API's conditional UPDATE sees it: a
     * re-read serves it, a PUT carrying its version saves and is answered
     * `saved`, and a PUT carrying any other version is stale and is answered
     * 409 DEM1002 with it. Called once the window has read the record.
     */
    function storeConcurrentChange(saved: CustomerResponse): void {
      served = { ...CONCURRENT };
      server.use(
        http.put(CUSTOMER_PATH, async ({ request }) => {
          const { version } = (await request.json()) as CustomerUpdateRequest;
          return version === CONCURRENT.version
            ? HttpResponse.json(saved)
            : problem(409, 'DEM1002', { current: CONCURRENT, instance: CUSTOMER_PATH });
        }),
      );
    }

    /**
     * Answers the reviews in order, the n-th passing with the n-th of
     * `answers` and DEM0000. A review beyond them is answered 500 DEM9999,
     * so an unexpected review shows as an alert and an extra request body.
     */
    function answerReviewsInOrder(...answers: FieldValues[]): void {
      let answered = 0;
      answerReview(() => {
        const customer = answers[answered];
        answered += 1;
        return customer === undefined
          ? problem(500, 'DEM9999', { instance: REVIEW_PATH })
          : reviewPassed('EDIT', customer, true);
      });
    }

    /** The method and path of every recorded request, in the order they were sent. */
    function requestOrder(): string[] {
      return traffic.map(({ method, path }) => `${method} ${path}`);
    }

    it('a 409 DEM1002 opens the comparison with its text and no toast; Refresh loads the current record, whose version the next save carries', async () => {
      const current = CONCURRENT;
      // The Refreshed record reviewed again: Account Manager normalized and Address standardized.
      const refreshedReviewed: FieldValues = {
        ...fieldsOf(current),
        addr: 'P.O. BOX 103 9218 VIVAMUS AVE',
        acctMgr: 'NORMAN,  ABBOT R.',
      };
      const saved = savedAs(refreshedReviewed);
      answerReviewsInOrder(FIRST_REVIEWED, refreshedReviewed);
      const { user, dialog, onClose } = await confirmEdit();
      storeConcurrentChange(saved);

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
      expect(sent('PUT', CUSTOMER_PATH)).toHaveLength(1);

      // Enter reviews the refreshed fields; Enter at the confirmation saves
      // the reviewed values under the version Refresh took from `current`.
      await user.click(inputOf(dialog, 'name'));
      const panel = await reviewToConfirmation(user, dialog, 'DEM0000');
      expect(await bodiesOf<ReviewRequest>('POST', REVIEW_PATH)).toEqual([
        { purpose: 'EDIT', ...fieldsOf(STORED), name: 'NEW NAME CO' },
        { purpose: 'EDIT', ...fieldsOf(current) },
      ]);
      expect(valuesOf(panel)).toEqual(refreshedReviewed);

      await user.keyboard('{Enter}');

      await waitFor(() => expect(onClose).toHaveBeenCalledTimes(1));
      expect(onClose).toHaveBeenCalledWith({ saved });
      expect(await bodiesOf<CustomerUpdateRequest>('PUT', CUSTOMER_PATH)).toEqual([
        { ...FIRST_REVIEWED, version: STORED.version },
        { ...refreshedReviewed, version: current.version },
      ]);
      expect(requestOrder()).toEqual([
        `GET ${CUSTOMER_PATH}`,
        `POST ${REVIEW_PATH}`,
        `PUT ${CUSTOMER_PATH}`,
        `POST ${REVIEW_PATH}`,
        `PUT ${CUSTOMER_PATH}`,
      ]);
      expect(alertRegion()).toBeEmptyDOMElement();
    });

    it("a 409 DEM1002 then Re-apply my changes reviews the user's changes on the current record and saves the reviewed values under its version", async () => {
      // Re-apply copies the fields the rejected values changed (Name, the
      // standardized Address and the normalized Account Manager) onto the
      // current record, so another user's City PORTLAND is kept.
      const merged: FieldValues = {
        ...fieldsOf(CONCURRENT),
        name: 'NEW NAME CO',
        addr: 'P.O. BOX 103 9218 VIVAMUS AVE',
        acctMgr: 'NORMAN,  ABBOT R.',
      };
      expect(merged.city).toBe('PORTLAND');
      // The merged fields reviewed: the address service gives the Portland
      // address a new ZIP+4, so the values to save differ from the merged ones.
      const reviewed: FieldValues = { ...merged, zip: '04101-3509' };
      expect(reviewed).not.toEqual(merged);
      const saved = savedAs(reviewed);
      answerReviewsInOrder(FIRST_REVIEWED, reviewed);
      const { user, dialog, panel, onClose } = await confirmEdit();
      storeConcurrentChange(saved);

      await user.keyboard('{Enter}');

      const compare = await screen.findByRole('dialog', { name: CONFLICT_NAME });
      expect(alertRegion()).toBeEmptyDOMElement();

      await user.click(within(compare).getByRole('button', { name: 'Re-apply my changes' }));

      // Re-apply returns to review by itself: the merged fields are reviewed,
      // and the passed review shows a new confirmation, focused, with DEM0000.
      await waitFor(() => expect(compare).not.toBeInTheDocument());
      await within(statusRegion()).findByText(messageText('DEM0000'));
      const confirmation = await within(dialog).findByRole('group', { name: CONFIRM_GROUP_NAME });
      expect(confirmation).not.toBe(panel);
      await waitFor(() => expect(confirmation).toHaveFocus());
      expect(await bodiesOf<ReviewRequest>('POST', REVIEW_PATH)).toEqual([
        { purpose: 'EDIT', ...fieldsOf(STORED), name: 'NEW NAME CO' },
        { purpose: 'EDIT', ...merged },
      ]);
      expect(valuesOf(confirmation)).toEqual(reviewed);
      // Re-apply itself writes nothing and re-reads nothing.
      expect(sent('PUT', CUSTOMER_PATH)).toHaveLength(1);
      expect(sent('GET', CUSTOMER_PATH)).toHaveLength(1);
      expect(alertRegion()).toBeEmptyDOMElement();
      expect(onClose).not.toHaveBeenCalled();

      await user.keyboard('{Enter}');

      // The save sends the reviewed values, conditional on the current
      // record's version rather than the stale one the window first read.
      await waitFor(() => expect(onClose).toHaveBeenCalledTimes(1));
      expect(onClose).toHaveBeenCalledWith({ saved });
      expect(await bodiesOf<CustomerUpdateRequest>('PUT', CUSTOMER_PATH)).toEqual([
        { ...FIRST_REVIEWED, version: STORED.version },
        { ...reviewed, version: CONCURRENT.version },
      ]);
      expect(requestOrder()).toEqual([
        `GET ${CUSTOMER_PATH}`,
        `POST ${REVIEW_PATH}`,
        `PUT ${CUSTOMER_PATH}`,
        `POST ${REVIEW_PATH}`,
        `PUT ${CUSTOMER_PATH}`,
      ]);
      expect(alertRegion()).toBeEmptyDOMElement();
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
  // PageUp and PageDown: n/a in the detail window (AAP 0.3.8 keyboard table)
  // -------------------------------------------------------------------------

  describe('PageUp and PageDown (n/a in every phase)', () => {
    const PAGING_KEYS = ['PageUp', 'PageDown'] as const;
    type PagingKey = (typeof PAGING_KEYS)[number];

    /**
     * Presses `key` on the focused element, as a user does, and reports
     * whether its browser default (paging, scrolling, moving the caret) was
     * prevented. The keydown is observed in the window's capture phase, ahead
     * of the key scope's document listener, and read once dispatch is over.
     */
    async function pressPaging(user: UserEvent, key: PagingKey): Promise<boolean> {
      const pressed: KeyboardEvent[] = [];
      const observe = (event: KeyboardEvent): void => {
        if (event.key === key) {
          pressed.push(event);
        }
      };
      window.addEventListener('keydown', observe, true);
      try {
        await user.keyboard(`{${key}}`);
      } finally {
        window.removeEventListener('keydown', observe, true);
      }
      expect(pressed).toHaveLength(1);
      return pressed.every((event) => event.defaultPrevented);
    }

    /** Asserts that the alert region holds exactly one message: DEM0003 "Key is not active now". */
    function expectKeyNotActiveOnce(): void {
      const region = alertRegion();
      expect(region.children).toHaveLength(1);
      expect(within(region).getByText('Key is not active now')).toBeInTheDocument();
    }

    it.each(PAGING_KEYS)('display: %s shows DEM0003 once, is not paged natively and the window stays as it was', async (key) => {
      const { user, dialog, onClose } = await openDialog('display');

      expect(await pressPaging(user, key)).toBe(true);

      expectKeyNotActiveOnce();
      expect(onClose).not.toHaveBeenCalled();
      expect(screen.getByRole('dialog', { name: DIALOG_NAMES.display })).toBe(dialog);
      expect(valuesOf(dialog)).toEqual(fieldsOf(STORED));
      expect(inputOf(dialog, 'name')).toHaveAttribute('readonly');

      // The window still answers its own keys, and the paging key sent nothing.
      await user.keyboard('{F12}');
      expect(onClose).toHaveBeenCalledTimes(1);
      expect(onClose).toHaveBeenCalledWith();
      expect(traffic.map(({ method, path }) => `${method} ${path}`)).toEqual([`GET ${CUSTOMER_PATH}`]);
    });

    it.each(PAGING_KEYS)('edit form: %s shows DEM0003 once and keeps the typed entries on the form, sending nothing', async (key) => {
      const { user, dialog, onClose } = await openDialog('edit');
      await retype(user, dialog, 'name', 'paged name co');
      const typed: FieldValues = { ...fieldsOf(STORED), name: 'PAGED NAME CO' };
      expect(valuesOf(dialog)).toEqual(typed);

      expect(await pressPaging(user, key)).toBe(true);

      expectKeyNotActiveOnce();
      expect(queryConfirmation(dialog)).not.toBeInTheDocument();
      expect(valuesOf(dialog)).toEqual(typed);
      expect(inputOf(dialog, 'name')).not.toHaveAttribute('readonly');
      expect(inputOf(dialog, 'name')).toHaveFocus();
      expect(onClose).not.toHaveBeenCalled();

      // Enter then reviews exactly the typed entries; the paging key sent nothing before it.
      await reviewToConfirmation(user, dialog, 'DEM0000');
      expect(traffic.map(({ method, path }) => `${method} ${path}`)).toEqual([
        `GET ${CUSTOMER_PATH}`,
        `POST ${REVIEW_PATH}`,
      ]);
      expect(await bodiesOf<ReviewRequest>('POST', REVIEW_PATH)).toEqual([{ purpose: 'EDIT', ...typed }]);
    });

    it.each(PAGING_KEYS)('add form: %s shows DEM0003 once and keeps the typed entries on the form, sending nothing', async (key) => {
      const { user, dialog, onClose } = await openDialog('add');
      await fillForm(user, dialog, NEW_CUSTOMER);
      expect(valuesOf(dialog)).toEqual(NEW_CUSTOMER);

      expect(await pressPaging(user, key)).toBe(true);

      expectKeyNotActiveOnce();
      expect(queryConfirmation(dialog)).not.toBeInTheDocument();
      expect(valuesOf(dialog)).toEqual(NEW_CUSTOMER);
      expect(inputOf(dialog, 'name')).not.toHaveAttribute('readonly');
      expect(traffic).toHaveLength(0);
      expect(onClose).not.toHaveBeenCalled();

      // Enter then reviews exactly the typed entries; the paging key sent nothing before it.
      await reviewToConfirmation(user, dialog, 'DEM0009');
      expect(traffic.map(({ method, path }) => `${method} ${path}`)).toEqual([`POST ${REVIEW_PATH}`]);
      expect(await bodiesOf<ReviewRequest>('POST', REVIEW_PATH)).toEqual([{ purpose: 'ADD', ...NEW_CUSTOMER }]);
    });

    it.each(PAGING_KEYS)('edit confirmation: %s shows DEM0003 once and leaves the confirmation shown, with no update', async (key) => {
      const { user, dialog, onClose } = await openDialog('edit');
      await retype(user, dialog, 'name', 'paged name co');
      const panel = await reviewToConfirmation(user, dialog, 'DEM0000');
      const confirmed = valuesOf(panel);

      expect(await pressPaging(user, key)).toBe(true);

      expectKeyNotActiveOnce();
      expect(queryConfirmation(dialog)).toBe(panel);
      expect(valuesOf(panel)).toEqual(confirmed);
      expect(panel).toHaveFocus();
      expect(sent('PUT', CUSTOMER_PATH)).toHaveLength(0);
      expect(onClose).not.toHaveBeenCalled();

      // Enter then commits exactly the confirmed values with the version read;
      // the paging key sent nothing before it.
      const saved: CustomerResponse = { ...STORED, ...confirmed, version: STORED.version + 1 };
      answerUpdate(() => HttpResponse.json(saved));
      await user.keyboard('{Enter}');
      await waitFor(() => expect(onClose).toHaveBeenCalledTimes(1));
      expect(onClose).toHaveBeenCalledWith({ saved });
      expect(traffic.map(({ method, path }) => `${method} ${path}`)).toEqual([
        `GET ${CUSTOMER_PATH}`,
        `POST ${REVIEW_PATH}`,
        `PUT ${CUSTOMER_PATH}`,
      ]);
      expect(await bodiesOf<CustomerUpdateRequest>('PUT', CUSTOMER_PATH)).toEqual([
        { ...confirmed, version: STORED.version },
      ]);
    });

    it.each(PAGING_KEYS)('add confirmation: %s shows DEM0003 once and leaves the confirmation shown, with no add', async (key) => {
      const { user, dialog, onClose } = await openDialog('add');
      await fillForm(user, dialog, NEW_CUSTOMER);
      const panel = await reviewToConfirmation(user, dialog, 'DEM0009');

      expect(await pressPaging(user, key)).toBe(true);

      expectKeyNotActiveOnce();
      expect(queryConfirmation(dialog)).toBe(panel);
      expect(valuesOf(panel)).toEqual(NEW_CUSTOMER);
      expect(panel).toHaveFocus();
      expect(sent('POST', ADD_PATH)).toHaveLength(0);
      expect(onClose).not.toHaveBeenCalled();

      // Enter then adds exactly the confirmed values; the paging key sent nothing before it.
      await user.keyboard('{Enter}');
      await waitFor(() => expect(onClose).toHaveBeenCalledTimes(1));
      expect(onClose).toHaveBeenCalledWith({ added: true });
      expect(traffic.map(({ method, path }) => `${method} ${path}`)).toEqual([`POST ${REVIEW_PATH}`, `POST ${ADD_PATH}`]);
      expect(await bodiesOf<unknown>('POST', ADD_PATH)).toEqual([NEW_CUSTOMER]);
    });

    it.each(PAGING_KEYS)('while the opening read is pending: %s is swallowed with no message, no close and no native paging', async (key) => {
      const read = hold();
      server.use(http.get(CUSTOMER_PATH, () => read.answer(() => HttpResponse.json({ ...STORED }))));
      const { user, onClose } = renderDialog({ mode: 'edit', custId: STORED.custId });
      try {
        await waitFor(() => expect(screen.getByTestId(CATALOG_PROBE_ID)).toHaveAttribute('data-ready', 'true'));
        await waitFor(() => expect(sent('GET', CUSTOMER_PATH)).toHaveLength(1));
        expect(screen.queryByRole('dialog')).not.toBeInTheDocument();

        expect(await pressPaging(user, key)).toBe(true);

        expect(alertRegion()).toBeEmptyDOMElement();
        expect(onClose).not.toHaveBeenCalled();
        expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
      } finally {
        read.release();
      }

      // The window then opens on the stored record, read once.
      const dialog = await screen.findByRole('dialog', { name: DIALOG_NAMES.edit });
      expect(valuesOf(dialog)).toEqual(fieldsOf(STORED));
      expect(sent('GET', CUSTOMER_PATH)).toHaveLength(1);
      expect(alertRegion()).toBeEmptyDOMElement();
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
  // Change stamp (FillScreenFields :340-363, run again at the edit
  // confirmation :221-225)
  // -------------------------------------------------------------------------

  describe('change stamp', () => {
    /** The stamp line of `record` as the window shows it, built by the one formatter. */
    function stampLineOf(record: CustomerResponse): string {
      const text = formatChangeStamp(record.chgTime, record.chgUser);
      if (text === null) {
        throw new Error(`formatChangeStamp hides the stamp of ${record.custId}`);
      }
      return `Last Change ${text}`;
    }

    /** Every "Last Change …" line inside `scope`. */
    function stampLinesIn(scope: HTMLElement): HTMLElement[] {
      return within(scope).queryAllByText(/^Last Change /);
    }

    it('a record a user changed shows "Last Change … by <user>" on the form and the same line at the edit confirmation', async () => {
      const stamped: CustomerResponse = { ...STORED, chgTime: '2026-10-05T14:03:09Z', chgUser: MAINTENANCE_USER.username };
      served = stamped;
      const stampLine = stampLineOf(stamped);
      expect(stampLine.endsWith(` by ${MAINTENANCE_USER.username}`)).toBe(true);
      const { user, dialog } = await openDialog('edit');

      // The form: one stamp line, after the nine fields.
      const formStamp = within(dialog).getByText(stampLine);
      expect(stampLinesIn(dialog)).toEqual([formStamp]);
      expect(formStamp.tagName).toBe('P');
      expect(formStamp).toHaveClass('change-stamp');
      expect(formStamp.previousElementSibling).toContainElement(inputOf(dialog, 'corpPhone'));

      await retype(user, dialog, 'name', 'stamped name co');
      const panel = await reviewToConfirmation(user, dialog, 'DEM0000');

      // The confirmation: the identical line, once in the window, inside the
      // panel and after its nine fields, as on the form.
      const confirmStamp = within(panel).getByText(stampLine);
      expect(stampLinesIn(dialog)).toEqual([confirmStamp]);
      expect(confirmStamp.tagName).toBe('P');
      expect(confirmStamp).toHaveClass('change-stamp');
      expect(confirmStamp.previousElementSibling).toContainElement(inputOf(panel, 'corpPhone'));
    });

    it('a *SYSTEM* record shows no stamp on the form or at the edit confirmation', async () => {
      expect(STORED.chgUser).toBe('*SYSTEM*');
      expect(formatChangeStamp(STORED.chgTime, STORED.chgUser)).toBeNull();
      const { user, dialog } = await openDialog('edit');

      expect(stampLinesIn(dialog)).toEqual([]);

      await retype(user, dialog, 'name', 'system name co');
      await reviewToConfirmation(user, dialog, 'DEM0000');

      expect(stampLinesIn(dialog)).toEqual([]);
    });

    it('add shows no stamp on the form or at the confirmation: there is no stored record', async () => {
      // A stamped stored row must not leak into add, which reads no record.
      served = { ...STORED, chgTime: '2026-10-05T14:03:09Z', chgUser: MAINTENANCE_USER.username };
      const { user, dialog } = await openDialog('add');

      expect(stampLinesIn(dialog)).toEqual([]);

      await fillForm(user, dialog, NEW_CUSTOMER);
      await reviewToConfirmation(user, dialog, 'DEM0009');

      expect(stampLinesIn(dialog)).toEqual([]);
      expect(sent('GET', CUSTOMER_PATH)).toHaveLength(0);
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

    it('a passed review takes the reviewed State, standardized: NV reviewed into TX, F4 at the confirmation, AZ cancelled back to TX', async () => {
      // The address service standardized the typed NV into TX, with the street
      // and the ZIP, so the reviewed State is neither the stored CA nor the typed NV.
      const reviewed: FieldValues = {
        ...fieldsOf(STORED),
        addr: 'P.O. BOX 103 9218 VIVAMUS AVE',
        state: 'TX',
        zip: '75201-0103',
        acctMgr: 'NORMAN,  ABBOT R.',
      };
      answerReview(() => reviewPassed('EDIT', reviewed, true));
      const { user, dialog, onClose } = await openDialog('edit');
      await retype(user, dialog, 'state', 'nv');
      expect(inputOf(dialog, 'state')).toHaveValue('NV');

      const panel = await reviewToConfirmation(user, dialog, 'DEM0000');

      expect(await bodiesOf<ReviewRequest>('POST', REVIEW_PATH)).toEqual([
        { purpose: 'EDIT', ...fieldsOf(STORED), state: 'NV' },
      ]);
      expect(within(statusRegion()).getByText('Press Enter to update. F12 to Cancel.')).toBeInTheDocument();
      expect(inputOf(panel, 'state')).toHaveValue('TX');
      expect(valuesOf(panel)).toEqual(reviewed);
      expect(within(panel).getByText('Address standardized.')).toBeInTheDocument();

      // F4 at the edit confirmation: DEM0003, then the form with the reviewed entries kept and no re-read.
      await user.keyboard('{F4}');

      await waitForForm(dialog);
      expect(within(alertRegion()).getByText('Key is not active now')).toBeInTheDocument();
      expect(valuesOf(dialog)).toEqual(reviewed);
      expect(sent('GET', CUSTOMER_PATH)).toHaveLength(1);

      await retype(user, dialog, 'state', 'az');
      expect(inputOf(dialog, 'state')).toHaveValue('AZ');
      await cancelStatePrompt(user, dialog);

      // The reviewed TX: not the stored CA, the typed NV or the AZ typed since.
      expect(inputOf(dialog, 'state')).toHaveValue('TX');
      await waitFor(() => expect(inputOf(dialog, 'state')).toHaveFocus());
      // Only State is put back: every other field stays as reviewed.
      expect(valuesOf(dialog)).toEqual(reviewed);
      expect(sent('POST', REVIEW_PATH)).toHaveLength(1);
      expect(sent('GET', CUSTOMER_PATH)).toHaveLength(1);
      expect(sent('PUT', CUSTOMER_PATH)).toHaveLength(0);
      expect(onClose).not.toHaveBeenCalled();
    });

    it('a DEM9898 address failure carrying stateAccepted NV makes it NV: AZ cancelled back to NV', async () => {
      const { user, dialog, onClose } = await openDialog('edit');
      // The typed NV passes the State rule; the stub address service then does
      // not find a street holding BADADDR, so the default review answers 422
      // DEM9898 with stateAccepted NV, the normalized input State.
      await retype(user, dialog, 'state', 'nv');
      await retype(user, dialog, 'addr', 'badaddr 1 main street');
      const typed: FieldValues = { ...fieldsOf(STORED), addr: 'BADADDR 1 MAIN STREET', state: 'NV' };

      await user.keyboard('{Enter}');

      await within(alertRegion()).findByText('USPS: Address Not Found.');
      expect(messageText('DEM9898', ['Address Not Found.'])).toBe('USPS: Address Not Found.');
      expect(await bodiesOf<ReviewRequest>('POST', REVIEW_PATH)).toEqual([{ purpose: 'EDIT', ...typed }]);
      await waitFor(() => expect(inputOf(dialog, 'addr')).toHaveFocus());
      for (const field of ['addr', 'city', 'state', 'zip'] as const) {
        expect(inputOf(dialog, field)).toHaveAttribute('aria-invalid', 'true');
      }
      expect(queryConfirmation(dialog)).not.toBeInTheDocument();

      await retype(user, dialog, 'state', 'az');
      expect(inputOf(dialog, 'state')).toHaveValue('AZ');
      await cancelStatePrompt(user, dialog);

      // The accepted NV: not the stored CA or the AZ typed since.
      expect(inputOf(dialog, 'state')).toHaveValue('NV');
      await waitFor(() => expect(inputOf(dialog, 'state')).toHaveFocus());
      // Only State is put back: every other field stays as typed.
      expect(valuesOf(dialog)).toEqual(typed);
      expect(sent('POST', REVIEW_PATH)).toHaveLength(1);
      expect(sent('PUT', CUSTOMER_PATH)).toHaveLength(0);
      expect(onClose).not.toHaveBeenCalled();
    });
  });

  // -------------------------------------------------------------------------
  // While a request is pending (review, F5 reload, save)
  // -------------------------------------------------------------------------

  describe('while a request is pending', () => {
    /** One key-bar button of the window, by its legend. */
    function keyButton(dialog: HTMLElement, legend: string): HTMLElement {
      const bar = within(dialog).getByRole('toolbar', { name: 'Function keys' });
      return within(bar).getByRole('button', { name: legend });
    }

    /**
     * Asserts the protected form of a pending request: every input
     * read-only, Name keeping `name` whether typed into or changed before a
     * render could protect it, Enter, F4 and F5 disabled and F12 enabled.
     */
    async function expectProtectedForm(user: UserEvent, dialog: HTMLElement, name: string): Promise<void> {
      for (const input of within(dialog).getAllByRole('textbox')) {
        expect(input).toHaveAttribute('readonly');
      }
      const nameInput = inputOf(dialog, 'name');
      await user.type(nameInput, 'zzz');
      expect(nameInput).toHaveValue(name);
      // A change event bypasses `readonly`, as a keystroke that lands before
      // the read-only render would: the draft still does not change.
      fireEvent.change(nameInput, { target: { value: 'TYPED WHILE PENDING' } });
      expect(nameInput).toHaveValue(name);
      for (const legend of ['Enter', 'F4=Prompt+', 'F5=Refresh']) {
        expect(keyButton(dialog, legend)).toBeDisabled();
      }
      expect(keyButton(dialog, 'F12=Cancel')).toBeEnabled();
    }

    it('a pending review protects the form and disables Enter, F4 and F5; the confirmation then shows the reviewed values', async () => {
      const gate = hold();
      const reviewed: FieldValues = { ...fieldsOf(STORED), name: 'NEW NAME CO' };
      server.use(http.post(REVIEW_PATH, () => gate.answer(() => reviewPassed('EDIT', reviewed))));
      const { user, dialog } = await openDialog('edit');
      await retype(user, dialog, 'name', 'new name co');

      await user.keyboard('{Enter}');
      await waitFor(() => expect(keyButton(dialog, 'Enter')).toBeDisabled());

      expect(sent('POST', REVIEW_PATH)).toHaveLength(1);
      await expectProtectedForm(user, dialog, 'NEW NAME CO');
      expect(queryConfirmation(dialog)).not.toBeInTheDocument();

      gate.release();

      const panel = await within(dialog).findByRole('group', { name: CONFIRM_GROUP_NAME });
      expect(valuesOf(panel)).toEqual(reviewed);
      await within(statusRegion()).findByText(messageText('DEM0000'));
      await waitFor(() => expect(panel).toHaveFocus());
      for (const legend of ['Enter', 'F4=Prompt+', 'F5=Refresh', 'F12=Cancel']) {
        expect(keyButton(dialog, legend)).toBeEnabled();
      }
      expect(sent('POST', REVIEW_PATH)).toHaveLength(1);
    });

    it('F12 and the F12=Cancel button still close the window while a review is pending', async () => {
      const gate = hold();
      server.use(http.post(REVIEW_PATH, () => gate.answer(() => reviewPassed('EDIT', fieldsOf(STORED)))));
      const { user, dialog, onClose } = await openDialog('edit');

      await user.keyboard('{Enter}');
      await waitFor(() => expect(keyButton(dialog, 'Enter')).toBeDisabled());

      await user.keyboard('{F12}');
      expect(onClose).toHaveBeenCalledTimes(1);
      expect(onClose).toHaveBeenLastCalledWith();
      await user.click(keyButton(dialog, 'F12=Cancel'));
      expect(onClose).toHaveBeenCalledTimes(2);
      expect(onClose).toHaveBeenLastCalledWith();

      // The harness keeps the window rendered after onClose; let the review land.
      gate.release();
      await within(dialog).findByRole('group', { name: CONFIRM_GROUP_NAME });
    });

    it('a pending F5 reload protects the form and disables Enter, F4 and F5; the form then shows the reloaded values, editable', async () => {
      const { user, dialog } = await openDialog('edit');
      const reloaded: CustomerResponse = { ...STORED, name: 'NIBH RELOADED CO', chgUser: 'other-user', version: 4 };
      const gate = hold();
      server.use(http.get('/api/customers/:custId', () => gate.answer(() => HttpResponse.json({ ...reloaded }))));
      await retype(user, dialog, 'city', 'bangor');

      await user.keyboard('{F5}');
      await waitFor(() => expect(keyButton(dialog, 'F5=Refresh')).toBeDisabled());

      expect(sent('GET', CUSTOMER_PATH)).toHaveLength(2);
      await expectProtectedForm(user, dialog, STORED.name);
      expect(inputOf(dialog, 'city')).toHaveValue('BANGOR');

      gate.release();

      await waitFor(() => expect(inputOf(dialog, 'name')).toHaveValue('NIBH RELOADED CO'));
      expect(valuesOf(dialog)).toEqual(fieldsOf(reloaded));
      for (const { field } of FIELD_CONTRACT) {
        expect(inputOf(dialog, field)).not.toHaveAttribute('readonly');
      }
      for (const legend of ['Enter', 'F4=Prompt+', 'F5=Refresh', 'F12=Cancel']) {
        expect(keyButton(dialog, legend)).toBeEnabled();
      }
      await waitFor(() => expect(inputOf(dialog, 'name')).toHaveFocus());
      await user.type(inputOf(dialog, 'city'), 'x');
      expect(inputOf(dialog, 'city')).toHaveValue(`${reloaded.city}X`);
      expect(sent('GET', CUSTOMER_PATH)).toHaveLength(2);
    });

    it('a key-bar Enter pressed from the keyboard leaves focus inside the window, also after a 502 that moves none', async () => {
      const gate = hold();
      // The stored values pass the State rule, so the address service's
      // failure carries the accepted input State, as the review answers it.
      server.use(
        http.post(REVIEW_PATH, () =>
          gate.answer(() => problem(502, 'APP0502', { stateAccepted: STORED.state, instance: REVIEW_PATH })),
        ),
      );
      const { user, dialog } = await openDialog('edit');
      const enterKey = keyButton(dialog, 'Enter');
      act(() => enterKey.focus());

      await user.keyboard('{Enter}');
      await waitFor(() => expect(enterKey).toBeDisabled());

      // The window body, the form's key container, holds focus meanwhile.
      await waitFor(() => expect(document.activeElement).toHaveAttribute('aria-busy', 'true'));
      expect(dialog).toContainElement(document.activeElement as HTMLElement);

      gate.release();

      await within(alertRegion()).findByText(messageText('APP0502'));
      await waitFor(() => expect(enterKey).toBeEnabled());
      const focused = document.activeElement as HTMLElement;
      expect(dialog).toContainElement(focused);
      expect(focused).not.toBe(document.body);
      // Enter on the window body is a command: it reviews again.
      await user.keyboard('{Enter}');
      await waitFor(() => expect(sent('POST', REVIEW_PATH)).toHaveLength(2));
    });

    it('a pending save disables all four keys at the confirmation; a 500 DEM9999 then leaves the confirmation focused', async () => {
      const gate = hold();
      answerReview(() => reviewPassed('EDIT', fieldsOf(STORED)));
      server.use(
        http.put('/api/customers/:custId', () =>
          gate.answer(() => problem(500, 'DEM9999', { instance: CUSTOMER_PATH })),
        ),
      );
      const { user, dialog, onClose } = await openDialog('edit');
      const panel = await reviewToConfirmation(user, dialog, 'DEM0000');
      const enterKey = keyButton(dialog, 'Enter');
      act(() => enterKey.focus());

      await user.keyboard('{Enter}');
      await waitFor(() => expect(enterKey).toBeDisabled());

      for (const legend of ['Enter', 'F4=Prompt+', 'F5=Refresh', 'F12=Cancel']) {
        expect(keyButton(dialog, legend)).toBeDisabled();
      }
      await waitFor(() => expect(panel).toHaveFocus());
      expect(sent('PUT', CUSTOMER_PATH)).toHaveLength(1);

      gate.release();

      await within(alertRegion()).findByText(messageText('DEM9999'));
      expect(messageText('DEM9999')).toBe('Program Error! Please contact IT now.');
      await waitFor(() => expect(enterKey).toBeEnabled());
      expect(queryConfirmation(dialog)).toBe(panel);
      expect(panel).toHaveFocus();
      expect(onClose).not.toHaveBeenCalled();
    });

    it('a pending add disables all four keys at the add confirmation; a 503 APP0503 then leaves the confirmation focused', async () => {
      // Only an add allocates a customer id, so only it can find the ids exhausted.
      const gate = hold();
      server.use(http.post(ADD_PATH, () => gate.answer(() => problem(503, 'APP0503', { instance: ADD_PATH }))));
      const { user, dialog, onClose } = await openDialog('add');
      await fillForm(user, dialog, NEW_CUSTOMER);
      const panel = await reviewToConfirmation(user, dialog, 'DEM0009');
      const enterKey = keyButton(dialog, 'Enter');
      act(() => enterKey.focus());

      await user.keyboard('{Enter}');
      await waitFor(() => expect(enterKey).toBeDisabled());

      for (const legend of ['Enter', 'F4=Prompt+', 'F5=Refresh', 'F12=Cancel']) {
        expect(keyButton(dialog, legend)).toBeDisabled();
      }
      await waitFor(() => expect(panel).toHaveFocus());
      expect(sent('POST', ADD_PATH)).toHaveLength(1);

      gate.release();

      await within(alertRegion()).findByText(messageText('APP0503'));
      expect(messageText('APP0503')).toBe('No customer ids are left. Contact IT.');
      await waitFor(() => expect(enterKey).toBeEnabled());
      for (const legend of ['Enter', 'F4=Prompt+', 'F5=Refresh', 'F12=Cancel']) {
        expect(keyButton(dialog, legend)).toBeEnabled();
      }
      expect(queryConfirmation(dialog)).toBe(panel);
      expect(panel).toHaveFocus();
      expect(valuesOf(panel)).toEqual(NEW_CUSTOMER);
      expect(onClose).not.toHaveBeenCalled();
      expect(await bodiesOf<unknown>('POST', ADD_PATH)).toEqual([NEW_CUSTOMER]);
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
