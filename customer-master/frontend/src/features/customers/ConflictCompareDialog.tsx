/**
 * ConflictCompareDialog: the DEM1002 "record changed" window.
 *
 * It replaces the stale-update branch of MTNCUSTR's UpdateRecd
 * (5250_Subfile/MTNCUSTR.SQLRPGLE:593-599). The source UPDATE is conditional
 * on the CHGTIME read earlier; when no row matches it sends DEM1002 to the
 * message subfile, re-reads the record (ReadRecd) and refills the screen with
 * it (FillScreenFields), so the user sees the current record and keys the
 * change again. Here the PUT is conditional on `version`, and a 409 DEM1002
 * response carries `current`, the customer as now stored. This window shows:
 *
 * - the DEM1002 text from the message catalog, "Someone else changed record.
 *   Review data." (CRTMSGF.CLLE:40 with its typo corrected), rendered in the
 *   window itself rather than as a toast: `useProblemPresenter` publishes no
 *   toast for DEM1002 when the detail dialog passes `onConflict`;
 * - a comparison table, one row per customer field in screen order: the
 *   user's values, the current record's values, and "Changed" on every row
 *   where the two differ, so the mark is text as well as colour;
 * - **Refresh**, the source outcome: the detail dialog loads `current` into
 *   the form and the user re-keys, exactly as FillScreenFields left it;
 * - **Re-apply my changes**: the fields the user edited are copied onto
 *   `current`, and the detail dialog runs the review again with `current`'s
 *   version, so the next save is conditional on the record just shown.
 *
 * Keys. The window registers its own key scope while open, on top of the
 * detail dialog's: F12, and Escape through it, runs Refresh, the outcome the
 * source gives a stale update. Enter is deliberately unbound, so Enter on a
 * focused button keeps its native click. Every other function key shows
 * DEM0003 "Key is not active now" and changes nothing.
 *
 * Responsibilities stop at the field-by-field comparison the UI needs: no
 * request is sent and no business rule is applied. The detail dialog owns the
 * record, the draft, the version and whether this window is open.
 *
 * Layer rule: imports come from `api/` (types), `components/`, `keyboard/`,
 * `messages/`, `auth/` and this folder only.
 *
 * @example
 * <ConflictCompareDialog
 *   open={conflict !== null}
 *   original={conflict.original}
 *   mine={conflict.mine}
 *   current={conflict.current}
 *   onRefresh={() => refreshFrom(conflict.current)}
 *   onReapply={(merged, version) => reviewAgain(merged, version)}
 * />
 */
import { useRef } from 'react';
import type { CustomerFields, CustomerResponse } from '../../api/customers';
import { useAuth } from '../../auth/AuthProvider';
import { Dialog } from '../../components/Dialog';
import { FunctionKeyBar } from '../../components/FunctionKeyBar';
import { ScreenHeader } from '../../components/ScreenHeader';
import { useToasts } from '../../components/ToastRegion';
import { useFunctionKeys } from '../../keyboard/useFunctionKeys';
import { useMessages } from '../../messages/MessageCatalogProvider';
import { CUSTOMER_FORM_FIELDS } from './CustomerForm';
import type { CustomerFieldName } from './CustomerForm';

// ---------------------------------------------------------------------------
// Public API
// ---------------------------------------------------------------------------

/** Props of {@link ConflictCompareDialog}. */
export interface ConflictCompareDialogProps {
  /** Whether the window is shown. When false nothing is rendered and no key scope exists. */
  open: boolean;
  /**
   * The nine fields of the record the user started editing from, the one
   * whose `version` the rejected PUT carried. A field the user edited is the
   * one whose value in `mine` differs from its value here.
   */
  original: CustomerFields;
  /** The values the user tried to save: the reviewed values the rejected PUT sent. */
  mine: CustomerFields;
  /** The customer as now stored: `problem.current` of the 409 DEM1002 response. */
  current: CustomerResponse;
  /**
   * Refresh: the detail dialog loads `current` into the form, discarding the
   * user's entries (the source behaviour). Called by the Refresh button, by
   * F12 and by Escape.
   */
  onRefresh: () => void;
  /**
   * Re-apply my changes: receives exactly the nine customer fields, `current`
   * with every user-edited field taken from `mine`, and `current.version`.
   * The detail dialog then runs the review again with them.
   */
  onReapply: (merged: CustomerFields, version: number) => void;
  /**
   * Accepted for symmetry with the pickers' props and never called: cancelling
   * a stale update has the source outcome, a refresh, so F12 and Escape run
   * `onRefresh`. The detail dialog does not pass it.
   */
  onCancel?: () => void;
}

/**
 * The DEM1002 compare window. Renders nothing while `open` is false;
 * otherwise mounts the window body, whose key scope therefore exists only
 * while the window is shown and is removed with it.
 */
export function ConflictCompareDialog(props: ConflictCompareDialogProps) {
  if (!props.open) {
    return null;
  }
  return <ConflictCompareBody {...props} />;
}

// ---------------------------------------------------------------------------
// Field comparison
// ---------------------------------------------------------------------------

/**
 * The text of one field as shown and compared. A `null` or absent member,
 * which the `CustomerFields` contract allows, reads as the empty string, so
 * an absent value never renders as "null" or "undefined" and never counts as
 * a difference from a stored blank.
 */
function fieldText(values: CustomerFields | CustomerResponse, field: CustomerFieldName): string {
  return values[field] ?? '';
}

/**
 * `current` with each field the user edited (its value in `mine` differs
 * from its value in `original`) replaced by the user's value. Only the nine
 * customer fields are copied, so `custId`, `chgTime`, `chgUser` and `version`
 * of `current` never reach the result: the API rejects any other property in
 * a write body.
 */
function mergeEdits(original: CustomerFields, mine: CustomerFields, current: CustomerResponse): CustomerFields {
  const merged: CustomerFields = {};
  for (const { field } of CUSTOMER_FORM_FIELDS) {
    const edited = fieldText(mine, field) !== fieldText(original, field);
    merged[field] = edited ? fieldText(mine, field) : fieldText(current, field);
  }
  return merged;
}

// ---------------------------------------------------------------------------
// Window body
// ---------------------------------------------------------------------------

/** Id prefix of the window's header; `Dialog` is named by its title and function line. */
const HEADER_ID = 'conflict-compare';

/** Id of the DEM1002 paragraph, which also describes the Refresh button. */
const MESSAGE_ID = 'conflict-compare-message';

/** The open window: header, DEM1002 text, comparison, actions and key legend. */
function ConflictCompareBody({ original, mine, current, onRefresh, onReapply }: ConflictCompareDialogProps) {
  const { username } = useAuth();
  const { format } = useMessages();
  const { publish } = useToasts();

  // Refresh takes focus when the window opens: it is the source outcome and
  // the safe default, since it sends nothing to the server.
  const refreshButtonRef = useRef<HTMLButtonElement>(null);
  const bodyRef = useRef<HTMLDivElement>(null);

  // F12 (and Escape, which the provider delivers as F12) refreshes, the
  // outcome UpdateRecd gives a stale update. Enter stays unbound so a focused
  // button keeps its native click; any other function key is not active here.
  useFunctionKeys(
    { F12: onRefresh },
    {
      onUnbound: () => publish({ kind: 'alert', text: format('DEM0003') }),
      containerRef: bodyRef,
    },
  );

  function reapply(): void {
    onReapply(mergeEdits(original, mine, current), current.version);
  }

  return (
    <Dialog
      open
      labelledBy={`${HEADER_ID}-title ${HEADER_ID}-function`}
      initialFocusRef={refreshButtonRef}
      className="dialog dialog--conflict"
    >
      <div ref={bodyRef}>
        <ScreenHeader id={HEADER_ID} functionText="Record Changed" user={username ?? undefined} />
        <p id={MESSAGE_ID} className="conflict-message">
          {format('DEM1002')}
        </p>
        <table className="results-table">
          <caption>Your changes compared with the current record</caption>
          <thead>
            <tr>
              <th scope="col">Field</th>
              <th scope="col">Your values</th>
              <th scope="col">Current record</th>
              <th scope="col">Differs</th>
            </tr>
          </thead>
          <tbody>
            {CUSTOMER_FORM_FIELDS.map(({ field, label }) => {
              const yours = fieldText(mine, field);
              const stored = fieldText(current, field);
              const differs = yours !== stored;
              return (
                <tr key={field} className={differs ? 'conflict-row--differs' : undefined}>
                  <th scope="row">{label}</th>
                  <td>{yours}</td>
                  <td>{stored}</td>
                  <td>{differs ? 'Changed' : ''}</td>
                </tr>
              );
            })}
          </tbody>
        </table>
        <div className="conflict-actions">
          {/* onRefresh is called with no arguments, as the F12 binding calls it, never with the click event. */}
          <button type="button" ref={refreshButtonRef} aria-describedby={MESSAGE_ID} onClick={() => onRefresh()}>
            Refresh
          </button>
          <button type="button" onClick={reapply}>
            Re-apply my changes
          </button>
        </div>
        <FunctionKeyBar keys={[{ key: 'F12', label: 'F12=Cancel', onPress: onRefresh }]} />
      </div>
    </Dialog>
  );
}
