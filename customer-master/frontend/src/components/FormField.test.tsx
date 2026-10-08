/**
 * Component tests for {@link FormField}, the labelled input every form uses.
 *
 * What is under test, and the 5250 behaviour each part replaces:
 *
 * - **Uppercase as typed** (`uppercase`). No MTNCUSTD or PMTCUSTD input field
 *   declares `CHECK(LC)`, so the workstation uppercased every keyed character
 *   (`SD_NAME 40`, `SC_NAME 13A`). The prop applies the shared
 *   length-preserving rule of `./upperField` on every change: `ß` stays `ß`
 *   (its full mapping `SS` is two code points), the caret stays at the typed
 *   offset, `maxLength` keeps counting typed characters, and the submitted
 *   value is exactly the value shown.
 * - **Error attributes** (`error`). The RI indicator of each MTNCUSTD field
 *   (`DSPATR(RI)`) becomes `aria-invalid="true"` plus a visible message the
 *   input references through `aria-describedby`.
 * - **Cursor position** (`inputRef`). `DSPATR(PC)` becomes focus that the
 *   caller moves through the ref.
 * - **Protection** (`readOnly`). `DSPATR(PR)` under indicator 10 becomes the
 *   `readonly` attribute and the `read-only` class.
 *
 * Behaviour and attributes are asserted; markup is never snapshotted, so a
 * layout change that keeps the contract does not break these tests.
 *
 * The shared test base (`src/test/setup.ts`) supplies the jest-dom matchers
 * and the cleanup between tests. `FormField` makes no request and needs no
 * provider, so nothing is rendered around the harness.
 */
import { createRef, useState } from 'react';
import type { Ref } from 'react';
import { fireEvent, render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import { FormField } from './FormField';

/** Props of {@link Harness}. */
type HarnessProps = {
  /** Forwarded to `FormField`. */
  uppercase?: boolean;
  /** Forwarded to `FormField`; defaults to 40, the MTNCUSTD `SD_NAME` length. */
  maxLength?: number;
  /** Forwarded to `FormField`. */
  error?: string;
  /** Forwarded to `FormField`. */
  readOnly?: boolean;
  /** Forwarded to `FormField`. */
  inputRef?: Ref<HTMLInputElement>;
  /** The value the controlled state starts with; empty by default. */
  initialValue?: string;
  /** Receives the controlled value when the form is submitted. */
  onSubmit: (value: string) => void;
  /** Observes every value `FormField` reports through `onChange`. */
  onValueChange?: (value: string) => void;
};

/**
 * A minimal controlled host form, as the detail form and the search filters
 * use `FormField`: the value lives in the host's state, and submitting hands
 * the host's value, not the DOM's, to `onSubmit`.
 */
function Harness(props: HarnessProps) {
  const [value, setValue] = useState(props.initialValue ?? '');
  return (
    <form
      onSubmit={(event) => {
        event.preventDefault();
        props.onSubmit(value);
      }}
    >
      <FormField
        id="name"
        label="Name"
        value={value}
        onChange={(next) => {
          props.onValueChange?.(next);
          setValue(next);
        }}
        maxLength={props.maxLength ?? 40}
        uppercase={props.uppercase}
        error={props.error}
        readOnly={props.readOnly}
        inputRef={props.inputRef}
      />
      <button type="submit">Submit</button>
    </form>
  );
}

/** The harness's input, found the way a user finds it: by its label. */
function nameInput(): HTMLInputElement {
  return screen.getByLabelText<HTMLInputElement>('Name');
}

/**
 * Writes `value` into the input through the native `HTMLInputElement` setter.
 *
 * React and user-event each wrap the `value` property of the element itself;
 * the prototype setter bypasses both, so the next `input` event is seen by
 * React as a real edit, exactly as a keystroke the browser applied would be.
 */
function setNativeValue(input: HTMLInputElement, value: string): void {
  const setter = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value')?.set;
  if (setter === undefined) {
    throw new Error('HTMLInputElement.prototype.value has no setter in this DOM');
  }
  setter.call(input, value);
}

describe('FormField', () => {
  describe('uppercase as typed', () => {
    it('uppercases each typed character, keeps ß, leaves the caret at the end and submits the shown value', async () => {
      const user = userEvent.setup();
      const onSubmit = vi.fn();
      const onValueChange = vi.fn();
      render(<Harness uppercase onSubmit={onSubmit} onValueChange={onValueChange} />);
      const input = nameInput();

      await user.type(input, 'straße');

      // `ß` has the two-code-point full mapping `SS`, so it is kept and the
      // value keeps the six characters that were typed.
      expect(input.value).toBe('STRAßE');
      expect(input.value).toHaveLength(6);
      expect(input.selectionStart).toBe(6);
      expect(input.selectionEnd).toBe(6);
      // Every value reported to the host, keystroke by keystroke, is already
      // uppercased, and none is ever longer than what was typed.
      expect(onValueChange.mock.calls.map(([reported]) => reported)).toEqual([
        'S',
        'ST',
        'STR',
        'STRA',
        'STRAß',
        'STRAßE',
      ]);

      await user.click(screen.getByRole('button', { name: 'Submit' }));

      expect(onSubmit).toHaveBeenCalledTimes(1);
      expect(onSubmit).toHaveBeenCalledWith('STRAßE');
    });

    it('restores the caret at the typed offset when a character is inserted mid-string', async () => {
      const user = userEvent.setup();
      const onSubmit = vi.fn();
      render(<Harness uppercase onSubmit={onSubmit} />);
      const input = nameInput();
      await user.type(input, 'abc');
      expect(input.value).toBe('ABC');

      // The browser has applied a lowercase `x` at offset 1 and left the caret
      // after it; the change handler must uppercase it without moving the
      // caret to the end, which a plain value assignment would do.
      setNativeValue(input, 'AxBC');
      input.setSelectionRange(2, 2);
      fireEvent.input(input);

      expect(input.value).toBe('AXBC');
      expect(input.selectionStart).toBe(2);
      expect(input.selectionEnd).toBe(2);

      await user.click(screen.getByRole('button', { name: 'Submit' }));
      expect(onSubmit).toHaveBeenCalledWith('AXBC');
    });

    it('restores a non-collapsed selection the browser left after the edit', () => {
      const onSubmit = vi.fn();
      render(<Harness uppercase initialValue="ABCD" onSubmit={onSubmit} />);
      const input = nameInput();

      setNativeValue(input, 'ABxyCD');
      input.setSelectionRange(2, 4);
      fireEvent.input(input);

      expect(input.value).toBe('ABXYCD');
      expect(input.selectionStart).toBe(2);
      expect(input.selectionEnd).toBe(4);
    });

    it('lets maxLength count typed characters, because the value never grows', async () => {
      const user = userEvent.setup();
      const onSubmit = vi.fn();
      render(<Harness uppercase maxLength={40} onSubmit={onSubmit} />);
      const input = nameInput();
      expect(input).toHaveAttribute('maxlength', '40');

      await user.type(input, 'ß' + 'a'.repeat(39));

      expect(input.value).toHaveLength(40);
      expect(input.value).toBe('ß' + 'A'.repeat(39));

      // A forty-first character is refused by the browser's length limit.
      await user.type(input, 'b');

      expect(input.value).toHaveLength(40);
      expect(input.value).toBe('ß' + 'A'.repeat(39));

      await user.click(screen.getByRole('button', { name: 'Submit' }));
      expect(onSubmit).toHaveBeenCalledWith('ß' + 'A'.repeat(39));
    });

    it('keeps leading, inner and trailing blanks, because trimming is the server’s job', async () => {
      const user = userEvent.setup();
      const onSubmit = vi.fn();
      render(<Harness uppercase onSubmit={onSubmit} />);
      const input = nameInput();

      await user.type(input, '  ab c ');

      expect(input.value).toBe('  AB C ');
      await user.click(screen.getByRole('button', { name: 'Submit' }));
      expect(onSubmit).toHaveBeenCalledWith('  AB C ');
    });

    it('passes composing text through raw and uppercases it once the composition ends', () => {
      const onSubmit = vi.fn();
      const onValueChange = vi.fn();
      render(<Harness uppercase onSubmit={onSubmit} onValueChange={onValueChange} />);
      const input = nameInput();

      // Rewriting the value mid-composition would cancel or duplicate the
      // input method's text, so the raw value is reported while composing.
      fireEvent.compositionStart(input);
      setNativeValue(input, 'é');
      input.setSelectionRange(1, 1);
      fireEvent.input(input, { isComposing: true });

      expect(input.value).toBe('é');
      expect(onValueChange).toHaveBeenLastCalledWith('é');

      fireEvent.compositionEnd(input);

      expect(input.value).toBe('É');
      expect(input.selectionStart).toBe(1);
      expect(onValueChange).toHaveBeenLastCalledWith('É');
    });

    it('leaves the value exactly as typed without the prop', async () => {
      const user = userEvent.setup();
      const onSubmit = vi.fn();
      render(<Harness onSubmit={onSubmit} />);
      const input = nameInput();

      await user.type(input, 'abc');

      expect(input.value).toBe('abc');
      await user.click(screen.getByRole('button', { name: 'Submit' }));
      expect(onSubmit).toHaveBeenCalledWith('abc');
    });
  });

  describe('error attributes (DSPATR(RI))', () => {
    it('marks the input invalid and describes it by the rendered message', () => {
      render(<Harness error="Name: Must not be blank" onSubmit={vi.fn()} />);
      const input = nameInput();

      expect(input).toHaveAttribute('aria-invalid', 'true');
      expect(input).toHaveAttribute('aria-describedby', 'name-error');
      expect(input).toHaveAccessibleDescription('Name: Must not be blank');
      const message = document.getElementById('name-error');
      expect(message).not.toBeNull();
      expect(message).toHaveClass('form-field__message');
      expect(message).toHaveTextContent('Name: Must not be blank');
    });

    it('renders neither the attributes nor the message without an error', () => {
      render(<Harness onSubmit={vi.fn()} />);
      const input = nameInput();

      expect(input).not.toHaveAttribute('aria-invalid');
      expect(input).not.toHaveAttribute('aria-describedby');
      expect(document.getElementById('name-error')).toBeNull();
    });

    it('treats an empty error as no error', () => {
      render(<Harness error="" onSubmit={vi.fn()} />);
      const input = nameInput();

      expect(input).not.toHaveAttribute('aria-invalid');
      expect(input).not.toHaveAttribute('aria-describedby');
      expect(document.getElementById('name-error')).toBeNull();
    });

    it('drops the attributes and the message once the error is cleared', () => {
      const onSubmit = vi.fn();
      const { rerender } = render(<Harness error="Name: Must not be blank" onSubmit={onSubmit} />);
      expect(nameInput()).toHaveAttribute('aria-invalid', 'true');

      rerender(<Harness onSubmit={onSubmit} />);

      const input = nameInput();
      expect(input).not.toHaveAttribute('aria-invalid');
      expect(input).not.toHaveAttribute('aria-describedby');
      expect(document.getElementById('name-error')).toBeNull();
    });
  });

  describe('label association', () => {
    it('associates the label with the input', () => {
      render(<Harness onSubmit={vi.fn()} />);
      const input = nameInput();

      expect(input.tagName).toBe('INPUT');
      expect(input).toHaveAttribute('id', 'name');
      expect(input).toHaveAttribute('type', 'text');
      expect(input).toHaveClass('form-field__input');
      expect(input).not.toHaveClass('read-only');
      expect(screen.getByRole('textbox', { name: 'Name' })).toBe(input);
    });
  });

  describe('read-only (DSPATR(PR))', () => {
    it('protects the value and marks the input read-only', async () => {
      const user = userEvent.setup();
      const onSubmit = vi.fn();
      const onValueChange = vi.fn();
      render(
        <Harness readOnly uppercase initialValue="ACME" onSubmit={onSubmit} onValueChange={onValueChange} />,
      );
      const input = nameInput();

      expect(input).toHaveAttribute('readonly');
      expect(input).toHaveClass('form-field__input', 'read-only');

      await user.type(input, 'xyz');

      expect(input.value).toBe('ACME');
      expect(onValueChange).not.toHaveBeenCalled();
      // A protected field stays reachable, so its value can still be read.
      expect(input).toHaveFocus();

      await user.click(screen.getByRole('button', { name: 'Submit' }));
      expect(onSubmit).toHaveBeenCalledWith('ACME');
    });
  });

  describe('inputRef (DSPATR(PC))', () => {
    it('points an object ref at the input, so the caller can focus the first field in error', () => {
      const inputRef = createRef<HTMLInputElement>();
      render(<Harness inputRef={inputRef} error="Name: Must not be blank" onSubmit={vi.fn()} />);
      const input = nameInput();

      expect(inputRef.current).toBe(input);
      // FormField never focuses itself; the caller does it through the ref.
      expect(input).not.toHaveFocus();

      inputRef.current?.focus();

      expect(input).toHaveFocus();
    });

    it('calls a callback ref with the input', () => {
      const callbackRef = vi.fn();
      render(<Harness inputRef={callbackRef} onSubmit={vi.fn()} />);

      expect(callbackRef).toHaveBeenCalledWith(nameInput());
    });
  });
});
