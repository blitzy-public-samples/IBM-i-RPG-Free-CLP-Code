/**
 * ResultsTable: one page of the customer search results. It replaces the
 * PMTCUSTD `SFL` record and the column headings of its `SFLCTL` record
 * (5250_Subfile/PMTCUSTD.DSPF:57-72, :105-118).
 *
 * The list arrives ordered `NAME, CITY, STATE` (PMTCUSTR.SQLRPGLE:220, with
 * the id as the server's final tiebreak), so those three headings carry
 * `aria-sort="ascending"` and the `is-sorted` highlight. The order is fixed:
 * the headings are not sort controls. ZIP shows `zip5`, the first five
 * characters of the stored ZIP (`SF_ZIP 5A`). An inactive row (indicator 83,
 * set from `SF_ACT_H = 'N'`, PMTCUSTR.SQLRPGLE:604-612) gets `row--inactive`
 * plus a visually hidden "Inactive" after the name, so colour is not the only
 * signal.
 *
 * Opt is a one-character text input whose id is a `useId()` prefix plus the
 * row's `custId`, named "Option for <name>" by a visually hidden
 * `<label for>`. While the panel marks it invalid, it carries
 * `aria-invalid="true"` (indicator 81's reverse image) and `aria-describedby`
 * naming a visually hidden `<input id>-error` that holds the panel's DEM0004
 * text, which outlasts the alert. It is marked `data-option-input`, so the
 * keyboard scope's Enter rule treats it as a command target. The action
 * buttons make each option reachable without knowing its code: a button only
 * reports `onAction`, and the panel handles it exactly as that option typed
 * into the row followed by Enter.
 *
 * The component is stateless and validates nothing. The panel owns which
 * options each mode allows, DEM0004, focusing the first invalid option
 * through `optionRef` (indicator 82, `DSPATR(PC)`) and uppercasing; each
 * typed value is reported unchanged through `onOptionChange`. Names, cities
 * and codes render as plain React text, so `NIBH L'LOR COMPANY` and
 * `URNA \NUNC\ COMPANY` show exactly as stored.
 *
 * Columns keep the PMTCUSTD field positions: the `<colgroup>` sizes Opt,
 * City, St, ZIP and the action buttons, Name takes the rest of the row, and
 * the rows never resize a column (`.results-table--fixed` in
 * `src/styles/global.css`). St, ZIP and the buttons stay on one line. Where
 * the columns do not fit, the table scrolls sideways in a `ScrollRegion`
 * named by its caption.
 *
 * That box (`.customer-list`) gives the rows their compact height. On the
 * search page it also reserves the heading row and the twelve rows of a full
 * page (`SFLPAG(0012)`, PMTCUSTD.DSPF:59), so an empty table, a short last
 * page and a first page still loading take a full page's height, and the
 * "More..." / "Bottom" line, the footer and the keys beneath stay where they
 * are when rows arrive. Where the page is not framed (a narrow or short
 * viewport), the Name column is at least `SF_NAME 40A` wide and each name
 * (`cell--name`) stays on one line, as PMTCUSTD shows it at row 9, so no row
 * and not the "Customer Name" heading wraps and the reservation holds any
 * page; the table scrolls sideways there. On the framed page a
 * long name wraps in a narrow Name column, which only moves the "More..." /
 * "Bottom" line down the scrolling list, as the footer and keys stay at the
 * viewport bottom. The Customer picker's window reserves nothing and sizes to
 * its rows, so a short list and its "More..." / "Bottom" line show together
 * without scrolling.
 */
import { useId } from 'react';
import type { CSSProperties } from 'react';
import type { CustomerSummaryResponse } from '../../api/customers';
import { ScrollRegion } from '../../components/ScrollRegion';

/**
 * A list option a row can take: `1` = Select (Selection mode), `2` = Edit
 * (Maintenance), `5` = Display (every mode).
 */
export type RowOption = '1' | '2' | '5';

/** Props of {@link ResultsTable}. `options` and `invalid` are keyed by `custId`. */
export interface ResultsTableProps {
  /**
   * The customers of the current page, rendered in the order given; an empty
   * array renders the headings over an empty body.
   */
  rows: CustomerSummaryResponse[];
  /** The text typed into each row's Opt input; a missing key shows an empty field. */
  options: Record<string, string>;
  /** The DEM0004 text of each rejected row; a non-empty entry marks that row's Opt input invalid. */
  invalid: Record<string, string>;
  /**
   * The options the current mode offers, one action button each. Buttons are
   * always rendered in the order 1, 2, 5, whatever the order here.
   */
  allowedOptions: RowOption[];
  /** Receives every change of one row's Opt input. */
  onOptionChange: (custId: string, value: string) => void;
  /** Receives each action button press. */
  onAction: (custId: string, option: RowOption) => void;
  /** Optional ref factory for the Opt inputs, called once per row and render with its `custId`. */
  optionRef?: (custId: string) => (el: HTMLInputElement | null) => void;
  /** Accessible table caption; visually hidden, as the 5250 screen shows none. Defaults to "Customers". */
  caption?: string;
  /**
   * The panel is loading rows for the table (a first page or the next one):
   * `aria-busy="true"` on the table until they arrive. Defaults to false.
   */
  busy?: boolean;
}

/** The option-code order of the source legends. */
const OPTION_ORDER: readonly RowOption[] = ['1', '2', '5'];

/** From the PMTCUSTR option legends. */
const OPTION_VERBS: Readonly<Record<RowOption, string>> = {
  '1': 'Select',
  '2': 'Edit',
  '5': 'Display',
};

/**
 * The width, in rem, the Actions column allows for each button's label in the
 * proportional button font, a little more than the widest common system
 * font sets it in. global.css adds each button's padding and border.
 */
const OPTION_LABEL_ALLOWANCE_REM: Readonly<Record<RowOption, number>> = {
  '1': 3.25,
  '2': 2.25,
  '5': 3.75,
};

/**
 * The Actions column inputs of `.results-table--fixed`: how many buttons each
 * row holds and their label allowances together, so the column fits this
 * mode's buttons whatever rows the page holds.
 */
function actionsColumnStyle(actions: readonly RowOption[]): CSSProperties {
  const label = actions.reduce((sum, option) => sum + OPTION_LABEL_ALLOWANCE_REM[option], 0);
  return {
    '--actions-count': String(actions.length),
    '--actions-label': `${label}rem`,
  } as CSSProperties;
}

const DEFAULT_CAPTION = 'Customers';

const INACTIVE = 'N';

export function ResultsTable({
  rows,
  options,
  invalid,
  allowedOptions,
  onOptionChange,
  onAction,
  optionRef,
  caption = DEFAULT_CAPTION,
  busy = false,
}: ResultsTableProps) {
  // Opt input ids are `${idPrefix}-opt-${custId}`: unique per row, and per
  // table, so a Customer picker's table over the search page shares no id.
  const idPrefix = useId();
  const captionId = `${idPrefix}-caption`;
  const actions = OPTION_ORDER.filter((option) => allowedOptions.includes(option));

  const table = (
    <table
      className="results-table results-table--fixed results-table--customers"
      style={actionsColumnStyle(actions)}
      aria-busy={busy ? 'true' : undefined}
    >
      <caption id={captionId} className="visually-hidden">
        {caption}
      </caption>
      {/* Opt 1A, Name 40A (the rest of the row), City 20A, St 2A, ZIP 5A, then the action buttons. */}
      <colgroup>
        <col className="col--opt" />
        <col />
        <col className="col--city" />
        <col className="col--st" />
        <col className="col--zip" />
        <col className="col--actions" />
      </colgroup>
      <thead>
        <tr>
          <th scope="col">Opt</th>
          <th scope="col" aria-sort="ascending" className="is-sorted">
            Customer Name
          </th>
          <th scope="col" aria-sort="ascending" className="is-sorted">
            City
          </th>
          <th scope="col" aria-sort="ascending" className="is-sorted">
            St
          </th>
          <th scope="col">ZIP</th>
          <th scope="col">
            <span className="visually-hidden">Actions</span>
          </th>
        </tr>
      </thead>
      <tbody>
        {rows.map((row) => {
          const { custId } = row;
          // Typed as strings; the fallbacks keep a malformed row from ever
          // showing "undefined" in a label or a cell.
          const name = row.name ?? '';
          const inactive = row.active === INACTIVE;
          const optionId = `${idPrefix}-opt-${custId}`;
          const error = invalid[custId] ?? '';
          const errorId = `${optionId}-error`;
          return (
            <tr key={custId} className={inactive ? 'row--inactive' : undefined}>
              <td>
                <label htmlFor={optionId} className="visually-hidden">
                  {`Option for ${name}`}
                </label>
                <input
                  id={optionId}
                  type="text"
                  maxLength={1}
                  size={1}
                  inputMode="numeric"
                  autoComplete="off"
                  spellCheck={false}
                  value={options[custId] ?? ''}
                  onChange={(event) => onOptionChange(custId, event.target.value)}
                  aria-invalid={error !== '' ? 'true' : undefined}
                  aria-describedby={error !== '' ? errorId : undefined}
                  ref={optionRef?.(custId)}
                  data-option-input=""
                />
                {/* The rejection, kept after its alert clears, so returning to the field explains it. */}
                {error !== '' ? (
                  <span id={errorId} className="visually-hidden">
                    {error}
                  </span>
                ) : null}
              </td>
              {/*
                The separating space of each hidden suffix is its own text node
                outside the hidden span, not leading text inside it: accessible
                name computation trims an element's own text, so a space inside
                the span would fuse "Display" and the name into "DisplayNIBH…".
                Outside, it yields "Display NIBH L'LOR COMPANY" and
                "… COMPANY Inactive", and as trailing line white space it
                collapses, so nothing visible changes.
              */}
              <td className="cell--name">
                {name}
                {inactive ? (
                  <>
                    {' '}
                    <span className="visually-hidden">Inactive</span>
                  </>
                ) : null}
              </td>
              <td>{row.city ?? ''}</td>
              <td className="cell--nowrap">{row.state ?? ''}</td>
              <td className="cell--nowrap">{row.zip5 ?? ''}</td>
              <td className="cell--actions">
                {actions.map((option) => (
                  <button key={option} type="button" onClick={() => onAction(custId, option)}>
                    {OPTION_VERBS[option]}{' '}
                    <span className="visually-hidden">{name}</span>
                  </button>
                ))}
              </td>
            </tr>
          );
        })}
      </tbody>
    </table>
  );

  return (
    <ScrollRegion labelledBy={captionId} className="customer-list">
      {table}
    </ScrollRegion>
  );
}
