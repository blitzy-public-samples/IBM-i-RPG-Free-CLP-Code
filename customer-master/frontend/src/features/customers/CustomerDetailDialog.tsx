/**
 * CustomerDetailDialog: the customer display / change / add window.
 *
 * Replaces MTNCUSTR and MTNCUSTD (both variants), the 17×54 window over the
 * search list, with stateless requests to /api/customers: ReadRecd (:315-338)
 * → GET /{custId}; EditUpdData / EditAddData and Edit_Address → POST /review;
 * UpdateRecd (:567-607) → PUT /{custId} with `version`; AddRecd (:548-566) → POST.
 *
 * It owns the window's state (the error model's owning feature) and applies no
 * business rule: field rules, normalization and standardization are the
 * server's, and message texts come from the server or the catalog.
 *
 * MTNCUSTD enables CF04, CA05 and CA12 only (:33-35). Every key it does not
 * enable, PageUp and PageDown included, shows DEM0003 and changes nothing,
 * standing in for the workstation's own rejection.
 *
 * Working State (F04Prompt :364-382, Edit_SD_STATE :488-500): what a cancelled
 * State prompt puts back. It starts as the stored State (blank in add), becomes
 * a chosen code, a reviewed State or a failed review's `stateAccepted`, and
 * resets on every reload or clear. It is never inferred from which field failed.
 *
 * Requires `QueryClientProvider`, `AuthProvider`, `MessageCatalogProvider`,
 * `ToastProvider` and `KeyScopeProvider` above it. Layer rule: imports only
 * `api/`, `errors/`, `components/`, `keyboard/`, `messages/`, `auth/`,
 * `features/states/`, this folder, React, `react-dom` and react-query.
 */
import { useEffect, useId, useRef, useState } from 'react';
import { flushSync } from 'react-dom';
import { useQuery } from '@tanstack/react-query';
import { CUSTOMER_FIELD_NAMES, customersApi } from '../../api/customers';
import type { CustomerFields, CustomerResponse, ReviewPurpose } from '../../api/customers';
import { isApiError } from '../../api/problem';
import type { FieldError } from '../../api/problem';
import { useAuth } from '../../auth/AuthProvider';
import { Dialog } from '../../components/Dialog';
import type { FormFieldElement } from '../../components/FormField';
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

/**
 * Which MTNCUSTR function the window runs: `display` (function code `D`,
 * option 5), `edit` (`E`, option 2) or `add` (`A`, F6, with no `custId`).
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

export interface CustomerDetailDialogProps {
  open: boolean;
  mode: DetailMode;
  custId?: string;
  /**
   * Called once when the window should close:
   * - Enter, F4, F5, F12 or Escape in display mode; every other key there
   *   (F3, PageUp, PageDown, …) shows DEM0003 and the window stays;
   * - F12 or Escape on the edit or add form, or while the stored customer is
   *   still being read (at a confirmation they re-read or clear instead);
   * - a successful commit, with its {@link DetailCloseResult}.
   * Called without an argument unless a commit succeeded. The caller closes
   * the window by setting `open` to false.
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

const HEADER_ID = 'customer-detail';
const LABELLED_BY = `${HEADER_ID}-title ${HEADER_ID}-function`;
const FORM_ID_PREFIX = 'customer-detail';

/** Differs from {@link FORM_ID_PREFIX}, so the form and the confirmation share no id. */
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

const STATUS_CONFLICT = 409;
const STATUS_NOT_FOUND = 404;
const STATUS_UNPROCESSABLE = 422;
const CODE_LOCKED = 'DEM1001';

function isCustomerField(name: string): name is CustomerFieldName {
  return (CUSTOMER_FIELD_NAMES as readonly string[]).includes(name);
}

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

function keepDisplayedValue(_field: CustomerFieldName, _value: string): void {
  return undefined;
}

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
 *
 * Each opening owns its read. The query key carries an id of this opening,
 * so reopening the same customer while an earlier read is still pending
 * sends a request of its own instead of joining the closed window's.
 * Closing the window while the opening read is pending (F12 or Escape)
 * aborts that read: the query function hands react-query's abort signal to
 * `customersApi.get`, and the browser stops waiting, though the server may
 * still answer. Under React StrictMode in development the simulated unmount
 * aborts the first read and the remount sends one more; production builds
 * send one read per opening. The abort's own rejection, and any read that
 * settles after its window closed, presents nothing, so no late DEM0599 or
 * other alert reaches the screen now displayed; it still rejects, so the
 * query settles and is then dropped. An answer that did arrive while the
 * window is open is still presented, even when handling it aborted the
 * read: a 401 whose sign-out cancelled the query shows APP0401.
 */
function StoredCustomerLoader({ mode, custId, onClose }: SessionProps & { custId: string }) {
  const { present } = useProblemPresenter();
  const opening = useId();
  // Read only in the query function, never during render. Setting true again
  // on mount keeps StrictMode's simulated unmount and remount working.
  const mounted = useRef(true);
  useEffect(() => {
    mounted.current = true;
    return () => {
      mounted.current = false;
    };
  }, []);
  const query = useQuery({
    queryKey: ['customers', 'detail', custId, opening],
    queryFn: async ({ signal }): Promise<CustomerResponse> => {
      try {
        return await customersApi.get(custId, signal);
      } catch (error) {
        // Shown only while this opening is mounted, and never when the
        // rejection is the abort's own: after a close the screen now
        // displayed did not ask for it, and StrictMode's aborted first read
        // is followed by the remount's. An answer that did arrive, such as a
        // 401 whose sign-out cancelled this query, is still shown.
        const abandoned = signal.aborted && error === signal.reason;
        if (mounted.current && !abandoned) {
          present(error);
        }
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
 * shown yet. It is the topmost scope from the moment the user asked for the
 * window, so no command key the scope contract dispatches (F1–F24, Escape,
 * PageUp, PageDown, and Enter in a text or option field) reaches the search
 * list's scope. F12 and Escape close; Enter, PageUp, PageDown and every other
 * function key are ignored, because nothing is displayed for them to act on,
 * and prevented, so paging never scrolls the screen beneath either. Keys the
 * contract leaves native (Enter or Space on a focused button, Tab, printable
 * characters, modifier chords) and mouse clicks still act on the screen
 * beneath, which is not inert until the window opens. A row action button
 * pressed then is its option plus Enter, as at any time: a new search when
 * the criteria changed, else a fresh option walk over every option still
 * typed, which keeps this window, and its read, only when the walk's first
 * option is this customer in this mode. Removed as soon as the read settles
 * and the window takes over with its own scope.
 */
function PendingScope({ onClose }: Pick<SessionProps, 'onClose'>) {
  useFunctionKeys(
    {
      Enter: ignoreKey,
      PageUp: ignoreKey,
      PageDown: ignoreKey,
      F12: () => onClose(),
    },
    { onUnbound: ignoreKey },
  );
  return null;
}

function ignoreKey(): void {
  return undefined;
}

type Phase = 'form' | 'confirm';

/** An open DEM1002 comparison: what the user started from, what they tried to save, what is stored now. */
interface ConflictState {
  original: CustomerFields;
  mine: CustomerFields;
  current: CustomerResponse;
}

/**
 * The window's keys, each with the handler of the current phase: the four
 * keys MTNCUSTD offers (Enter, CF04, CA05, CA12), plus PageUp and PageDown,
 * on which MTNCUSTR never acts. The paging keys are n/a in every phase
 * (display, form and confirmation): they are bound, so the browser neither
 * pages nor scrolls, to the DEM0003 answer, which changes nothing.
 * Shift+PageUp and Shift+PageDown are chords and stay native.
 */
interface DetailKeys {
  Enter: () => void;
  F4: () => void;
  F5: () => void;
  F12: () => void;
  PageUp: () => void;
  PageDown: () => void;
}

interface DetailWindowProps extends SessionProps {
  initialRecord: CustomerResponse | null;
}

/** The open window, holding every piece of its state and its one key scope. */
function DetailWindow({ mode, custId, initialRecord, onClose }: DetailWindowProps) {
  const { username } = useAuth();
  const { publish } = useToasts();
  const { format } = useMessages();
  const { present } = useProblemPresenter();

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
  // A review, reload or write is pending: the rendered side of `inFlight`.
  // It sets the editable form's inputs read-only (CustomerForm `pending`,
  // which keeps their element and editable look, so focus stays and nothing
  // flashes) and disables every key-bar entry whose handler ignores a press
  // meanwhile, so nothing is typed or pressed that the response would
  // silently replace or swallow.
  const [busy, setBusy] = useState(false);
  // Bumped whenever the form is reloaded, cleared or shown again after the
  // confirmation; as the form's key it remounts the form, which re-applies
  // its initial focus (DSPATR(PC)).
  const [formKey, setFormKey] = useState(0);

  // Refs: written by callback refs, effects and handlers; never read during render.
  const inputs = useRef<Partial<Record<CustomerFieldName, FormFieldElement | null>>>({});
  const nameRef = useRef<FormFieldElement | null>(null);
  // The window body: the key container outside the confirmation, and the
  // Display window's initial focus.
  const bodyRef = useRef<HTMLDivElement | null>(null);
  const confirmRef = useRef<HTMLDivElement | null>(null);
  // A request is in flight. Read by the key handlers and by `changeField`,
  // so a second Enter or click before the next render can never send a
  // second PUT or POST, and a keystroke that lands before the read-only form
  // renders never changes the draft the response is about to replace.
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

  /** DEM0003 "Key is not active now": the answer to every key the screen does not enable. */
  function keyNotActive(): void {
    publish({ kind: 'alert', text: format('DEM0003') });
  }

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
   * the "is focus on State" test of F4, and keeps `nameRef` (the initial
   * focus of an Edit or Add window) pointing at the Name input.
   */
  function bindInput(field: CustomerFieldName) {
    return (el: FormFieldElement | null): void => {
      inputs.current[field] = el;
      if (field === 'name') {
        nameRef.current = el;
      }
    };
  }

  /**
   * Records one keyed field value; FormField has already uppercased it.
   * While a review or reload is in flight the change is ignored: the form is
   * rendered read-only then, and this synchronous guard also covers a
   * keystroke that arrives before that render, so the response never
   * replaces text the user typed meanwhile.
   */
  function changeField(field: CustomerFieldName, value: string): void {
    if (inFlight.current) {
      return;
    }
    setDraft((current) => ({ ...current, [field]: value }));
  }

  function begin(): boolean {
    if (inFlight.current) {
      return false;
    }
    inFlight.current = true;
    setBusy(true);
    return true;
  }

  /**
   * Marks the request as finished. It runs in the same continuation as the
   * response's own state updates, so React batches them into one render: the
   * form turns editable again in the very render that shows the response.
   */
  function finish(): void {
    inFlight.current = false;
    if (alive.current) {
      setBusy(false);
    }
  }

  /** Shows the form again, remounted so its initial focus applies. */
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
    // MTNCUSTR clears its RI/PC indicators after every read. They are dropped
    // when the answer arrives, in the render that shows it, not when the
    // request starts: the 5250 writes the screen once per cycle, so its old
    // highlights stay until the new screen replaces them, and the window
    // keeps its height and its fields their places while the request waits.
    try {
      const response = await customersApi.review({ purpose: REVIEW_PURPOSE[mode], ...fields });
      if (!alive.current) {
        return;
      }
      setFieldErrors([]);
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
      // A failure naming fields replaces the highlights through the
      // presenter; any other failure leaves none.
      setFieldErrors([]);
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

  /** F4 on the form (F04Prompt, MTNCUSTR :364-382): the State picker when focus is on State, otherwise DEM0005. */
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

  /** Refresh: `current` replaces the entries, as the source re-read and redisplayed the record (UpdateRecd :593-599). */
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

  // Every handler that starts a request, or changes what a pending request
  // will land on, does nothing while one is in flight; F12 on the form always
  // closes. Requests are gated once more by `begin`. While one is in flight
  // the key bar shows exactly those keys disabled (Enter, F4 and F5 on the
  // form; all four at the confirmation), and the form is read-only.

  function enterOnForm(): void {
    void runReview(draft);
  }

  function promptOnForm(): void {
    if (!inFlight.current) {
      prompt();
    }
  }

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

  const confirming = phase === 'confirm' && reviewed !== null;
  let keys: DetailKeys;
  if (mode === 'display') {
    // One protected screen I/O (MTNCUSTR :181-191): whichever enabled key returns closes the window.
    keys = { Enter: close, F4: close, F5: close, F12: close, PageUp: keyNotActive, PageDown: keyNotActive };
  } else if (!confirming) {
    keys = {
      Enter: enterOnForm,
      F4: promptOnForm,
      F5: refreshOnForm,
      F12: close,
      PageUp: keyNotActive,
      PageDown: keyNotActive,
    };
  } else {
    // The paging keys answer DEM0003 and leave the confirmation shown, unlike
    // the enabled-but-inactive F4 (notActiveAtConfirm), which returns to the form.
    keys = {
      Enter: enterAtConfirm,
      F4: notActiveAtConfirm,
      F5: mode === 'edit' ? cancelAtConfirm : notActiveAtConfirm,
      F12: cancelAtConfirm,
      PageUp: keyNotActive,
      PageDown: keyNotActive,
    };
  }

  // The window's only key scope. The State picker and the conflict
  // comparison push their own scopes on top while open, which suspends this
  // one until they close (search → detail → picker). Enter is a command on
  // the text inputs and on the container: the confirmation panel while
  // confirming (it takes focus on mount), the window body otherwise (a
  // Display window opens with focus there). On a protected value's read-only
  // textarea Enter keeps its native action.
  useFunctionKeys(keys, {
    onUnbound: keyNotActive,
    containerRef: confirming ? confirmRef : bodyRef,
  });

  // The visible legend (MTNCUSTR BldFkeyText :638-649), each entry calling
  // the very handler its key runs. An entry is disabled exactly while its
  // handler ignores a press because a request is in flight; F12 on the form
  // closes even then, so the window can always be cancelled.
  const keyBar: FunctionKeyBarItem[] = [
    { key: 'F4', label: 'F4=Prompt+', onPress: keys.F4, disabled: busy },
    { key: 'F5', label: 'F5=Refresh', onPress: keys.F5, disabled: busy },
    { key: 'F12', label: 'F12=Cancel', onPress: keys.F12, disabled: busy && confirming },
    { key: 'Enter', label: 'Enter', onPress: keys.Enter, disabled: busy },
  ];

  // A key-bar entry pressed from the keyboard (Tab to it, then Space or
  // Enter) holds focus as the request it starts disables it. A disabled
  // button cannot keep focus usefully, and a browser may drop it to <body>,
  // where Enter is no command and Tab starts outside the window. The phase's
  // key container takes focus instead, so Enter, the function keys and the
  // Tab trap keep working. Where the response moves focus itself (the
  // confirmation panel, a reloaded form's Name, the first field in error),
  // that move follows and wins; a failure that moves nothing leaves focus
  // here, inside the window.
  useEffect(() => {
    if (!busy) {
      return;
    }
    const container = confirming ? confirmRef.current : bodyRef.current;
    const active = document.activeElement;
    const dropped =
      active === null ||
      active === document.body ||
      (active instanceof HTMLElement && bodyRef.current?.contains(active) === true && active.matches(':disabled'));
    if (container !== null && dropped) {
      container.focus();
    }
  }, [busy, confirming]);

  const editable = mode !== 'display';
  const errors = errorsByField(fieldErrors);
  const firstErrorField = fieldErrors.map((error) => error.field).find(isCustomerField);

  // Initial focus: Name in Edit and Add, the first editable field. A Display
  // window has none, so it opens on its key container, where Enter, like F4,
  // F5, F12 and Escape, closes it; `customer-detail--display` keeps that
  // deliberate focus visible.
  return (
    <Dialog
      open
      labelledBy={LABELLED_BY}
      initialFocusRef={editable ? nameRef : bodyRef}
      className="dialog dialog--detail"
    >
      <div
        ref={bodyRef}
        tabIndex={-1}
        className={editable ? 'customer-detail' : 'customer-detail customer-detail--display'}
        aria-busy={busy || undefined}
      >
        <ScreenHeader id={HEADER_ID} functionText={FUNCTION_TEXT[mode]} user={username ?? undefined} />
        {confirming ? (
          <ConfirmationPanel
            idPrefix={CONFIRM_ID_PREFIX}
            custId={custId ?? ''}
            values={reviewed}
            standardized={standardized}
            containerRef={confirmRef}
            stamp={record !== null ? { chgTime: record.chgTime, chgUser: record.chgUser } : null}
          />
        ) : (
          <CustomerForm
            key={formKey}
            idPrefix={FORM_ID_PREFIX}
            custId={record?.custId ?? custId ?? ''}
            values={draft}
            onChange={editable ? changeField : keepDisplayedValue}
            readOnly={!editable}
            pending={busy}
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
