/**
 * CustomerPicker: the customer search in Selection mode, as a window that
 * returns one customer id to the screen that opened it.
 *
 * What it replaces. PMTCUSTR called with mode `S` and a return parameter
 * (5250_Subfile/PMTCUSTR.SQLRPGLE, 5250_Subfile/README.md "Selection could be
 * used for any in-house program that needed to prompt for a customer id"):
 *
 *   PMTCUSTR (mode `S`, two parameters)            Here
 *   Init: SH_FUNCT 'Selection', SC_OPTIONS          `CustomerSearchPanel mode="selection"`:
 *     '1=Select 5=Display' (:756-766)                 header "Selection", options 1 and 5
 *   `clear pCustID` on entry (:245-249)             nothing is returned until `onSelect`
 *   Option 1: pCustID = SF_CUST_H, CloseDownPgm,    `onSelect(custId)`, at most once per
 *     return (:439-445)                               opening; the host closes the window
 *   Option 5: CustDsp display window                the panel's display dialog, over this one
 *   F3 / F12: CloseDownPgm, return with pCustID     `onCancel()`; Escape is F12
 *     still cleared (:359-368)
 *
 * Contract (the picker contract of the plan):
 * - **Props.** `{ open, initialName?, onSelect(custId), onCancel() }`.
 * - **Options.** Only 1 = Select and 5 = Display are offered and accepted,
 *   whatever the signed-in user's role, because source `S` mode never sets
 *   Maint_OK. Any other option is DEM0004 on its row; F6 is not enabled and
 *   answers DEM0003. Both rules are the panel's (`ALLOWED_OPTIONS`,
 *   `classifyOption`), so Selection behaves exactly as the search screen does.
 * - **Select once.** The first option 1 (typed and Enter, or the row's Select
 *   button) calls `onSelect` with that row's id. The host is expected to set
 *   `open` to false in that call, as PMTCUSTR ended once it had moved the id;
 *   until it does, every later selection in the same opening is ignored, so
 *   a host never receives two ids for one prompt.
 * - **Cancel.** F3, F12 and Escape call `onCancel()` and return nothing.
 *   PMTCUSTR handed back a cleared pCustID; here no value is handed back at
 *   all, so the host's field keeps whatever it held. The host closes the
 *   window in that call too.
 * - **Initial name.** `initialName` presets the "Name starts with" filter;
 *   as in every mode except Inquiry, nothing is searched until the first
 *   Enter (NewSearchCriteria is on at entry, :243; only `I` loads at once,
 *   :252-256).
 * - **Not carried.** The "--> Bad Parm 1 <--" header of `S` without a return
 *   parameter (:756-759): a picker always has its return slot, `onSelect`.
 *
 * Fresh on every open. The window is mounted only while `open`, so the
 * criteria, the list, the typed options, the key scope and the select-once
 * guard all start anew each time it is shown, as PMTCUSTR ran `Init` on every
 * call (:706-767). Nothing is reset from an effect.
 *
 * Keyboard and focus.
 * - This component registers no key scope. The panel's own `useFunctionKeys`
 *   scope is the picker's scope; a second scope registered here would be
 *   pushed after the panel's (React runs child layout effects first) and sit
 *   topmost, swallowing every key the panel binds. The panel's scope is
 *   pushed when the picker opens, above the host's, so the host's keys are
 *   suspended until it closes; the display dialog and the State prompt the
 *   panel opens push theirs above it in turn.
 * - `Dialog` moves focus to the first editable field when the window opens,
 *   the "Name starts with" filter, traps Tab inside the window, keeps the
 *   host screen inert, and returns focus to the element that opened the
 *   picker (the host's field or button) when it closes.
 *
 * Layer rule: imports come only from `components/` and this folder, plus
 * React. Messages, problems and keys are handled inside the panel.
 *
 * Rendering requires, above it: `QueryClientProvider`, `AuthProvider`,
 * `MessageCatalogProvider`, `ToastProvider` and `KeyScopeProvider`, the
 * providers `CustomerSearchPanel` needs.
 *
 * @example
 * ```tsx
 * // A host form's "Customer id +" field, prompted with F4 or a button.
 * const [pickerOpen, setPickerOpen] = useState(false);
 * <CustomerPicker
 *   open={pickerOpen}
 *   onSelect={(custId) => { setCustomerId(custId); setPickerOpen(false); }}
 *   onCancel={() => setPickerOpen(false)}
 * />
 * ```
 */
import { useRef } from 'react';
import { Dialog } from '../../components/Dialog';
import { CustomerSearchPanel } from './CustomerSearchPage';

/**
 * Id prefix of the panel's ScreenHeader inside the picker. The header gives
 * its title `customer-picker-title` and its function line
 * `customer-picker-function`, which together name the window.
 */
const HEADER_ID = 'customer-picker';

/** The window's accessible name: "Customer Master" followed by "Selection". */
const LABELLED_BY = `${HEADER_ID}-title ${HEADER_ID}-function`;

/** Props of {@link CustomerPicker}. */
export interface CustomerPickerProps {
  /** Whether the picker is shown. Each change from false to true opens a fresh search. */
  open: boolean;
  /** The "Name starts with" filter's value when the picker opens; blank when absent. */
  initialName?: string;
  /**
   * Receives the id of the customer chosen with option 1, at most once per
   * opening (pCustID = SF_CUST_H, PMTCUSTR :439-445). The host closes the
   * picker here, by setting `open` to false.
   */
  onSelect: (custId: string) => void;
  /**
   * F3, F12 or Escape: the picker was left without a selection and returns
   * nothing (PMTCUSTR :359-368). The host closes the picker here.
   */
  onCancel: () => void;
}

/**
 * The Selection-mode customer picker. Renders nothing while closed; while
 * open, a modal window holding the customer search in Selection mode.
 */
export function CustomerPicker({ open, initialName, onSelect, onCancel }: CustomerPickerProps) {
  if (!open) {
    return null;
  }
  return <CustomerPickerWindow initialName={initialName} onSelect={onSelect} onCancel={onCancel} />;
}

/**
 * The open picker; mounted only while {@link CustomerPicker} is open, so its
 * state, the select-once guard included, belongs to one opening.
 */
function CustomerPickerWindow({ initialName, onSelect, onCancel }: Omit<CustomerPickerProps, 'open'>) {
  // Set by the first selection of this opening and never cleared: the window
  // unmounts when the host closes it, and the next opening mounts a new one.
  // Written and read only in the selection handler, never during render.
  const selectedRef = useRef(false);

  /**
   * Option 1's return slot: hands the first selected id to the host and
   * ignores any later one, which can only arrive if the host keeps the
   * picker open after its `onSelect` (a second Enter, a double click on
   * Select).
   */
  function selectOnce(custId: string): void {
    if (selectedRef.current) {
      return;
    }
    selectedRef.current = true;
    onSelect(custId);
  }

  return (
    <Dialog open labelledBy={LABELLED_BY} className="dialog dialog--picker">
      <CustomerSearchPanel
        mode="selection"
        headerId={HEADER_ID}
        initialName={initialName}
        onSelect={selectOnce}
        onExit={onCancel}
      />
    </Dialog>
  );
}
