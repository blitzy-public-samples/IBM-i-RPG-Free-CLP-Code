/**
 * CustomerDetailDialog: the customer display / change / add window.
 *
 * What it replaces. MTNCUSTR and its display file MTNCUSTD, in both variants
 * (5250_Subfile/MTNCUSTR.SQLRPGLE, 5250_Subfile/MTNCUSTD.DSPF,
 * USPS_Address/MTNCUSTR.SQLRPGLE, USPS_Address/MTNCUSTD.DSPF): the 17×54
 * window PMTCUSTR called with a customer id and a function code (`D`, `E` or
 * `A`) over the search list. Here the caller passes `mode` and `custId`, and
 * each program step is a stateless request:
 *
 *   MTNCUSTR step                              Here
 *   ReadRecd (:315-338)                        GET /api/customers/{custId}
 *   EditUpdData / EditAddData, Edit_Address    POST /api/customers/review
 *   UpdateRecd (:567-607)                      PUT /api/customers/{custId} with `version`
 *   AddRecd (:548-566)                         POST /api/customers
 *
 * This component is the state owner of the error model's client ownership
 * rule: it holds the stored record and its `version`, the draft, the
 * reviewed values, the phase (form or confirmation), the working State, the
 * field errors and whether the State picker or the conflict comparison is
 * open. It applies no business rule: every field rule, the normalization and
 * the address standardization are the server's. It only routes keys,
 * requests and responses.
 *
 * Flows (MTNCUSTD enables CF04, CA05 and CA12 only, :33-35):
 * - **Display** (:181-191). Every field protected; one screen I/O, so Enter,
 *   F4, F5 and F12 (Escape) all close the window. F4 opens no picker and F5
 *   reloads nothing.
 * - **Edit** (:195-247). Enter reviews; a passed review shows the
 *   confirmation with DEM0000. F5 re-reads the record (a vanished row shows
 *   DEM0599 and clears the form). F4 on State opens the State picker, F4
 *   elsewhere shows DEM0005. At the confirmation Enter saves; F12 or F5
 *   re-read the record and discard the entries (preserved source defect); F4
 *   shows DEM0003 and returns to the form with the entries kept.
 * - **Add** (:251-297). Opens cleared with Active `Y`; F5 clears again.
 *   Review shows DEM0009. At the confirmation Enter adds; F12 clears the form
 *   (preserved source defect); F4 or F5 show DEM0003 and return to the form
 *   with the entries kept.
 * - **Keys MTNCUSTD does not enable** (F3, F6, …) show DEM0003 and change
 *   nothing, standing in for the workstation's own rejection.
 *
 * Working State (F04Prompt :364-382, Edit_SD_STATE :488-500). The State
 * picker is called with the program's working STATE, which is then always
 * copied back into the State field, so cancelling a prompt puts the working
 * State back. The working State starts as the stored State (blank in add),
 * becomes the chosen code on a prompt selection, becomes the reviewed State
 * on a passed review, becomes the problem's `stateAccepted` on a review that
 * failed after the State rule passed, and resets on every reload or clear.
 * It is never inferred from which field failed.
 *
 * Concurrency (UpdateRecd :593-602). A stale `version` answers 409 DEM1002
 * with `current`, which opens `ConflictCompareDialog` (Refresh loads
 * `current`; Re-apply copies the user's edits onto it and reviews again with
 * its version). A row lock answers 409 DEM1001, shown as an alert, and the
 * form returns with the entries kept.
 *
 * Messages. Server notices (DEM0000, DEM0009) are published from the
 * review's `notice`; problems go through `useProblemPresenter`; the
 * client-raised DEM0003 and DEM0005 come from the message catalog. The
 * bundle holds no message text: the strings below are screen labels, key
 * legends and the 5250 header texts (MTNCUSTR :101-103).
 *
 * Layer rule: imports come only from `api/`, `errors/`, `components/`,
 * `keyboard/`, `messages/`, `auth/`, `features/states/` and this folder, plus
 * React, `react-dom` and react-query.
 *
 * Rendering requires, above it: `QueryClientProvider`, `AuthProvider`,
 * `MessageCatalogProvider`, `ToastProvider` and `KeyScopeProvider`.
 *
 * @example
 * ```tsx
 * <CustomerDetailDialog
 *   open={detail !== null}
 *   mode={detail.mode}
 *   custId={detail.custId}
 *   onClose={(result) => {
 *     if (result?.saved) replaceRow(result.saved);
 *     setDetail(null);
 *   }}
 * />
 * ```
 */
import { useEffect, useRef, useState } from 'react';
import { flushSync } from 'react-dom';
import { useQuery } from '@tanstack/react-query';
import { CUSTOMER_FIELD_NAMES, customersApi } from '../../api/customers';
import type { CustomerFields, CustomerResponse, ReviewPurpose } from '../../api/customers';
import { isApiError } from '../../api/problem';
import type { FieldError } from '../../api/problem';
import { useAuth } from '../../auth/AuthProvider';
import { Dialog } from '../../components/Dialog';
import { FunctionKeyBar } from '../../components/FunctionKeyBar';
import type { FunctionKeyBarItem } from '../../components/FunctionKeyBar';
import { ScreenHeader } from '../../components/ScreenHeader';
import { useToasts } from '../../components/ToastRegion';
import { useProblemPresenter } from '../../errors/useProblemPresenter';
import { useFunctionKeys } from '../../keyboard/useFunctionKeys';
import { useMessages } from '../../messages/MessageCatalogProvider';
import { StatePicker } from '../states/StatePicker';
import { ConfirmationPanel } from './ConfirmationPanel';
import { ConflictCompareDialog } from './ConflictCompareDialog';
import { CustomerForm } from './CustomerForm';
import type { CustomerFieldName } from './CustomerForm';

// ---------------------------------------------------------------------------
// Public API
// ---------------------------------------------------------------------------

/**
 * Which MTNCUSTR function the window runs: `display` (function code `D`,
 * option 5), `edit` (`E`, option 2) or `add` (`A`, F6).
 */
export type DetailMode = 'display' | 'edit' | 'add';

/**
 * How the window ended, passed to `onClose`:
 * - `saved`: an edit was committed; the customer as now stored, so the
 *   caller can update its list row in place, as PMTCUSTR re-read the row
 *   into the subfile (5250_Subfile/PMTCUSTR.SQLRPGLE:427-515).
 * - `added`: an add was committed; PMTCUSTR leaves the list as it was, with
 *   no message (5250_Subfile/PMTCUSTR.SQLRPGLE:397-400).
 * Absent (`onClose()`) when the window was closed without a commit.
 */
export interface DetailCloseResult {
  saved?: CustomerResponse;
  added?: boolean;
}

/** Props of {@link CustomerDetailDialog}. */
export interface CustomerDetailDialogProps {
  /** Whether the window is shown. Each opening starts afresh: nothing carries over from an earlier one. */
  open: boolean;
  /** The function to run. */
  mode: DetailMode;
  /** The customer to display or change; absent in add mode. */
  custId?: string;
  /**
   * Called once when the window should close: F12/Escape, any key in
   * display mode, or a successful commit with its {@link DetailCloseResult}.
   * The caller closes the window by setting `open` to false.
   */
  onClose: (result?: DetailCloseResult) => void;
}

/**
 * The customer detail window. Renders nothing while `open` is false.
 *
 * Every opening mounts a fresh session keyed by mode and id, so the draft,
 * the phase, the working State and the key scope all start anew, as each
 * call of MTNCUSTR ran `Init` again; nothing is reset from an effect.
 */
export function CustomerDetailDialog({ open, mode, custId, onClose }: CustomerDetailDialogProps) {
  if (!open) {
    return null;
  }
  return <DetailSession key={`${mode}:${custId ?? ''}`} mode={mode} custId={custId} onClose={onClose} />;
}

// ---------------------------------------------------------------------------
// Constants and pure helpers
// ---------------------------------------------------------------------------

/** Id prefix of the header: the window is named by `customer-detail-title` and `customer-detail-function`. */
const HEADER_ID = 'customer-detail';

/** The ids that name the window, through `aria-labelledby`. */
const LABELLED_BY = `${HEADER_ID}-title ${HEADER_ID}-function`;

/** Id prefix of the editable form's inputs (`customer-detail-name`, …). */
const FORM_ID_PREFIX = 'customer-detail';

/** Id prefix of the confirmation panel's inputs; it differs from the form's, so no id is shared. */
const CONFIRM_ID_PREFIX = 'customer-confirm';

/** The SH_FUNCT header of each function: H2TextD, H2TextE, H2TextA (MTNCUSTR :101-103). */
const FUNCTION_TEXT: Readonly<Record<DetailMode, string>> = {
  display: 'Displaying Customer',
  edit: 'Change Customer',
  add: 'Add Customer',
};

/** The review purpose of each changing function: DEM0000 follows EDIT, DEM0009 follows ADD. */
const REVIEW_PURPOSE: Readonly<Record<'edit' | 'add', ReviewPurpose>> = {
  edit: 'EDIT',
  add: 'ADD',
};

/** Every field blank: a customer that could not be read, or a row that vanished on F5. */
const BLANK: Readonly<CustomerFields> = Object.freeze({
  active: '',
  name: '',
  addr: '',
  city: '',
  state: '',
  zip: '',
  acctPhone: '',
  acctMgr: '',
  corpPhone: '',
});

/** The cleared add form: `clear CUSTMAST_ds; ACTIVE = 'Y'` (MTNCUSTR :251-254, :280-282). */
const EMPTY_ADD: Readonly<CustomerFields> = Object.freeze({ ...BLANK, active: 'Y' });

/** The status of a problem whose meaning depends on its code (DEM1001 or DEM1002). */
const STATUS_CONFLICT = 409;

/** A record that no longer exists: 404 DEM0599. */
const STATUS_NOT_FOUND = 404;

/** A field rule or the address check failed: 422. */
const STATUS_UNPROCESSABLE = 422;

/** The catalog key of a row lock that outlasted the lock timeout. */
const CODE_LOCKED = 'DEM1001';

/** Whether a problem field names one of the nine customer data fields of the form. */
function isCustomerField(name: string): name is CustomerFieldName {
  return (CUSTOMER_FIELD_NAMES as readonly string[]).includes(name);
}

/** Whether a customer id is present (not absent and not empty). */
function hasId(id: string | undefined): id is string {
  return id !== undefined && id !== '';
}

/** Exactly the nine customer data fields of a stored customer, never its id, stamp or version. */
function fieldsOf(source: CustomerResponse): CustomerFields {
  const fields: CustomerFields = {};
  for (const name of CUSTOMER_FIELD_NAMES) {
    fields[name] = source[name];
  }
  return fields;
}

/**
 * The form's `errors` prop: each customer field named by the problem, with
 * its message. The first entry for a field wins; field names outside the
 * form (`version`, `purpose`) are left out, their alert is shown regardless.
 */
function errorsByField(errors: readonly FieldError[]): Partial<Record<CustomerFieldName, string>> {
  const byField: Partial<Record<CustomerFieldName, string>> = {};
  for (const { field, message } of errors) {
    if (isCustomerField(field) && byField[field] === undefined) {
      byField[field] = message;
    }
  }
  return byField;
}

/** The fields the form opens with: the stored record, the cleared add form, or blanks when nothing could be read. */
function initialDraft(mode: DetailMode, record: CustomerResponse | null): CustomerFields {
  if (mode === 'add') {
    return { ...EMPTY_ADD };
  }
  return record !== null ? fieldsOf(record) : { ...BLANK };
}

/** Change handler of the protected display form; a read-only input never changes. */
function keepDisplayedValue(_field: CustomerFieldName, _value: string): void {
  return undefined;
}

// ---------------------------------------------------------------------------
// Session: initial data
// ---------------------------------------------------------------------------

/** Props shared by the session, its loader and the window body. */
interface SessionProps {
  mode: DetailMode;
  custId: string | undefined;
  onClose: (result?: DetailCloseResult) => void;
}

/**
 * One opening of the window. Add starts at once from the cleared form; edit
 * and display read the stored customer first. Without an id there is nothing
 * to read, so the window opens on blank fields, as after a failed read.
 */
function DetailSession({ mode, custId, onClose }: SessionProps) {
  if (mode === 'add' || !hasId(custId)) {
    return <DetailWindow mode={mode} custId={custId} initialRecord={null} onClose={onClose} />;
  }
  return <StoredCustomerLoader mode={mode} custId={custId} onClose={onClose} />;
}

/**
 * Reads the stored customer once (ReadRecd, MTNCUSTR :315-338) and then
 * mounts the window seeded with it.
 *
 * The window body takes the result as the initial value of its own state
 * (useState initializers) and is mounted only once the read has settled, so
 * query data is never copied into state from an effect. A failed read shows
 * its problem (404 → DEM0599 "Customer deleted. Exit & redo search.") and
 * still opens the window, on blank fields: editable in edit mode, protected
 * in display mode. The read is not repeated behind the user's back: no
 * retry, no refetch on focus or reconnect, and nothing kept once the window
 * closes; F5 in edit mode is the one way to read again.
 */
function StoredCustomerLoader({ mode, custId, onClose }: SessionProps & { custId: string }) {
  const { present } = useProblemPresenter();
  const query = useQuery({
    queryKey: ['customers', 'detail', custId],
    queryFn: async (): Promise<CustomerResponse> => {
      try {
        return await customersApi.get(custId);
      } catch (error) {
        present(error);
        throw error;
      }
    },
    retry: false,
    staleTime: 0,
    gcTime: 0,
    refetchOnWindowFocus: false,
    refetchOnReconnect: false,
  });

  if (query.isPending) {
    return <PendingScope onClose={onClose} />;
  }
  // Mounted once per opening: a successful and a failed read lead to the
  // same element, so the seeded state is never replaced while the window
  // stays open.
  return <DetailWindow key="settled" mode={mode} custId={custId} initialRecord={query.data ?? null} onClose={onClose} />;
}

/**
 * The key scope while the stored customer is being read and no window is
 * shown yet. It sits on top of the screen beneath from the moment the user
 * asked for the window, so a key pressed during the read can never reach the
 * search list (no second option processing, no second window). F12 and
 * Escape close; Enter and every other function key are ignored, because
 * nothing is displayed for them to act on. Removed as soon as the read
 * settles and the window takes over with its own scope.
 */
function PendingScope({ onClose }: Pick<SessionProps, 'onClose'>) {
  useFunctionKeys(
    {
      Enter: ignoreKey,
      F12: () => onClose(),
    },
    { onUnbound: ignoreKey },
  );
  return null;
}

/** A key that is swallowed on purpose: the window is not displayed yet. */
function ignoreKey(): void {
  return undefined;
}

// ---------------------------------------------------------------------------
// Window body: state and flows
// ---------------------------------------------------------------------------

/** Where an edit or add stands: keying the fields, or confirming the reviewed values. */
type Phase = 'form' | 'confirm';

/** An open DEM1002 comparison: what the user started from, what they tried to save, what is stored now. */
interface ConflictState {
  original: CustomerFields;
  mine: CustomerFields;
  current: CustomerResponse;
}

/** The four keys MTNCUSTD offers (Enter, CF04, CA05, CA12), each with the handler of the current phase. */
interface DetailKeys {
  Enter: () => void;
  F4: () => void;
  F5: () => void;
  F12: () => void;
}

/** Props of the window body: the session props plus the record read on opening, if any. */
interface DetailWindowProps extends SessionProps {
  /** The stored customer read on opening; `null` in add mode or when the read failed. */
  initialRecord: CustomerResponse | null;
}


/**
 * The open window: header, the form or the confirmation, the key legend and
 * the nested State picker and conflict comparison. Holds every piece of the
 * window's state and its one key scope.
 */
function DetailWindow({ mode, custId, initialRecord, onClose }: DetailWindowProps) {
  const { username } = useAuth();
  const { publish } = useToasts();
  const { format } = useMessages();
  const { present } = useProblemPresenter();

  // --- State ---------------------------------------------------------------
  // The stored record the stamp and the conflict comparison refer to; null in
  // add mode, after a failed read and after a vanished-row clear.
  const [record, setRecord] = useState<CustomerResponse | null>(initialRecord);
  // The version the next PUT is conditional on. It survives a vanished-row
  // clear, so a later save gets the server's 404 DEM0599 rather than a 400.
  const [version, setVersion] = useState<number | undefined>(initialRecord?.version);
  const [draft, setDraft] = useState<CustomerFields>(() => initialDraft(mode, initialRecord));
  const [reviewed, setReviewed] = useState<CustomerFields | null>(null);
  const [standardized, setStandardized] = useState(false);
  const [phase, setPhase] = useState<Phase>('form');
  // MTNCUSTR's program field STATE, which F04Prompt copies back into the
  // State field whether or not a code was chosen.
  const [workingState, setWorkingState] = useState<string>(initialRecord?.state ?? '');
  const [fieldErrors, setFieldErrors] = useState<FieldError[]>([]);
  const [pickerOpen, setPickerOpen] = useState(false);
  const [conflict, setConflict] = useState<ConflictState | null>(null);
  const [busy, setBusy] = useState(false);
  // Bumped whenever the form is reloaded, cleared or shown again after the
  // confirmation; as the form's key it remounts the form, which re-applies
  // its initial focus (DSPATR(PC)).
  const [formKey, setFormKey] = useState(0);

  // --- Refs (written by callback refs, effects and handlers; never read during render)
  const inputs = useRef<Partial<Record<CustomerFieldName, HTMLInputElement | null>>>({});
  const nameRef = useRef<HTMLInputElement | null>(null);
  const bodyRef = useRef<HTMLDivElement | null>(null);
  const confirmRef = useRef<HTMLDivElement | null>(null);
  // A request is in flight. Read by the key handlers, so a second Enter or
  // click before the next render can never send a second PUT or POST.
  const inFlight = useRef(false);
  // The window is still mounted. A response that arrives after the window
  // closed (F12 while a request was pending) is dropped: no state, no toast.
  const alive = useRef(true);
  useEffect(() => {
    alive.current = true;
    return () => {
      alive.current = false;
    };
  }, []);

  // --- Small helpers -------------------------------------------------------

  /** DEM0003 "Key is not active now": the answer to every key the screen does not enable. */
  function keyNotActive(): void {
    publish({ kind: 'alert', text: format('DEM0003') });
  }

  /** Closes the window without a commit. */
  function close(): void {
    onClose();
  }

  /** Moves focus to a customer field (DSPATR(PC)); a field the form does not hold is ignored. */
  function focusField(field: string): void {
    if (isCustomerField(field)) {
      inputs.current[field]?.focus();
    }
  }

  /**
   * The callback ref of one form input: records it for focus moves and for
   * the "is focus on State" test of F4, and keeps `nameRef` (the window's
   * initial focus) pointing at the Name input.
   */
  function bindInput(field: CustomerFieldName) {
    return (el: HTMLInputElement | null): void => {
      inputs.current[field] = el;
      if (field === 'name') {
        nameRef.current = el;
      }
    };
  }

  /** Records one keyed field value; FormField has already uppercased it. */
  function changeField(field: CustomerFieldName, value: string): void {
    setDraft((current) => ({ ...current, [field]: value }));
  }

  /** Marks a request as started; false when one is already in flight. */
  function begin(): boolean {
    if (inFlight.current) {
      return false;
    }
    inFlight.current = true;
    setBusy(true);
    return true;
  }

  /** Marks the request as finished. */
  function finish(): void {
    inFlight.current = false;
    if (alive.current) {
      setBusy(false);
    }
  }

  /** Shows the form again (remounted, so its focus applies) with `values` and no review pending. */
  function showForm(values: CustomerFields): void {
    setDraft(values);
    setReviewed(null);
    setStandardized(false);
    setFieldErrors([]);
    setPhase('form');
    setFormKey((key) => key + 1);
  }

  /**
   * Loads a stored customer into the window, discarding the entries: the
   * record, its version, the fields and the working State all come from it
   * (ReadRecd plus FillScreenFields).
   */
  function loadRecord(stored: CustomerResponse): void {
    setRecord(stored);
    setVersion(stored.version);
    setWorkingState(stored.state);
    showForm(fieldsOf(stored));
  }

  /** Add mode's clear (F5, and F12 at the confirmation): blank fields, Active `Y`, blank working State. */
  function clearAdd(): void {
    setWorkingState('');
    showForm({ ...EMPTY_ADD });
  }


  // --- Requests ------------------------------------------------------------

  /**
   * Edit mode's re-read (F5, and F12 or F5 at the confirmation;
   * MTNCUSTR :209-216, :226-247): the stored record replaces the entries.
   * A vanished row (404) shows DEM0599 and clears the form and the stamp,
   * keeping the id and the last known version. Any other failure is shown
   * and the entries stay, back on the form, so F5 can simply be pressed again.
   */
  async function reloadStored(): Promise<void> {
    if (!hasId(custId) || !begin()) {
      return;
    }
    try {
      const stored = await customersApi.get(custId);
      if (alive.current) {
        loadRecord(stored);
      }
    } catch (error) {
      if (!alive.current) {
        return;
      }
      present(error);
      if (isApiError(error) && error.status === STATUS_NOT_FOUND) {
        setRecord(null);
        setWorkingState('');
        showForm({ ...BLANK });
      } else {
        showForm(reviewed ?? draft);
      }
    } finally {
      finish();
    }
  }

  /**
   * Enter on the form (EditUpdData / EditAddData, then Edit_Address in the
   * USPS variant): the server runs the nine field rules and, when enabled,
   * the address standardization.
   *
   * - Passed: the reviewed values (normalized, possibly standardized) become
   *   the draft and the confirmation, the working State becomes the reviewed
   *   State, and the notice (DEM0000 or DEM0009) is published.
   * - Failed: the problem is shown with every field it names highlighted and
   *   the first focused (DEM9898 marks addr, city, state and zip, addr
   *   first). When the State rule had passed before the failure, the problem
   *   carries `stateAccepted`, which becomes the working State, as
   *   Edit_SD_STATE had already moved the State into the program field.
   */
  async function runReview(fields: CustomerFields): Promise<void> {
    if (mode === 'display' || !begin()) {
      return;
    }
    // Each screen cycle starts without the previous cycle's highlights, as
    // MTNCUSTR clears its RI/PC indicators after every read.
    setFieldErrors([]);
    try {
      const response = await customersApi.review({ purpose: REVIEW_PURPOSE[mode], ...fields });
      if (!alive.current) {
        return;
      }
      setReviewed(response.customer);
      setStandardized(response.standardized);
      setDraft(response.customer);
      setWorkingState(response.customer.state ?? '');
      setPhase('confirm');
      publish({ kind: 'status', text: response.notice.message });
    } catch (error) {
      if (!alive.current) {
        return;
      }
      present(error, { setFieldErrors, focusField });
      const accepted = isApiError(error) ? error.problem.stateAccepted : undefined;
      if (typeof accepted === 'string') {
        setWorkingState(accepted);
      }
    } finally {
      finish();
    }
  }

  /**
   * Enter at the confirmation: UpdateRecd (edit) or AddRecd (add) with
   * exactly the reviewed values.
   *
   * An edit whose opening read failed has no version to make the update
   * conditional on, so it re-reads the record instead of sending a PUT: an
   * update is never sent for a record whose stored state the user has not
   * seen.
   */
  async function commit(values: CustomerFields): Promise<void> {
    if (mode === 'display') {
      return;
    }
    if (mode === 'add') {
      await save(async () => {
        await customersApi.add(values);
        return { added: true };
      }, values);
      return;
    }
    const id = custId;
    const readVersion = version;
    if (!hasId(id) || readVersion === undefined) {
      await reloadStored();
      return;
    }
    await save(async () => ({ saved: await customersApi.update(id, { ...values, version: readVersion }) }), values);
  }

  /** Runs one write; success closes the window with its result, a failure is routed by {@link commitFailed}. */
  async function save(write: () => Promise<DetailCloseResult>, values: CustomerFields): Promise<void> {
    if (!begin()) {
      return;
    }
    try {
      const result = await write();
      if (alive.current) {
        onClose(result);
      }
    } catch (error) {
      if (alive.current) {
        commitFailed(error, values);
      }
    } finally {
      finish();
    }
  }

  /**
   * Routes a failed commit:
   * - 422 (a field rule): back to the form with the reviewed values, the
   *   fields highlighted and the first one focused once the form is shown.
   * - 409 DEM1001 (row lock) or 404 DEM0599: the alert, and back to the form
   *   with the entries kept (UpdateRecd :600-602).
   * - 409 DEM1002 with `current`: the comparison opens over the confirmation
   *   and shows the DEM1002 text itself, so no alert is published.
   * - Anything else (502, 503, 500, 403, 400): the alert, and the
   *   confirmation stays.
   */
  function commitFailed(error: unknown, values: CustomerFields): void {
    const status = isApiError(error) ? error.status : undefined;
    const code = isApiError(error) ? error.problem.code : undefined;
    if (status === STATUS_UNPROCESSABLE) {
      // The form is shown first, so the highlight set by the presenter lands
      // on the remounted form, which focuses the first field in error.
      showForm(values);
      present(error, { setFieldErrors, focusField });
      return;
    }
    if ((status === STATUS_CONFLICT && code === CODE_LOCKED) || status === STATUS_NOT_FOUND) {
      present(error);
      showForm(values);
      return;
    }
    present(error, {
      onConflict: (current) =>
        setConflict({ original: record !== null ? fieldsOf(record) : { ...BLANK }, mine: values, current }),
    });
  }

  // --- State prompt (F04Prompt, MTNCUSTR :364-382) -------------------------

  /** F4 on the form: the State picker when focus is on State, otherwise DEM0005. */
  function prompt(): void {
    setFieldErrors([]);
    const stateInput = inputs.current.state;
    if (stateInput !== undefined && stateInput !== null && document.activeElement === stateInput) {
      setPickerOpen(true);
      return;
    }
    publish({ kind: 'alert', text: format('DEM0005') });
  }

  /**
   * A code chosen in the picker becomes the State field and the working
   * State. No review is sent (F4 runs no edit); the picker's window returns
   * focus to the State field as it closes.
   */
  function stateSelected(code: string): void {
    setDraft((current) => ({ ...current, state: code }));
    setWorkingState(code);
    setPickerOpen(false);
  }

  /**
   * A cancelled prompt puts the working State back into the State field,
   * discarding what was typed there since; every other typed field stays.
   * The picker is closed synchronously so State, no longer behind a modal
   * window, can take focus.
   */
  function stateCancelled(): void {
    flushSync(() => {
      setDraft((current) => ({ ...current, state: workingState }));
      setPickerOpen(false);
    });
    focusField('state');
  }

  // --- Conflict comparison (UpdateRecd :593-599) ---------------------------

  /** Refresh: `current` replaces the entries, as the source re-read and redisplayed the record. */
  function refreshFromConflict(current: CustomerResponse): void {
    setConflict(null);
    loadRecord(current);
  }

  /**
   * Re-apply my changes: the merged fields go back to review under
   * `current`'s version, so the next save is conditional on the record just
   * shown. The form is shown while the review runs, so a failed review
   * highlights fields on it; a passed one moves on to the confirmation.
   */
  function reapplyOnConflict(current: CustomerResponse, merged: CustomerFields, nextVersion: number): void {
    setConflict(null);
    setRecord(current);
    setVersion(nextVersion);
    showForm(merged);
    void runReview(merged);
  }

  // --- Key handlers ----------------------------------------------------------
  // Every handler that starts a request, or changes what a pending request
  // will land on, does nothing while one is in flight; F12 on the form always
  // closes. Requests are gated once more by `begin`.

  /** Enter on the form: review the draft. */
  function enterOnForm(): void {
    void runReview(draft);
  }

  /** F4 on the form: the State prompt, or DEM0005 away from State. */
  function promptOnForm(): void {
    if (!inFlight.current) {
      prompt();
    }
  }

  /** F5 on the form: edit re-reads the stored record (CA05 "Refresh"); add clears to Active `Y`. */
  function refreshOnForm(): void {
    if (inFlight.current) {
      return;
    }
    if (mode === 'edit') {
      void reloadStored();
    } else {
      clearAdd();
    }
  }

  /** Enter at the confirmation: commit exactly the reviewed values. */
  function enterAtConfirm(): void {
    if (reviewed !== null) {
      void commit(reviewed);
    }
  }

  /**
   * A key that is enabled but not active at the confirmation (F4 at both,
   * F5 at the add confirmation): DEM0003, then the form again with the
   * entries kept (MTNCUSTR :236-242, :289-291).
   */
  function notActiveAtConfirm(): void {
    if (inFlight.current || reviewed === null) {
      return;
    }
    keyNotActive();
    showForm(reviewed);
  }

  /**
   * F12 at the confirmation, and F5 at the edit confirmation: edit re-reads
   * the stored record, discarding the entries; add clears the form to
   * Active `Y` (preserved source defects, MTNCUSTR :226-247, :281-282).
   */
  function cancelAtConfirm(): void {
    if (inFlight.current) {
      return;
    }
    if (mode === 'edit') {
      void reloadStored();
    } else {
      clearAdd();
    }
  }

  // --- Keys: one scope, handlers per mode and phase ------------------------

  const confirming = phase === 'confirm' && reviewed !== null;
  let keys: DetailKeys;
  if (mode === 'display') {
    // One protected screen I/O: whichever enabled key returns closes the window.
    keys = { Enter: close, F4: close, F5: close, F12: close };
  } else if (!confirming) {
    keys = { Enter: enterOnForm, F4: promptOnForm, F5: refreshOnForm, F12: close };
  } else {
    keys = {
      Enter: enterAtConfirm,
      F4: notActiveAtConfirm,
      F5: mode === 'edit' ? cancelAtConfirm : notActiveAtConfirm,
      F12: cancelAtConfirm,
    };
  }

  // The window's only key scope. The State picker and the conflict
  // comparison push their own scopes on top while open, which suspends this
  // one until they close (search → detail → picker). Enter is a command on
  // the text inputs and on the container: the confirmation panel while
  // confirming (it takes focus on mount), the window body otherwise.
  useFunctionKeys(keys, {
    onUnbound: keyNotActive,
    containerRef: confirming ? confirmRef : bodyRef,
  });

  // The visible legend (MTNCUSTR BldFkeyText :638-649), each entry calling
  // the very handler its key runs.
  const keyBar: FunctionKeyBarItem[] = [
    { key: 'F4', label: 'F4=Prompt+', onPress: keys.F4 },
    { key: 'F5', label: 'F5=Refresh', onPress: keys.F5 },
    { key: 'F12', label: 'F12=Cancel', onPress: keys.F12 },
    { key: 'Enter', label: 'Enter', onPress: keys.Enter },
  ];

  // --- Render --------------------------------------------------------------

  const editable = mode !== 'display';
  const errors = errorsByField(fieldErrors);
  const firstErrorField = fieldErrors.map((error) => error.field).find(isCustomerField);

  return (
    <Dialog
      open
      labelledBy={LABELLED_BY}
      initialFocusRef={editable ? nameRef : undefined}
      className="dialog dialog--detail"
    >
      <div ref={bodyRef} tabIndex={-1} className="customer-detail" aria-busy={busy || undefined}>
        <ScreenHeader id={HEADER_ID} functionText={FUNCTION_TEXT[mode]} user={username ?? undefined} />
        {confirming ? (
          <ConfirmationPanel
            idPrefix={CONFIRM_ID_PREFIX}
            custId={custId ?? ''}
            values={reviewed}
            standardized={standardized}
            containerRef={confirmRef}
          />
        ) : (
          <CustomerForm
            key={formKey}
            idPrefix={FORM_ID_PREFIX}
            custId={record?.custId ?? custId ?? ''}
            values={draft}
            onChange={editable ? changeField : keepDisplayedValue}
            readOnly={!editable}
            errors={errors}
            inputRef={bindInput}
            initialFocusField={editable ? (firstErrorField ?? 'name') : undefined}
            stamp={record !== null ? { chgTime: record.chgTime, chgUser: record.chgUser } : null}
          />
        )}
        {/* MTNCUSTD SFT_FKEY: the underlined brand on row 14, the key legend on row 15. */}
        <p className="footer-brand">Demo Corp of America</p>
        <FunctionKeyBar keys={keyBar} />
      </div>
      <StatePicker open={pickerOpen} onSelect={stateSelected} onCancel={stateCancelled} />
      {conflict !== null ? (
        <ConflictCompareDialog
          open
          original={conflict.original}
          mine={conflict.mine}
          current={conflict.current}
          onRefresh={() => refreshFromConflict(conflict.current)}
          onReapply={(merged, nextVersion) => reapplyOnConflict(conflict.current, merged, nextVersion)}
        />
      ) : null}
    </Dialog>
  );
}

