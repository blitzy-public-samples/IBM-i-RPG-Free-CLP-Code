/**
 * StatePicker: the USA State prompt window. It replaces PMTSTATER and its
 * display file PMTSTATED, a "load all" subfile in a 16×40 window
 * (5250_Subfile/PMTSTATED.DSPF:39) that a State field's F4 called and that
 * returned the chosen 2-character code through its one parameter
 * (5250_Subfile/PMTSTATER.SQLRPGLE:52-54).
 *
 * - **Contract.** Option 1 or a row's Select button calls `onSelect(code)`
 *   once; F3, F12 and Escape call `onCancel()`, and the picker writes
 *   nothing. What follows either outcome is the host's.
 * - **Fresh on every open.** The window is mounted only while `open`, so its
 *   filter, sort, options, page and key scope start anew each time, as
 *   PMTSTATER's `Init` reset them on each call (:442-465). Nothing is reset
 *   from an effect.
 * - **Load all.** The list loads once on open, sorted by name (:171-177),
 *   and reloads only when Enter applies a changed filter or F7 changes the
 *   order. Rows are paged six at a time on the client, as the workstation
 *   paged the loaded subfile itself.
 * - **Keys.** Only the topmost key scope receives keys, so a picker opened
 *   over the detail dialog suspends the dialog's and the search page's keys
 *   until it closes.
 * - **Messages.** A failed request is shown through `useProblemPresenter`,
 *   and only while it is still the current request of an open window. The
 *   bundle holds no message text: the strings below are screen labels and
 *   key legends.
 *
 * Not carried: the header's SH_PGM, DATE and TIME (5250 chrome); its
 * function line stays blank.
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
  return open ? <StatePickerWindow onSelect={onSelect} onCancel={onCancel} /> : null;
}

/** SFLPAG(0006) (PMTSTATED:74-75); SFLPAGESIZE (PMTSTATER:102). */
const PAGE_SIZE = 6;

/** SC_NAME 10A (PMTSTATED:88); it has no CHECK(LC), so it is uppercased as typed. */
const FILTER_LENGTH = 10;

/** SF_OPT 1A (PMTSTATED:57). */
const OPTION_LENGTH = 1;

/** OPT1TEXT '1=Select' (PMTSTATER:124). */
const SELECT_OPTION = '1';

/** The title id `state-picker-title` names the window. */
const HEADER_ID = 'state-picker';

const FILTER_ID = 'state-picker-name';

/** `Init` sets SortbyName (PMTSTATER:443-448). */
const INITIAL_QUERY: StateQuery = { nameContains: '', sort: 'name' };

const NO_INVALID: Readonly<Record<string, string>> = Object.freeze({});

/** SC_SORTED: SortbyName / SortbyCode (PMTSTATER:121-122). */
const SORT_LABEL: Readonly<Record<StateSort, string>> = { name: 'Name', code: 'Code' };

/** F7Text names the order F7 switches to (PMTSTATER:262-276). */
const F7_LABEL: Readonly<Record<StateSort, string>> = { name: 'F7=By Code', code: 'F7=By Name' };

type RejectedOption = { index: number; code: string; option: string };

/**
 * What the list shows, decided in this order: a pending request; nothing
 * applied (F5 empties the list until the next Enter); a failure; no matching
 * state; rows.
 */
type ListStatus = 'pending' | 'cleared' | 'failed' | 'empty' | 'rows';

const STATUS_LABEL: Readonly<Record<Exclude<ListStatus, 'cleared' | 'rows'>, string>> = {
  pending: 'Loading...',
  failed: 'States not loaded.',
  empty: 'No states match.',
};

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

function pageStartOf(index: number): number {
  return Math.floor(index / PAGE_SIZE) * PAGE_SIZE;
}

/** SF_OPT = ' ' (PMTSTATER:306); a field never typed in is blank too. */
function isBlankOption(option: string | undefined): boolean {
  return option === undefined || option.trim() === '';
}

function StatePickerWindow({ onSelect, onCancel }: Omit<StatePickerProps, 'open'>) {
  const { username } = useAuth();
  const { format } = useMessages();
  const { publish } = useToasts();
  const { present } = useProblemPresenter();

  // `typed` is the filter as shown, sent as is: trimming, uppercasing and
  // matching are the server's. The filter last applied, PMTSTATER's
  // LastSearchCriteria, is `applied.nameContains` from useStates.
  const [typed, setTyped] = useState('');
  const [sort, setSort] = useState<StateSort>(INITIAL_QUERY.sort);
  // By state code. Survives paging, as SF_OPT stayed in its subfile record,
  // and is dropped on every reload (SflClear).
  const [options, setOptions] = useState<Record<string, string>>({});
  // Rejected codes, shown in reverse image: DSPATR(RI), indicator 81 (PMTSTATED:58),
  // each with the DEM0004 text it was rejected with.
  const [invalid, setInvalid] = useState<Readonly<Record<string, string>>>(NO_INVALID);
  // The first row in view, 0-based: SC_CSR_RCD less one.
  const [pageStart, setPageStart] = useState(0);
  const [filterError, setFilterError] = useState<string | undefined>(undefined);

  const filterRef = useRef<HTMLInputElement>(null);
  const containerRef = useRef<HTMLDivElement>(null);
  const tableBodyRef = useRef<HTMLTableSectionElement>(null);
  // By state code, so the first rejected option can take focus: DSPATR(PC),
  // indicator 82 (PMTSTATED:59).
  const optionInputs = useRef(new Map<string, HTMLInputElement>());
  const optionIdPrefix = useId();

  function optionIdOf(code: string): string {
    return `${optionIdPrefix}-opt-${code}`;
  }

  /** The filter is the only input sent, so it takes the first field error. */
  function showFilterErrors(errors: FieldError[]): void {
    setFilterError(errors[0]?.message);
  }

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

  /** PMTSTATER's SflClear. */
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

    // Invalid options: one DEM0004 per row in list order, every one marked
    // and described by its text, the page of the first shown and its input
    // focused (:312-329). The values stay in their inputs to be checked again
    // (SFLNXTCHG). flushSync renders the page before focus moves, so the
    // input exists to receive it.
    const errors = rejected.map((entry) => [entry.code, format('DEM0004', [entry.option])] as const);
    for (const [, text] of errors) {
      publish({ kind: 'alert', text });
    }
    flushSync(() => {
      setInvalid(Object.fromEntries(errors));
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

  function pageDown(): void {
    if (firstRow + PAGE_SIZE < rows.length) {
      showPage(firstRow + PAGE_SIZE);
    }
  }

  function pageUp(): void {
    showPage(Math.max(0, firstRow - PAGE_SIZE));
  }

  /** F3 and F12 (PMTSTATER:252-255). */
  function cancel(): void {
    onCancel();
  }

  /**
   * Function keys PMTSTATED does not enable; it enables only CF03, CF05, CF07
   * and CF12 (:68-71). PMTSTATER:281-282.
   */
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
      {/*
        tabIndex -1: the div is the key scope's container, so a click on its
        plain text gives it focus and Enter stays a command there; it is never
        a tab stop. The state-picker__body class keeps its focus ring off
        (global.css).
      */}
      <div ref={containerRef} tabIndex={-1} className="state-picker__body">
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
          autoComplete="off"
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
            {pageRows.map((row, slot) => {
              // Ids derive from the code, so they follow the row in view, not its slot.
              const optionId = optionIdOf(row.state);
              const error = invalid[row.state] ?? '';
              const errorId = `${optionId}-error`;
              return (
                <tr key={slot}>
                  <td>
                    <label htmlFor={optionId} className="visually-hidden">
                      {`Option for ${row.name}`}
                    </label>
                    <input
                      id={optionId}
                      type="text"
                      maxLength={OPTION_LENGTH}
                      size={OPTION_LENGTH}
                      inputMode="numeric"
                      autoComplete="off"
                      spellCheck={false}
                      value={options[row.state] ?? ''}
                      onChange={(event) => changeOption(row.state, event.target.value)}
                      aria-invalid={error !== '' ? 'true' : undefined}
                      aria-describedby={error !== '' ? errorId : undefined}
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
                    {/* The rejection, kept after its alert clears, so returning to the field explains it. */}
                    {error !== '' ? (
                      <span id={errorId} className="visually-hidden">
                        {error}
                      </span>
                    ) : null}
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
              );
            })}
          </tbody>
        </table>
        {/*
          The list status: always rendered, so assistive technology has
          registered the region before its first change; atomic, so each change
          is read whole. Not role="status", which is the toast host's. Its
          texts are screen labels, never toasts: a failure's problem is
          already the one alert.
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
