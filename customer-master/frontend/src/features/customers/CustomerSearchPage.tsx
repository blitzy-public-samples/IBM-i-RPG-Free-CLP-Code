/**
 * CustomerSearchPage and CustomerSearchPanel: the customer search screen,
 * replacing PMTCUSTR and its display file PMTCUSTD (layout
 * 5250_Subfile/Images/Inquiry_Subfile.png). The caller-asserted mode `I`,
 * `M` or `S` is replaced by the signed-in user's role (Inquiry,
 * Maintenance) and the Customer picker context (Selection); it never comes
 * from a URL or a parameter the browser could forge.
 *
 * The business rules (DEM0007, the 9,999-row cap, the DEM0002/DEM0006
 * notices) are the server's; this file applies only the option validity per
 * mode and the key routing. Messages, from the server or the catalog, show as
 * toasts in place of the SndSflMsg message subfile; the strings here are
 * screen labels and key legends only.
 *
 * Enter: new search criteria take precedence over options. A search runs
 * when one is pending (at entry, NewSearchCriteria = *on, :243; after F5, an
 * F4 prompt that left a State differing from the applied one, DEM0002 or a
 * failed first page) or when the typed Name, City or State differ from the
 * criteria last applied. A failed next page (PageDown) leaves the loaded
 * pages current and no search pending; PageDown retries it. Otherwise the
 * typed options are processed over every loaded row in list order, as READC
 * read every changed record, their windows opened one at a time. With nothing
 * to process the last page loaded so far is shown (the preserved
 * ProcessOption defect, :506-513).
 *
 * Keys: one scope with the panel's `<section>` as its container, so Enter is
 * a command in the filters, the option fields and the panel itself. The
 * detail window and the State picker render inside the panel and stack their
 * own scopes when open, so only the topmost window receives keys.
 *
 * Pending requests: no key or button is disabled while one is pending. A
 * search, F9 or F5 starts a new list generation, so only that list's responses
 * are shown, and a later page change supersedes a page load still pending
 * (the hook's navigation token), so the late page never replaces the page the
 * user moved to.
 *
 * Layer rule: imports come only from `api/` (types), `errors/`,
 * `components/`, `keyboard/`, `messages/`, `auth/`, `features/states/` and
 * this folder, plus React and react-router-dom.
 *
 * Rendering requires, above it: `QueryClientProvider`, a router (for
 * `CustomerSearchPage`), `AuthProvider`, `MessageCatalogProvider`,
 * `ToastProvider` and `KeyScopeProvider`.
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

/**
 * The function the screen performs, the PMTCUSTR first parameter:
 * `inquiry` (`I`, role INQUIRY), `maintenance` (`M`, role MAINTENANCE) or
 * `selection` (`S`, the Customer picker, open to every signed-in user).
 */
export type SearchMode = 'inquiry' | 'maintenance' | 'selection';

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
 * `Init` again (:712-767).
 */
export function CustomerSearchPage() {
  const { mode } = useAuth();
  const navigate = useNavigate();
  const panelMode: SearchMode = mode === 'MAINTENANCE' ? 'maintenance' : 'inquiry';

  // screen--framed: on a large enough viewport the page fills the viewport,
  // its footer and key bar stay at the bottom, its list scrolls inside the
  // panel, and messages show over the end of the list (global.css frames).
  return (
    <main className="screen screen--framed">
      <CustomerSearchPanel key={panelMode} mode={panelMode} onExit={() => void navigate('/')} />
    </main>
  );
}

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

type OptionKind = 'select' | Extract<DetailMode, 'edit' | 'display'>;

interface OptionAction {
  custId: string;
  kind: OptionKind;
}

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
  remaining: OptionAction[];
  /** Rejected options in list order; their DEM0004 messages are published when the walk ends. */
  rejected: RejectedOption[];
  /** The last option run, where the cursor stays when nothing was rejected (SetCursorPosition, :667-674). */
  lastProcessed: string | null;
}

interface DetailRequest {
  mode: DetailMode;
  custId?: string;
  fromWalk: boolean;
}

/**
 * Where a focus request lands: one customer's row, on whichever page holds
 * it, or the first row of the page with a 0-based index, the page a key asked
 * to show.
 */
type FocusTarget = { readonly custId: string } | { readonly page: number };

/**
 * A request to focus a row's option field once its page is rendered. A
 * customer's row is focused once its input exists. A page's first row is
 * focused only once that page is the one shown: until then (its rows not yet
 * rendered) the request waits, and it never lands on the rows of a page shown
 * meanwhile. `seq` makes every request distinct, so each is honoured exactly
 * once and a newer request replaces one still waiting.
 *
 * `onlyIfLost` makes the move conditional: it happens only when focus was
 * lost, because the field that had it was on the page just replaced (rows are
 * keyed by customer id, so their inputs unmount with the page). Focus that is
 * still on a live element, a filter or the field a closed window returned it
 * to, stays there.
 */
interface FocusRequest {
  seq: number;
  target: FocusTarget;
  onlyIfLost: boolean;
}

/**
 * A request to show a page from its first row: once the page with the 0-based
 * index `page` is the one shown, its heading row and first row are brought
 * into view in whatever scrolls the list (the framed page's or the picker
 * window's body, or the page itself where it is not framed), as the 5250
 * displayed each page from its first record (SC_CSR_RCD, PMTCUSTR :135-137,
 * :559-560). Until then the request waits, as a page focus request does, and
 * `seq` makes every request distinct, so each is honoured once and a newer
 * one replaces one still waiting. It never moves focus.
 */
interface ScrollRequest {
  seq: number;
  page: number;
}

/**
 * The least scrolling that brings a row into view, never smooth, so nothing
 * moves with a timed effect; the scroll container's scroll padding holds.
 */
const ROW_INTO_VIEW: ScrollIntoViewOptions = { behavior: 'instant', block: 'nearest', inline: 'nearest' };

/** ProcessOption, :437-499. */
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

/** SF_OPT = ' ', :470. */
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

/**
 * Every element around `element` that scrolls sideways, the page's root
 * included, each with its sideways scroll position, so a scroll into view can
 * put them back.
 */
function sidewaysScrollPositions(element: Element | null): Array<readonly [Element, number]> {
  const positions: Array<readonly [Element, number]> = [];
  for (let box = element?.parentElement ?? null; box !== null; box = box.parentElement) {
    if (box.scrollWidth > box.clientWidth) {
      positions.push([box, box.scrollLeft]);
    }
  }
  return positions;
}

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

/** The hidden page summary read with the SFLEND indicator, such as "Page 2, rows 13 to 23.". */
function describePage(pages: readonly CustomerSummaryResponse[][], position: number): string {
  const before = pages.slice(0, position).reduce((count, items) => count + items.length, 0);
  const shown = pages[position]?.length ?? 0;
  const number = position + 1;
  return shown === 0 ? `Page ${number}.` : `Page ${number}, rows ${before + 1} to ${before + shown}.`;
}

/**
 * The search screen body shared by the `/customers` page and the Customer
 * picker; it owns the search state, the screen's one key scope and the
 * detail window and State picker it opens.
 */
export function CustomerSearchPanel({ mode, initialName, onSelect, onExit, headerId }: CustomerSearchPanelProps) {
  const { username } = useAuth();
  const { publish } = useToasts();
  const { format } = useMessages();
  const { present } = useProblemPresenter();
  const idPrefix = useId();

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
  // Rows whose option the last Enter rejected (DSPATR(RI), indicator 81),
  // each with the DEM0004 text it was rejected with.
  const [invalid, setInvalid] = useState<Record<string, string>>({});
  // CustDsp (MTNCUSTR).
  const [detail, setDetail] = useState<DetailRequest | null>(null);
  // PmtState (PMTSTATER).
  const [pickerOpen, setPickerOpen] = useState(false);
  const [focusRequest, setFocusRequest] = useState<FocusRequest | null>(null);
  const [scrollRequest, setScrollRequest] = useState<ScrollRequest | null>(null);

  const containerRef = useRef<HTMLElement | null>(null);
  const nameRef = useRef<HTMLInputElement | null>(null);
  const stateRef = useRef<HTMLInputElement | null>(null);
  const optionInputs = useRef(new Map<string, HTMLInputElement>());
  const walkRef = useRef<OptionWalk | null>(null);
  // The detail window currently open; a second close of the same window is ignored.
  const openDetailRef = useRef<DetailRequest | null>(null);
  // Sequence of focus requests, and the last one honoured.
  const focusSeqRef = useRef(0);
  const handledFocusRef = useRef(0);
  // The results table's heading row and the first row of the page shown.
  const headingRowRef = useRef<HTMLTableRowElement | null>(null);
  const firstRowRef = useRef<HTMLTableRowElement | null>(null);
  // Sequence of scroll requests, and the last one honoured.
  const scrollSeqRef = useRef(0);
  const handledScrollRef = useRef(0);

  /** DEM0003 "Key is not active now": every key or action the screen does not enable now. */
  function keyNotActive(): void {
    publish({ kind: 'alert', text: format('DEM0003') });
  }

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
   * Asks for a row's option field to be focused once rendered: the row of
   * `{ custId }`, or the first row of `{ page }` once that page is shown; with
   * `onlyIfLost`, only when focus has fallen to the page body by then.
   */
  function requestRowFocus(target: FocusTarget, onlyIfLost: boolean): void {
    focusSeqRef.current += 1;
    setFocusRequest({ seq: focusSeqRef.current, target, onlyIfLost });
  }

  /**
   * Asks for the page with the 0-based index `page` to be shown from its first
   * row once it is the page shown: its heading row and first row scrolled into
   * view, focus left where it is. Made only by a key that changes the page
   * shown.
   */
  function requestPageScroll(page: number): void {
    scrollSeqRef.current += 1;
    setScrollRequest({ seq: scrollSeqRef.current, page });
  }

  const list = useCustomerSearch({
    // Inquiry loads the first page with the criteria on screen at entry
    // (:252-256); Maintenance and Selection wait for the first Enter.
    initial: mode === 'inquiry' ? { name: initialName ?? '', city: '', state: '', includeInactive: false } : null,
    onNotice: (notice) => publish({ kind: 'status', text: notice.message }),
    onError: (error) => present(error, { setFieldErrors: showFilterErrors, focusField: focusFilter }),
  });

  function lastLoadedPage(): number {
    return Math.max(list.pages.length - 1, 0);
  }

  // The cursor on open: the Name filter (SC_NAME_PC in Inquiry, :745-748;
  // the first input field otherwise). This effect only moves focus; inside a
  // picker the Dialog's own initial focus lands on the same field.
  useEffect(() => {
    nameRef.current?.focus();
  }, []);

  // Honours a focus request once the row it names is rendered: rows are keyed
  // by customer id, so a page change replaces the inputs and the target only
  // exists after the render that shows its page. A page's first row is looked
  // up only while that very page is shown, so a render that still shows the
  // page being left (the page asked for still loading, or its rows not yet
  // rendered) leaves the request waiting. Each request is honoured once; one
  // whose page has no rows is dropped. Focus moves only to the field asked
  // for. An unconditional request for the field that already has focus (Enter
  // typed in it, or tapped on the key bar, which keeps focus there) scrolls
  // that field the least that brings it into view instead, within the scroll
  // padding that keeps it clear of the message band, because focus() on the
  // focused element scrolls nothing. A conditional request only moves focus,
  // and only when focus was lost.
  useEffect(() => {
    if (focusRequest === null || focusRequest.seq === handledFocusRef.current) {
      return;
    }
    const { target } = focusRequest;
    if ('page' in target && list.position !== target.page) {
      // The page asked for is not shown yet; the render that shows it runs this again.
      return;
    }
    const custId = 'custId' in target ? target.custId : list.page[0]?.custId;
    const input = custId === undefined ? undefined : optionInputs.current.get(custId);
    if (custId !== undefined && input === undefined) {
      // Its page is not on screen yet; the render that shows it runs this again.
      return;
    }
    handledFocusRef.current = focusRequest.seq;
    if (input === undefined) {
      return;
    }
    if (!focusRequest.onlyIfLost && input === document.activeElement) {
      input.scrollIntoView({ block: 'nearest', inline: 'nearest' });
    } else if (!focusRequest.onlyIfLost || isFocusLost()) {
      input.focus();
    }
  }, [focusRequest, list.page, list.position]);

  // Honours a scroll request once the page it names is shown, so its rows are
  // rendered: the page's first row, then the heading row, each scrolled the
  // least that brings it into view, so a list already showing both does not
  // move, and the heading and first row end up in view whether the scroll
  // position left them above or below the visible part of the list. Only the
  // block direction moves: `inline: 'nearest'` still aligns the start of a row
  // wider than the list's sideways-scrolling box with the box's edge, which
  // would scroll the box's focus-ring room out of view, so every sideways
  // position is put back. Declared after the focus effect, so a focus move,
  // which scrolls on its own, comes first. Each request is honoured once.
  // This effect never moves focus.
  useEffect(() => {
    if (scrollRequest === null || scrollRequest.seq === handledScrollRef.current) {
      return;
    }
    if (list.position !== scrollRequest.page) {
      // The page asked for is not shown yet; the render that shows it runs this again.
      return;
    }
    handledScrollRef.current = scrollRequest.seq;
    const firstRow = firstRowRef.current;
    const headingRow = headingRowRef.current;
    const sideways = sidewaysScrollPositions(headingRow ?? firstRow);
    firstRow?.scrollIntoView(ROW_INTO_VIEW);
    headingRow?.scrollIntoView(ROW_INTO_VIEW);
    for (const [box, left] of sideways) {
      box.scrollLeft = left;
    }
  }, [scrollRequest, list.position]);

  /** The SflClear half of the source: the typed options and their errors. */
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

  function openDetail(request: DetailRequest): void {
    openDetailRef.current = request;
    setDetail(request);
  }

  /**
   * Enter with nothing to process: the last page loaded so far, cursor on its
   * first record (PMTCUSTR :506-512). Focus follows to that row only when the
   * field that had it left with the page it was on; the page is shown from its
   * first row when it replaces another.
   */
  function showLastLoaded(): void {
    const destination = lastLoadedPage();
    const changesPage = list.position !== destination;
    list.toLastLoaded();
    requestRowFocus({ page: destination }, true);
    if (changesPage) {
      requestPageScroll(destination);
    }
  }

  /**
   * Ends an option walk: one DEM0004 per rejected option in list order,
   * published now so no interaction inside an earlier window cleared them;
   * the rejected inputs marked and described by the same texts; and the
   * cursor positioned, on the first rejected option when there is one (its
   * field always takes focus, DSPATR(PC)), else on the last option run (its
   * field takes focus only when the element a closed window returned focus to
   * has left with its page).
   */
  function finishWalk(walk: OptionWalk): void {
    // One catalog text per rejected option: alerted now, and kept as the
    // description of its input while that input stays marked.
    const errors = walk.rejected.map((entry) => [entry.custId, format('DEM0004', [entry.option])] as const);
    for (const [, text] of errors) {
      publish({ kind: 'alert', text });
    }
    setInvalid(Object.fromEntries(errors));
    const first = walk.rejected[0];
    if (first !== undefined) {
      list.showPageOf(first.custId);
      requestRowFocus({ custId: first.custId }, false);
      return;
    }
    if (walk.lastProcessed !== null) {
      list.showPageOf(walk.lastProcessed);
      requestRowFocus({ custId: walk.lastProcessed }, true);
      return;
    }
    showLastLoaded();
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
   * read every changed subfile record (ProcessOption, :427-515).
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
      showLastLoaded();
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

  /** Uppercased by the shared rule; SF_OPT has no CHECK(LC). */
  function changeOption(custId: string, value: string): void {
    setOptions((previous) => ({ ...previous, [custId]: upperField(value) }));
  }

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

  function exit(): void {
    onExit();
  }

  /**
   * F4: the State prompt from the State filter only, the field marked "+"
   * (SC_PMT_FLD = 'SC_STATE', :376); anywhere else DEM0005.
   */
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
   * `PmtState(SC_STATE)`, which returns the field unchanged on cancel, a
   * State that now differs from the one last applied clears the list so the
   * next Enter searches (:377-381). Focus returns to the State filter through
   * the picker's Dialog.
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

  /**
   * F5: blank criteria, inactive rows off, the list emptied with no request;
   * the next Enter searches (:389-394).
   */
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
   * stays; at the bottom the page stays, as it does when a newer key took
   * over while the page loaded. Focus that was on the page left behind
   * follows to the first row of the new page, once that page is shown, as
   * the 5250 cursor landed on the page's first record (SFLRCDNBR(CURSOR)),
   * and the new page is shown from its first row (SflFillPage, :570-585).
   */
  function pageDown(): void {
    if (!list.hasList) {
      keyNotActive();
      return;
    }
    const destination = list.position + 1;
    list.next().then(
      (outcome) => {
        if (outcome === 'none') {
          keyNotActive();
        } else if (outcome === 'moved' || outcome === 'loaded') {
          requestRowFocus({ page: destination }, true);
          requestPageScroll(destination);
        }
      },
      (error: unknown) => present(error),
    );
  }

  /**
   * PageUp: the previous loaded page, with no request (the loaded pages are
   * the cursor stack); DEM0003 with no list; at the first page it stays.
   * Focus follows, and the page is shown from its first row, as for PageDown.
   */
  function pageUp(): void {
    if (!list.hasList) {
      keyNotActive();
      return;
    }
    const destination = list.position - 1;
    if (list.previous()) {
      requestRowFocus({ page: destination }, true);
      requestPageScroll(destination);
    }
  }

  // ProcessFunctionKey (:355-420): one handler per action, shared by the key
  // binding and its legend button. F6 is bound in Maintenance only; elsewhere
  // it reaches onUnbound (DEM0003).
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

  // SFT_KEYS with BldFkeyText's texts in its order (:676-702), then the keys
  // a browser user cannot assume: Enter and the two paging keys.
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

  // The pending states the page shown waits on (see "List status" below): a
  // first page, or the next page of the deepest loaded page while that page
  // is the one shown.
  const searching = list.loading && !list.loadingNext;
  const loadingNextInView = list.loadingNext && list.position === lastLoadedPage();

  return (
    // tabIndex -1: the section is the key scope's container, so a click on
    // its plain text gives it focus and Enter stays a command there; it is
    // never a tab stop.
    <section ref={containerRef} className="search-panel" tabIndex={-1}>
      <ScreenHeader title="Customer Master" functionText={FUNCTION_TEXT[mode]} user={username ?? undefined} id={headerId} />
      {/*
        The screen's scrolling part, between the fixed header and footer, in
        the picker's window and on the framed page (global.css frames).
      */}
      <div className="screen-body">
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
          busy={searching || loadingNextInView}
          headingRowRef={headingRowRef}
          firstRowRef={firstRowRef}
        />
        {/*
          List status: the list's one polite live region (aria-atomic; not
          role="status", which is the toast host's), always present so every
          change of its whole text is announced, as screen labels and never as
          toasts. With a list, a visually hidden summary of the page shown, then
          the SFLEND(*MORE) indicator "More..." or "Bottom"; with no list,
          nothing (ERASE(SFL), PMTCUSTD :84-88), and DEM0002 and DEM0006 stay
          status toasts. A pending label, and the table's aria-busy, show only
          while its request can change the page shown: "Searching..." for a
          first page always; "Loading next page..." only while the deepest loaded
          page, whose next page is loading, is shown (a PageDown there joins that
          load). After a PageUp the load runs on unannounced, and paging back to
          the deepest page before it arrives shows the label and aria-busy again.
          The region keeps one line, empty or not, and a pending label comes
          first on it, before the indicator (.list-status), so nothing beneath
          moves as labels come and go.
        */}
        <div className="list-status" aria-live="polite" aria-atomic="true">
          {searching ? <p className="paging-indicator">Searching...</p> : null}
          {loadingNextInView ? <p className="paging-indicator">Loading next page...</p> : null}
          {list.hasList ? (
            <p className="paging-indicator">
              <span className="visually-hidden">{describePage(list.pages, list.position)}</span>{' '}
              {list.more ? 'More...' : 'Bottom'}
            </p>
          ) : null}
        </div>
      </div>
      {/* The SFT_FKEY footer constant, PMTCUSTD :128. */}
      <footer className="screen-footer">
        <p className="footer-brand">Demo Corp of America</p>
        <FunctionKeyBar keys={keys} />
      </footer>
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
