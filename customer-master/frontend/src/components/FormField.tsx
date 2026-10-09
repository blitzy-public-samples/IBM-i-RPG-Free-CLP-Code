/**
 * FormField: the labelled text input used by every form.
 *
 * - **Error.** A non-empty `error` sets `aria-invalid="true"`, which the
 *   stylesheet keys the reverse-image style on, and renders the message under
 *   the input, referenced by `aria-describedby`, so colour is never the only
 *   signal. `label` and `error` arrive already formatted; the component holds
 *   no message text.
 * - **Protected value.** With `readOnly`, a text value renders in a read-only
 *   `<textarea>`, because an input cannot wrap and a protected 40-character
 *   value must stay readable on a narrow screen; a password stays a masked
 *   `<input>`. The textarea keeps the input's id, label, classes and
 *   attributes and has one row. It takes exactly the width the editable
 *   input of `size` columns takes ({@link fitProtectedValue}; `cols` until it
 *   is laid out), so a field keeps its width and position between the form
 *   and the confirmation. It wraps the value at that width and grows to every
 *   line it takes, so nothing is cut and nothing scrolls inside it. It is
 *   still a labelled form control, found by its label and reporting its
 *   value as the input does.
 * - **Pending request.** `pending` sets an editable input read-only while a
 *   request is in flight without protecting it: the element, its classes and
 *   its editable look stay, so nothing moves, flashes or loses focus.
 * - **Focus.** The caller moves focus through `inputRef`; this component never
 *   sets `autoFocus` and never focuses itself. A protected value stays
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
import { useCallback, useEffect, useLayoutEffect, useRef } from 'react';
import type { ChangeEvent, CompositionEvent, Ref } from 'react';
import { upperField } from './upperField';

/**
 * The control a field renders: an `<input>`, or the read-only `<textarea>`
 * of a protected text value (see `readOnly`).
 */
export type FormFieldElement = HTMLInputElement | HTMLTextAreaElement;

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
  /**
   * Protect the value (DSPATR(PR)): display mode and the confirmation panel.
   * A text value then renders in a read-only `<textarea>` that wraps it; a
   * password keeps its masked `<input>`.
   */
  readOnly?: boolean;
  /**
   * A request is in flight (a review, a reload or a write): the editable
   * input takes the `readonly` attribute, so nothing is typed that the
   * response would replace. Unlike `readOnly` it protects nothing: the field
   * keeps its `<input>` element and classes, so focus and refs stay on it,
   * and the stylesheet keeps its editable border and background, so the
   * request changes nothing on screen. Ignored while `readOnly` is set.
   */
  pending?: boolean;
  /**
   * Render the control's `required` attribute, which exposes the required
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
   * Browser spell checking; defaults to `false`. Every field holds data, not
   * prose: codes, names, addresses, phone numbers and credentials, the ported
   * ones uppercased like their 5250 fields, so spelling marks would only flag
   * valid values.
   */
  spellCheck?: boolean;
  /**
   * Visible width in characters. The stylesheet sizes text inputs by this
   * attribute, so callers pass the field length (13 for search filters, 40
   * for Name, 1 for Active); a protected value's textarea takes it as `cols`
   * and is fitted to the width an input of this size takes. Without it the
   * browser default applies.
   */
  size?: number;
  /**
   * Ref to the rendered control, the input or a protected value's textarea,
   * so the caller can move focus to it (DSPATR(PC)). A callback ref may
   * return a cleanup function, which runs when the control goes away.
   */
  inputRef?: Ref<FormFieldElement>;
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
 * Hands the mounted control to the caller's `inputRef`, an object or a
 * callback ref, and returns the function that takes it back when the control
 * goes away: the cleanup the callback returned, or else `null` passed to the
 * callback or set on the object, as React itself would do.
 */
function attachRef(ref: Ref<FormFieldElement> | undefined, el: FormFieldElement): () => void {
  if (typeof ref === 'function') {
    const cleanup = ref(el);
    return typeof cleanup === 'function'
      ? cleanup
      : () => {
          ref(null);
        };
  }
  if (ref !== null && ref !== undefined) {
    ref.current = el;
    return () => {
      ref.current = null;
    };
  }
  return () => undefined;
}

/** A computed length in CSS pixels; 0 for an empty or unparsable one, as a DOM without layout reports. */
function px(length: string): number {
  const value = Number.parseFloat(length);
  return Number.isFinite(value) ? value : 0;
}

/**
 * Sets a protected value's textarea to the height of its wrapped text, so
 * every line shows and nothing scrolls inside it.
 *
 * The inline block size is cleared first, so the textarea falls back to its
 * one-row height and `scrollHeight` measures the text and padding alone; the
 * borders are then added back (`border-box`) or the padding taken off
 * (`content-box`). A textarea without layout (not rendered, or a DOM without
 * layout such as jsdom) reports a `scrollHeight` of 0 and keeps its one-row
 * height.
 *
 * Called from a layout effect and an animation frame only, never during
 * render.
 */
function fitToContent(el: HTMLTextAreaElement): void {
  el.style.blockSize = '';
  const contentHeight = el.scrollHeight;
  if (contentHeight === 0) {
    return;
  }
  const style = getComputedStyle(el);
  const outside =
    style.boxSizing === 'border-box'
      ? px(style.borderBlockStartWidth) + px(style.borderBlockEndWidth)
      : -(px(style.paddingBlockStart) + px(style.paddingBlockEnd));
  el.style.blockSize = `${contentHeight + outside}px`;
}

/**
 * The border-box inline size the editable input of `size` columns takes in
 * the textarea's place, or 0 where nothing is laid out.
 *
 * Browsers size an `<input size>` and a `<textarea cols>` by different
 * formulas (an input adds a font-dependent allowance), so `cols` alone leaves
 * the textarea a few pixels narrower than the input it stands in for. A probe
 * input, styled as an editable FormField input, is measured beside the
 * textarea, where the same rules and inherited font apply, and removed again
 * before anything paints. It is hidden, out of flow and unconstrained in
 * width; the textarea's own `max-inline-size` still applies.
 */
function editableInlineSize(el: HTMLTextAreaElement, size: number | undefined): number {
  const parent = el.parentElement;
  if (parent === null) {
    return 0;
  }
  const probe = el.ownerDocument.createElement('input');
  probe.type = 'text';
  if (size !== undefined && Number.isInteger(size) && size > 0) {
    probe.size = size;
  }
  probe.className = 'form-field__input';
  probe.tabIndex = -1;
  probe.setAttribute('aria-hidden', 'true');
  probe.style.position = 'absolute';
  probe.style.visibility = 'hidden';
  probe.style.maxInlineSize = 'none';
  parent.insertBefore(probe, el);
  const width = probe.getBoundingClientRect().width;
  probe.remove();
  return width;
}

/**
 * Fits a protected value's textarea: the inline size of the editable input
 * of `size` columns ({@link editableInlineSize}), then the height of the
 * value wrapped at that width ({@link fitToContent}). Where nothing is laid
 * out, the inline size is left to `cols`.
 *
 * Called from a layout effect and an animation frame only, never during
 * render.
 */
function fitProtectedValue(el: HTMLTextAreaElement, size: number | undefined): void {
  const width = editableInlineSize(el, size);
  if (width > 0) {
    const style = getComputedStyle(el);
    const inside =
      style.boxSizing === 'border-box'
        ? 0
        : px(style.paddingInlineStart) +
          px(style.paddingInlineEnd) +
          px(style.borderInlineStartWidth) +
          px(style.borderInlineEndWidth);
    el.style.inlineSize = `${width - inside}px`;
  } else {
    el.style.inlineSize = '';
  }
  fitToContent(el);
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
  pending,
  required,
  type,
  autoComplete,
  spellCheck,
  size,
  inputRef,
  className,
}: FormFieldProps) {
  const hasError = error !== undefined && error !== '';
  const errorId = `${id}-error`;
  // A protected text value wraps in a textarea; a protected password stays an
  // input, so its characters stay masked.
  const protectedText = readOnly === true && (type ?? 'text') === 'text';

  // The protected value's textarea while one is mounted, for its fit.
  // Written only by bindControl (during commit), read only in effects.
  const textareaRef = useRef<HTMLTextAreaElement | null>(null);

  /**
   * The callback ref of the rendered control: records a protected value's
   * textarea for its fit and hands the control to `inputRef`. React
   * calls a callback ref that returns a cleanup with the mounted control only,
   * never with `null`; the `null` the type admits leaves nothing to attach.
   */
  const bindControl = useCallback(
    (el: FormFieldElement | null) => {
      if (el === null) {
        return undefined;
      }
      if (el instanceof HTMLTextAreaElement) {
        textareaRef.current = el;
      }
      const detach = attachRef(inputRef, el);
      return () => {
        if (textareaRef.current === el) {
          textareaRef.current = null;
        }
        detach();
      };
    },
    [inputRef],
  );

  // Fits the protected value's width and height before the browser paints, on
  // mount and whenever the value or the width in columns changes, so no frame
  // shows it at another width or cut to one row.
  useLayoutEffect(() => {
    const el = textareaRef.current;
    if (el !== null) {
      fitProtectedValue(el, size);
    }
  }, [value, protectedText, size]);

  // Refits when the textarea's width changes (a narrower viewport, the first
  // layout of a field mounted where nothing was laid out), because the value
  // then wraps to more or fewer lines. The first notification, delivered as
  // observation starts, refits too. The refit waits for the next animation frame: resizing the observed
  // element inside the observer's own callback would leave its notification
  // undelivered and raise the browser's "ResizeObserver loop" error. A DOM
  // without ResizeObserver (jsdom) keeps the fit of the layout effect.
  useEffect(() => {
    const el = textareaRef.current;
    if (el === null || typeof ResizeObserver === 'undefined') {
      return undefined;
    }
    let fittedWidth: number | null = null;
    let frame = 0;
    const observer = new ResizeObserver((entries) => {
      const width = entries[entries.length - 1]?.contentRect.width;
      if (width === undefined || width === fittedWidth) {
        return;
      }
      fittedWidth = width;
      cancelAnimationFrame(frame);
      frame = requestAnimationFrame(() => {
        fitProtectedValue(el, size);
      });
    });
    observer.observe(el);
    return () => {
      observer.disconnect();
      cancelAnimationFrame(frame);
    };
  }, [protectedText, size]);

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
      {protectedText ? (
        // No change handler: nothing can be typed into the protected value,
        // and a scripted change is reverted to `value` by React.
        <textarea
          id={id}
          ref={bindControl}
          className="form-field__input read-only"
          value={value}
          maxLength={maxLength}
          cols={size}
          rows={1}
          autoComplete={autoComplete}
          spellCheck={spellCheck ?? false}
          readOnly
          required={required}
          aria-invalid={hasError ? 'true' : undefined}
          aria-describedby={hasError ? errorId : undefined}
        />
      ) : (
        <input
          id={id}
          ref={bindControl}
          type={type ?? 'text'}
          className={readOnly ? 'form-field__input read-only' : 'form-field__input'}
          value={value}
          maxLength={maxLength}
          size={size}
          autoComplete={autoComplete}
          spellCheck={spellCheck ?? false}
          readOnly={readOnly === true || pending === true}
          required={required}
          aria-invalid={hasError ? 'true' : undefined}
          aria-describedby={hasError ? errorId : undefined}
          onChange={handleChange}
          onCompositionEnd={uppercase ? handleCompositionEnd : undefined}
        />
      )}
      {hasError ? (
        <span id={errorId} className="form-field__message">
          {error}
        </span>
      ) : null}
    </div>
  );
}
