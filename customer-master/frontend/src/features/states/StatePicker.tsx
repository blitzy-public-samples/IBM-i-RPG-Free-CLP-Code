/**
 * StatePicker: the USA State prompt window.
 *
 * What it replaces. PMTSTATER and its display file PMTSTATED were a "load
 * all" subfile in a 16×40 window (5250_Subfile/PMTSTATED.DSPF:39) that any
 * screen called with F4 from a State field and that returned the chosen
 * 2-character code through its one parameter
 * (5250_Subfile/PMTSTATER.SQLRPGLE:52-54). This component is that window:
 *
 * - **Contract.** `{ open, onSelect(code), onCancel() }`. Option 1, or the
 *   row's Select button, calls `onSelect(code)` once; F3, F12 and Escape call
 *   `onCancel()`, and the picker writes nothing. What the host does with
 *   either outcome (the detail dialog's working-State rule on cancel, focus
 *   returning to its State field) is the host's; the picker knows none of it.
 * - **Fresh on every open.** `StatePicker` mounts the window only while
 *   `open`, so the filter, the sort, the typed options, the page and the key
 *   scope all start anew each time, as PMTSTATER's `Init` reset everything
 *   on each call (:442-465). Nothing is reset from an effect.
 * - **Load all.** The list is loaded once on open, sorted by name
 *   (:171-177), and reloaded only when Enter applies a changed filter or F7
 *   changes the order. Rows are paged six at a time on the client (SFLPAG 6,
 *   PMTSTATED:74-75), as the workstation paged the loaded subfile itself.
 * - **Keys** (PMTSTATED enables `CF03 CF05 CF07 CF12`, :68-71):
 *   - Enter (:196-207, :289-342): a changed filter searches; otherwise the
 *     typed options are processed; otherwise the last six-row page is shown
 *     and no request is sent.
 *   - F5 (:257-260) clears the filter and empties the list until the next
 *     Enter, which always searches.
 *   - F7 (:262-279) toggles the order between name and code and reloads with
 *     the filter last applied, which is also put back into the filter field.
 *   - PageUp / PageDown move six rows and stop at either end.
 *   - Any other function key shows DEM0003 and changes nothing (:281-282).
 *   Only the topmost key scope receives keys (keyboard scope contract), so a
 *   picker opened over the detail dialog suspends the dialog's and the
 *   search page's keys until it closes.
 * - **Messages.** DEM0003 and DEM0004 come from the message catalog; a
 *   failed request is shown by `useProblemPresenter`, which highlights and
 *   focuses the filter when the problem names a field. `useStates` reports a
 *   failure only while its request is still the current one and the window
 *   is still open, so a request that F5, a newer Enter or F7, or closing the
 *   window made obsolete shows nothing. The bundle holds no message text: the
 *   strings below are screen labels and key legends.
 * - **List status.** One polite, atomic live region under the list, always
 *   rendered while the window is open, carries the list's state, so each
 *   change is both seen and announced: "Loading..." while a request is
 *   pending (the table is also `aria-busy`); once rows are shown, the
 *   SFLEND(*MORE) "More..." or "Bottom" (PMTSTATED:82-86) with a visually
 *   hidden summary of the page in view, its row range, the row count and the
 *   order, which changes with every filter, PageUp, PageDown and F7;
 *   "No states match." when an applied filter matched nothing; and
 *   "States not loaded." after a failure, whose problem the presenter has
 *   already published as the one alert. After F5 the region is empty, as the
 *   source blanks the list until the next Enter. These are screen labels,
 *   never toasts. The region has no `status` role, which belongs to the
 *   shared toast host.
 * - **Input.** "Name Contains" (SC_NAME 10A, no CHECK(LC), PMTSTATED:87-88)
 *   and the option fields uppercase as typed by the shared length-preserving
 *   rule. The filter is sent exactly as typed: trimming, uppercasing and the
 *   `rpad(upper(name), 30) LIKE '%…%'` match are the server's. Each option
 *   field's id is a `useId()` prefix plus the row's state code, and a visually
 *   hidden `<label for>` in its cell names it "Option for <Name>".
 *
 * Not carried: SH_PGM, DATE and TIME of the header (5250 chrome), and the
 * function line, which PMTSTATER never assigns (SH_FUNCT stays blank).
 *
 * Layer rule: imports come only from `./useStates`, `api/` (types),
 * `errors/`, `components/`, `keyboard/`, `messages/` and `auth/`, plus React
 * and `react-dom`.
 *
 * Rendering requires, above it: `QueryClientProvider`, `AuthProvider`,
 * `MessageCatalogProvider`, `ToastProvider` and `KeyScopeProvider`.
 *
 * @example
 * ```tsx
 * <StatePicker
 *   open={pickerOpen}
 *   onSelect={(code) => { setState(code); setPickerOpen(false); }}
 *   onCancel={() => setPickerOpen(false)}
 * />
 * ```
 */
import { useId, useRef, useState } from 'react';
import { flushSync } from 'react-dom';
import type { FieldError } from '../../api/problem';
import type { StateSort } from '../../api/states';
import { useAuth } from '../../auth/AuthProvider';
import { Dialog } from '../../components/Dialog';
import { FormField } from '../../components/FormField';
import { FunctionKeyBar } from '../../components/FunctionKeyBar';
import type { FunctionKeyBarItem } from '../../components/FunctionKeyBar';
import { ScreenHeader } from '../../components/ScreenHeader';
import { useToasts } from '../../components/ToastRegion';
import { upperField } from '../../components/upperField';
import { useProblemPresenter } from '../../errors/useProblemPresenter';
import { useFunctionKeys } from '../../keyboard/useFunctionKeys';
import { useMessages } from '../../messages/MessageCatalogProvider';
import { useStates } from './useStates';
import type { StateQuery } from './useStates';

// ---------------------------------------------------------------------------
// Public API
// ---------------------------------------------------------------------------

/** Props of {@link StatePicker}. */
export interface StatePickerProps {
  /** Whether the window is shown. Each opening starts a fresh window. */
  open: boolean;
  /** Receives the chosen 2-character state code (option 1 or Select); called once per choice. */
  onSelect(code: string): void;
  /** Called for F3, F12 and Escape. The picker returns no code. */
  onCancel(): void;
}

/**
 * The USA State prompt window (PMTSTATER / PMTSTATED). Renders nothing while
 * closed; while open it renders a modal window that loads all states sorted
 * by name.
 */
export function StatePicker({ open, onSelect, onCancel }: StatePickerProps) {
  // The window is mounted only while open, so every opening starts with a new
  // filter, sort, option set, page and key scope, and no state is ever reset
  // from an effect.
  return open ? <StatePickerWindow onSelect={onSelect} onCancel={onCancel} /> : null;
}

// ---------------------------------------------------------------------------
// Constants and pure helpers
// ---------------------------------------------------------------------------

/** Rows per page: SFLPAG(0006) (PMTSTATED:74-75), PMTSTATER's SFLPAGESIZE (:102). */
const PAGE_SIZE = 6;

/** Length of the "Name Contains" field, SC_NAME 10A (PMTSTATED:88). */
const FILTER_LENGTH = 10;

/** Length of an option field, SF_OPT 1A (PMTSTATED:57). */
const OPTION_LENGTH = 1;

/** The option that returns the row's code, OPT1TEXT '1=Select' (PMTSTATER:124). */
const SELECT_OPTION = '1';

/** Id prefix of the header; the title `state-picker-title` names the window. */
const HEADER_ID = 'state-picker';

/** Id of the "Name Contains" input. */
const FILTER_ID = 'state-picker-name';

/** The query issued on open: all states, by name (`Init` sets SortbyName, PMTSTATER:443-448). */
const INITIAL_QUERY: StateQuery = { nameContains: '', sort: 'name' };

/** The empty invalid-option set; one shared instance keeps re-renders cheap. */
const NO_INVALID: ReadonlySet<string> = new Set<string>();

/** The "Sorted by:" value of each order (SortbyName / SortbyCode, PMTSTATER:121-122). */
const SORT_LABEL: Readonly<Record<StateSort, string>> = { name: 'Name', code: 'Code' };

/** The F7 legend of each order: it names the order F7 switches to (PMTSTATER:262-276). */
const F7_LABEL: Readonly<Record<StateSort, string>> = { name: 'F7=By Code', code: 'F7=By Name' };

/** One option the Enter walk rejected, with its row position and the value as typed. */
type RejectedOption = { index: number; code: string; option: string };

/**
 * What the list shows, decided in this order: a request is pending; nothing
 * is applied (F5 emptied the list until the next Enter); the request failed;
 * the applied filter matched no state; or rows.
 */
type ListStatus = 'pending' | 'cleared' | 'failed' | 'empty' | 'rows';

/** The visible status line of each list state that shows no rows; a cleared list shows none. */
const STATUS_LABEL: Readonly<Record<Exclude<ListStatus, 'cleared' | 'rows'>, string>> = {
  pending: 'Loading...',
  failed: 'States not loaded.',
  empty: 'No states match.',
};

/** The {@link ListStatus} of the list `useStates` reports. */
function listStatusOf(loading: boolean, error: unknown, applied: StateQuery | null, rowCount: number): ListStatus {
  if (loading) {
    return 'pending';
  }
  if (applied === null) {
    return 'cleared';
  }
  if (error !== null) {
    return 'failed';
  }
  return rowCount === 0 ? 'empty' : 'rows';
}

/**
 * The spoken summary of the page in view: its 1-based row range, the number
 * of rows and the order, as in "Showing 7 to 12 of 58 states, sorted by Name."
 */
function pageSummary(first: number, last: number, total: number, sort: StateSort): string {
  const range = first === last ? `${first}` : `${first} to ${last}`;
  const noun = total === 1 ? 'state' : 'states';
  return `Showing ${range} of ${total} ${noun}, sorted by ${SORT_LABEL[sort]}.`;
}

/**
 * The 0-based first row of the last six-row page: the 1-based
 * `%int((RcdsInSfl - 1) / SFLPAGESIZE) * SFLPAGESIZE + 1` of PMTSTATER
 * (:336-341) less one, so row 19 of 20. An empty list starts at 0.
 */
function lastPageStart(rowCount: number): number {
  return rowCount === 0 ? 0 : Math.floor((rowCount - 1) / PAGE_SIZE) * PAGE_SIZE;
}

/** The 0-based first row of the page holding row `index`. */
function pageStartOf(index: number): number {
  return Math.floor(index / PAGE_SIZE) * PAGE_SIZE;
}

/** Whether an option field is blank: never typed, emptied, or a blank (SF_OPT = ' ', :306). */
function isBlankOption(option: string | undefined): boolean {
  return option === undefined || option.trim() === '';
}

// ---------------------------------------------------------------------------
// The open window
// ---------------------------------------------------------------------------

/** The open window; mounted only while {@link StatePicker} is open. */
function StatePickerWindow({ onSelect, onCancel }: Omit<StatePickerProps, 'open'>) {
  const { username } = useAuth();
  const { format } = useMessages();
  const { publish } = useToasts();
  const { present } = useProblemPresenter();

  // Screen state. `typed` is the filter as shown; the filter last applied is
  // `applied.nameContains` from useStates (PMTSTATER's LastSearchCriteria).
  const [typed, setTyped] = useState('');
  const [sort, setSort] = useState<StateSort>(INITIAL_QUERY.sort);
  // Option text per state code. It survives paging, as SF_OPT stayed in each
  // subfile record, and is dropped whenever the list is reloaded (SflClear).
  const [options, setOptions] = useState<Record<string, string>>({});
  // Codes whose option the last Enter rejected: the DSPATR(RI) of indicator
  // 81 (PMTSTATED:57-59), kept until the next Enter re-checks them.
  const [invalid, setInvalid] = useState<ReadonlySet<string>>(NO_INVALID);
  // 0-based index of the first row on the page (SC_CSR_RCD less one).
  const [pageStart, setPageStart] = useState(0);
  // The message of a failed request that named the filter.
  const [filterError, setFilterError] = useState<string | undefined>(undefined);

  const filterRef = useRef<HTMLInputElement>(null);
  const containerRef = useRef<HTMLDivElement>(null);
  const tableBodyRef = useRef<HTMLTableSectionElement>(null);
  // The rendered option inputs by state code, filled by callback refs, so the
  // first rejected option can receive focus (DSPATR(PC), indicator 82).
  const optionInputs = useRef(new Map<string, HTMLInputElement>());
  // Option input ids are `${optionIdPrefix}-opt-${code}`, unique per row and per window.
  const optionIdPrefix = useId();

  /** The id of the option input of the row holding state `code`. */
  function optionIdOf(code: string): string {
    return `${optionIdPrefix}-opt-${code}`;
  }

  /** Shows the first field error of a failed request on the filter, the only input sent. */
  function showFilterErrors(errors: FieldError[]): void {
    setFilterError(errors[0]?.message);
  }

  /** Moves focus to the filter input; called by the presenter after a failed request. */
  function focusFilter(): void {
    filterRef.current?.focus();
  }

  const { rows, loading, error, applied, search, clear } = useStates({
    initial: INITIAL_QUERY,
    onError: (failure) => present(failure, { setFieldErrors: showFilterErrors, focusField: focusFilter }),
  });

  // The page actually shown. `pageStart` is reset with every reload, so it is
  // normally within the list already; the bound keeps a page in view however
  // the list and the page were changed.
  const firstRow = Math.min(pageStart, lastPageStart(rows.length));
  const pageRows = rows.slice(firstRow, firstRow + PAGE_SIZE);
  const hasMore = firstRow + PAGE_SIZE < rows.length;
  const listStatus = listStatusOf(loading, error, applied, rows.length);

  /*
   * Focus recovery. Rows are keyed by their slot on the page, so paging keeps
   * the same six inputs and buttons and focus stays in its slot, as the 5250
   * cursor kept its screen position on a roll. Only a control that is about
   * to disappear loses focus; these helpers move focus first, from the
   * handler, so it never falls to <body>, where Enter would no longer reach
   * this window. Focus that stays valid is never moved.
   */

  /** The page slot (0-5) of the row holding focus, or -1 when focus is not in a row. */
  function focusedSlot(): number {
    const active = document.activeElement;
    const body = tableBodyRef.current;
    if (active === null || body === null) {
      return -1;
    }
    return Array.from(body.rows).findIndex((row) => row.contains(active));
  }

  /** Before a reload empties the list: focus in a row moves to the filter. */
  function keepFocusOnReload(): void {
    if (focusedSlot() !== -1) {
      filterRef.current?.focus();
    }
  }

  /**
   * Shows the page starting at row `start`. When the focused slot does not
   * exist on that page (a shorter last page), focus moves to the option field
   * of the page's last row, whose slot exists on both pages.
   */
  function showPage(start: number): void {
    const rowsOnPage = Math.min(PAGE_SIZE, rows.length - start);
    if (focusedSlot() >= rowsOnPage) {
      const lastSlot = pageRows[rowsOnPage - 1];
      const target = lastSlot === undefined ? undefined : optionInputs.current.get(lastSlot.state);
      (target ?? filterRef.current)?.focus();
    }
    setPageStart(start);
  }

  /** Forgets the typed options and their errors and shows the first page (SflClear). */
  function resetList(): void {
    keepFocusOnReload();
    setPageStart(0);
    setOptions({});
    setInvalid(NO_INVALID);
  }

  /**
   * Enter (PMTSTATER:196-207, ProcessOption :289-342). New search criteria
   * take precedence over options.
   */
  function enter(): void {
    // 1. New search: nothing applied since F5 (NewSearchCriteria), or a filter
    //    other than the one applied. The CHAR comparison of the source ignores
    //    trailing blanks, so only those are disregarded here.
    if (applied === null || typed.trimEnd() !== applied.nameContains.trimEnd()) {
      search(typed, sort);
      resetList();
      setFilterError(undefined);
      return;
    }

    // 2. Options, walked over every loaded row in list order, not only the
    //    page in view, as READC read every changed subfile record.
    const rejected: RejectedOption[] = [];
    for (const [index, row] of rows.entries()) {
      const option = options[row.state];
      if (option === SELECT_OPTION) {
        // The code is returned at once. Messages collected for earlier rows
        // are not shown: the source returned before displaying them (:299-304).
        onSelect(row.state);
        return;
      }
      if (option !== undefined && !isBlankOption(option)) {
        rejected.push({ index, code: row.state, option });
      }
    }

    // 3. No invalid option: position on the last page and send nothing. Blank
    //    options clear an earlier rejection (:306-310).
    const first = rejected[0];
    if (first === undefined) {
      setInvalid(NO_INVALID);
      showPage(lastPageStart(rows.length));
      return;
    }

    // Invalid options: one DEM0004 per row in list order, every one marked,
    // the page of the first shown and its input focused (:312-329). The
    // values stay in their inputs to be checked again (SFLNXTCHG). flushSync
    // renders the page before focus moves, so the input exists to receive it.
    for (const entry of rejected) {
      publish({ kind: 'alert', text: format('DEM0004', [entry.option]) });
    }
    flushSync(() => {
      setInvalid(new Set(rejected.map((entry) => entry.code)));
      setPageStart(pageStartOf(first.index));
    });
    optionInputs.current.get(first.code)?.focus();
  }

  /**
   * F7 (PMTSTATER:262-279): toggles the order and reloads with the filter
   * last applied, which goes back into the filter field (the source restores
   * LastSearchCriteria to the screen before reloading, :185). The filter
   * error is dropped because the reload decides it anew.
   */
  function toggleSort(): void {
    const nextSort: StateSort = sort === 'name' ? 'code' : 'name';
    const filter = applied?.nameContains ?? '';
    setSort(nextSort);
    setTyped(filter);
    setFilterError(undefined);
    resetList();
    search(filter, nextSort);
  }

  /**
   * F5 (PMTSTATER:257-260): clears the filter and empties the list; the next
   * Enter always searches, because nothing is applied any more.
   */
  function refresh(): void {
    setTyped('');
    setFilterError(undefined);
    resetList();
    clear();
  }

  /** PageDown: the next six rows; stays on the last page. No request. */
  function pageDown(): void {
    if (firstRow + PAGE_SIZE < rows.length) {
      showPage(firstRow + PAGE_SIZE);
    }
  }

  /** PageUp: the previous six rows; stays on the first page. No request. */
  function pageUp(): void {
    showPage(Math.max(0, firstRow - PAGE_SIZE));
  }

  /** F3, F12 and Escape (PMTSTATER:252-255): close without a code. */
  function cancel(): void {
    onCancel();
  }

  /** Any function key PMTSTATED does not enable (PMTSTATER:281-282). */
  function keyNotActive(): void {
    publish({ kind: 'alert', text: format('DEM0003') });
  }

  /** Stores an option as typed, uppercased by the shared rule (no CHECK(LC) on SF_OPT). */
  function changeOption(code: string, value: string): void {
    setOptions((previous) => ({ ...previous, [code]: upperField(value) }));
  }

  // One handler per action, shared by the key binding and its legend button.
  // Escape reaches the F12 binding through the keyboard scope contract.
  useFunctionKeys(
    {
      Enter: enter,
      F3: cancel,
      F5: refresh,
      F7: toggleSort,
      F12: cancel,
      PageUp: pageUp,
      PageDown: pageDown,
    },
    { onUnbound: keyNotActive, containerRef },
  );

  // SFT_KEYS in BldFkeyText order (PMTSTATER:423-429), then the keys a
  // browser user cannot assume: Enter and the two paging keys.
  const keys: FunctionKeyBarItem[] = [
    { key: 'F3', label: 'F3=Exit', onPress: cancel },
    { key: 'F5', label: 'F5=Refresh', onPress: refresh },
    { key: 'F7', label: F7_LABEL[sort], onPress: toggleSort },
    { key: 'F12', label: 'F12=Cancel', onPress: cancel },
    { key: 'Enter', label: 'Enter', onPress: enter },
    { key: 'PageUp', label: 'Page Up', onPress: pageUp },
    { key: 'PageDown', label: 'Page Down', onPress: pageDown },
  ];

  return (
    <Dialog open labelledBy={`${HEADER_ID}-title`} initialFocusRef={filterRef} className="state-picker">
      <div ref={containerRef}>
        {/* SH_FUNCT is never assigned by PMTSTATER, so the function line stays blank. */}
        <ScreenHeader id={HEADER_ID} title="USA States" functionText="" user={username ?? undefined} />
        <FormField
          id={FILTER_ID}
          label="Name Contains"
          value={typed}
          onChange={setTyped}
          maxLength={FILTER_LENGTH}
          size={FILTER_LENGTH}
          uppercase
          inputRef={filterRef}
          error={filterError}
        />
        {/* SC_OPTIONS: a picker always has its return slot, onSelect, so 1=Select always applies. */}
        <p className="instructions">1=Select</p>
        <p>{`Sorted by: ${SORT_LABEL[sort]}`}</p>
        <table className="results-table" aria-busy={loading}>
          <caption className="visually-hidden">USA states</caption>
          <thead>
            <tr>
              <th scope="col">Opt</th>
              <th
                scope="col"
                aria-sort={sort === 'code' ? 'ascending' : undefined}
                className={sort === 'code' ? 'is-sorted' : undefined}
              >
                Code
              </th>
              <th
                scope="col"
                aria-sort={sort === 'name' ? 'ascending' : undefined}
                className={sort === 'name' ? 'is-sorted' : undefined}
              >
                Name
              </th>
              <th scope="col">
                <span className="visually-hidden">Actions</span>
              </th>
            </tr>
          </thead>
          <tbody ref={tableBodyRef}>
            {/* Keyed by page slot, so paging keeps the controls and the focus within them. */}
            {pageRows.map((row, slot) => (
              <tr key={slot}>
                <td>
                  {/* Every input has a <label for>; this hidden one names the option field "Option for <Name>". */}
                  <label htmlFor={optionIdOf(row.state)} className="visually-hidden">
                    {`Option for ${row.name}`}
                  </label>
                  <input
                    id={optionIdOf(row.state)}
                    type="text"
                    maxLength={OPTION_LENGTH}
                    size={OPTION_LENGTH}
                    inputMode="numeric"
                    autoComplete="off"
                    value={options[row.state] ?? ''}
                    onChange={(event) => changeOption(row.state, event.target.value)}
                    aria-invalid={invalid.has(row.state) ? 'true' : undefined}
                    ref={(element) => {
                      if (element === null) {
                        return undefined;
                      }
                      const inputs = optionInputs.current;
                      inputs.set(row.state, element);
                      return () => {
                        if (inputs.get(row.state) === element) {
                          inputs.delete(row.state);
                        }
                      };
                    }}
                    data-option-input=""
                  />
                </td>
                <td>{row.state}</td>
                <td>{row.name}</td>
                <td>
                  {/*
                    The hidden name completes the accessible name "Select <Name>";
                    the separating space stays outside the span, because accessible
                    name computation trims an element's own text.
                  */}
                  <button type="button" onClick={() => onSelect(row.state)}>
                    Select{' '}
                    <span className="visually-hidden">{row.name}</span>
                  </button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
        {/*
          The list status: always rendered, so assistive technology has
          registered the region before its first change; atomic, so each change
          is read whole. Not role="status", which is the toast host's.
        */}
        <div aria-live="polite" aria-atomic="true">
          {listStatus === 'rows' ? (
            <>
              <p className="visually-hidden">
                {pageSummary(firstRow + 1, firstRow + pageRows.length, rows.length, sort)}
              </p>
              {/* SFLEND(*MORE), shown only while the subfile holds records (PMTSTATED:82-86). */}
              <p className="paging-indicator">{hasMore ? 'More...' : 'Bottom'}</p>
            </>
          ) : null}
          {listStatus === 'pending' || listStatus === 'failed' || listStatus === 'empty' ? (
            <p className="paging-indicator">{STATUS_LABEL[listStatus]}</p>
          ) : null}
        </div>
        <p className="footer-brand">Demo Corp of America</p>
        <FunctionKeyBar keys={keys} />
      </div>
    </Dialog>
  );
}
