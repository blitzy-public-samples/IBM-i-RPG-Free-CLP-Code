/**
 * ResultsTable: one page of the customer search results (PMTCUSTD subfile).
 *
 * It replaces the PMTCUSTD `SFL` record and the column headings of its
 * `SFLCTL` record (5250_Subfile/PMTCUSTD.DSPF:57-72, :105-118):
 *
 *   PMTCUSTD                                  Here
 *   'Opt' DSPATR(HI) + SF_OPT 1A B            "Opt" column: a one-character text
 *     DSPATR(RI) on indicator 81                input, `aria-invalid="true"` (the
 *                                               reverse image of global.css) when
 *                                               the panel marks it invalid
 *   'Customer Name' + SF_NAME 40A             "Customer Name" column
 *   'City'          + SF_CITY 20A             "City" column
 *   'St'            + SF_STATE 2A             "St" column
 *   'ZIP'           + SF_ZIP 5A               "ZIP" column: `zip5`, the first five
 *                                               characters of the stored ZIP
 *   COLOR(RED) on indicator 83, set from      `row--inactive` on the row, plus a
 *     SF_ACT_H = 'N' in UpdSflRecd              visually hidden "Inactive" after
 *     (PMTCUSTR.SQLRPGLE:604-612)               the name, so colour is not the
 *                                               only signal
 *   SF_CUST_H 4D hidden                       `custId`, the row key and the id
 *                                               every callback receives
 *
 * The list arrives ordered `NAME, CITY, STATE` (PMTCUSTR.SQLRPGLE:220, with
 * the id as the server's final tiebreak), so those three headings carry
 * `aria-sort="ascending"` and the `is-sorted` highlight. The order is fixed:
 * the headings are not sort controls.
 *
 * Row actions. On the 5250 the user keys 1, 2 or 5 into a row's Opt field
 * and presses Enter. That path stays: the Opt input is a text input marked
 * `data-option-input`, which the keyboard scope's Enter rule treats as a
 * command target. A trailing "Actions" column adds one button per option the
 * current mode allows, so each action is also reachable without knowing the
 * option codes. A button only reports `onAction(custId, option)`; the owning
 * panel handles it exactly as that option typed into the row followed by
 * Enter.
 *
 * Responsibilities stop at rendering and collecting input. The component
 * holds no state, fetches nothing, shows no message text and validates no
 * option: which options are valid in which mode, DEM0004 for an invalid one,
 * focusing the first invalid option (the source's indicator 82,
 * `DSPATR(PC)`), and uppercasing what is typed all belong to the panel that
 * owns `options` and `invalid`. Each typed value is reported unchanged
 * through `onOptionChange`. Names, cities and codes are rendered as plain
 * React text, so `NIBH L'LOR COMPANY` and `URNA \NUNC\ COMPANY` show exactly
 * as stored.
 *
 * An empty `rows` renders the caption and the headings over an empty body;
 * whether to show the table at all is the panel's decision.
 *
 * @example
 * <ResultsTable
 *   rows={page.items}
 *   options={options}
 *   invalid={invalidOptions}
 *   allowedOptions={['2', '5']}
 *   onOptionChange={(custId, value) => setOptions((o) => ({ ...o, [custId]: value }))}
 *   onAction={(custId, option) => runOption(custId, option)}
 *   optionRef={(custId) => (el) => { optionInputs.current[custId] = el; }}
 * />
 */
import type { CustomerSummaryResponse } from '../../api/customers';

/**
 * A list option a row can take: `1` = Select (Selection mode), `2` = Edit
 * (Maintenance), `5` = Display (every mode).
 */
export type RowOption = '1' | '2' | '5';

/** Props of {@link ResultsTable}. `options` and `invalid` are keyed by `custId`. */
export interface ResultsTableProps {
  /** The customers of the current page, in the order the server returned them. */
  rows: CustomerSummaryResponse[];
  /** The text typed into each row's Opt input, keyed by `custId`; a missing key shows an empty field. */
  options: Record<string, string>;
  /**
   * Rows whose option the panel rejected (DEM0004), keyed by `custId`. A
   * `true` entry marks that row's Opt input `aria-invalid="true"`.
   */
  invalid: Record<string, boolean>;
  /**
   * The options the current mode offers, one action button each. Buttons are
   * always rendered in the order 1, 2, 5, whatever the order here.
   */
  allowedOptions: RowOption[];
  /** Receives every change of one row's Opt input with the value as typed. */
  onOptionChange: (custId: string, value: string) => void;
  /** Receives an action button press: the panel runs `option` for `custId` as if typed and entered. */
  onAction: (custId: string, option: RowOption) => void;
  /**
   * Optional ref factory for the Opt inputs, so the panel can focus the
   * first invalid option. Called once per row and render with its `custId`.
   */
  optionRef?: (custId: string) => (el: HTMLInputElement | null) => void;
  /** Accessible table caption; visually hidden, as the 5250 screen shows none. Defaults to "Customers". */
  caption?: string;
}

/** The order the action buttons appear in, which is the option-code order of the source legends. */
const OPTION_ORDER: readonly RowOption[] = ['1', '2', '5'];

/** The visible verb of each option's action button, from the PMTCUSTR option legends. */
const OPTION_VERBS: Readonly<Record<RowOption, string>> = {
  '1': 'Select',
  '2': 'Edit',
  '5': 'Display',
};

/** The default accessible caption of the table. */
const DEFAULT_CAPTION = 'Customers';

/** The stored value of an inactive customer (`ACTIVE = 'N'`, indicator 83). */
const INACTIVE = 'N';

/**
 * The search results table: a caption, the Opt, Customer Name, City, St and
 * ZIP headings plus a visually hidden "Actions" heading, and one row per
 * entry of `rows`.
 */
export function ResultsTable({
  rows,
  options,
  invalid,
  allowedOptions,
  onOptionChange,
  onAction,
  optionRef,
  caption = DEFAULT_CAPTION,
}: ResultsTableProps) {
  // Fixed order and no duplicates, however the panel lists its options.
  const actions = OPTION_ORDER.filter((option) => allowedOptions.includes(option));

  return (
    <table className="results-table">
      <caption className="visually-hidden">{caption}</caption>
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
          return (
            <tr key={custId} className={inactive ? 'row--inactive' : undefined}>
              <td>
                <input
                  type="text"
                  maxLength={1}
                  size={1}
                  inputMode="numeric"
                  autoComplete="off"
                  aria-label={`Option for ${name}`}
                  value={options[custId] ?? ''}
                  onChange={(event) => onOptionChange(custId, event.target.value)}
                  aria-invalid={invalid[custId] ? 'true' : undefined}
                  ref={optionRef?.(custId)}
                  data-option-input=""
                />
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
              <td>
                {name}
                {inactive ? (
                  <>
                    {' '}
                    <span className="visually-hidden">Inactive</span>
                  </>
                ) : null}
              </td>
              <td>{row.city ?? ''}</td>
              <td>{row.state ?? ''}</td>
              <td>{row.zip5 ?? ''}</td>
              <td>
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
}
