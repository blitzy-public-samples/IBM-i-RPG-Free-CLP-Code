/**
 * Tests of the DEM1002 compare window: `./ConflictCompareDialog.tsx`.
 *
 * What it replaces. MTNCUSTR's UpdateRecd updates CUSTMAST only where CHGTIME
 * still equals the value read earlier; when no row matches it sends DEM1002
 * "Someone else changed record.  Rewiew data." to the message subfile,
 * re-reads the record (ReadRecd) and refills the screen with it
 * (FillScreenFields) (5250_Subfile/MTNCUSTR.SQLRPGLE:593-599; message text
 * 5250_Subfile/CRTMSGF.CLLE:40). Here a PUT carrying a stale `version` is
 * answered 409 DEM1002 with `current`, and the detail dialog opens this
 * window over the form.
 *
 * What is pinned down here (AAP 0.8.3, `ConflictCompareDialog.test.tsx`):
 * - **DEM1002 text.** The window shows the catalog text with its typos fixed,
 *   "Someone else changed record. Review data.", in the window itself; the
 *   alert region stays empty, because `useProblemPresenter` publishes no
 *   toast for DEM1002 when the detail dialog handles the conflict.
 * - **Differing fields marked.** One comparison row per customer field in
 *   screen order, labelled as `CUSTOMER_FORM_FIELDS` labels the form, with the
 *   user's value, the current value and "Changed" exactly where they differ.
 * - **Refresh loads `current`.** The Refresh button, F12 and Escape (which
 *   the key scope delivers as F12) each call `onRefresh` once, the source
 *   outcome of a stale update.
 * - **Re-apply copies the edits onto `current.version`.** `onReapply`
 *   receives `current`'s nine fields with each field the user edited taken
 *   from `mine`, and `current.version`, so the next review and save are
 *   conditional on the record just shown.
 * - **Keys.** Any other function key shows DEM0003 "Key is not active now"
 *   and calls nothing; Enter on a focused button keeps its native click.
 * - **Closed.** `open={false}` renders no window and registers no key scope.
 *
 * Fixtures. `original` is seed row AAAD (`customerDetail`) as the user read
 * it. The user edited Name and ZIP (`mine`); meanwhile someone else changed
 * City, which raised the stored version by one (`current`). Name, City and
 * ZIP therefore differ between `mine` and `current`, and only Name and ZIP
 * are the user's edits.
 *
 * The harness mounts the providers in the order `src/App.tsx` does, with a
 * fresh `QueryClient` per test, and the default MSW handler of
 * `/api/messages` serves the catalog fixture. Every test waits for the
 * catalog text before asserting, since `format` returns the bare code until
 * the catalog has loaded.
 */
import type { ReactElement, ReactNode } from 'react';
import { render, screen, within } from '@testing-library/react';
import type { RenderResult } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type { UserEvent } from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { Mock } from 'vitest';
import { setCredentials } from '../../api/client';
import type { CustomerFields, CustomerResponse } from '../../api/customers';
import { AuthProvider } from '../../auth/AuthProvider';
import { ToastProvider, useToasts } from '../../components/ToastRegion';
import { KeyScopeProvider } from '../../keyboard/KeyScopeProvider';
import { MessageCatalogProvider } from '../../messages/MessageCatalogProvider';
import { catalog, customerDetail, messageText, users } from '../../test/handlers';
import { ConflictCompareDialog } from './ConflictCompareDialog';
import type { ConflictCompareDialogProps } from './ConflictCompareDialog';
import { CUSTOMER_FORM_FIELDS } from './CustomerForm';
import type { CustomerFieldName } from './CustomerForm';

// ---------------------------------------------------------------------------
// Fixtures
// ---------------------------------------------------------------------------

/** The nine customer data fields, every one present as a string. */
type FieldValues = Record<CustomerFieldName, string>;

/**
 * The nine customer data fields of a stored record, without `custId`,
 * `chgTime`, `chgUser` and `version`: the shape of a write body.
 */
function fields(record: CustomerResponse): FieldValues {
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

/** The demo user with the MAINTENANCE role, who alone can reach an update conflict. */
const MAINTENANCE_USER = (() => {
  const user = users.find((candidate) => candidate.roles.includes('MAINTENANCE'));
  if (user === undefined) {
    throw new Error('The users fixture holds no MAINTENANCE user');
  }
  return user;
})();

/** Seed row AAAD as the user read it before editing: the record whose version the rejected PUT carried. */
const original: CustomerResponse = { ...customerDetail };

/** What the user tried to save: `original` with Name and ZIP edited. */
const mine: FieldValues = { ...fields(original), name: 'NIBH LOR HOLDINGS', zip: '15762-0002' };

/** The record as now stored: someone else changed City, which raised the version by one. */
const current: CustomerResponse = {
  ...original,
  city: 'PORTLAND',
  chgTime: '2026-10-05T14:10:00Z',
  chgUser: 'other-user',
  version: original.version + 1,
};

/** The fields whose value in `mine` differs from `current`: the user's two edits and the other user's one. */
const DIFFERING_FIELDS: ReadonlySet<CustomerFieldName> = new Set<CustomerFieldName>(['name', 'city', 'zip']);

/** Accessible name of the window: its ScreenHeader title and function line. */
const DIALOG_NAME = 'Customer Master Record Changed';

/** Accessible name of the comparison table: its caption. */
const TABLE_NAME = 'Your changes compared with the current record';

// ---------------------------------------------------------------------------
// Harness
// ---------------------------------------------------------------------------

/** Wires the toast clear to the key scope's `onBeforeCommand`, as `src/App.tsx` does. */
function KeyedScreens({ children }: { children: ReactNode }) {
  const { clear } = useToasts();
  return <KeyScopeProvider onBeforeCommand={clear}>{children}</KeyScopeProvider>;
}

/**
 * Renders `ui` inside the application's providers: a fresh query client
 * (no retries), the message catalog, the toast host, the key scope stack, a
 * router and a MAINTENANCE session. The providers are passed as the render
 * `wrapper`, so `rerender` keeps the same query client, catalog and session.
 */
function renderWithProviders(ui: ReactElement): RenderResult {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  function Providers({ children }: { children: ReactNode }) {
    return (
      <QueryClientProvider client={queryClient}>
        <MessageCatalogProvider>
          <ToastProvider>
            <KeyedScreens>
              <MemoryRouter>
                <AuthProvider initialSession={{ username: MAINTENANCE_USER.username, roles: ['MAINTENANCE'] }}>
                  {children}
                </AuthProvider>
              </MemoryRouter>
            </KeyedScreens>
          </ToastProvider>
        </MessageCatalogProvider>
      </QueryClientProvider>
    );
  }
  return render(ui, { wrapper: Providers });
}

/** The two callbacks the detail dialog passes, as fresh mocks. */
type Callbacks = {
  onRefresh: Mock<ConflictCompareDialogProps['onRefresh']>;
  onReapply: Mock<ConflictCompareDialogProps['onReapply']>;
};

/** New mock callbacks for one test. */
function newCallbacks(): Callbacks {
  return {
    onRefresh: vi.fn<ConflictCompareDialogProps['onRefresh']>(),
    onReapply: vi.fn<ConflictCompareDialogProps['onReapply']>(),
  };
}

/** The window as the detail dialog renders it for the fixture conflict. */
function conflictDialog(open: boolean, callbacks: Callbacks): ReactElement {
  const originalFields: CustomerFields = fields(original);
  return (
    <ConflictCompareDialog
      open={open}
      original={originalFields}
      mine={mine}
      current={current}
      onRefresh={callbacks.onRefresh}
      onReapply={callbacks.onReapply}
    />
  );
}

/** What {@link openDialog} returns. */
type OpenedDialog = Callbacks & { user: UserEvent; dialog: HTMLElement };

/**
 * Renders the open window and waits until the catalog has loaded, which is
 * when the DEM1002 code has turned into its text.
 */
async function openDialog(): Promise<OpenedDialog> {
  const user = userEvent.setup();
  const callbacks = newCallbacks();
  renderWithProviders(conflictDialog(true, callbacks));
  const dialog = await screen.findByRole('dialog', { name: DIALOG_NAME });
  await within(dialog).findByText(messageText('DEM1002'));
  return { ...callbacks, user, dialog };
}

/** The shared alert region of the toast host (problem details and client-raised errors). */
function alertRegion(): HTMLElement {
  return screen.getByRole('alert');
}

beforeEach(() => {
  // The window sends no request of its own; the session's credentials are
  // stored as after a real sign-in, so the harness matches the running app.
  setCredentials({ username: MAINTENANCE_USER.username, password: MAINTENANCE_USER.password });
});

afterEach(() => {
  setCredentials(null);
});

// ---------------------------------------------------------------------------
// Specs
// ---------------------------------------------------------------------------

describe('ConflictCompareDialog', () => {
  it('starts from the fixture conflict: the user edited Name and ZIP, someone else changed City', () => {
    expect(mine.city).toBe(original.city);
    expect(mine.name).not.toBe(original.name);
    expect(mine.zip).not.toBe(original.zip);
    expect(current.city).not.toBe(original.city);
    expect(current.version).toBe(original.version + 1);
  });

  describe('DEM1002 text', () => {
    it('shows "Someone else changed record. Review data." in the window, not as a toast', async () => {
      const { dialog } = await openDialog();

      // The source text with its typos fixed (CRTMSGF.CLLE:40, AAP 0.4.6),
      // exactly as the catalog serves it.
      expect(catalog.DEM1002).toBe('Someone else changed record. Review data.');
      expect(messageText('DEM1002')).toBe(catalog.DEM1002);

      const message = within(dialog).getByText('Someone else changed record. Review data.');
      expect(message).toBeInTheDocument();
      expect(within(dialog).getByRole('button', { name: 'Refresh' })).toHaveAccessibleDescription(
        'Someone else changed record. Review data.',
      );

      // The window carries the text itself: nothing is published to the
      // shared message host.
      expect(alertRegion()).toBeEmptyDOMElement();
      expect(within(alertRegion()).queryByText(messageText('DEM1002'))).not.toBeInTheDocument();
    });

    it('names the window by its header and shows the signed-in user', async () => {
      const { dialog } = await openDialog();

      expect(dialog).toHaveAttribute('aria-modal', 'true');
      expect(within(dialog).getByRole('heading', { name: 'Customer Master' })).toBeInTheDocument();
      expect(within(dialog).getByText('Record Changed')).toBeInTheDocument();
      expect(within(dialog).getByText(MAINTENANCE_USER.username)).toBeInTheDocument();
    });

    it('puts focus on Refresh when it opens', async () => {
      const { dialog } = await openDialog();

      expect(within(dialog).getByRole('button', { name: 'Refresh' })).toHaveFocus();
    });
  });

  describe('field comparison', () => {
    it('has one row per customer field in screen order, with the user value, the current value and "Changed" where they differ', async () => {
      const { dialog } = await openDialog();
      const table = within(dialog).getByRole('table', { name: TABLE_NAME });

      const headers = within(table)
        .getAllByRole('columnheader')
        .map((header) => header.textContent);
      expect(headers).toEqual(['Field', 'Your values', 'Current record', 'Differs']);

      // The header row first, then one row per field. textContent is compared
      // unnormalized, so the seed's double spaces must come through intact.
      const [, ...bodyRows] = within(table).getAllByRole('row');
      const actual = bodyRows.map((row) => ({
        label: within(row).getByRole('rowheader').textContent,
        cells: within(row)
          .getAllByRole('cell')
          .map((cell) => cell.textContent),
      }));
      const expected = CUSTOMER_FORM_FIELDS.map(({ field, label }) => ({
        label,
        cells: [mine[field], current[field], DIFFERING_FIELDS.has(field) ? 'Changed' : ''],
      }));
      expect(actual).toEqual(expected);
    });

    it('marks exactly Name, City and ZIP as changed', async () => {
      const { dialog } = await openDialog();
      const table = within(dialog).getByRole('table', { name: TABLE_NAME });

      const changedLabels = within(table)
        .getAllByText('Changed')
        .map((cell) => {
          const row = cell.closest('tr');
          return row === null ? null : within(row).getByRole('rowheader').textContent;
        });
      expect(changedLabels).toEqual(['Name', 'City', 'ZIP']);

      // A field neither side changed carries no mark.
      const addressRow = within(table).getByRole('rowheader', { name: 'Address' }).closest('tr');
      expect(addressRow).not.toBeNull();
      if (addressRow !== null) {
        expect(within(addressRow).queryByText('Changed')).not.toBeInTheDocument();
        expect(within(addressRow).getAllByText(original.addr, { normalizer: (text) => text })).toHaveLength(2);
      }
    });

    it('shows both sides of a differing field: the user value against the current record', async () => {
      const { dialog } = await openDialog();
      const table = within(dialog).getByRole('table', { name: TABLE_NAME });

      const cityRow = within(table).getByRole('rowheader', { name: 'City' }).closest('tr');
      expect(cityRow).not.toBeNull();
      if (cityRow !== null) {
        expect(
          within(cityRow)
            .getAllByRole('cell')
            .map((cell) => cell.textContent),
        ).toEqual([original.city, 'PORTLAND', 'Changed']);
      }
    });
  });

  describe('Refresh', () => {
    it('calls onRefresh once, with no arguments, when Refresh is clicked', async () => {
      const { user, dialog, onRefresh, onReapply } = await openDialog();

      await user.click(within(dialog).getByRole('button', { name: 'Refresh' }));

      expect(onRefresh).toHaveBeenCalledTimes(1);
      expect(onRefresh).toHaveBeenCalledWith();
      expect(onReapply).not.toHaveBeenCalled();
    });

    it.each(['F12', 'Escape'])('calls onRefresh once when %s is pressed (Escape mirrors F12)', async (key) => {
      const { user, onRefresh, onReapply } = await openDialog();

      await user.keyboard(`{${key}}`);

      expect(onRefresh).toHaveBeenCalledTimes(1);
      expect(onRefresh).toHaveBeenCalledWith();
      expect(onReapply).not.toHaveBeenCalled();
      expect(alertRegion()).toBeEmptyDOMElement();
    });

    it('calls onRefresh once when the F12=Cancel key button is clicked', async () => {
      const { user, dialog, onRefresh, onReapply } = await openDialog();

      const keyButton = within(dialog).getByRole('button', { name: 'F12=Cancel' });
      expect(keyButton).toHaveAttribute('aria-keyshortcuts');
      await user.click(keyButton);

      expect(onRefresh).toHaveBeenCalledTimes(1);
      expect(onReapply).not.toHaveBeenCalled();
    });

    it('leaves Enter unbound, so Enter on the focused Refresh button clicks it exactly once', async () => {
      const { user, dialog, onRefresh, onReapply } = await openDialog();
      expect(within(dialog).getByRole('button', { name: 'Refresh' })).toHaveFocus();

      await user.keyboard('{Enter}');

      expect(onRefresh).toHaveBeenCalledTimes(1);
      expect(onReapply).not.toHaveBeenCalled();
      expect(alertRegion()).toBeEmptyDOMElement();
    });
  });

  describe('Re-apply my changes', () => {
    it("calls onReapply once with current's fields, the user's edits copied on, and current.version", async () => {
      const { user, dialog, onRefresh, onReapply } = await openDialog();

      await user.click(within(dialog).getByRole('button', { name: 'Re-apply my changes' }));

      // Name and ZIP are the user's edits; City was not edited by the user,
      // so the other user's value is kept.
      const merged: CustomerFields = { ...fields(current), name: mine.name, zip: mine.zip };
      expect(onReapply).toHaveBeenCalledTimes(1);
      expect(onReapply).toHaveBeenCalledWith(merged, current.version);
      expect(onRefresh).not.toHaveBeenCalled();
    });

    it('passes exactly the nine customer fields, never the id, stamp or version, as the write body', async () => {
      const { user, dialog, onReapply } = await openDialog();

      await user.click(within(dialog).getByRole('button', { name: 'Re-apply my changes' }));

      const call = onReapply.mock.calls[0];
      expect(call).toBeDefined();
      if (call !== undefined) {
        const [body, version] = call;
        expect(Object.keys(body).sort()).toEqual(CUSTOMER_FORM_FIELDS.map(({ field }) => field).sort());
        expect(body).not.toHaveProperty('custId');
        expect(body).not.toHaveProperty('version');
        expect(body.city).toBe(current.city);
        expect(version).toBe(current.version);
        expect(version).not.toBe(original.version);
      }
    });
  });

  describe('keys the window does not enable', () => {
    it('shows DEM0003 "Key is not active now" for F6 and calls neither callback', async () => {
      const { user, dialog, onRefresh, onReapply } = await openDialog();

      await user.keyboard('{F6}');

      expect(messageText('DEM0003')).toBe('Key is not active now');
      expect(within(alertRegion()).getByText('Key is not active now')).toBeInTheDocument();
      expect(onRefresh).not.toHaveBeenCalled();
      expect(onReapply).not.toHaveBeenCalled();
      // The window stays as it was.
      expect(dialog).toBeInTheDocument();
      expect(within(dialog).getByText(messageText('DEM1002'))).toBeInTheDocument();
    });
  });

  describe('closed', () => {
    it('renders nothing and registers no key scope while open is false', async () => {
      const user = userEvent.setup();
      const callbacks = newCallbacks();
      const { rerender } = renderWithProviders(conflictDialog(false, callbacks));

      expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
      expect(screen.queryByRole('table')).not.toBeInTheDocument();
      expect(screen.queryByRole('button')).not.toBeInTheDocument();
      expect(screen.queryByText(messageText('DEM1002'))).not.toBeInTheDocument();

      // With no scope on the stack F12 reaches nothing: no refresh, no DEM0003.
      await user.keyboard('{F12}');
      await user.keyboard('{F6}');
      expect(callbacks.onRefresh).not.toHaveBeenCalled();
      expect(callbacks.onReapply).not.toHaveBeenCalled();
      expect(alertRegion()).toBeEmptyDOMElement();

      // Opening it later shows the window over the same providers.
      rerender(conflictDialog(true, callbacks));
      const dialog = await screen.findByRole('dialog', { name: DIALOG_NAME });
      expect(await within(dialog).findByText(messageText('DEM1002'))).toBeInTheDocument();
    });
  });
});
