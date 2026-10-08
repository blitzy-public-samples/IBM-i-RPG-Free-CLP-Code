/**
 * CustomerPicker: the customer search (`CustomerSearchPanel`) in Selection
 * mode, in a modal window that returns one customer id to the screen that
 * opened it. It replaces PMTCUSTR called with mode `S` and a return slot
 * (5250_Subfile/PMTCUSTR.SQLRPGLE; option 1 :439-445, F3/F12 :359-368).
 *
 * - Only options 1 = Select and 5 = Display are offered, whatever the user's
 *   role, because source `S` mode never sets Maint_OK; the panel answers any
 *   other option with DEM0004 and F6 with DEM0003.
 * - The host owns closing: it sets `open` to false in `onSelect`, as PMTCUSTR
 *   ended once it had moved the id, and in `onCancel`. Until it does, later
 *   selections in that opening are ignored, so a host never receives two ids.
 * - F3, F12 and Escape call `onCancel()` and hand back nothing (PMTCUSTR
 *   returned a cleared pCustID), so the host's field keeps its value.
 * - This component registers no key scope. The panel's `useFunctionKeys`
 *   scope is the picker's; a second one registered here would be pushed after
 *   the panel's (child layout effects run first) and sit topmost, swallowing
 *   every key the panel binds.
 * - The window is mounted only while `open`, so the criteria, list, options,
 *   key scope and select-once guard start fresh each opening, as PMTCUSTR ran
 *   `Init` on every call. `Dialog` focuses the "Name starts with" filter on
 *   open and returns focus to the invoking element on close.
 */
import { useRef } from 'react';
import { Dialog } from '../../components/Dialog';
import { CustomerSearchPanel } from './CustomerSearchPage';

/** Id prefix of the panel's header, whose title and function line name the window. */
const HEADER_ID = 'customer-picker';
const LABELLED_BY = `${HEADER_ID}-title ${HEADER_ID}-function`;

export interface CustomerPickerProps {
  /** Whether the picker is shown. Each change from false to true opens a fresh search. */
  open: boolean;
  /**
   * The "Name starts with" filter's value when the picker opens; blank when
   * absent. Nothing is searched until the first Enter, as in source `S` mode.
   */
  initialName?: string;
  /**
   * Receives the id of the customer chosen with option 1, at most once per
   * opening. The host closes the picker here, by setting `open` to false.
   */
  onSelect: (custId: string) => void;
  /**
   * F3, F12 or Escape: the picker was left without a selection and returns
   * nothing. The host closes the picker here.
   */
  onCancel: () => void;
}

/** Renders nothing while closed; while open, the Selection-mode search window. */
export function CustomerPicker({ open, initialName, onSelect, onCancel }: CustomerPickerProps) {
  if (!open) {
    return null;
  }
  return <CustomerPickerWindow initialName={initialName} onSelect={onSelect} onCancel={onCancel} />;
}

function CustomerPickerWindow({ initialName, onSelect, onCancel }: Omit<CustomerPickerProps, 'open'>) {
  // Set by the first selection; read and written only in `selectOnce`, never during render.
  const selectedRef = useRef(false);

  /**
   * Hands the first selected id to the host and ignores later ones, which arrive only
   * if the host keeps the picker open (a second Enter, a double click on Select).
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
