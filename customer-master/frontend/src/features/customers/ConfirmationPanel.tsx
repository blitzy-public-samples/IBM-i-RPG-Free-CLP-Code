/**
 * ConfirmationPanel: the confirm-before-update and confirm-before-add pass of
 * the customer detail window.
 *
 * It replaces the confirmation screen MTNCUSTR shows once the field rules
 * pass. After EditUpdData (edit) or EditAddData (add) succeeds, the program
 * protects every field, refills them from the program fields and writes the
 * window again with the confirmation message:
 *
 *   ProtectAll(); FillScreenFields(); SndSflMsg('DEM0000'); ScreenIO();
 *     (edit, 5250_Subfile/MTNCUSTR.SQLRPGLE:221-240)
 *   ProtectAll(); SndSflMsg('DEM0009'); FillScreenFields(); ScreenIO();
 *     (add, 5250_Subfile/MTNCUSTR.SQLRPGLE:273-292)
 *
 * FillScreenFields copies the values the rules accepted, so in the USPS
 * variant the window shows the address Edit_Address standardized
 * (USPS_Address/MTNCUSTR.SQLRPGLE:471-499): the street, city and state the
 * service returned and the ZIP built as `Zip5-Zip4`. The panel renders the
 * same thing from the `customer` of a passed `POST /api/customers/review`,
 * so the user confirms exactly the normalized and standardized values that
 * the commit will send.
 *
 * What maps where:
 *   - ProtectAll (MTNCUSTD indicator 10, DSPATR(PR)): every field is a
 *     read-only FormField. Read-only inputs stay focusable, so Tab and
 *     Shift+Tab move between them natively; the keyboard scope never
 *     intercepts those keys (keyboard scope contract), so moving focus raises
 *     no message and changes no state.
 *   - FillScreenFields: the protected Customer Id (blank in add mode, until
 *     the server assigns one), then the nine data fields in screen order,
 *     with the labels of `CUSTOMER_FORM_FIELDS`, the one label table shared
 *     with the editable form and the conflict comparison, then the stored
 *     record's "Last Change … by …" stamp (MTNCUSTR.SQLRPGLE:340-363). A
 *     review changes no stamp, so the panel takes the same `stamp` the form
 *     shows and renders it through the form's `ChangeStampLine`, with the
 *     same text and place. The edit confirmation of a record a user changed
 *     keeps the line; the add confirmation has no stored record (add clears
 *     CUSTMAST_ds, :251-254), so it shows none, and a `*SYSTEM*` or blank
 *     user hides it, as indicator 61 does.
 *   - SndSflMsg('DEM0000' | 'DEM0009'): not rendered here. The dialog
 *     publishes the review's `notice.message` as a `status` toast, which is
 *     the message-subfile equivalent; rendering it here as well would show
 *     the same text twice.
 *   - The standardized address: when the review ran the address service, a
 *     static "Address standardized." line tells the user the street, city,
 *     state and ZIP shown may differ from what was typed. No CUSTMSGF message
 *     exists for it, so it is a plain label rather than a catalog text, and
 *     the group's `aria-describedby` points at it for screen-reader users.
 *   - ScreenIO: the panel is presentational only. It sends no request and
 *     binds no key. `CustomerDetailDialog` owns the confirmation keys in its
 *     single key scope: Enter commits (PUT or POST), and F12, F5 and F4 act
 *     per mode as MTNCUSTR does at the confirmation.
 *
 * Focus. On mount the panel focuses its own container (`tabIndex={-1}`, so
 * it is focusable by script but not a Tab stop). The dialog passes the same
 * ref, or a ref to an enclosing container, as `useFunctionKeys`'
 * `containerRef`; Enter on that container, or on any of the read-only text
 * inputs inside it, is a command, so Enter commits wherever focus sits in the
 * panel. Focusing the panel also takes focus off the form input that pressed
 * Enter, which unmounts with the form.
 *
 * Layer rule: imports come from `api/` (types only), `components/` and this
 * folder only.
 *
 * @example
 * const confirmRef = useRef<HTMLDivElement>(null);
 * useFunctionKeys(bindings, { onUnbound, containerRef: confirmRef });
 * // ...
 * <ConfirmationPanel
 *   idPrefix="customer-confirm"
 *   custId={custId ?? ''}
 *   values={reviewed}
 *   standardized={standardized}
 *   containerRef={confirmRef}
 *   stamp={record !== null ? { chgTime: record.chgTime, chgUser: record.chgUser } : null}
 * />
 */
import { useEffect } from 'react';
import type { RefObject } from 'react';
import type { CustomerFields } from '../../api/customers';
import { FormField } from '../../components/FormField';
import { CUSTOMER_FORM_FIELDS, ChangeStampLine } from './CustomerForm';
import type { CustomerChangeStamp } from './CustomerForm';

/** The SD_CUSTID length: a customer id is four base-36 characters. */
const CUSTOMER_ID_LENGTH = 4;

/**
 * Change handler of every protected input on the panel. A read-only input
 * never fires a change, but FormField requires a handler; this one keeps the
 * reviewed values exactly as the server returned them.
 */
function keepReviewedValue(_value: string): void {
  return undefined;
}

/** Props of {@link ConfirmationPanel}. */
export interface ConfirmationPanelProps {
  /**
   * Prefix of every input id: the Customer Id is `${idPrefix}-custId` and
   * each data field `${idPrefix}-${field}`. It must differ from the editable
   * form's prefix (the dialog passes `customer-confirm`), so the two never
   * share an id.
   */
  idPrefix: string;
  /** The customer id shown protected; `''` in add mode, before the server assigns one. */
  custId: string;
  /**
   * The reviewed values to confirm: the `customer` of a passed review,
   * normalized and, when the address service ran, standardized. A `null` or
   * absent member, which the `CustomerFields` contract allows, renders as an
   * empty field.
   */
  values: CustomerFields;
  /** Whether the review standardized the address; shows the "Address standardized." line. */
  standardized: boolean;
  /**
   * Ref attached to the panel's container, which the panel focuses on mount.
   * The dialog passes the same ref (or one to an enclosing element) to
   * `useFunctionKeys` as `containerRef`, so Enter on the container commits.
   */
  containerRef: RefObject<HTMLDivElement | null>;
  /**
   * The stored customer's change stamp, the same one the editable form
   * shows, or `null`/absent (add mode, a row no longer found). The line
   * renders through `ChangeStampLine` only when `formatChangeStamp` yields
   * text, so it is hidden for `*SYSTEM*` or blank users, as MTNCUSTD's
   * indicator 61 hides it.
   */
  stamp?: CustomerChangeStamp | null;
}

/**
 * The protected confirmation fields. Renders, inside one labelled group, the
 * "Address standardized." line when it applies, the Customer Id, then the
 * nine reviewed data fields in screen order, all read-only, then the
 * "Last Change … by …" line when the stamp is visible.
 */
export function ConfirmationPanel({
  idPrefix,
  custId,
  values,
  standardized,
  containerRef,
  stamp,
}: ConfirmationPanelProps) {
  // Focus the panel once it is in the DOM, so Enter commits and Tab starts
  // from the panel. The effect only moves focus and sets no state; it runs
  // again only if the dialog hands over a different ref object.
  useEffect(() => {
    containerRef.current?.focus();
  }, [containerRef]);

  const standardizedId = `${idPrefix}-standardized`;

  return (
    <div
      ref={containerRef}
      tabIndex={-1}
      className="confirmation-panel"
      role="group"
      aria-label="Confirm customer"
      aria-describedby={standardized ? standardizedId : undefined}
    >
      {standardized ? (
        <p id={standardizedId} className="instructions">
          Address standardized.
        </p>
      ) : null}
      <FormField
        id={`${idPrefix}-custId`}
        label="Customer Id"
        value={custId}
        onChange={keepReviewedValue}
        maxLength={CUSTOMER_ID_LENGTH}
        size={CUSTOMER_ID_LENGTH}
        readOnly
      />
      {CUSTOMER_FORM_FIELDS.map(({ field, label, maxLength }) => (
        <FormField
          key={field}
          id={`${idPrefix}-${field}`}
          label={label}
          value={values[field] ?? ''}
          onChange={keepReviewedValue}
          maxLength={maxLength}
          size={maxLength}
          readOnly
        />
      ))}
      <ChangeStampLine stamp={stamp} />
    </div>
  );
}
