/**
 * ConfirmationPanel: the confirmation pass MTNCUSTR runs once EditUpdData
 * (edit) or EditAddData (add) succeeds: ProtectAll, then FillScreenFields
 * with the accepted values (5250_Subfile/MTNCUSTR.SQLRPGLE:221-240,
 * :273-292). It renders the `customer` of a passed review, so the user
 * confirms exactly the normalized and standardized values the commit sends:
 * in the USPS variant, the street, city and state Edit_Address returned and
 * the ZIP as `Zip5-Zip4` (USPS_Address/MTNCUSTR.SQLRPGLE:471-499).
 *
 * Every field is read-only but focusable, so Tab and Shift+Tab move natively
 * and the keyboard scope never intercepts them. The data fields take their
 * order and labels from `CUSTOMER_FORM_FIELDS`, the one label table shared
 * with the editable form and the conflict comparison. The DEM0000/DEM0009
 * notice is not rendered here: the dialog publishes it as a status toast, so
 * it shows only once. The panel is presentational only: it sends no request
 * and binds no key; `CustomerDetailDialog` owns the confirmation keys.
 *
 * Layer rule: imports come from `api/` (types only), `components/` and this
 * folder only.
 */
import { useEffect } from 'react';
import type { RefObject } from 'react';
import type { CustomerFields } from '../../api/customers';
import { FormField } from '../../components/FormField';
import { CUSTOMER_FORM_FIELDS, ChangeStampLine } from './CustomerForm';
import type { CustomerChangeStamp } from './CustomerForm';

/** SD_CUSTID: four base-36 characters. */
const CUSTOMER_ID_LENGTH = 4;

/** FormField requires a change handler; the read-only inputs never fire it. */
function keepReviewedValue(_value: string): void {
  return undefined;
}

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
   * The reviewed `customer`. A `null` or absent member, which the
   * `CustomerFields` contract allows, renders as an empty field.
   */
  values: CustomerFields;
  /**
   * Whether the review standardized the address. It adds the "Address
   * standardized." line, which warns that the address shown may differ from
   * what was typed and which the group's `aria-describedby` points at; it is
   * a plain label because no CUSTMSGF message exists for it.
   */
  standardized: boolean;
  /**
   * Ref to the panel's `tabIndex={-1}` container (focusable by script, not a
   * Tab stop), which the panel focuses on mount. The dialog passes it, or a
   * ref to an enclosing element, to `useFunctionKeys` as `containerRef`, so
   * Enter on the container or any read-only input in it commits. Focusing
   * the panel also takes focus off the form input that pressed Enter, which
   * unmounts with the form.
   */
  containerRef: RefObject<HTMLDivElement | null>;
  /**
   * The stored customer's "Last Change … by …" stamp
   * (5250_Subfile/MTNCUSTR.SQLRPGLE:340-363). A review changes no stamp, so
   * this is the one the editable form shows, rendered through
   * `ChangeStampLine`. It is `null` or absent, so no line shows, in add mode,
   * which has no stored record (add clears CUSTMAST_ds, :251-254), and for a
   * row no longer found. The line is also hidden for a `*SYSTEM*` or blank
   * user, for whom `formatChangeStamp` yields no text, as MTNCUSTD's
   * indicator 61 hides it.
   */
  stamp?: CustomerChangeStamp | null;
}

/** The protected confirmation fields, inside one labelled group. */
export function ConfirmationPanel({
  idPrefix,
  custId,
  values,
  standardized,
  containerRef,
  stamp,
}: ConfirmationPanelProps) {
  // Focus the panel so Tab starts there. Moves focus only and sets no state;
  // re-runs only if the dialog hands over a different ref object.
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
