/**
 * ConflictCompareDialog: the DEM1002 "record changed" window. It replaces the
 * stale-update branch of MTNCUSTR's UpdateRecd
 * (5250_Subfile/MTNCUSTR.SQLRPGLE:593-599), which sends DEM1002 to the
 * message subfile, re-reads the record (ReadRecd) and redisplays it
 * (FillScreenFields). Here a 409 DEM1002 response carries `current`.
 *
 * The window shows the catalog DEM1002 text itself (CRTMSGF.CLLE:40, typo
 * corrected), because `useProblemPresenter` publishes no DEM1002 toast when
 * the detail dialog passes `onConflict`. The comparison has one row per
 * customer field, with "Changed" as text so the mark is not colour alone. No
 * request is sent here: the detail dialog owns the record, the draft and the
 * version.
 *
 * Keys: F12, and Escape through it, runs Refresh, the source outcome. Enter is
 * unbound, so a focused button keeps its native click. Other function keys
 * show DEM0003.
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

/** Props of {@link ConflictCompareDialog}. */
export interface ConflictCompareDialogProps {
  /** Whether the window is shown. When false nothing is rendered. */
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
  /** Refresh: the detail dialog loads `current` into the form, discarding the user's entries. */
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
 * Mounts the window body only while `open`, so its key scope exists only
 * while the window is shown.
 */
export function ConflictCompareDialog(props: ConflictCompareDialogProps) {
  if (!props.open) {
    return null;
  }
  return <ConflictCompareBody {...props} />;
}

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
 * `current` with each field the user edited replaced by the user's value.
 * Only the nine customer fields are copied, so `custId`, `chgTime`, `chgUser`
 * and `version` of `current` never reach the result: the API rejects any
 * other property in a write body.
 */
function mergeEdits(original: CustomerFields, mine: CustomerFields, current: CustomerResponse): CustomerFields {
  const merged: CustomerFields = {};
  for (const { field } of CUSTOMER_FORM_FIELDS) {
    const edited = fieldText(mine, field) !== fieldText(original, field);
    merged[field] = edited ? fieldText(mine, field) : fieldText(current, field);
  }
  return merged;
}

const HEADER_ID = 'conflict-compare';

/** Id of the DEM1002 paragraph, which also describes the Refresh button. */
const MESSAGE_ID = 'conflict-compare-message';

function ConflictCompareBody({ original, mine, current, onRefresh, onReapply }: ConflictCompareDialogProps) {
  const { username } = useAuth();
  const { format } = useMessages();
  const { publish } = useToasts();

  // Refresh takes focus when the window opens: it is the source outcome and
  // the safe default, since it sends nothing to the server.
  const refreshButtonRef = useRef<HTMLButtonElement>(null);
  const bodyRef = useRef<HTMLDivElement>(null);

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
        {/* The window's scrolling part, between the fixed header and footer. */}
        <div className="screen-body">
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
        </div>
        {/*
          The actions sit in the fixed footer with the key legend, so focusing
          Refresh on open never scrolls the DEM1002 text out of view, and both
          actions stay visible however far the comparison scrolls.
        */}
        <footer className="screen-footer">
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
        </footer>
      </div>
    </Dialog>
  );
}
