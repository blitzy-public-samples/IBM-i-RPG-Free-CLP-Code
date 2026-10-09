/**
 * FormField: the labelled text input used by every form.
 *
 * - **Error.** A non-empty `error` sets `aria-invalid="true"`, which the
 *   stylesheet keys the reverse-image style on, and renders the message under
 *   the input, referenced by `aria-describedby`, so colour is never the only
 *   signal. `label` and `error` arrive already formatted; the component holds
 *   no message text.
 * - **Focus.** The caller moves focus through `inputRef`; this component never
 *   sets `autoFocus` and never focuses itself. A `readOnly` input stays
 *   focusable, so screen-reader users can still reach and read the value.
 * - **Uppercase as typed.** With `uppercase`, the shared, length-preserving
 *   rule of `./upperField` is applied on every change, or once an input
 *   method's composition ends, and the caret is restored at the typed offset.
 *   Because the length never changes, `maxLength` keeps counting the typed
 *   characters and the value submitted is exactly the value shown.
 *   `value.toUpperCase()` on the whole string is never used: it expands `ß` to
 *   `SS` and changes the length. Without the prop the value passes through
 *   untouched, as passwords and the sign-in user name require: the name is
 *   sent exactly as typed, the server matches it in any case and reports its
 *   configured spelling.
 */
import type { ChangeEvent, CompositionEvent, Ref } from 'react';
import { upperField } from './upperField';

export type FormFieldProps = {
  /**
   * The input's id. The label's `htmlFor` points at it, and the error
   * message, when present, gets the id `${id}-error`. It must be unique among
   * the controls mounted at the same time; a `useId()` prefix is one way to
   * guarantee that.
   */
  id: string;
  label: string;
  value: string;
  /**
   * Receives the new value on every change: uppercased by the shared rule
   * when `uppercase` is set, otherwise exactly as typed.
   */
  onChange: (value: string) => void;
  /**
   * Longest value the input accepts (a ported field's 5250 length); the
   * browser stops input beyond it.
   */
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
  /**
   * Render the input's `required` attribute, which exposes the required
   * state to assistive technology. The host form sets `noValidate` and
   * reports a blank field itself through `error`, so the browser shows no
   * validation bubble of its own.
   */
  required?: boolean;
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

export function FormField({
  id,
  label,
  value,
  onChange,
  maxLength,
  error,
  uppercase,
  readOnly,
  required,
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
        required={required}
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
