/**
 * Component tests for {@link FormField}. Behaviour and attributes are
 * asserted; markup is never snapshotted, so a layout change that keeps the
 * contract does not break these tests. `FormField` makes no request and needs
 * no provider, so nothing is rendered around the harness.
 */
import { createRef, useState } from 'react';
import type { Ref } from 'react';
import { fireEvent, render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { Mock } from 'vitest';
import { FormField } from './FormField';
import type { FormFieldElement } from './FormField';

type HarnessProps = {
  uppercase?: boolean;
  /** Defaults to 40, the MTNCUSTD `SD_NAME` length. */
  maxLength?: number;
  error?: string;
  readOnly?: boolean;
  required?: boolean;
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
        required={props.required}
        inputRef={props.inputRef}
      />
      <button type="submit">Submit</button>
    </form>
  );
}

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

  // jsdom implements no `spellcheck` IDL property, so the content attribute
  // React renders is asserted; a browser reflects `"false"` as
  // `spellcheck === false`.
  describe('browser spell checking and autocomplete', () => {
    it('turns spell checking off by default, on an editable and on a read-only field', () => {
      const { unmount } = render(<Harness uppercase onSubmit={vi.fn()} />);
      expect(nameInput()).toHaveAttribute('spellcheck', 'false');
      unmount();

      render(<Harness readOnly uppercase initialValue="ACME" onSubmit={vi.fn()} />);
      expect(nameInput()).toHaveAttribute('spellcheck', 'false');
    });

    it('turns spell checking on when spellCheck is true', () => {
      render(<FormField id="note" label="Note" value="" onChange={vi.fn()} maxLength={40} spellCheck />);

      expect(screen.getByLabelText('Note')).toHaveAttribute('spellcheck', 'true');
    });

    it('passes autoComplete through, and renders none without it', () => {
      const { unmount } = render(<Harness onSubmit={vi.fn()} />);
      expect(nameInput()).not.toHaveAttribute('autocomplete');
      unmount();

      render(
        <>
          <FormField id="filter" label="Filter" value="" onChange={vi.fn()} maxLength={13} autoComplete="off" />
          <FormField
            id="password"
            label="Password"
            type="password"
            value=""
            onChange={vi.fn()}
            maxLength={64}
            autoComplete="current-password"
          />
        </>,
      );
      expect(screen.getByLabelText('Filter')).toHaveAttribute('autocomplete', 'off');
      expect(screen.getByLabelText('Password')).toHaveAttribute('autocomplete', 'current-password');
    });
  });

  describe('required', () => {
    it('renders the required attribute only with the prop, and marks nothing invalid by itself', () => {
      const onSubmit = vi.fn();
      const { rerender } = render(<Harness required onSubmit={onSubmit} />);
      const input = nameInput();

      expect(input).toBeRequired();
      expect(input).toHaveAttribute('required');
      expect(input).not.toHaveAttribute('aria-invalid');
      expect(input).not.toHaveAttribute('aria-describedby');

      rerender(<Harness onSubmit={onSubmit} />);

      expect(nameInput()).not.toBeRequired();
      expect(nameInput()).not.toHaveAttribute('required');
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

    /** An unbroken 40-character run: the longest protected value, which only a wrapping control shows whole on a narrow screen. */
    const LONG_TOKEN = 'QALONGTOKEN'.repeat(4).slice(0, 40);

    it('renders a protected text value as a labelled, read-only textarea with the input’s id, classes, length and width', () => {
      render(
        <FormField id="name" label="Name" value={LONG_TOKEN} onChange={vi.fn()} maxLength={40} size={40} readOnly />,
      );
      const field = screen.getByRole('textbox', { name: 'Name' });

      expect(field).toBeInstanceOf(HTMLTextAreaElement);
      expect(screen.getByLabelText('Name')).toBe(field);
      expect(field).toHaveAttribute('id', 'name');
      expect(field).toHaveAttribute('readonly');
      expect(field).toHaveClass('form-field__input', 'read-only');
      expect(field).toHaveAttribute('maxlength', '40');
      expect(field).toHaveAttribute('cols', '40');
      expect(field).toHaveAttribute('rows', '1');
      expect(field).toHaveAttribute('spellcheck', 'false');
      expect(field).toHaveValue(LONG_TOKEN);
    });

    it('ignores typing and a scripted change on the protected textarea, keeps its value, stays focusable and reports nothing', async () => {
      const user = userEvent.setup();
      const onSubmit = vi.fn();
      const onValueChange = vi.fn();
      render(<Harness readOnly initialValue={LONG_TOKEN} onSubmit={onSubmit} onValueChange={onValueChange} />);
      const field = screen.getByRole('textbox', { name: 'Name' });

      await user.type(field, 'xyz{Enter}');
      fireEvent.change(field, { target: { value: 'CHANGED BY SCRIPT' } });

      expect(field).toHaveValue(LONG_TOKEN);
      expect(onValueChange).not.toHaveBeenCalled();
      expect(field).toHaveFocus();
      await user.click(screen.getByRole('button', { name: 'Submit' }));
      expect(onSubmit).toHaveBeenCalledWith(LONG_TOKEN);
    });

    it('keeps the error attributes on a protected value', () => {
      render(<Harness readOnly initialValue="ACME" error="Name: Must not be blank" onSubmit={vi.fn()} />);
      const field = screen.getByRole('textbox', { name: 'Name' });

      expect(field).toBeInstanceOf(HTMLTextAreaElement);
      expect(field).toHaveAttribute('aria-invalid', 'true');
      expect(field).toHaveAttribute('aria-describedby', 'name-error');
      expect(field).toHaveAccessibleDescription('Name: Must not be blank');
    });

    it('keeps a protected password a masked, read-only input', () => {
      render(
        <FormField id="secret" label="Password" type="password" value="hunter2" onChange={vi.fn()} maxLength={64} readOnly />,
      );
      const field = screen.getByLabelText('Password');

      expect(field).toBeInstanceOf(HTMLInputElement);
      expect(field).toHaveAttribute('type', 'password');
      expect(field).toHaveAttribute('readonly');
      expect(field).toHaveClass('form-field__input', 'read-only');
    });

    it('hands the protected textarea to an object ref and takes it back on unmount', () => {
      const ref = createRef<FormFieldElement>();
      const { unmount } = render(
        <FormField id="name" label="Name" value="ACME" onChange={vi.fn()} maxLength={40} readOnly inputRef={ref} />,
      );

      expect(ref.current).toBeInstanceOf(HTMLTextAreaElement);
      expect(ref.current).toBe(screen.getByLabelText('Name'));

      unmount();

      expect(ref.current).toBeNull();
    });

    it('runs the cleanup a callback ref returns instead of passing it null, and passes null to one without', () => {
      const cleanup = vi.fn();
      const withCleanup = vi.fn((_el: FormFieldElement | null) => cleanup);
      const plain = vi.fn((_el: FormFieldElement | null): void => undefined);
      const { unmount } = render(
        <>
          <FormField id="name" label="Name" value="ACME" onChange={vi.fn()} maxLength={40} readOnly inputRef={withCleanup} />
          <FormField id="city" label="City" value="BANGOR" onChange={vi.fn()} maxLength={20} readOnly inputRef={plain} />
        </>,
      );

      expect(withCleanup).toHaveBeenCalledWith(screen.getByLabelText('Name'));
      expect(plain).toHaveBeenCalledWith(screen.getByLabelText('City'));

      unmount();

      expect(cleanup).toHaveBeenCalledTimes(1);
      expect(withCleanup).not.toHaveBeenCalledWith(null);
      expect(plain).toHaveBeenLastCalledWith(null);
    });

    describe('height of the wrapped value', () => {
      /** The `scrollHeight` the next measurement reads; jsdom lays nothing out and reports 0 itself. */
      let measured = 0;

      function measureAs(height: number): void {
        measured = height;
      }

      afterEach(() => {
        vi.restoreAllMocks();
        vi.unstubAllGlobals();
      });

      function stubScrollHeight(): void {
        vi.spyOn(Element.prototype, 'scrollHeight', 'get').mockImplementation(() => measured);
      }

      it('grows to every wrapped line before paint and refits when the value changes', () => {
        stubScrollHeight();
        measureAs(72);
        const props = { id: 'name', label: 'Name', onChange: vi.fn(), maxLength: 40, size: 40, readOnly: true } as const;
        const { rerender } = render(<FormField {...props} value={LONG_TOKEN} />);
        const field = screen.getByRole('textbox', { name: 'Name' });

        expect(field.style.blockSize).toBe('72px');

        measureAs(36);
        rerender(<FormField {...props} value="ACME" />);

        expect(field.style.blockSize).toBe('36px');
      });

      it('takes the width of the editable input it stands in for, measured by a hidden probe that leaves nothing behind', () => {
        stubScrollHeight();
        measureAs(36);
        const probes: Array<{ size: string | null; hidden: string | null; className: string; parent: Element | null }> = [];
        const original = Element.prototype.getBoundingClientRect;
        vi.spyOn(Element.prototype, 'getBoundingClientRect').mockImplementation(function (this: Element) {
          if (this instanceof HTMLInputElement) {
            probes.push({
              size: this.getAttribute('size'),
              hidden: this.getAttribute('aria-hidden'),
              className: this.className,
              parent: this.parentElement,
            });
            return { x: 0, y: 0, top: 0, left: 0, right: 428, bottom: 34, width: 428, height: 34, toJSON: () => ({}) };
          }
          return original.call(this);
        });
        const { container } = render(
          <FormField id="name" label="Name" value={LONG_TOKEN} onChange={vi.fn()} maxLength={40} size={40} readOnly />,
        );
        const field = screen.getByRole('textbox', { name: 'Name' });

        expect(field.style.inlineSize).toBe('428px');
        expect(probes.length).toBeGreaterThan(0);
        for (const probe of probes) {
          expect(probe).toEqual({ size: '40', hidden: 'true', className: 'form-field__input', parent: field.parentElement });
        }
        expect(container.querySelectorAll('input')).toHaveLength(0);
      });

      it('keeps the one-row height while nothing is laid out', () => {
        stubScrollHeight();
        measureAs(0);
        render(<FormField id="name" label="Name" value={LONG_TOKEN} onChange={vi.fn()} maxLength={40} readOnly />);

        expect(screen.getByRole('textbox', { name: 'Name' }).style.blockSize).toBe('');
      });

      it('refits in the next animation frame when its width changes, never inside the observer callback', () => {
        stubScrollHeight();
        const frames: FrameRequestCallback[] = [];
        vi.stubGlobal('requestAnimationFrame', (callback: FrameRequestCallback): number => frames.push(callback));
        vi.stubGlobal('cancelAnimationFrame', (handle: number): void => {
          if (handle > 0 && handle <= frames.length) {
            frames[handle - 1] = () => undefined;
          }
        });
        const observers: FakeResizeObserver[] = [];
        class FakeResizeObserver {
          readonly callback: ResizeObserverCallback;
          observed: Element[] = [];
          readonly disconnect: Mock<() => void> = vi.fn();
          constructor(callback: ResizeObserverCallback) {
            this.callback = callback;
            observers.push(this);
          }
          observe(target: Element): void {
            this.observed.push(target);
          }
          unobserve(target: Element): void {
            this.observed = this.observed.filter((element) => element !== target);
          }
        }
        vi.stubGlobal('ResizeObserver', FakeResizeObserver);
        measureAs(36);
        const { unmount } = render(
          <FormField id="name" label="Name" value={LONG_TOKEN} onChange={vi.fn()} maxLength={40} size={40} readOnly />,
        );
        const field = screen.getByRole('textbox', { name: 'Name' });
        const [observer] = observers;
        if (observer === undefined) {
          throw new Error('no ResizeObserver was created');
        }
        expect(observer.observed).toEqual([field]);
        const notify = (width: number): void => {
          observer.callback([{ contentRect: { width } } as ResizeObserverEntry], observer as unknown as ResizeObserver);
        };

        // A narrower box wraps the value to more lines.
        measureAs(108);
        notify(300);
        expect(field.style.blockSize).toBe('36px');
        expect(frames).toHaveLength(1);
        frames[0]?.(0);
        expect(field.style.blockSize).toBe('108px');

        // The resize the refit itself causes keeps the width: no further frame.
        notify(300);
        expect(frames).toHaveLength(1);

        unmount();
        expect(observer.disconnect).toHaveBeenCalledTimes(1);
      });
    });
  });

  describe('pending request', () => {
    it('keeps the same editable input, its classes, focus and ref while pending, and takes no typing until it ends', async () => {
      const user = userEvent.setup();
      const onChange = vi.fn();
      const ref = createRef<FormFieldElement>();
      const props = { id: 'name', label: 'Name', value: 'ACME', onChange, maxLength: 40, size: 40, uppercase: true, inputRef: ref };
      const { rerender } = render(<FormField {...props} />);
      const input = screen.getByLabelText('Name');
      input.focus();

      rerender(<FormField {...props} pending />);

      expect(screen.getByLabelText('Name')).toBe(input);
      expect(input).toBeInstanceOf(HTMLInputElement);
      expect(input).toHaveAttribute('readonly');
      expect(input).toHaveClass('form-field__input');
      expect(input).not.toHaveClass('read-only');
      expect(input).toHaveAttribute('size', '40');
      expect(input).toHaveFocus();
      expect(ref.current).toBe(input);
      await user.type(input, 'xyz');
      expect(input).toHaveValue('ACME');
      expect(onChange).not.toHaveBeenCalled();

      rerender(<FormField {...props} pending={false} />);

      expect(screen.getByLabelText('Name')).toBe(input);
      expect(input).not.toHaveAttribute('readonly');
      await user.type(input, 'b');
      expect(onChange).toHaveBeenLastCalledWith('ACMEB');
    });

    it('lets readOnly win over pending: the value is protected in its textarea', () => {
      render(<FormField id="name" label="Name" value="ACME" onChange={vi.fn()} maxLength={40} readOnly pending />);
      const field = screen.getByLabelText('Name');

      expect(field).toBeInstanceOf(HTMLTextAreaElement);
      expect(field).toHaveAttribute('readonly');
      expect(field).toHaveClass('form-field__input', 'read-only');
    });
  });

  describe('inputRef (DSPATR(PC))', () => {
    it('points an object ref at the input, so the caller can focus the first field in error', () => {
      const inputRef = createRef<HTMLInputElement>();
      render(<Harness inputRef={inputRef} error="Name: Must not be blank" onSubmit={vi.fn()} />);
      const input = nameInput();

      expect(inputRef.current).toBe(input);
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
