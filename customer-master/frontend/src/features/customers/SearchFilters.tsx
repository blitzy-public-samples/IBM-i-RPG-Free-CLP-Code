/**
 * SearchFilters: the customer search criteria, replacing the input fields of
 * the PMTCUSTD `SFLCTL` record, rows 4 and 5 [5250_Subfile/PMTCUSTD.DSPF:89-101].
 * The "Including Inactives" label corrects the source typo "Inctives".
 *
 * The "+" after State marks the one field F4 can prompt, as the 5250
 * convention does; the caller decides what F4 does.
 *
 * Uppercase as typed: no PMTCUSTD field declares `CHECK(LC)` and LIKE is
 * case-sensitive, so each input sets FormField's `uppercase` prop, the one
 * shared length-preserving rule; this file never uppercases a value itself.
 *
 * It holds no state, fetches nothing and applies no search rule: trimming,
 * the appended `%`, DEM0007 and F9 belong to the server or the owning panel,
 * and field errors arrive already formatted.
 */
import type { Ref } from 'react';
import { FormField } from '../../components/FormField';

/** A search criterion the user types: the PMTCUSTD `SC_NAME`, `SC_CITY` and `SC_STATE` fields. */
export type FilterField = 'name' | 'city' | 'state';

export interface SearchFiltersProps {
  /**
   * The owning panel's `useId()` value. Input ids are `${idPrefix}-name`,
   * `${idPrefix}-city` and `${idPrefix}-state`, so the search page and a
   * Customer picker mounted over it never share an id.
   */
  idPrefix: string;
  /** The controlled values, as typed and therefore already uppercase. */
  values: Record<FilterField, string>;
  onChange: (field: FilterField, value: string) => void;
  /**
   * Whether inactive customers are included (F9, indicator 03); the red
   * "Including Inactives" label shows only while it is true.
   */
  includeInactive: boolean;
  /**
   * Field messages keyed by filter, for example DEM0007's text on `state`. A
   * non-empty message marks its input `aria-invalid="true"` and renders under
   * it, linked by `aria-describedby`.
   */
  errors: Partial<Record<FilterField, string>>;
  /** Ref to the Name input, so the panel can focus it (the source's indicator 79, `DSPATR(PC)`). */
  nameRef?: Ref<HTMLInputElement>;
  /**
   * Ref to the State input, so the caller can tell whether focus is on it
   * when F4 is pressed and return focus to it after the picker.
   */
  stateRef?: Ref<HTMLInputElement>;
}

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
