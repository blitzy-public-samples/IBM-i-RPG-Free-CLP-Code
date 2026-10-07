/*
 * Modal window primitive.
 *
 * Replaces the 5250 WINDOW records: the customer detail window of MTNCUSTD
 * (WINDOW(*DFT 17 54), pink border) and the USA States window of PMTSTATED
 * (WINDOW(*DFT 16 40), blue border). A 5250 window is modal by construction:
 * the screen beneath is shown but cannot be keyed, and there is no way to
 * dismiss the window except through the keys its program enables. This
 * component supplies the browser equivalent of that modality and nothing
 * more:
 *
 * - a backdrop that dims the screen beneath (`.dialog__backdrop`);
 * - a `<dialog open aria-modal="true">` named by the owner's ScreenHeader
 *   through `aria-labelledby`;
 * - focus moved into the window when it opens, a Tab / Shift+Tab focus trap
 *   while it is open, and focus returned to the invoking element when it
 *   closes.
 *
 * Because the keyboard scope stack never moves focus (KeyScopeProvider), this
 * component is the only place where focus enters and leaves a window, so
 * focus and key handling return to the screen beneath together.
 *
 * What it deliberately does not do:
 *
 * - It never calls `showModal()`. A top-layer dialog would paint above the
 *   single shared toast host (ToastRegion, mounted once outside every dialog)
 *   and make it inert. Stacking comes from CSS instead: the backdrop sits at
 *   `--z-dialog`, the window one layer above it, and toasts at `--z-toast`.
 *   jsdom does not implement `showModal()` either.
 * - It binds no keys. Escape, F12 and every other command key belong to the
 *   owner's `useFunctionKeys` scope (Escape runs the F12 binding there). A
 *   `<dialog>` opened through its `open` attribute is non-modal to the
 *   browser, so no native `cancel` closes it behind the owner's back.
 * - It renders no text. Titles come from the owner's ScreenHeader.
 * - It closes on nothing by itself: a 5250 window has no click-away close.
 *
 * Imports are React only: components never reach into api/, errors/,
 * features/ or keyboard/.
 */
import { useEffect, useRef, useState } from 'react';
import type { KeyboardEvent, MouseEvent, ReactNode, RefObject } from 'react';

/** Props of {@link Dialog}. */
export type DialogProps = {
  /**
   * Whether the window is shown. When false nothing is rendered, so the
   * children unmount and their key scopes are removed with them.
   */
  open: boolean;
  /**
   * Id, or space-separated ids, of the element(s) that name the window,
   * normally the ScreenHeader title and function text, for example
   * `"customer-detail-title customer-detail-function"`.
   */
  labelledBy: string;
  /**
   * Extra classes for the `<dialog>` element, for example `"state-picker"`.
   * The base class `dialog` is always present and never duplicated.
   */
  className?: string;
  /**
   * Element to focus when the window opens. Without it, focus goes to the
   * first editable field, else the first focusable element, else the window
   * itself.
   */
  initialFocusRef?: RefObject<HTMLElement | null>;
  /** Window content: header, fields, function-key bar, nested windows. */
  children: ReactNode;
};

/**
 * Candidate focus targets inside a window, in DOM order. The list is narrowed
 * further by {@link tabbableElements}.
 */
const FOCUSABLE_SELECTOR = [
  'a[href]',
  'button:not([disabled])',
  'input:not([disabled]):not([type="hidden"])',
  'select:not([disabled])',
  'textarea:not([disabled])',
  '[tabindex]:not([tabindex="-1"])',
].join(', ');

/**
 * The elements Tab can reach inside `dialog`, in DOM order.
 *
 * Besides the selector, an element must belong to this window and not to a
 * window nested inside it (a State picker opened over the detail window owns
 * its own trap), must not sit inside a `hidden` or `inert` subtree, must not
 * be disabled through a disabled fieldset, and must not have been taken out
 * of the tab order with a negative `tabindex`.
 */
function tabbableElements(dialog: HTMLDialogElement): HTMLElement[] {
  return Array.from(dialog.querySelectorAll<HTMLElement>(FOCUSABLE_SELECTOR)).filter(
    (element) =>
      element.closest('dialog') === dialog &&
      element.tabIndex >= 0 &&
      !element.matches(':disabled') &&
      element.closest('[hidden], [inert]') === null,
  );
}

/**
 * True for a field the user can type into or change: an input other than a
 * hidden one, a select or a textarea that is enabled and, for inputs and
 * textareas, not read-only. Display mode renders every field read-only, so
 * it has no editable field and focus falls through to the first focusable
 * element.
 */
function isEditable(
  element: HTMLElement,
): element is HTMLInputElement | HTMLSelectElement | HTMLTextAreaElement {
  if (element instanceof HTMLInputElement) {
    return element.type !== 'hidden' && !element.disabled && !element.readOnly;
  }
  if (element instanceof HTMLTextAreaElement) {
    return !element.disabled && !element.readOnly;
  }
  if (element instanceof HTMLSelectElement) {
    return !element.disabled;
  }
  return false;
}

/**
 * Where focus goes when the window opens: the owner's preferred element, else
 * the first editable field, else the first focusable element, else the
 * window itself (it carries `tabIndex={-1}` for exactly this fallback).
 */
function initialFocusTarget(
  dialog: HTMLDialogElement,
  preferred: HTMLElement | null,
): HTMLElement {
  if (preferred !== null && preferred.isConnected) {
    return preferred;
  }
  const candidates = tabbableElements(dialog);
  return candidates.find(isEditable) ?? candidates[0] ?? dialog;
}

/**
 * The element that has focus right now, which is the element that invoked
 * the window when this runs as the window mounts. `<body>` means nothing had
 * focus, so there is nothing to return to.
 */
function captureInvoker(): HTMLElement | null {
  if (typeof document === 'undefined') {
    return null;
  }
  const active = document.activeElement;
  return active instanceof HTMLElement && active !== document.body ? active : null;
}

/** True when `node` comes before `other` in document order (or contains it). */
function precedes(node: Node, other: Node): boolean {
  return (node.compareDocumentPosition(other) & Node.DOCUMENT_POSITION_FOLLOWING) !== 0;
}

/** True when `node` comes after `other` in document order (or lies inside it). */
function follows(node: Node, other: Node): boolean {
  return (node.compareDocumentPosition(other) & Node.DOCUMENT_POSITION_PRECEDING) !== 0;
}

/**
 * The `<dialog>` class list: `dialog` first, then the owner's classes. An
 * owner that already lists `dialog` (the detail window passes
 * `"dialog dialog--detail"`) does not get it twice.
 */
function dialogClassName(className: string | undefined): string {
  const extra = (className ?? '')
    .split(/\s+/)
    .filter((name) => name !== '' && name !== 'dialog');
  return ['dialog', ...new Set(extra)].join(' ');
}

/**
 * A press on the backdrop keeps focus where it is. The backdrop is not
 * focusable, so by default the press would move focus to the nearest
 * focusable ancestor: `<body>` for a top-level window, which lets the next
 * Tab leave the trap, or the enclosing window for a State picker nested in
 * the detail window, whose backdrop is a descendant of that window. The press
 * closes nothing.
 */
function keepFocus(event: MouseEvent<HTMLDivElement>): void {
  event.preventDefault();
}

/**
 * Modal window: backdrop, `<dialog open aria-modal="true">`, focus in on
 * open, Tab trap while open, focus return on close.
 *
 * Initial focus is owned here. The element that had focus when the window
 * opened (the invoking option field, button or State field) is captured while
 * the window first renders, before any child can move focus, and receives
 * focus again when the window closes or unmounts, provided it is still in the
 * document. After the children's own mount effects have run, the window
 * focuses `initialFocusRef`, else its first editable field, else its first
 * focusable element, else itself, so that choice always wins at open. Owners
 * therefore pick the opening field through `initialFocusRef` (Name in the
 * detail window, the filter in the pickers) rather than with `autoFocus` or a
 * focusing mount effect, which this component would override. A child may
 * still move focus later, from a handler or when it remounts while the window
 * stays open (the detail form focusing the first field in error).
 *
 * Keys are not handled here; the owner closes the window from its
 * `useFunctionKeys` scope by setting `open` to false.
 *
 * @example
 * const filterRef = useRef<HTMLInputElement>(null);
 * <Dialog open={open} labelledBy="state-picker-title" initialFocusRef={filterRef}
 *         className="state-picker">
 *   <ScreenHeader id="state-picker" title="USA States" user={username} />
 *   <FormField id="state-picker-name" label="Name Contains" inputRef={filterRef} … />
 * </Dialog>
 */
export function Dialog({ open, labelledBy, className, initialFocusRef, children }: DialogProps) {
  // Each opening mounts a fresh window, so the invoker is captured anew every
  // time the window is shown and released when it is hidden.
  if (!open) {
    return null;
  }
  return (
    <DialogWindow labelledBy={labelledBy} className={className} initialFocusRef={initialFocusRef}>
      {children}
    </DialogWindow>
  );
}

type DialogWindowProps = Omit<DialogProps, 'open'>;

/** The open window; mounted only while {@link Dialog} is open. */
function DialogWindow({ labelledBy, className, initialFocusRef, children }: DialogWindowProps) {
  const dialogRef = useRef<HTMLDialogElement>(null);

  // The invoker is read during the first render, not in an effect: child
  // effects run before this component's effects, and the detail form focuses
  // its first field from its own mount effect, so an effect would record
  // that field instead of the option field or button that opened the window,
  // and focus could never return there.
  const [invoker] = useState(captureInvoker);

  // Focus in. Runs after every child's mount effect, so it decides where
  // focus is when the window opens.
  useEffect(() => {
    const dialog = dialogRef.current;
    if (dialog === null) {
      return;
    }
    initialFocusTarget(dialog, initialFocusRef?.current ?? null).focus();
  }, [initialFocusRef]);

  // Focus return when the window closes or unmounts. A window opened over
  // another window (the State picker over the detail window) returns focus
  // to the field inside the window beneath, which keeps its own trap.
  useEffect(
    () => () => {
      if (invoker !== null && invoker.isConnected) {
        invoker.focus();
      }
    },
    [invoker],
  );

  /**
   * Focus trap. Only Tab and Shift+Tab are handled, and only at the two ends
   * of the window's tab order; every other key, Tab inside the range
   * included, keeps its native behaviour. This is an element handler, not a
   * document listener: the keyboard scope provider owns the one document
   * listener and never intercepts Tab.
   */
  function handleKeyDown(event: KeyboardEvent<HTMLDialogElement>): void {
    if (event.key !== 'Tab' || event.altKey || event.ctrlKey || event.metaKey) {
      return;
    }
    const dialog = dialogRef.current;
    const target = event.target;
    // A Tab pressed inside a nested window bubbles here too; that window's
    // own trap has already handled it.
    if (dialog === null || !(target instanceof Element) || target.closest('dialog') !== dialog) {
      return;
    }

    const tabbable = tabbableElements(dialog);
    const first = tabbable[0];
    const last = tabbable[tabbable.length - 1];
    if (first === undefined || last === undefined) {
      event.preventDefault();
      dialog.focus();
      return;
    }

    // Focus can also sit on an element outside the tab order, such as the
    // window itself after a press on its padding; a Tab from before the first
    // or after the last tabbable element would otherwise leave the window.
    const inTabOrder = tabbable.some((element) => element === target);
    if (event.shiftKey) {
      if (target === dialog || target === first || (!inTabOrder && precedes(target, first))) {
        event.preventDefault();
        last.focus();
      }
    } else if (target === last || (!inTabOrder && follows(target, last))) {
      event.preventDefault();
      first.focus();
    }
  }

  return (
    <>
      <div className="dialog__backdrop" aria-hidden="true" onMouseDown={keepFocus} />
      <dialog
        open
        ref={dialogRef}
        aria-modal="true"
        aria-labelledby={labelledBy}
        className={dialogClassName(className)}
        tabIndex={-1}
        onKeyDown={handleKeyDown}
      >
        {children}
      </dialog>
    </>
  );
}
