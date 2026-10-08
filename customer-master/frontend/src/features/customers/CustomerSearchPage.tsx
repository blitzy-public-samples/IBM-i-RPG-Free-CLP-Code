/**
 * CustomerSearchPage and CustomerSearchPanel: the customer search screen.
 *
 * What it replaces. PMTCUSTR and its display file PMTCUSTD
 * (5250_Subfile/PMTCUSTR.SQLRPGLE, 5250_Subfile/PMTCUSTD.DSPF; layout
 * 5250_Subfile/Images/Inquiry_Subfile.png): the full-screen expanding
 * subfile that searched CUSTMAST by name, city and state and offered list
 * options per mode. Its first parameter, the caller-asserted mode `I`, `M`
 * or `S`, is replaced by the signed-in user's role (Inquiry, Maintenance)
 * and by the Customer picker context (Selection); the mode never comes from
 * a URL or a parameter the browser could forge.
 *
 *   PMTCUSTR / PMTCUSTD                          Here
 *   Init: SH_FUNCT Inquiry / Maintenance /       `ScreenHeader` function line and
 *     Selection, SC_OPTIONS (:712-767)             the options line per {@link SearchMode}
 *   SflFirstPage on entry in `I` (:252-256)      `useCustomerSearch({ initial })`
 *   NewSearchCriteria = *on at start (:243)      `pendingNewSearch`: the first Enter searches
 *   Enter: new criteria, else ProcessOption      `enter()` (:261-285, :427-515)
 *   PageDown: SflFillPage, DEM0006 at 9,999,     `pageDown()` (:287-299)
 *     DEM0003 with no list
 *   ProcessFunctionKey F3 F4 F5 F6 F9 F12,        the one `useFunctionKeys` scope and
 *     other keys DEM0003 (:355-420)               `FunctionKeyBar` built from the same handlers
 *   BldFkeyText (:676-702)                       the key legends, F9's switching text
 *   CustDsp(MTNCUSTR) E / D / A                  `CustomerDetailDialog` edit / display / add
 *   PmtState(PMTSTATER) from SC_STATE            `StatePicker` from the State filter
 *   SndSflMsg to the message subfile             `useToasts().publish` and `useProblemPresenter`
 *   'Demo Corp of America' footer (:128)         `.footer-brand`
 *
 * Behaviour carried from the source (the business rules themselves, such as
 * DEM0007, the 9,999-row cap and the DEM0002/DEM0006 notices, are the
 * server's; this file applies only the option validity per mode and the key
 * routing, which are screen behaviour):
 * - **Enter.** New search criteria take precedence over options: a new
 *   search runs when one is pending (no list yet, after F5, after a State
 *   chosen through F4 that differs from the applied one, after DEM0002 or a
 *   failed search such as DEM0007) or when the typed Name, City or State
 *   differ from the criteria last applied. Otherwise the typed options are
 *   processed over every loaded row in list order, as READC read every
 *   changed subfile record: `1` (Selection) returns the id and ends; `2`
 *   (Maintenance) opens the change window and `5` (any mode) the display
 *   window, one at a time, each option cleared once its window closes and a
 *   saved row updated in place; any other entry is DEM0004 with the option
 *   typed, its input marked reverse image; a blank clears an earlier error.
 *   With options processed the page of the first invalid option is shown and
 *   its input focused, else the page of the last processed option. With
 *   nothing to process the last page loaded so far is shown (the preserved
 *   ProcessOption defect, :506-513).
 * - **F4** prompts only from the State filter, the field marked "+"; F4
 *   anywhere else is DEM0005. After the prompt closes, whether a code was
 *   chosen or not, a State that differs from the one last applied clears the
 *   list, so the next Enter searches (:376-381, PmtState returns the field
 *   unchanged on cancel).
 * - **F5** clears the criteria, turns inactive rows off and empties the list;
 *   the next Enter searches. No request is sent.
 * - **F6** adds a customer in Maintenance only; the list stays as it was
 *   afterwards, with no message (:397-400). Elsewhere it is not bound and
 *   answers DEM0003.
 * - **F9** toggles inactive rows, switches its legend and reloads the first
 *   page with the criteria on screen, in every mode (:406-413).
 * - **F3 and F12** (Escape is F12) leave through `onExit`.
 * - **PageDown / PageUp** page through the loaded list; with no list both are
 *   DEM0003. PageUp needs no request (the loaded pages are the cursor stack).
 * - Any other function key is DEM0003 and changes nothing.
 *
 * Keyboard and focus. The panel registers one key scope with its own
 * `<section>` as the container, so Enter is a command in the filters, in the
 * option fields and on the panel itself (the section takes focus when the
 * user clicks its plain text). The detail window and the State picker are
 * rendered inside the panel as siblings of the list; they push their own
 * scopes on top when they open, so only the topmost window receives keys.
 * Rows are keyed by customer id, so a page change unmounts the field that
 * had focus: when focus was lost that way, it follows to the first row of the
 * new page (the 5250 cursor on the first record of the page, SFLRCDNBR
 * CURSOR), and a new search or F5 moves it to the Name filter. The Name
 * filter receives focus on open: Inquiry positions the cursor there
 * explicitly (SC_NAME_PC, :745-748) and in the other modes it is the first
 * input field, where the 5250 cursor lands by default.
 *
 * Messages. Notices and problems arrive as data: a page's notice is a
 * `status` toast, a failed request goes through `useProblemPresenter`, which
 * highlights and focuses the filter it names (DEM0007 on State). The client
 * raised DEM0003, DEM0004 and DEM0005 come from the message catalog. The
 * strings below are screen labels and key legends only.
 *
 * Layer rule: imports come only from `api/` (types), `errors/`,
 * `components/`, `keyboard/`, `messages/`, `auth/`, `features/states/` and
 * this folder, plus React and react-router-dom.
 *
 * Rendering requires, above it: `QueryClientProvider`, a router (for
 * `CustomerSearchPage`), `AuthProvider`, `MessageCatalogProvider`,
 * `ToastProvider` and `KeyScopeProvider`.
 *
 * @example
 * ```tsx
 * // The /customers route: mode from the session, exit to the home page.
 * <RequireRole role="INQUIRY"><CustomerSearchPage /></RequireRole>
 *
 * // The Customer picker: Selection mode inside a Dialog named by the header.
 * <Dialog open labelledBy="customer-picker-title customer-picker-function" className="dialog dialog--picker">
 *   <CustomerSearchPanel mode="selection" headerId="customer-picker" onSelect={selectOnce} onExit={onCancel} />
 * </Dialog>
 * ```
 */
import { useEffect, useId, useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import type { CustomerResponse, CustomerSummaryResponse } from '../../api/customers';
import type { FieldError } from '../../api/problem';
import { useAuth } from '../../auth/AuthProvider';
import { FunctionKeyBar } from '../../components/FunctionKeyBar';
import type { FunctionKeyBarItem } from '../../components/FunctionKeyBar';
import { ScreenHeader } from '../../components/ScreenHeader';
import { useToasts } from '../../components/ToastRegion';
// The one shared, length-preserving uppercase rule. ResultsTable leaves the
// Opt fields' uppercasing to the panel that owns them, and the rule must not
// be re-implemented here, so it is imported although the plan's dependency
// list for this file names only the components it renders.
import { upperField } from '../../components/upperField';
import { useProblemPresenter } from '../../errors/useProblemPresenter';
import { useFunctionKeys } from '../../keyboard/useFunctionKeys';
import type { KeyBindings } from '../../keyboard/useFunctionKeys';
import { useMessages } from '../../messages/MessageCatalogProvider';
import { StatePicker } from '../states/StatePicker';
import { CustomerDetailDialog } from './CustomerDetailDialog';
import type { DetailCloseResult, DetailMode } from './CustomerDetailDialog';
import { ResultsTable } from './ResultsTable';
import type { RowOption } from './ResultsTable';
import { SearchFilters } from './SearchFilters';
import type { FilterField } from './SearchFilters';
import { useCustomerSearch } from './useCustomerSearch';
import type { SearchCriteria } from './useCustomerSearch';

// ---------------------------------------------------------------------------
// Public API
// ---------------------------------------------------------------------------

/**
 * The function the screen performs, the PMTCUSTR first parameter:
 * `inquiry` (`I`, role INQUIRY), `maintenance` (`M`, role MAINTENANCE) or
 * `selection` (`S`, the Customer picker, open to every signed-in user).
 */
export type SearchMode = 'inquiry' | 'maintenance' | 'selection';

/** Props of {@link CustomerSearchPanel}. */
export interface CustomerSearchPanelProps {
  /** The screen's function; fixes the header, the options offered, F6 and whether the list loads on open. */
  mode: SearchMode;
  /** The Name filter's value on open (a picker may preset it); blank when absent. */
  initialName?: string;
  /**
   * Selection mode's return slot: receives the customer id of option 1
   * (PMTCUSTR moves SF_CUST_H into pCustID and ends, :439-445).
   */
  onSelect?: (custId: string) => void;
  /** F3, F12 and Escape: leave the screen (PMTCUSTR CloseDownPgm and return, :359-368). */
  onExit: () => void;
  /**
   * Id prefix of the header, passed to `ScreenHeader id`, so a dialog around
   * the panel can name itself by `"<headerId>-title <headerId>-function"`.
   */
  headerId?: string;
}

/**
 * The `/customers` screen: the search panel in the mode the signed-in user's
 * role gives (MAINTENANCE → Maintenance, anything else → Inquiry). F3 and
 * F12 return to the home page, which replaces the calling menu.
 *
 * The panel is keyed by its mode, so a change of role (sign-out and sign-in
 * as another user) starts a fresh screen, as each call of PMTCUSTR ran
 * `Init` again.
 */
export function CustomerSearchPage() {
  const { mode } = useAuth();
  const navigate = useNavigate();
  const panelMode: SearchMode = mode === 'MAINTENANCE' ? 'maintenance' : 'inquiry';

  return (
    <main className="screen">
      <CustomerSearchPanel key={panelMode} mode={panelMode} onExit={() => void navigate('/')} />
    </main>
  );
}

// ---------------------------------------------------------------------------
// Constants and pure helpers
// ---------------------------------------------------------------------------

/** SH_FUNCT of each mode: HdrInq, HdrMaint, HdrSelect (PMTCUSTR :726-728). */
const FUNCTION_TEXT: Readonly<Record<SearchMode, string>> = {
  inquiry: 'Inquiry',
  maintenance: 'Maintenance',
  selection: 'Selection',
};

/** SC_OPTIONS of each mode, built by Init from OPT1TEXT, OPT2TEXT and OPT5TEXT (:750-766). */
const OPTION_LEGEND: Readonly<Record<SearchMode, string>> = {
  inquiry: '5=Display',
  maintenance: '2=Edit 5=Display',
  selection: '1=Select 5=Display',
};

/**
 * The options each mode accepts, one row action button each. Selection
 * offers only 1 and 5 whatever the user's role, as source `S` mode never
 * sets Maint_OK.
 */
const ALLOWED_OPTIONS: Readonly<Record<SearchMode, RowOption[]>> = {
  inquiry: ['5'],
  maintenance: ['2', '5'],
  selection: ['1', '5'],
};

/** The criteria fields after F5 or on open: all blank (`clear SearchCriteria`, :391). */
const BLANK_FILTERS: Readonly<Record<FilterField, string>> = Object.freeze({ name: '', city: '', state: '' });

/** What a valid option does: return the id (option 1) or open the detail window (2 = edit, 5 = display). */
type OptionKind = 'select' | Extract<DetailMode, 'edit' | 'display'>;

/** One valid option the Enter walk will run, in list order. */
interface OptionAction {
  custId: string;
  kind: OptionKind;
}

/** One option the Enter walk rejected (DEM0004), with the value as typed. */
interface RejectedOption {
  custId: string;
  option: string;
}

/**
 * The state of one Enter's option processing, which may span several detail
 * windows opened one after another. Lives in a ref: it is read and written
 * only by handlers, never during render.
 */
interface OptionWalk {
  /** Valid options not yet run, in list order. */
  remaining: OptionAction[];
  /** Rejected options in list order; their DEM0004 messages are published when the walk ends. */
  rejected: RejectedOption[];
  /** The last option run, where the cursor stays when nothing was rejected (SetCursorPosition, :667-674). */
  lastProcessed: string | null;
}

/** The detail window requested: its function, its customer (absent on add), and whether an option walk opened it. */
interface DetailRequest {
  mode: DetailMode;
  custId?: string;
  fromWalk: boolean;
}

/**
 * A request to focus a row's option field once its page is rendered: the
 * given customer's row, or (`custId` null) the first row of the page shown.
 * `seq` makes every request distinct, so each is honoured exactly once.
 *
 * `onlyIfLost` makes the move conditional: it happens only when focus was
 * lost, because the field that had it was on the page just replaced (rows are
 * keyed by customer id, so their inputs unmount with the page). Focus that is
 * still on a live element, a filter or the field a closed window returned it
 * to, stays there.
 */
interface FocusRequest {
  seq: number;
  custId: string | null;
  onlyIfLost: boolean;
}

/** What each mode does with a typed option: the action, or null for DEM0004 (ProcessOption, :437-499). */
function classifyOption(mode: SearchMode, option: string): OptionKind | null {
  if (option === '1' && mode === 'selection') {
    return 'select';
  }
  if (option === '2' && mode === 'maintenance') {
    return 'edit';
  }
  if (option === '5') {
    return 'display';
  }
  return null;
}

/** Whether an option field is blank: never typed, emptied, or blanks only (SF_OPT = ' ', :470). */
function isBlankOption(option: string | undefined): boolean {
  return option === undefined || option.trim() === '';
}

/**
 * Whether the typed criteria differ from the criteria last applied
 * (`SearchCriteria <> LastSearchCriteria`, :273). The source compares CHAR
 * fields, which ignores trailing blanks only, so only those are disregarded.
 */
function criteriaDiffer(typed: Readonly<Record<FilterField, string>>, applied: SearchCriteria): boolean {
  return (
    typed.name.trimEnd() !== applied.name.trimEnd() ||
    typed.city.trimEnd() !== applied.city.trimEnd() ||
    typed.state.trimEnd() !== applied.state.trimEnd()
  );
}

/**
 * Whether keyboard focus was lost: nothing, or the page body, holds it, or the
 * element that held it has left the document. Enter is no longer a command
 * then, so the panel puts focus back on a row.
 */
function isFocusLost(): boolean {
  const active = document.activeElement;
  return active === null || active === document.body || !active.isConnected;
}

/** Whether a problem's field names one of the three filters. */
function isFilterField(field: string): field is FilterField {
  return field === 'name' || field === 'city' || field === 'state';
}

/**
 * The list row of a customer as now stored, after a committed edit: the
 * `ReadByKey` + `BuildSflRecd` re-read of PMTCUSTR (:454-457). ZIP shows its
 * first five characters, as SF_ZIP 5A and the server's `zip5` do.
 */
function toSummary(saved: CustomerResponse): CustomerSummaryResponse {
  return {
    custId: saved.custId,
    name: saved.name,
    city: saved.city,
    state: saved.state,
    zip5: saved.zip.slice(0, 5),
    active: saved.active,
  };
}

// ---------------------------------------------------------------------------
// The panel
// ---------------------------------------------------------------------------

/**
 * The customer search screen body, shared by the `/customers` page and the
 * Customer picker: header, criteria, options line, one page of results, the
 * paging indicator, the footer and the key legend, plus the detail window
 * and the State picker it opens. Owns the search state and the one key scope
 * of the screen.
 */
export function CustomerSearchPanel({ mode, initialName, onSelect, onExit, headerId }: CustomerSearchPanelProps) {
  const { username } = useAuth();
  const { publish } = useToasts();
  const { format } = useMessages();
  const { present } = useProblemPresenter();
  const idPrefix = useId();

  // --- State ---------------------------------------------------------------
  // The criteria as shown on screen (SC_NAME, SC_CITY, SC_STATE).
  const [typed, setTyped] = useState<Record<FilterField, string>>(() => ({
    ...BLANK_FILTERS,
    name: initialName ?? '',
  }));
  // F9: indicator 03, scIncActInc, off on entry (:741).
  const [includeInactive, setIncludeInactive] = useState(false);
  // Field messages of a failed search, keyed by filter (DEM0007 on State).
  const [filterErrors, setFilterErrors] = useState<Partial<Record<FilterField, string>>>({});
  // The text in each row's Opt field (SF_OPT), kept across paging as the
  // subfile kept it, and dropped whenever the list is cleared (SflClear).
  const [options, setOptions] = useState<Record<string, string>>({});
  // Rows whose option the last Enter rejected: DSPATR(RI), indicator 81.
  const [invalid, setInvalid] = useState<Record<string, boolean>>({});
  // The detail window shown, if any (CustDsp).
  const [detail, setDetail] = useState<DetailRequest | null>(null);
  // Whether the State prompt (PmtState) is open.
  const [pickerOpen, setPickerOpen] = useState(false);
  // A row option field to focus once its page is on screen.
  const [focusRequest, setFocusRequest] = useState<FocusRequest | null>(null);

  // --- Refs (written by callback refs, effects and handlers; never read during render)
  const containerRef = useRef<HTMLElement | null>(null);
  const nameRef = useRef<HTMLInputElement | null>(null);
  const stateRef = useRef<HTMLInputElement | null>(null);
  // The rendered Opt inputs by customer id, filled by callback refs.
  const optionInputs = useRef(new Map<string, HTMLInputElement>());
  // The option processing of the current Enter, while windows are shown.
  const walkRef = useRef<OptionWalk | null>(null);
  // The detail window currently open; a second close of the same window is ignored.
  const openDetailRef = useRef<DetailRequest | null>(null);
  // Sequence of focus requests, and the last one honoured.
  const focusSeqRef = useRef(0);
  const handledFocusRef = useRef(0);

  // --- Message and focus helpers -------------------------------------------

  /** DEM0003 "Key is not active now": every key or action the screen does not enable now. */
  function keyNotActive(): void {
    publish({ kind: 'alert', text: format('DEM0003') });
  }

  /** The input of one filter, by the id SearchFilters gives it. */
  function filterInput(field: FilterField): HTMLElement | null {
    return document.getElementById(`${idPrefix}-${field}`);
  }

  /** Shows a failed search's field messages on the filters it names (the reverse image of the field). */
  function showFilterErrors(errors: FieldError[]): void {
    const next: Partial<Record<FilterField, string>> = {};
    for (const error of errors) {
      if (isFilterField(error.field) && next[error.field] === undefined) {
        next[error.field] = error.message;
      }
    }
    setFilterErrors(next);
  }

  /** Moves focus to the filter a failed search names first (DSPATR(PC)); other fields are ignored. */
  function focusFilter(field: string): void {
    if (isFilterField(field)) {
      filterInput(field)?.focus();
    }
  }

  /** Whether focus is in a row of the list: its Opt field or one of its action buttons. */
  function isFocusInRows(): boolean {
    const active = document.activeElement;
    if (active === null) {
      return false;
    }
    for (const input of optionInputs.current.values()) {
      if (input.closest('tr')?.contains(active)) {
        return true;
      }
    }
    return false;
  }

  /**
   * Before the list is cleared or replaced: focus in a row would fall to the
   * page body, where Enter is no longer a command, so it moves to the Name
   * filter first. Focus anywhere else stays where it is.
   */
  function keepFocusOutOfRows(): void {
    if (isFocusInRows()) {
      nameRef.current?.focus();
    }
  }

  /**
   * Asks for a row's option field (or, with null, the first row's on the page
   * shown) to be focused once rendered; with `onlyIfLost`, only when focus has
   * fallen to the page body by then.
   */
  function requestRowFocus(custId: string | null, onlyIfLost: boolean): void {
    focusSeqRef.current += 1;
    setFocusRequest({ seq: focusSeqRef.current, custId, onlyIfLost });
  }

  // --- The list --------------------------------------------------------------

  const list = useCustomerSearch({
    // Inquiry loads the first page with the criteria on screen at entry
    // (:252-256); Maintenance and Selection wait for the first Enter.
    initial: mode === 'inquiry' ? { name: initialName ?? '', city: '', state: '', includeInactive: false } : null,
    onNotice: (notice) => publish({ kind: 'status', text: notice.message }),
    onError: (error) => present(error, { setFieldErrors: showFilterErrors, focusField: focusFilter }),
  });

  // The cursor on open: the Name filter (SC_NAME_PC in Inquiry, :745-748;
  // the first input field otherwise). This effect only moves focus; inside a
  // picker the Dialog's own initial focus lands on the same field.
  useEffect(() => {
    nameRef.current?.focus();
  }, []);

  // Honours a focus request once the row it names is rendered: rows are keyed
  // by customer id, so a page change replaces the inputs and the target only
  // exists after the render that shows its page. Each request is honoured
  // once; one whose page has no rows is dropped. This effect only moves focus.
  useEffect(() => {
    if (focusRequest === null || focusRequest.seq === handledFocusRef.current) {
      return;
    }
    const target = focusRequest.custId ?? list.page[0]?.custId;
    const input = target === undefined ? undefined : optionInputs.current.get(target);
    if (target !== undefined && input === undefined) {
      // Its page is not on screen yet; the render that shows it runs this again.
      return;
    }
    handledFocusRef.current = focusRequest.seq;
    if (input !== undefined && (!focusRequest.onlyIfLost || isFocusLost())) {
      input.focus();
    }
  }, [focusRequest, list.page]);

  /** Forgets the typed options and their errors (the SflClear half of the source). */
  function clearOptions(): void {
    walkRef.current = null;
    setOptions({});
    setInvalid({});
  }

  /**
   * A new list from its first page (SflClear + SflFirstPage). The filter
   * messages are dropped because the server decides them anew.
   */
  function newSearch(criteria: SearchCriteria): void {
    keepFocusOutOfRows();
    clearOptions();
    setFilterErrors({});
    list.search(criteria);
  }

  // --- Option processing (ProcessOption, :427-515) ---------------------------

  /** Opens the detail window for one request. */
  function openDetail(request: DetailRequest): void {
    openDetailRef.current = request;
    setDetail(request);
  }

  /**
   * Ends an option walk: one DEM0004 per rejected option in list order,
   * published now so no interaction inside an earlier window cleared them;
   * the rejected inputs marked; and the cursor positioned, on the first
   * rejected option when there is one (its field always takes focus,
   * DSPATR(PC)), else on the last option run (its field takes focus only when
   * the element a closed window returned focus to has left with its page).
   */
  function finishWalk(walk: OptionWalk): void {
    for (const entry of walk.rejected) {
      publish({ kind: 'alert', text: format('DEM0004', [entry.option]) });
    }
    setInvalid(Object.fromEntries(walk.rejected.map((entry) => [entry.custId, true])));
    const first = walk.rejected[0];
    if (first !== undefined) {
      list.showPageOf(first.custId);
      requestRowFocus(first.custId, false);
      return;
    }
    if (walk.lastProcessed !== null) {
      list.showPageOf(walk.lastProcessed);
      requestRowFocus(walk.lastProcessed, true);
      return;
    }
    list.toLastLoaded();
    requestRowFocus(null, true);
  }

  /**
   * Runs the next valid option of the walk: `1` returns the id and ends the
   * walk (and the screen, whose host closes it); `2` and `5` open their
   * window, and the walk resumes when it closes. With nothing left the walk
   * ends.
   *
   * @param processed the customer whose window just closed, or null at the start
   */
  function advanceWalk(processed: string | null): void {
    const walk = walkRef.current;
    if (walk === null) {
      return;
    }
    const current: OptionWalk = processed === null ? walk : { ...walk, lastProcessed: processed };
    const [next, ...rest] = current.remaining;
    if (next === undefined) {
      walkRef.current = null;
      finishWalk(current);
      return;
    }
    if (next.kind === 'select') {
      // The id is returned at once. Messages for rows before it are never
      // shown: the source returned before displaying them (:439-445).
      walkRef.current = null;
      setOptions((previous) => ({ ...previous, [next.custId]: '' }));
      onSelect?.(next.custId);
      return;
    }
    walkRef.current = { ...current, remaining: rest };
    openDetail({ mode: next.kind, custId: next.custId, fromWalk: true });
  }

  /**
   * Processes the options typed on every loaded row, in list order, as READC
   * read every changed subfile record.
   *
   * @param typedOptions the Opt fields to process; a row action passes its
   *   option already applied, before the state update has rendered
   */
  function processOptions(typedOptions: Record<string, string>): void {
    const actions: OptionAction[] = [];
    const rejected: RejectedOption[] = [];
    for (const row of list.pages.flat()) {
      const option = typedOptions[row.custId];
      if (option === undefined || isBlankOption(option)) {
        continue;
      }
      const kind = classifyOption(mode, option);
      if (kind === null) {
        rejected.push({ custId: row.custId, option });
      } else {
        actions.push({ custId: row.custId, kind });
      }
    }

    if (actions.length === 0 && rejected.length === 0) {
      // Nothing to process: blank options clear an earlier error, and the
      // last page loaded so far is shown, the cursor on its first row when
      // the field that had focus left with the page it was on (preserved
      // source defect, :506-513).
      setInvalid({});
      list.toLastLoaded();
      requestRowFocus(null, true);
      return;
    }

    walkRef.current = { remaining: actions, rejected, lastProcessed: null };
    advanceWalk(null);
  }

  /**
   * Enter (:269-284). New search criteria take precedence over options.
   *
   * @param typedOptions the Opt fields to process when no new search runs
   */
  function submit(typedOptions: Record<string, string>): void {
    if (list.pendingNewSearch || list.applied === null || criteriaDiffer(typed, list.applied)) {
      newSearch({ ...typed, includeInactive });
      return;
    }
    processOptions(typedOptions);
  }

  /** The Enter key and its legend button. */
  function enter(): void {
    submit(options);
  }

  /**
   * A row action button: exactly the option typed into that row followed by
   * Enter, so changed criteria still take precedence, as on the 5250.
   */
  function runAction(custId: string, option: RowOption): void {
    const typedOptions = { ...options, [custId]: option };
    setOptions(typedOptions);
    submit(typedOptions);
  }

  /** Stores a row's option as typed, uppercased by the shared rule (no CHECK(LC) on SF_OPT). */
  function changeOption(custId: string, value: string): void {
    setOptions((previous) => ({ ...previous, [custId]: upperField(value) }));
  }

  /** Stores one filter as typed (SearchFilters' inputs already uppercase it). */
  function changeFilter(field: FilterField, value: string): void {
    setTyped((previous) => ({ ...previous, [field]: value }));
  }

  /**
   * The detail window closed. A committed edit updates its row in place,
   * where it stays even if it no longer matches the criteria, red when now
   * inactive (ReadByKey + UpdSflRecd, :454-457); an add leaves the list as it
   * was, with no message (:397-400). A window an option opened clears its
   * option and lets the walk continue with the next one.
   */
  function closeDetail(result?: DetailCloseResult): void {
    const closing = openDetailRef.current;
    if (closing === null) {
      return;
    }
    openDetailRef.current = null;
    setDetail(null);
    if (result?.saved !== undefined) {
      list.replaceRow(toSummary(result.saved));
    }
    const custId = closing.custId;
    if (closing.fromWalk && custId !== undefined) {
      setOptions((previous) => ({ ...previous, [custId]: '' }));
      advanceWalk(custId);
    }
  }

  // --- Function keys (ProcessFunctionKey, :355-420) --------------------------

  /** F3 and F12 (Escape): leave the screen. */
  function exit(): void {
    onExit();
  }

  /** F4: the State prompt from the State filter only (SC_PMT_FLD = 'SC_STATE'); anywhere else DEM0005. */
  function prompt(): void {
    const stateInput = stateRef.current;
    if (stateInput !== null && document.activeElement === stateInput) {
      setPickerOpen(true);
      return;
    }
    publish({ kind: 'alert', text: format('DEM0005') });
  }

  /**
   * The State prompt closed, with a chosen code or without one. As after
   * `PmtState(SC_STATE)`, a State that now differs from the one last applied
   * clears the list so the next Enter searches (:377-381). Focus returns to
   * the State filter through the picker's Dialog.
   */
  function closePicker(code: string | null): void {
    setPickerOpen(false);
    const state = code ?? typed.state;
    if (code !== null) {
      setTyped((previous) => ({ ...previous, state: code }));
      // The message described the value just replaced.
      setFilterErrors((previous) => ({ ...previous, state: undefined }));
    }
    const applied = list.applied;
    if (applied !== null && state.trimEnd() !== applied.state.trimEnd()) {
      clearOptions();
      list.reset();
    }
  }

  /** F5: blank criteria, inactive rows off, the list emptied; the next Enter searches (:389-394). */
  function resetAll(): void {
    keepFocusOutOfRows();
    setTyped({ ...BLANK_FILTERS });
    setIncludeInactive(false);
    setFilterErrors({});
    clearOptions();
    list.reset();
  }

  /** F6 (Maintenance only): the add window; the list is left as it was (:396-400). */
  function add(): void {
    openDetail({ mode: 'add', fromWalk: false });
  }

  /** F9: toggles inactive rows and reloads the first page with the criteria on screen (:406-413). */
  function toggleInactive(): void {
    const next = !includeInactive;
    setIncludeInactive(next);
    newSearch({ ...typed, includeInactive: next });
  }

  /**
   * PageDown (:287-299): the next page, loaded when needed. With no list,
   * DEM0003; at the 9,999-row cap the hook re-sends DEM0006 and the page
   * stays; at the bottom the page stays. Focus that was on the page left
   * behind follows to the first row of the new page.
   */
  function pageDown(): void {
    if (!list.hasList) {
      keyNotActive();
      return;
    }
    list.next().then(
      (outcome) => {
        if (outcome === 'none') {
          keyNotActive();
        } else if (outcome === 'moved' || outcome === 'loaded') {
          requestRowFocus(null, true);
        }
      },
      (error: unknown) => present(error),
    );
  }

  /**
   * PageUp: the previous loaded page, with no request; DEM0003 with no list;
   * at the first page it stays. Focus follows as for PageDown.
   */
  function pageUp(): void {
    if (!list.hasList) {
      keyNotActive();
      return;
    }
    if (list.previous()) {
      requestRowFocus(null, true);
    }
  }

  // One handler per action, shared by the key binding and its legend button.
  // F6 is bound in Maintenance only; elsewhere it reaches onUnbound (DEM0003).
  const bindings: KeyBindings = {
    Enter: enter,
    F3: exit,
    F4: prompt,
    F5: resetAll,
    ...(mode === 'maintenance' ? { F6: add } : {}),
    F9: toggleInactive,
    F12: exit,
    PageUp: pageUp,
    PageDown: pageDown,
  };
  useFunctionKeys(bindings, { onUnbound: keyNotActive, containerRef });

  // SFT_KEYS in BldFkeyText order (:687-701), then the keys a browser user
  // cannot assume: Enter and the two paging keys.
  const keys: FunctionKeyBarItem[] = [
    { key: 'F3', label: 'F3=Exit', onPress: exit },
    { key: 'F4', label: 'F4=Prompt+', onPress: prompt },
    { key: 'F5', label: 'F5=Reset', onPress: resetAll },
    ...(mode === 'maintenance' ? [{ key: 'F6', label: 'F6=Add', onPress: add } satisfies FunctionKeyBarItem] : []),
    {
      key: 'F9',
      label: includeInactive ? 'F9=Exclude Inactive' : 'F9=Include Inactive',
      onPress: toggleInactive,
    },
    { key: 'F12', label: 'F12=Cancel', onPress: exit },
    { key: 'Enter', label: 'Enter', onPress: enter },
    { key: 'PageUp', label: 'Page Up', onPress: pageUp },
    { key: 'PageDown', label: 'Page Down', onPress: pageDown },
  ];

  /** Ref factory for the Opt inputs, so a rejected option or a new page's first row can receive focus. */
  function optionRef(custId: string): (element: HTMLInputElement | null) => void {
    return (element) => {
      const inputs = optionInputs.current;
      if (element === null) {
        inputs.delete(custId);
      } else {
        inputs.set(custId, element);
      }
    };
  }

  return (
    // tabIndex -1: the section is the key scope's container, so a click on
    // its plain text gives it focus and Enter stays a command there; it is
    // never a tab stop.
    <section ref={containerRef} className="search-panel" tabIndex={-1}>
      <ScreenHeader title="Customer Master" functionText={FUNCTION_TEXT[mode]} user={username ?? undefined} id={headerId} />
      <SearchFilters
        idPrefix={idPrefix}
        values={typed}
        onChange={changeFilter}
        includeInactive={includeInactive}
        errors={filterErrors}
        nameRef={nameRef}
        stateRef={stateRef}
      />
      <p className="instructions">Type options, press Enter.</p>
      <p className="instructions">{OPTION_LEGEND[mode]}</p>
      <ResultsTable
        rows={list.page}
        options={options}
        invalid={invalid}
        allowedOptions={ALLOWED_OPTIONS[mode]}
        onOptionChange={changeOption}
        onAction={runAction}
        optionRef={optionRef}
      />
      {/* SFLEND(*MORE); nothing while the subfile holds no records (ERASE(SFL), PMTCUSTD :84-88). */}
      {list.hasList ? <p className="paging-indicator">{list.more ? 'More...' : 'Bottom'}</p> : null}
      <p className="footer-brand">Demo Corp of America</p>
      <FunctionKeyBar keys={keys} />
      <CustomerDetailDialog
        open={detail !== null}
        mode={detail?.mode ?? 'display'}
        custId={detail?.custId}
        onClose={closeDetail}
      />
      <StatePicker open={pickerOpen} onSelect={(code) => closePicker(code)} onCancel={() => closePicker(null)} />
    </section>
  );
}
