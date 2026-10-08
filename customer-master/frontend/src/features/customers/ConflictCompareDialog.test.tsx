/**
 * Tests of the DEM1002 compare window: `./ConflictCompareDialog.tsx`.
 *
 * It replaces the stale-update branch of MTNCUSTR's UpdateRecd: when no row
 * still holds the CHGTIME read earlier, the source sends DEM1002, re-reads
 * the record (ReadRecd) and refills the screen with it (FillScreenFields)
 * (5250_Subfile/MTNCUSTR.SQLRPGLE:593-599; message text
 * 5250_Subfile/CRTMSGF.CLLE:40, typos fixed). Here a PUT carrying a stale
 * `version` is answered 409 DEM1002 with `current`, and the detail dialog
 * opens this window over its confirmation. F12 and Escape run Refresh, the
 * source outcome of a stale update.
 *
 * Fixtures: `original` is seed row AAAD as the user read it. The user edited
 * Name and ZIP (`mine`); meanwhile someone else changed City, raising the
 * stored version (`current`). Name, City and ZIP therefore differ, and only
 * Name and ZIP are the user's edits.
 *
 * Assertions on the open window wait for the catalog text first, since
 * `format` returns the bare code until the catalog has loaded.
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
import type { CustomerFieldName } from './CustomerForm';

type FieldValues = Record<CustomerFieldName, string>;

/**
 * The nine customer data fields in MTNCUSTD screen order (Active, Name,
 * Address, City, State, ZIP, Account Manager Phone, Account Manager Name,
 * Corporate Phone; 5250_Subfile/MTNCUSTD.DSPF:61-125), each with the label
 * the detail window shows. Written out here rather than read from the form's
 * field table, so a reordered, renamed or missing row fails these specs
 * instead of moving with the component.
 */
const SCREEN_FIELDS: ReadonlyArray<{ readonly field: CustomerFieldName; readonly label: string }> = [
  { field: 'active', label: 'Active (Y/N)' },
  { field: 'name', label: 'Name' },
  { field: 'addr', label: 'Address' },
  { field: 'city', label: 'City' },
  { field: 'state', label: 'State +' },
  { field: 'zip', label: 'ZIP' },
  { field: 'acctPhone', label: 'Account Manager Phone' },
  { field: 'acctMgr', label: 'Account Manager Name' },
  { field: 'corpPhone', label: 'Corporate Phone' },
];

/** A record's write-body fields: no id, stamp or version. */
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

/** Only a MAINTENANCE user can reach an update conflict. */
const MAINTENANCE_USER = (() => {
  const user = users.find((candidate) => candidate.roles.includes('MAINTENANCE'));
  if (user === undefined) {
    throw new Error('The users fixture holds no MAINTENANCE user');
  }
  return user;
})();

/** The record whose version the rejected PUT carried. */
const original: CustomerResponse = { ...customerDetail };

/** What the user tried to save. */
const mine: FieldValues = { ...fields(original), name: 'NIBH LOR HOLDINGS', zip: '15762-0002' };

/** The record as now stored, after the other user's change. */
const current: CustomerResponse = {
  ...original,
  city: 'PORTLAND',
  chgTime: '2026-10-05T14:10:00Z',
  chgUser: 'other-user',
  version: original.version + 1,
};

/** Where `mine` and `current` differ: the user's two edits and the other user's one. */
const DIFFERING_FIELDS: ReadonlySet<CustomerFieldName> = new Set<CustomerFieldName>(['name', 'city', 'zip']);

/** The window's name, from its header title and function line. */
const DIALOG_NAME = 'Customer Master Record Changed';

const TABLE_NAME = 'Your changes compared with the current record';

/** Toasts clear before each command key, as in `src/App.tsx`. */
function KeyedScreens({ children }: { children: ReactNode }) {
  const { clear } = useToasts();
  return <KeyScopeProvider onBeforeCommand={clear}>{children}</KeyScopeProvider>;
}

/**
 * Mounts `ui` in the application's providers as the render `wrapper`, so
 * `rerender` keeps the same query client, catalog and session.
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

type Callbacks = {
  onRefresh: Mock<ConflictCompareDialogProps['onRefresh']>;
  onReapply: Mock<ConflictCompareDialogProps['onReapply']>;
};

function newCallbacks(): Callbacks {
  return {
    onRefresh: vi.fn<ConflictCompareDialogProps['onRefresh']>(),
    onReapply: vi.fn<ConflictCompareDialogProps['onReapply']>(),
  };
}

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

      // The window carries the text itself and publishes no toast; neither
      // does `useProblemPresenter` when it hands a DEM1002 conflict here.
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
      const expected = SCREEN_FIELDS.map(({ field, label }) => ({
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
        expect(Object.keys(body).sort()).toEqual(SCREEN_FIELDS.map(({ field }) => field).sort());
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

      await user.keyboard('{F12}');
      await user.keyboard('{F6}');
      expect(callbacks.onRefresh).not.toHaveBeenCalled();
      expect(callbacks.onReapply).not.toHaveBeenCalled();
      expect(alertRegion()).toBeEmptyDOMElement();

      rerender(conflictDialog(true, callbacks));
      const dialog = await screen.findByRole('dialog', { name: DIALOG_NAME });
      expect(await within(dialog).findByText(messageText('DEM1002'))).toBeInTheDocument();
    });
  });
});
