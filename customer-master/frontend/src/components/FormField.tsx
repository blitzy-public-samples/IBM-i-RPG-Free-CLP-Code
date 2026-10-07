/**
 * FormField: the labelled text input used by every form.
 *
 * It replaces the 5250 input fields of the screens this application ports:
 * the nine MTNCUSTD customer fields plus the output-only Customer Id
 * (`SD_ACTIVE` … `SD_CORPPH`), the PMTCUSTD search filters (`SC_NAME 13A`,
 * `SC_CITY 13A`, `SC_STATE 2A`) and the PMTSTATED "Name Contains" filter
 * (`SC_NAME 10A`). Each MTNCUSTD field carries three indicator-driven display
 * attributes, and each becomes component state instead of an indicator:
 *
 *   - `DSPATR(RI)`, reverse image on error (which also turns `HI` off, since
 *     HI and RI together mean non-display): the `error` prop. A non-empty
 *     error sets `aria-invalid="true"`, which `src/styles/global.css` keys the
 *     reverse-image style on, and renders the message under the input,
 *     referenced by `aria-describedby`, so colour is never the only signal.
 *   - `DSPATR(PC)`, cursor position: focus, which the caller moves through
 *     `inputRef` (the detail form focuses its first field in error). This
 *     component never sets `autoFocus` and never focuses itself; `Dialog`
 *     and the callers own focus.
 *   - `DSPATR(PR)`, protect in display mode (indicator 10): the `readOnly`
 *     prop, styled by `.read-only`. A read-only input stays focusable, so
 *     screen-reader users can still reach and read the value.
 *
 * Uppercase as typed (the `uppercase` prop). No input field of those screens
 * declares `CHECK(LC)`, so the workstation uppercased every keyed character.
 * The prop applies the shared, length-preserving rule of `./upperField` on
 * every change and restores the caret at the typed offset. Because
 * `upperField` never changes the length, `maxLength` keeps counting the typed
 * characters and the value submitted is exactly the value shown; the server
 * (`TextNormalizer`) then changes it only by trimming. `value.toUpperCase()`
 * on the whole string is never used: it expands `ß` to `SS` and changes the
 * length. Without the prop the value passes through untouched, as passwords
 * and the case-sensitive sign-in user name require.
 *
 * The component holds no message text: `label` and `error` arrive already
 * formatted from the caller (labels such as "Name starts with:" or
 * "Active (Y/N)"; errors from a problem's `errors[].message`).
 *
 * Layer rule: components import nothing from `api/`, `errors/` or
 * `features/`. This file imports React types and `./upperField` only.
 *
 * @example
 * // Detail form field, protected in display mode, reverse image on error
 * <FormField id="detail-name" label="Name" value={values.name}
 *            onChange={(v) => onChange('name', v)} maxLength={40} size={40}
 *            uppercase readOnly={readOnly} error={errors.name}
 *            inputRef={inputRef?.('name')} />
 *
 * @example
 * // Sign-in password: no uppercasing, browser autofill hint
 * <FormField id="sign-in-password" label="Password" type="password"
 *            value={password} onChange={setPassword} maxLength={128}
 *            autoComplete="current-password" />
 */
import type { ChangeEvent, CompositionEvent, Ref } from 'react';
import { upperField } from './upperField';

/** Props of {@link FormField}. */
export type FormFieldProps = {
  /**
   * The input's id. The label's `htmlFor` points at it, and the error
   * message, when present, gets the id `${id}-error`. Callers derive it from
   * a `useId()` prefix so two screens mounted together never collide.
   */
  id: string;
  /** Visible label text, already formatted by the caller. */
  label: string;
  /** The controlled value. */
  value: string;
  /**
   * Receives the new value on every change: uppercased by the shared rule
   * when `uppercase` is set, otherwise exactly as typed.
   */
  onChange: (value: string) => void;
  /** The 5250 field length; the browser stops input beyond it. */
  maxLength: number;
  /**
   * Field error message (the RI/PC equivalent). Non-empty sets
   * `aria-invalid="true"` and `aria-describedby`, and renders the message;
   * absent or empty renders neither the message nor the two attributes.
   */
  error?: string;
  /** Apply the shared length-preserving uppercase rule as the user types. */
  uppercase?: boolean;
  /** Protect the value (DSPATR(PR)): display mode and the confirmation panel. */
  readOnly?: boolean;
  /** Input type; defaults to `text`. */
  type?: 'text' | 'password';
  /** Browser autofill hint, for example `username` or `current-password`. */
  autoComplete?: string;
  /**
   * Visible width in characters. The stylesheet sizes text inputs by this
   * attribute, so callers pass the field length (13 for search filters, 40
   * for Name, 1 for Active). Without it the browser default applies.
   */
  size?: number;
  /** Ref to the input, so the caller can move focus to it (DSPATR(PC)). */
  inputRef?: Ref<HTMLInputElement>;
  /** Extra classes for the wrapper; `form-field` is always present. */
  className?: string;
};

/**
 * Whether a change was fired while an input method editor is still composing
 * text. React's change event wraps the native `input` event, which carries
 * `isComposing`; events without it (such as a synthetic `change`) are not
 * composing.
 */
function isComposingEvent(event: Event): boolean {
  return (event as Partial<InputEvent>).isComposing === true;
}

/**
 * Applies the shared uppercase rule to the input's current value in place.
 *
 * The selection is read before the value is assigned, because assigning
 * `value` moves the caret to the end, and is then restored. `upperField`
 * never changes the length, so the restored offsets point at the same
 * characters the user typed around. The DOM is written only when the rule
 * changes something, which keeps the caret untouched on the common path.
 *
 * Called from event handlers only, never during render or from an effect.
 *
 * @returns The value now held by the input.
 */
function applyUppercase(el: HTMLInputElement): string {
  const typed = el.value;
  const upper = upperField(typed);
  if (upper === typed) {
    return typed;
  }
  const start = el.selectionStart;
  const end = el.selectionEnd;
  const direction = el.selectionDirection;
  el.value = upper;
  if (start !== null && end !== null) {
    el.setSelectionRange(start, end, direction ?? undefined);
  }
  return upper;
}

/**
 * The labelled input. Renders, in order, the label, the input and, only when
 * `error` is non-empty, the field message.
 */
export function FormField({
  id,
  label,
  value,
  onChange,
  maxLength,
  error,
  uppercase,
  readOnly,
  type,
  autoComplete,
  size,
  inputRef,
  className,
}: FormFieldProps) {
  const hasError = error !== undefined && error !== '';
  const errorId = `${id}-error`;

  function handleChange(event: ChangeEvent<HTMLInputElement>) {
    const el = event.currentTarget;
    // While an input method is composing, rewriting the value would cancel
    // or duplicate the composition, so the raw text is passed on and the
    // rule is applied once the composition ends (handleCompositionEnd).
    const next = uppercase && !isComposingEvent(event.nativeEvent) ? applyUppercase(el) : el.value;
    onChange(next);
  }

  function handleCompositionEnd(event: CompositionEvent<HTMLInputElement>) {
    const el = event.currentTarget;
    const composed = el.value;
    const next = applyUppercase(el);
    // The composing changes already reported the raw text; report again only
    // when the rule changed it.
    if (next !== composed) {
      onChange(next);
    }
  }

  return (
    <div className={className ? `form-field ${className}` : 'form-field'}>
      <label htmlFor={id} className="form-field__label">
        {label}
      </label>
      <input
        id={id}
        ref={inputRef}
        type={type ?? 'text'}
        className={readOnly ? 'form-field__input read-only' : 'form-field__input'}
        value={value}
        maxLength={maxLength}
        size={size}
        autoComplete={autoComplete}
        readOnly={readOnly}
        aria-invalid={hasError ? 'true' : undefined}
        aria-describedby={hasError ? errorId : undefined}
        onChange={handleChange}
        onCompositionEnd={uppercase ? handleCompositionEnd : undefined}
      />
      {hasError ? (
        <span id={errorId} className="form-field__message">
          {error}
        </span>
      ) : null}
    </div>
  );
}
