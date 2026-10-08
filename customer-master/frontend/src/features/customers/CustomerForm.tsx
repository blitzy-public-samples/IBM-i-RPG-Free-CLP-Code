/**
 * CustomerForm: the customer detail fields (MTNCUSTD record DETAILS).
 *
 * It replaces the DETAILS record of the MTNCUSTD window
 * (5250_Subfile/MTNCUSTD.DSPF:54-133), which MTNCUSTR fills in
 * FillScreenFields (5250_Subfile/MTNCUSTR.SQLRPGLE:340-363):
 *
 *   MTNCUSTD field (length)            Here (label, maxLength)
 *   'Customer Id'      SD_CUSTID  4    "Customer Id", 4, always protected
 *   'Active Status'    SD_ACTIVE  1    "Active (Y/N)", 1
 *   'Name'             SD_NAME   40    "Name", 40
 *   'Address'          SD_ADDR   40    "Address", 40
 *   'City'             SD_CITY   20    "City", 20
 *   'ST+'              SD_STATE   2    "State +", 2
 *   'ZIP'              SD_ZIP    10    "ZIP", 10
 *   'Account Manager'
 *      'Phone'         SD_ACCTPH 20    "Account Manager Phone", 20
 *      'Name'          SD_ACCTMGR 40   "Account Manager Name", 40
 *   'Corporate Phone'  SD_CORPPH 20    "Corporate Phone", 20
 *   'Last Change' SD_CHGTIME 'by' SD_CHGUSER, non-display unless
 *     indicator 61                     "Last Change … by …", or nothing
 *
 * Each input field's indicator-driven display attributes become props of
 * the shared FormField rather than indicators:
 *   - DSPATR(RI), reverse image on error, and DSPATR(PC), cursor on error:
 *     `errors[field]` marks the input `aria-invalid="true"` with its message
 *     linked through `aria-describedby`; the owning dialog moves focus to the
 *     first field in error through `inputRef` or `initialFocusField`.
 *   - DSPATR(PR) under indicator 10 (protect all, display mode): `readOnly`.
 *     The Customer Id is protected in every mode, as SD_CUSTID is an output
 *     field; it is blank in add mode until the server assigns an id.
 *
 * The USPS variant of the display file (USPS_Address/MTNCUSTD.DSPF) has the
 * same fields. Its CHECK(LC) on Name, Address, City and Account Manager Name
 * is not carried, so every field uppercases as typed like the 5250 variant,
 * and its zoned-numeric `SD_CUSTID 4 0` is not carried either: the id is the
 * 4-character base-36 string.
 *
 * Responsibilities stop at rendering and collecting input. The component
 * holds no field values, sends no request and applies no business rule: the
 * nine field rules, trimming and address standardization belong to the
 * server (`POST /api/customers/review`), and the shared length-preserving
 * uppercase rule is applied by FormField's `uppercase` prop alone. The
 * "Active (Y/N)" field is a one-character text input rather than a checkbox,
 * so any keyed value reaches the server and its DEM0501 rule stays reachable
 * from the keyboard.
 *
 * Layer rule: imports come from `api/` (types and the field-name list),
 * `components/` and this folder only.
 *
 * @example
 * <CustomerForm
 *   key={formKey}
 *   idPrefix="customer-detail"
 *   custId={record?.custId ?? ''}
 *   values={draft}
 *   onChange={(field, value) => setDraft((d) => ({ ...d, [field]: value }))}
 *   readOnly={mode === 'display'}
 *   errors={errorsByField}
 *   inputRef={(field) => (el) => { inputs.current[field] = el; }}
 *   initialFocusField="name"
 *   stamp={record ? { chgTime: record.chgTime, chgUser: record.chgUser } : null}
 * />
 */
import { useEffect, useRef, useState } from 'react';
// CUSTOMER_FIELD_NAMES is read only through `typeof`, by the compile-time
// coverage check of the field table, so a type-only import suffices.
import type { CUSTOMER_FIELD_NAMES, CustomerFields } from '../../api/customers';
import { FormField } from '../../components/FormField';
import { formatChangeStamp } from './formatChangeStamp';

// ---------------------------------------------------------------------------
// Field table
// ---------------------------------------------------------------------------

/** One of the nine customer data fields, by its JSON property name. */
export type CustomerFieldName = keyof CustomerFields;

/** One row of {@link CUSTOMER_FORM_FIELDS}: a data field, its label and its 5250 length. */
export interface CustomerFormFieldSpec {
  /** The JSON property name, also the suffix of the input id. */
  field: CustomerFieldName;
  /** Visible label; tests and the e2e specs locate the inputs by it. */
  label: string;
  /** The MTNCUSTD field length, which is also the column size on the server. */
  maxLength: number;
}

/**
 * The table shape the field list must satisfy: one entry per name of
 * `Names`, at the same position and with the same `field`. Mapped over the
 * `CUSTOMER_FIELD_NAMES` tuple, it makes the compiler reject a table that
 * misses, repeats, adds or reorders a field.
 */
type FieldTableFor<Names extends readonly CustomerFieldName[]> = {
  readonly [Index in keyof Names]: {
    readonly field: Names[Index];
    readonly label: string;
    readonly maxLength: number;
  };
};

/**
 * Whether two types are identical, not merely mutually assignable.
 * Evaluates to `true` or `false` at compile time.
 */
type IsExactly<A, B> =
  (<T>() => T extends A ? 1 : 2) extends <T>() => T extends B ? 1 : 2 ? true : false;

/**
 * The compile-time coverage check of {@link CUSTOMER_FORM_FIELDS}, in two
 * halves:
 * - the table matches `CUSTOMER_FIELD_NAMES` entry by entry
 *   ({@link FieldTableFor}), so no field is missing, repeated, extra or out
 *   of screen order;
 * - `CUSTOMER_FIELD_NAMES` names every key of the generated `CustomerFields`
 *   schema and nothing else. `customers.ts` checks only that each name is a
 *   schema key; this half also catches a field added to the OpenAPI schema
 *   but missing from the list. When it fails, the type collapses to `never`
 *   and `tsc -b` reports the table as not assignable to `never`.
 */
type ScreenOrderTable =
  IsExactly<CustomerFieldName, (typeof CUSTOMER_FIELD_NAMES)[number]> extends true
    ? FieldTableFor<typeof CUSTOMER_FIELD_NAMES>
    : never;

/**
 * The nine customer data fields in screen order, MTNCUSTD row by row: Active,
 * Name, Address, City, State, ZIP, Account Manager Phone, Account Manager
 * Name, Corporate Phone (5250_Subfile/MTNCUSTD.DSPF:61-125). It is the order
 * of `CUSTOMER_FIELD_NAMES`, in which the server runs and reports the field
 * rules, so the first field in error is also the first one on screen.
 *
 * The labels are the one definition shared by this form, the confirmation
 * panel and the conflict comparison; keep them verbatim, because tests and
 * the e2e specs find the inputs by label. "Name" and "Account Manager Name"
 * stay distinct, so an exact label query for "Name" resolves only the
 * customer name. "State +" keeps the 5250 "+" that marks the field F4 can
 * prompt.
 */
export const CUSTOMER_FORM_FIELDS: ReadonlyArray<CustomerFormFieldSpec> = [
  { field: 'active', label: 'Active (Y/N)', maxLength: 1 },
  { field: 'name', label: 'Name', maxLength: 40 },
  { field: 'addr', label: 'Address', maxLength: 40 },
  { field: 'city', label: 'City', maxLength: 20 },
  { field: 'state', label: 'State +', maxLength: 2 },
  { field: 'zip', label: 'ZIP', maxLength: 10 },
  { field: 'acctPhone', label: 'Account Manager Phone', maxLength: 20 },
  { field: 'acctMgr', label: 'Account Manager Name', maxLength: 40 },
  { field: 'corpPhone', label: 'Corporate Phone', maxLength: 20 },
] satisfies ScreenOrderTable;

/** The SD_CUSTID length: a customer id is four base-36 characters. */
const CUSTOMER_ID_LENGTH = 4;

/**
 * Change handler of the Customer Id output. The input is always read-only,
 * so the browser never fires a change for it; FormField still requires a
 * handler, and this one deliberately keeps the value as the caller set it.
 */
function keepCustomerId(_value: string): void {
  return undefined;
}


// ---------------------------------------------------------------------------
// Component
// ---------------------------------------------------------------------------

/**
 * The last-change stamp of a stored customer: the `chgTime` and `chgUser`
 * members of `CustomerResponse`. `chgTime` may be `null`, as the API
 * contract allows, which hides the stamp.
 */
export interface CustomerChangeStamp {
  /** ISO-8601 instant of the last change, or `null`. */
  chgTime: string | null;
  /** The user who made the last change; `*SYSTEM*` or blank hides the stamp. */
  chgUser: string;
}

/** Props of {@link CustomerForm}. */
export interface CustomerFormProps {
  /**
   * Prefix of every input id: the Customer Id is `${idPrefix}-custId` and
   * each data field `${idPrefix}-${field}`, so the form and a confirmation
   * panel mounted with another prefix never share an id.
   */
  idPrefix: string;
  /** The customer id shown protected; `''` in add mode until the server assigns one. */
  custId: string;
  /**
   * The nine field values to show. A `null` or absent member, which the
   * `CustomerFields` contract allows, renders as an empty field.
   */
  values: CustomerFields;
  /** Receives every change of one field with its new value, already uppercased by FormField. */
  onChange: (field: CustomerFieldName, value: string) => void;
  /** Protect every field (MTNCUSTD indicator 10, display mode). */
  readOnly: boolean;
  /**
   * Field error messages, already formatted (a problem's `errors[].message`).
   * A non-empty message marks that input `aria-invalid="true"` (reverse
   * image) and renders the message under it, linked by `aria-describedby`.
   */
  errors: Partial<Record<CustomerFieldName, string>>;
  /**
   * Ref factory: called once per field and render with the field name, it
   * returns the callback ref that receives that field's input (and `null`
   * when the input goes away), so the owner can move focus to a field in
   * error and tell whether focus sits on State before opening the picker.
   */
  inputRef?: (field: CustomerFieldName) => (el: HTMLInputElement | null) => void;
  /**
   * The field to focus once, right after the form mounts (DSPATR(PC)). The
   * owner remounts the form through `key` whenever it reloads, clears or
   * returns from the confirmation, so focus is re-applied each time; a later
   * change of this prop on a mounted form moves nothing.
   */
  initialFocusField?: CustomerFieldName;
  /**
   * The stored customer's change stamp, or `null`/absent (add mode, a row
   * no longer found). The stamp line renders only when `formatChangeStamp`
   * yields text, so it is hidden for `*SYSTEM*` or blank users, as
   * MTNCUSTD's indicator 61 hides it.
   */
  stamp?: CustomerChangeStamp | null;
}

/**
 * The detail fields. Renders the protected Customer Id, then the nine data
 * fields in screen order, then the "Last Change … by …" line when the stamp
 * is visible.
 */
export function CustomerForm({
  idPrefix,
  custId,
  values,
  onChange,
  readOnly,
  errors,
  inputRef,
  initialFocusField,
  stamp,
}: CustomerFormProps) {
  // The inputs of the mounted form, by field, for the focus-on-mount effect.
  // Written only by the callback refs below (during commit) and read only in
  // that effect, never during render.
  const inputs = useRef<Partial<Record<CustomerFieldName, HTMLInputElement>>>({});

  // The field to focus is the one given at mount; a useState initializer
  // captures it once, so the effect runs exactly once per mount.
  const [focusOnMount] = useState(initialFocusField);

  useEffect(() => {
    if (focusOnMount !== undefined) {
      inputs.current[focusOnMount]?.focus();
    }
  }, [focusOnMount]);

  /**
   * The callback ref of one field's input: records the element for the
   * focus-on-mount effect, then hands it to the owner's ref, if any.
   */
  function bindInput(field: CustomerFieldName) {
    const ownerRef = inputRef?.(field);
    return (el: HTMLInputElement | null): void => {
      if (el === null) {
        delete inputs.current[field];
      } else {
        inputs.current[field] = el;
      }
      ownerRef?.(el);
    };
  }

  const stampText = stamp ? formatChangeStamp(stamp.chgTime, stamp.chgUser) : null;

  return (
    <div className="customer-form">
      <FormField
        id={`${idPrefix}-custId`}
        label="Customer Id"
        value={custId}
        onChange={keepCustomerId}
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
          onChange={(value) => onChange(field, value)}
          maxLength={maxLength}
          size={maxLength}
          uppercase
          readOnly={readOnly}
          error={errors[field]}
          inputRef={bindInput(field)}
        />
      ))}
      {stampText !== null ? <p className="change-stamp">Last Change {stampText}</p> : null}
    </div>
  );
}
