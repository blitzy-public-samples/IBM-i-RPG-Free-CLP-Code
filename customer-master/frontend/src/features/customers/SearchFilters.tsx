/**
 * SearchFilters: the customer search criteria (PMTCUSTD subfile control,
 * rows 4 and 5).
 *
 * It replaces the three input fields of the PMTCUSTD `SFLCTL` record and the
 * red include-inactive literals beside them:
 *
 *   PMTCUSTD (row 4 label, row 5 field)    Here
 *   'Name starts with:' + SC_NAME 13A      "Name starts with:", 13 characters
 *   'City Starts with:' + SC_CITY 13A      "City starts with:", 13 characters
 *   'State+'            + SC_STATE 2A      "State +", 2 characters
 *   'Including' / 'Inctives' COLOR(RED),   "Including Inactives" (source typo
 *     non-display unless indicator 03        corrected), rendered only while
 *                                            `includeInactive` is true
 *
 * The "+" after State marks the one field F4 can prompt (the State picker),
 * as the 5250 convention does. The caller decides what F4 does; this
 * component only exposes the State input through `stateRef` so the caller
 * can tell whether focus is on it and return focus to it after the picker.
 *
 * Uppercase as typed: no PMTCUSTD input field declares `CHECK(LC)`, so the
 * workstation uppercased every keyed character, and the search depends on it
 * because LIKE is case-sensitive. Each input therefore sets FormField's
 * `uppercase` prop, the one shared, length-preserving rule; this file never
 * uppercases a value itself.
 *
 * Responsibilities stop at rendering and collecting input. The component
 * holds no state, fetches nothing and applies no search rule: trimming, the
 * appended `%`, the DEM0007 state-length rule and the F9 toggle all belong to
 * the server or to the panel that owns the criteria. Field errors arrive
 * already formatted (for example DEM0007's text on `state`) and are shown by
 * FormField as the reverse-image highlight plus the message, linked through
 * `aria-describedby`.
 *
 * Ids are `${idPrefix}-name`, `${idPrefix}-city` and `${idPrefix}-state`.
 * The owning panel passes a `useId()` value as `idPrefix`, so the search page
 * and a Customer picker mounted over it never share an id.
 *
 * @example
 * const idPrefix = useId();
 * <SearchFilters
 *   idPrefix={idPrefix}
 *   values={criteria}
 *   onChange={(field, value) => setCriteria((c) => ({ ...c, [field]: value }))}
 *   includeInactive={includeInactive}
 *   errors={filterErrors}
 *   nameRef={nameInput}
 *   stateRef={stateInput}
 * />
 */
import type { Ref } from 'react';
import { FormField } from '../../components/FormField';

/** A search criterion the user types: the PMTCUSTD `SC_NAME`, `SC_CITY` and `SC_STATE` fields. */
export type FilterField = 'name' | 'city' | 'state';

/** Props of {@link SearchFilters}. */
export interface SearchFiltersProps {
  /** Unique id prefix from the owning panel (`useId()`); each input id is `${idPrefix}-<field>`. */
  idPrefix: string;
  /** The controlled values of the three inputs, as typed (already uppercased by FormField). */
  values: Record<FilterField, string>;
  /** Receives every change of one input with its new value. */
  onChange: (field: FilterField, value: string) => void;
  /** Whether inactive customers are included (F9); shows "Including Inactives" when true. */
  includeInactive: boolean;
  /**
   * Field error messages keyed by filter, for example DEM0007's text on
   * `state`. A present, non-empty message marks that input
   * `aria-invalid="true"` and renders the message under it.
   */
  errors: Partial<Record<FilterField, string>>;
  /** Ref to the Name input, so the panel can focus it (the source's indicator 79, `DSPATR(PC)`). */
  nameRef?: Ref<HTMLInputElement>;
  /** Ref to the State input: F4 prompts only from it, and focus returns to it after the picker. */
  stateRef?: Ref<HTMLInputElement>;
}

/**
 * The search criteria group. Renders, in source screen order, the Name, City
 * and State inputs and, only while inactive customers are included, the red
 * "Including Inactives" label.
 */
export function SearchFilters({
  idPrefix,
  values,
  onChange,
  includeInactive,
  errors,
  nameRef,
  stateRef,
}: SearchFiltersProps) {
  return (
    <div className="search-filters" role="group" aria-label="Search criteria">
      <FormField
        id={`${idPrefix}-name`}
        label="Name starts with:"
        value={values.name}
        onChange={(value) => onChange('name', value)}
        maxLength={13}
        size={13}
        uppercase
        error={errors.name}
        inputRef={nameRef}
      />
      <FormField
        id={`${idPrefix}-city`}
        label="City starts with:"
        value={values.city}
        onChange={(value) => onChange('city', value)}
        maxLength={13}
        size={13}
        uppercase
        error={errors.city}
      />
      <FormField
        id={`${idPrefix}-state`}
        label="State +"
        value={values.state}
        onChange={(value) => onChange('state', value)}
        maxLength={2}
        size={2}
        uppercase
        error={errors.state}
        inputRef={stateRef}
      />
      {includeInactive ? <span className="label--inactive-included">Including Inactives</span> : null}
    </div>
  );
}
