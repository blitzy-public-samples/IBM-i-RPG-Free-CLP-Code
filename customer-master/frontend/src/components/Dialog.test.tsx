/**
 * Specs for the shared modal window primitive {@link Dialog} (AAP 0.3.8 and
 * 0.4.4): "Dialogs use `<dialog>` with `aria-modal="true"` and a focus trap";
 * opening a window moves focus to its first editable field and closing it
 * returns focus to the invoking control.
 *
 * The consumer specs (detail dialog, pickers) tab between controls inside one
 * window and never across its ends, so they would stay green with the trap
 * removed. This file pins the primitive itself; each `describe` is one part of
 * its contract:
 *
 * - **Focus trap.** Tab on the last reachable stop wraps to the first, and
 *   Shift+Tab on the first wraps to the last; a full cycle in either direction
 *   visits only the window's stops, in tab order. Controls that are not stops
 *   (disabled, `tabindex="-1"`, hidden by CSS or a `hidden` subtree, in a
 *   disabled fieldset, the unchecked radio of a checked group) sit after the
 *   last stop and never mark an end of the trap.
 * - **Background containment.** While a window is open everything beside it
 *   is `inert` except its own backdrop and the live regions (the toast host,
 *   rendered outside every window); focus that script moves onto something
 *   beneath that stays live goes back to the element last focused in the
 *   window; closing removes only the `inert` the window added.
 * - **Nested windows.** A window rendered inside another (the State picker in
 *   the detail window) holds focus and the trap alone: the outer window's
 *   content becomes inert while the outer `<dialog>` and the elements that
 *   name it (its `aria-labelledby` targets, reached by walking any wrapper
 *   and header around them, whose other content still becomes inert) stay
 *   live, so the outer window keeps its accessible name; a click on such a
 *   label is stopped and focus on it goes back into the inner window; and
 *   closing the inner window returns focus to its invoker, releases every
 *   `inert` and guard it added, and hands the trap back to the outer window.
 * - **Initial focus and focus return.** The window focuses `initialFocusRef`,
 *   else its first editable field, overriding a child that focused itself on
 *   mount; closing focuses the control that opened it, and an invoker removed
 *   meanwhile is left alone.
 * - **Keyboard scope.** Under the real `KeyScopeProvider` with a
 *   `useFunctionKeys` scope inside the window, Tab and Shift+Tab still wrap
 *   (the provider never prevents them), and F12 or Escape closes the window
 *   through the scope with focus back on the invoker.
 *
 * Environment. jsdom does not implement `inert`, and user-event's `tab()`
 * moves focus over every focusable element of the document, inert or not,
 * unless the keydown was prevented. Here, therefore, only the window's own
 * trap carries Tab from one end of the window to the other: without it, Tab
 * on the last stop moves focus onto a page control, the window's focus
 * containment sends it straight back to the stop it left, and focus never
 * reaches the other end, which every boundary assertion catches. Background
 * modality is asserted through the `inert` attribute and through focus
 * recovery. jsdom's accessible-name computation, behind `getByRole`'s
 * `name`, ignores `inert` as well, so the name a browser gives a window is
 * asserted through {@link browserNameOf}, which drops label targets that lie
 * in an inert subtree as browsers do.
 *
 * Every harness renders the real `Dialog`; nothing is mocked and no request is
 * made, so the MSW server of `src/test/setup.ts` stays idle.
 */
import { useEffect, useRef, useState } from 'react';
import type { JSX } from 'react';
import { act, fireEvent, render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type { UserEvent } from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';

import { KeyScopeProvider } from '../keyboard/KeyScopeProvider';
import { useFunctionKeys } from '../keyboard/useFunctionKeys';
import type { CommandKey } from '../keyboard/useFunctionKeys';
import { Dialog } from './Dialog';

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

/**
 * The name a spec uses for a focused element: its `aria-label`, else its text.
 * `<body>` and a `<dialog>` element get a marker of their own, so a failure
 * message shows where focus went.
 */
function nameOf(element: Element): string {
  const label = element.getAttribute('aria-label');
  if (label !== null) {
    return label;
  }
  if (element === element.ownerDocument.body) {
    return '<body>';
  }
  if (element.localName === 'dialog') {
    return `<dialog ${element.getAttribute('aria-labelledby') ?? ''}>`;
  }
  return element.textContent?.trim() ?? '';
}

/**
 * Presses Tab (Shift+Tab with `shift`) `count` times and returns the element
 * focused after each press.
 */
async function tabFrom(user: UserEvent, count: number, shift = false): Promise<Element[]> {
  const visited: Element[] = [];
  for (let press = 0; press < count; press += 1) {
    await user.tab({ shift });
    visited.push(document.activeElement ?? document.body);
  }
  return visited;
}

/**
 * The accessible name a browser gives `dialog` through its `aria-labelledby`:
 * the text of each target, in order, joined by a space. A target inside an
 * `inert` subtree contributes nothing, because browsers leave inert content
 * out of the accessibility tree, name computation included.
 */
function browserNameOf(dialog: HTMLElement): string {
  return (dialog.getAttribute('aria-labelledby') ?? '')
    .split(/\s+/)
    .filter((id) => id !== '')
    .map((id) => document.getElementById(id))
    .filter((label): label is HTMLElement => label !== null && label.closest('[inert]') === null)
    .map((label) => label.textContent?.trim() ?? '')
    .join(' ');
}

/** The backdrop `Dialog` renders immediately before its `<dialog>` element. */
function backdropOf(dialog: HTMLElement): Element {
  const backdrop = dialog.previousElementSibling;
  if (backdrop === null || !backdrop.classList.contains('dialog__backdrop')) {
    throw new Error('the window has no backdrop before it');
  }
  return backdrop;
}

// ---------------------------------------------------------------------------
// Harnesses
// ---------------------------------------------------------------------------

/**
 * A page laid out as the application lays out a screen: focusable page
 * controls (button, link, input) and the invoking button before the window, a
 * section the page made inert itself, the toast host with its two live
 * regions (as `ToastRegion` renders it, outside every window, with a
 * focusable control in the status region), the window, and more page controls
 * after it. The page wrapper is focusable (`tabindex="-1"`), as an ancestor of
 * the window that stays live.
 *
 * The window's stops, in tab order, are Name, Kind, Done, Help and the checked
 * High radio. After High come only controls that are not stops.
 */
function TrapPage(): JSX.Element {
  const [open, setOpen] = useState(false);
  return (
    <div data-testid="page" tabIndex={-1}>
      <header data-testid="page-header">
        <button type="button">Background button</button>
        <a href="#background">Background link</a>
        <input aria-label="Background field" />
        <button type="button" onClick={() => setOpen(true)}>
          Open window
        </button>
      </header>
      <section data-testid="page-inert" inert>
        <button type="button">Already inert</button>
      </section>
      <div className="toast-region" data-testid="toast-host">
        <div role="status" aria-live="polite">
          <button type="button">Status action</button>
        </div>
        <div role="alert" aria-live="assertive" />
      </div>
      <Dialog open={open} labelledBy="trap-title">
        <h2 id="trap-title">Trap window</h2>
        <input aria-label="Name" />
        <select aria-label="Kind" defaultValue="one">
          <option value="one">One</option>
          <option value="two">Two</option>
        </select>
        <button type="button" onClick={() => setOpen(false)}>
          Done
        </button>
        <a href="#help">Help</a>
        <input type="radio" name="priority" aria-label="High" defaultChecked />
        <input type="radio" name="priority" aria-label="Low" />
        <button type="button" disabled>
          Disabled action
        </button>
        <button type="button" tabIndex={-1}>
          Programmatic only
        </button>
        <button type="button" style={{ display: 'none' }}>
          Hidden by CSS
        </button>
        <div hidden>
          <button type="button">Hidden subtree</button>
        </div>
        <fieldset disabled>
          <input aria-label="Disabled fieldset field" />
        </fieldset>
      </Dialog>
      <footer data-testid="page-footer">
        <button type="button">After button</button>
        <input aria-label="After field" />
      </footer>
    </div>
  );
}

/** Renders {@link TrapPage}, opens its window with a click and returns the window. */
async function openTrapWindow(user: UserEvent): Promise<HTMLElement> {
  render(<TrapPage />);
  await user.click(screen.getByRole('button', { name: 'Open window' }));
  return screen.getByRole('dialog', { name: 'Trap window' });
}

/**
 * Two stacked windows, as the State picker sits inside the detail window: the
 * inner `Dialog` is rendered among the outer window's children, between the
 * outer fields (with the Prompt button that opens it) and the outer keys.
 * Page controls lie before and after the outer window.
 */
function NestedPage(): JSX.Element {
  const [outerOpen, setOuterOpen] = useState(false);
  const [innerOpen, setInnerOpen] = useState(false);
  return (
    <div data-testid="nested-page">
      <header data-testid="nested-header">
        <button type="button">Page button</button>
        <button type="button" onClick={() => setOuterOpen(true)}>
          Open outer
        </button>
      </header>
      <Dialog open={outerOpen} labelledBy="outer-title">
        <h2 id="outer-title">Outer window</h2>
        <div data-testid="outer-fields">
          <input aria-label="Outer name" />
          <input aria-label="Outer state" />
          <button type="button" onClick={() => setInnerOpen(true)}>
            Prompt
          </button>
        </div>
        <Dialog open={innerOpen} labelledBy="inner-title">
          <h3 id="inner-title">Inner window</h3>
          <input aria-label="Filter" />
          <button type="button">Select</button>
          <button type="button" onClick={() => setInnerOpen(false)}>
            Cancel
          </button>
        </Dialog>
        <div data-testid="outer-keys">
          <button type="button">Outer save</button>
          <button type="button" onClick={() => setOuterOpen(false)}>
            Outer close
          </button>
        </div>
      </Dialog>
      <footer data-testid="nested-footer">
        <button type="button">Page after</button>
      </footer>
    </div>
  );
}

/**
 * Renders {@link NestedPage}, opens the outer window and then, from its Prompt
 * button, the inner window, and returns both windows and the Prompt button.
 */
async function openNestedWindows(
  user: UserEvent,
): Promise<{ outer: HTMLElement; inner: HTMLElement; prompt: HTMLElement }> {
  render(<NestedPage />);
  await user.click(screen.getByRole('button', { name: 'Open outer' }));
  const outer = screen.getByRole('dialog', { name: 'Outer window' });
  expect(within(outer).getByRole('textbox', { name: 'Outer name' })).toHaveFocus();
  const prompt = within(outer).getByRole('button', { name: 'Prompt' });
  await user.click(prompt);
  const inner = screen.getByRole('dialog', { name: 'Inner window' });
  return { outer, inner, prompt };
}

/**
 * Two stacked windows laid out as the detail window lays out its content
 * (`div.customer-detail > header.screen-header > h1`): the outer window's
 * labels, a title and a function line, sit in a header beside a user line,
 * and that header sits in a focusable body wrapper beside the outer fields
 * and keys. The inner window is rendered beside the wrapper, as the State
 * picker is. "Add note" in the inner window adds an element inside the
 * wrapper while the inner window stays open.
 */
function WrappedNestedPage(): JSX.Element {
  const [outerOpen, setOuterOpen] = useState(false);
  const [innerOpen, setInnerOpen] = useState(false);
  const [noteShown, setNoteShown] = useState(false);
  return (
    <div>
      <button type="button" onClick={() => setOuterOpen(true)}>
        Open wrapped
      </button>
      <Dialog open={outerOpen} labelledBy="wrapped-title wrapped-function">
        <div data-testid="wrapped-body" tabIndex={-1}>
          <header data-testid="wrapped-header">
            <h2 id="wrapped-title">Customer Master</h2>
            <p id="wrapped-function">Change Customer</p>
            <p data-testid="wrapped-user">SALES</p>
          </header>
          <div data-testid="wrapped-fields">
            <input aria-label="Wrapped name" />
            <button type="button" onClick={() => setInnerOpen(true)}>
              State prompt
            </button>
          </div>
          {noteShown ? <p data-testid="wrapped-note">Note</p> : null}
          <div data-testid="wrapped-keys">
            <button type="button" onClick={() => setOuterOpen(false)}>
              Wrapped close
            </button>
          </div>
        </div>
        <Dialog open={innerOpen} labelledBy="wrapped-inner-title">
          <h3 id="wrapped-inner-title">USA States</h3>
          <input aria-label="Name Contains" />
          <button type="button" onClick={() => setNoteShown(true)}>
            Add note
          </button>
          <button type="button" onClick={() => setInnerOpen(false)}>
            Cancel
          </button>
        </Dialog>
      </Dialog>
    </div>
  );
}

/** Props of {@link SelfFocusingField}. */
type SelfFocusingFieldProps = {
  /** Receives the element that has focus right after the field focused itself. */
  onSelfFocus: (focused: Element | null) => void;
};

/**
 * A child that focuses its own field from its mount effect, which runs before
 * the window's effects, as the detail form focuses a field from its own
 * effect.
 */
function SelfFocusingField({ onSelfFocus }: SelfFocusingFieldProps): JSX.Element {
  const fieldRef = useRef<HTMLInputElement>(null);
  useEffect(() => {
    fieldRef.current?.focus();
    onSelfFocus(document.activeElement);
  }, [onSelfFocus]);
  return <input aria-label="Phone" ref={fieldRef} />;
}

/** Props of {@link ReturnPage}. */
type ReturnPageProps = {
  /** Passes the Remarks field to the window as its `initialFocusRef`. */
  preferRemarks?: boolean;
  /** Forwarded to {@link SelfFocusingField}. */
  onSelfFocus: (focused: Element | null) => void;
};

/**
 * A page whose window opens from the "Open details" button. The window's
 * first tab stop is a button and its second a read-only field, so the first
 * editable field (Name) is neither; a child then focuses Phone on mount.
 * "Remove opener" takes the invoking button out of the document while the
 * window stays open.
 */
function ReturnPage({ preferRemarks = false, onSelfFocus }: ReturnPageProps): JSX.Element {
  const [open, setOpen] = useState(false);
  const [openerShown, setOpenerShown] = useState(true);
  const remarksRef = useRef<HTMLTextAreaElement>(null);
  return (
    <div>
      <button type="button">Elsewhere</button>
      {openerShown ? (
        <button type="button" onClick={() => setOpen(true)}>
          Open details
        </button>
      ) : null}
      <Dialog
        open={open}
        labelledBy="details-title"
        initialFocusRef={preferRemarks ? remarksRef : undefined}
      >
        <h2 id="details-title">Details</h2>
        <button type="button">Help</button>
        <input aria-label="Customer id" readOnly defaultValue="AAAG" />
        <input aria-label="Name" />
        <SelfFocusingField onSelfFocus={onSelfFocus} />
        <textarea aria-label="Remarks" ref={remarksRef} />
        <button type="button" onClick={() => setOpenerShown(false)}>
          Remove opener
        </button>
        <button type="button" onClick={() => setOpen(false)}>
          Close
        </button>
      </Dialog>
    </div>
  );
}

/** Props of {@link KeyedWindowBody} and {@link KeyedPage}. */
type KeyedProps = {
  /** The scope's answer to a function key it does not bind. */
  onUnbound: (key: CommandKey) => void;
};

/**
 * Window content that registers its own key scope, as every screen does: F12
 * (and so Escape) closes the window.
 */
function KeyedWindowBody({ onUnbound, onClose }: KeyedProps & { onClose: () => void }): JSX.Element {
  useFunctionKeys({ F12: onClose }, { onUnbound });
  return (
    <>
      <h2 id="keyed-title">Keyed window</h2>
      <input aria-label="Filter" />
      <button type="button">Apply</button>
      <button type="button">Clear</button>
    </>
  );
}

/**
 * A page under the real `KeyScopeProvider`, with page controls before and
 * after a window whose content registers the window's key scope. The window
 * opens on a click, after the provider is mounted, as screens open in the
 * application.
 */
function KeyedPage({ onUnbound }: KeyedProps): JSX.Element {
  const [open, setOpen] = useState(false);
  return (
    <KeyScopeProvider>
      <button type="button">Page before</button>
      <button type="button" onClick={() => setOpen(true)}>
        Open keyed window
      </button>
      <Dialog open={open} labelledBy="keyed-title">
        <KeyedWindowBody onUnbound={onUnbound} onClose={() => setOpen(false)} />
      </Dialog>
      <button type="button">Page after</button>
    </KeyScopeProvider>
  );
}

// ---------------------------------------------------------------------------
// Specs
// ---------------------------------------------------------------------------

describe('Dialog focus trap', () => {
  it('Tab on the last reachable stop wraps to the first stop, past the controls after it that are not stops', async () => {
    const user = userEvent.setup();
    const dialog = await openTrapWindow(user);

    // The controls after High are in the window and none of them is a stop.
    expect(within(dialog).getByRole('radio', { name: 'Low' })).not.toBeChecked();
    expect(within(dialog).getByRole('button', { name: 'Disabled action' })).toBeDisabled();
    expect(within(dialog).getByRole('button', { name: 'Programmatic only' })).toHaveAttribute('tabindex', '-1');
    expect(within(dialog).getByText('Hidden by CSS')).not.toBeVisible();
    expect(within(dialog).getByText('Hidden subtree')).not.toBeVisible();
    expect(within(dialog).getByRole('textbox', { name: 'Disabled fieldset field' })).toBeDisabled();

    const high = within(dialog).getByRole('radio', { name: 'High' });
    act(() => {
      high.focus();
    });
    await user.tab();

    expect(within(dialog).getByRole('textbox', { name: 'Name' })).toHaveFocus();
  });

  it('a full Tab cycle visits only the window stops, in tab order, and never a page control', async () => {
    const user = userEvent.setup();
    const dialog = await openTrapWindow(user);
    expect(within(dialog).getByRole('textbox', { name: 'Name' })).toHaveFocus();

    const visited = await tabFrom(user, 10);

    expect(visited.map(nameOf)).toEqual([
      'Kind',
      'Done',
      'Help',
      'High',
      'Name',
      'Kind',
      'Done',
      'Help',
      'High',
      'Name',
    ]);
    expect(visited.filter((element) => !dialog.contains(element)).map(nameOf)).toEqual([]);
  });

  it('Shift+Tab on the first stop wraps to the last reachable stop', async () => {
    const user = userEvent.setup();
    const dialog = await openTrapWindow(user);
    expect(within(dialog).getByRole('textbox', { name: 'Name' })).toHaveFocus();

    await user.tab({ shift: true });

    expect(within(dialog).getByRole('radio', { name: 'High' })).toHaveFocus();
  });

  it('a full Shift+Tab cycle visits only the window stops, in reverse tab order, and never a page control', async () => {
    const user = userEvent.setup();
    const dialog = await openTrapWindow(user);

    const visited = await tabFrom(user, 10, true);

    expect(visited.map(nameOf)).toEqual([
      'High',
      'Help',
      'Done',
      'Kind',
      'Name',
      'High',
      'Help',
      'Done',
      'Kind',
      'Name',
    ]);
    expect(visited.filter((element) => !dialog.contains(element)).map(nameOf)).toEqual([]);
  });
});

describe('Dialog background containment', () => {
  it('makes everything beside the window inert except its backdrop and the live regions, and on close releases only what it made inert', async () => {
    const user = userEvent.setup();
    render(<TrapPage />);
    const header = screen.getByTestId('page-header');
    const footer = screen.getByTestId('page-footer');
    const pageInert = screen.getByTestId('page-inert');
    const toastHost = screen.getByTestId('toast-host');
    const status = screen.getByRole('status');
    const alert = screen.getByRole('alert');
    expect(header).not.toHaveAttribute('inert');
    expect(footer).not.toHaveAttribute('inert');
    expect(pageInert).toHaveAttribute('inert');

    await user.click(screen.getByRole('button', { name: 'Open window' }));
    const dialog = screen.getByRole('dialog', { name: 'Trap window' });

    expect(dialog).toBeInstanceOf(HTMLDialogElement);
    expect(dialog).toHaveAttribute('aria-modal', 'true');
    expect(header).toHaveAttribute('inert');
    expect(footer).toHaveAttribute('inert');
    expect(pageInert).toHaveAttribute('inert');
    expect(toastHost).not.toHaveAttribute('inert');
    expect(status).not.toHaveAttribute('inert');
    expect(alert).not.toHaveAttribute('inert');
    expect(dialog).not.toHaveAttribute('inert');
    expect(backdropOf(dialog)).not.toHaveAttribute('inert');
    expect(screen.getByTestId('page')).not.toHaveAttribute('inert');

    await user.click(within(dialog).getByRole('button', { name: 'Done' }));

    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(header).not.toHaveAttribute('inert');
    expect(footer).not.toHaveAttribute('inert');
    expect(pageInert).toHaveAttribute('inert');
    expect(screen.getByRole('button', { name: 'Open window' })).toHaveFocus();
  });

  it('moves focus that script puts on a live element beneath back to the element last focused in the window', async () => {
    const user = userEvent.setup();
    const dialog = await openTrapWindow(user);
    await user.tab();
    const kind = within(dialog).getByRole('combobox', { name: 'Kind' });
    expect(kind).toHaveFocus();

    // A focusable control inside the live status region, which stays live.
    act(() => {
      screen.getByRole('button', { name: 'Status action' }).focus();
    });
    expect(kind).toHaveFocus();

    // The focusable page wrapper, an ancestor of the window, which stays live.
    act(() => {
      screen.getByTestId('page').focus();
    });
    expect(kind).toHaveFocus();
  });
});

describe('Dialog nested in another Dialog', () => {
  it('the inner window takes focus and wraps Tab and Shift+Tab at its own ends, never reaching an outer control', async () => {
    const user = userEvent.setup();
    const { inner } = await openNestedWindows(user);
    expect(within(inner).getByRole('textbox', { name: 'Filter' })).toHaveFocus();

    const forward = await tabFrom(user, 6);
    const backward = await tabFrom(user, 6, true);

    expect(forward.map(nameOf)).toEqual(['Select', 'Cancel', 'Filter', 'Select', 'Cancel', 'Filter']);
    expect(backward.map(nameOf)).toEqual(['Cancel', 'Select', 'Filter', 'Cancel', 'Select', 'Filter']);
    expect([...forward, ...backward].filter((element) => !inner.contains(element)).map(nameOf)).toEqual([]);
  });

  it('makes the outer content inert while the outer dialog element and its label stay live, so both windows keep their names, and moves focus on the outer dialog back into the inner window', async () => {
    const user = userEvent.setup();
    const { outer, inner } = await openNestedWindows(user);
    const heading = within(outer).getByRole('heading', { name: 'Outer window' });

    expect(heading).not.toHaveAttribute('inert');
    expect(screen.getByRole('dialog', { name: 'Outer window' })).toBe(outer);
    expect(screen.getByRole('dialog', { name: 'Inner window' })).toBe(inner);
    expect(browserNameOf(outer)).toBe('Outer window');
    expect(browserNameOf(inner)).toBe('Inner window');
    expect(screen.getByTestId('outer-fields')).toHaveAttribute('inert');
    expect(screen.getByTestId('outer-keys')).toHaveAttribute('inert');
    expect(backdropOf(outer)).toHaveAttribute('inert');
    expect(screen.getByTestId('nested-header')).toHaveAttribute('inert');
    expect(screen.getByTestId('nested-footer')).toHaveAttribute('inert');
    expect(outer).not.toHaveAttribute('inert');
    expect(inner).not.toHaveAttribute('inert');
    expect(backdropOf(inner)).not.toHaveAttribute('inert');

    await user.tab();
    const select = within(inner).getByRole('button', { name: 'Select' });
    expect(select).toHaveFocus();

    // The outer <dialog> carries tabindex="-1" and is a live ancestor.
    act(() => {
      outer.focus();
    });
    expect(select).toHaveFocus();
  });

  it('stops a click on the live outer label before it reaches the outer window, and focus stays in the inner window', async () => {
    const user = userEvent.setup();
    const { outer, inner } = await openNestedWindows(user);
    const heading = within(outer).getByRole('heading', { name: 'Outer window' });
    await user.tab();
    const select = within(inner).getByRole('button', { name: 'Select' });
    expect(select).toHaveFocus();
    const outerClick = vi.fn();
    outer.addEventListener('click', outerClick);

    // A pointer press focuses the outer <dialog>, the label's focusable
    // ancestor, and focus goes straight back into the inner window.
    await user.click(heading);
    expect(select).toHaveFocus();
    expect(outerClick).not.toHaveBeenCalled();

    // A click dispatched by script is stopped and its default action prevented.
    expect(fireEvent.click(heading)).toBe(false);
    expect(outerClick).not.toHaveBeenCalled();
    expect(select).toHaveFocus();
    expect(browserNameOf(outer)).toBe('Outer window');

    outer.removeEventListener('click', outerClick);
  });

  it('walks the wrapper and header around the outer labels, so the labels stay live while the other content there, and content added there meanwhile, becomes inert', async () => {
    const user = userEvent.setup();
    render(<WrappedNestedPage />);
    await user.click(screen.getByRole('button', { name: 'Open wrapped' }));
    const outer = screen.getByRole('dialog', { name: 'Customer Master Change Customer' });
    const prompt = within(outer).getByRole('button', { name: 'State prompt' });
    await user.click(prompt);
    const inner = screen.getByRole('dialog', { name: 'USA States' });
    const filter = within(inner).getByRole('textbox', { name: 'Name Contains' });
    expect(filter).toHaveFocus();
    const wrapper = screen.getByTestId('wrapped-body');

    expect(wrapper).not.toHaveAttribute('inert');
    expect(screen.getByTestId('wrapped-header')).not.toHaveAttribute('inert');
    expect(within(outer).getByRole('heading', { name: 'Customer Master' })).not.toHaveAttribute('inert');
    expect(within(outer).getByText('Change Customer')).not.toHaveAttribute('inert');
    expect(screen.getByTestId('wrapped-user')).toHaveAttribute('inert');
    expect(screen.getByTestId('wrapped-fields')).toHaveAttribute('inert');
    expect(screen.getByTestId('wrapped-keys')).toHaveAttribute('inert');
    expect(backdropOf(outer)).toHaveAttribute('inert');
    expect(outer).not.toHaveAttribute('inert');
    expect(inner).not.toHaveAttribute('inert');
    expect(backdropOf(inner)).not.toHaveAttribute('inert');
    expect(browserNameOf(outer)).toBe('Customer Master Change Customer');
    expect(browserNameOf(inner)).toBe('USA States');

    // The walked wrapper stays live and carries tabindex="-1".
    act(() => {
      wrapper.focus();
    });
    expect(filter).toHaveFocus();

    await user.click(within(inner).getByRole('button', { name: 'Add note' }));
    expect(screen.getByTestId('wrapped-note')).toHaveAttribute('inert');
    expect(browserNameOf(outer)).toBe('Customer Master Change Customer');

    await user.click(within(inner).getByRole('button', { name: 'Cancel' }));

    expect(screen.queryByRole('dialog', { name: 'USA States' })).not.toBeInTheDocument();
    expect(prompt).toHaveFocus();
    const released = ['body', 'header', 'user', 'fields', 'note', 'keys'].map((part) =>
      screen.getByTestId(`wrapped-${part}`),
    );
    for (const element of released) {
      expect(element).not.toHaveAttribute('inert');
    }
    expect(backdropOf(outer)).not.toHaveAttribute('inert');
    expect(browserNameOf(outer)).toBe('Customer Master Change Customer');
  });

  it('closing the inner window returns focus to its invoker, releases its inert and hands the trap back to the outer window', async () => {
    const user = userEvent.setup();
    const { outer, inner, prompt } = await openNestedWindows(user);

    await user.click(within(inner).getByRole('button', { name: 'Cancel' }));

    expect(screen.queryByRole('dialog', { name: 'Inner window' })).not.toBeInTheDocument();
    expect(prompt).toHaveFocus();
    const heading = within(outer).getByRole('heading', { name: 'Outer window' });
    expect(heading).not.toHaveAttribute('inert');
    expect(screen.getByTestId('outer-fields')).not.toHaveAttribute('inert');
    expect(screen.getByTestId('outer-keys')).not.toHaveAttribute('inert');
    expect(backdropOf(outer)).not.toHaveAttribute('inert');
    expect(screen.getByTestId('nested-header')).toHaveAttribute('inert');
    expect(screen.getByTestId('nested-footer')).toHaveAttribute('inert');
    expect(browserNameOf(outer)).toBe('Outer window');

    // The label's click guard went with the inner window.
    const outerClick = vi.fn();
    outer.addEventListener('click', outerClick);
    expect(fireEvent.click(heading)).toBe(true);
    expect(outerClick).toHaveBeenCalledTimes(1);
    outer.removeEventListener('click', outerClick);
    expect(prompt).toHaveFocus();

    const forward = await tabFrom(user, 4);
    const backward = await tabFrom(user, 4, true);

    expect(forward.map(nameOf)).toEqual(['Outer save', 'Outer close', 'Outer name', 'Outer state']);
    expect(backward.map(nameOf)).toEqual(['Outer name', 'Outer close', 'Outer save', 'Prompt']);
    expect([...forward, ...backward].filter((element) => !outer.contains(element)).map(nameOf)).toEqual([]);

    await user.click(within(outer).getByRole('button', { name: 'Outer close' }));

    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Open outer' })).toHaveFocus();
    expect(screen.getByTestId('nested-header')).not.toHaveAttribute('inert');
    expect(screen.getByTestId('nested-footer')).not.toHaveAttribute('inert');
  });
});

describe('Dialog initial focus and focus return', () => {
  it('focuses the first editable field after a child focused its own field on mount, and closing returns focus to the invoking button', async () => {
    const user = userEvent.setup();
    const onSelfFocus = vi.fn<(focused: Element | null) => void>();
    render(<ReturnPage onSelfFocus={onSelfFocus} />);
    const opener = screen.getByRole('button', { name: 'Open details' });

    await user.click(opener);
    const dialog = screen.getByRole('dialog', { name: 'Details' });

    expect(onSelfFocus).toHaveBeenCalledTimes(1);
    expect(onSelfFocus.mock.calls[0]?.[0]).toBe(within(dialog).getByRole('textbox', { name: 'Phone' }));
    expect(within(dialog).getByRole('textbox', { name: 'Name' })).toHaveFocus();

    await user.click(within(dialog).getByRole('button', { name: 'Close' }));

    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(opener).toHaveFocus();
  });

  it('focuses initialFocusRef over the first editable field and the self-focused child, and closing returns focus to the invoking button', async () => {
    const user = userEvent.setup();
    const onSelfFocus = vi.fn<(focused: Element | null) => void>();
    render(<ReturnPage preferRemarks onSelfFocus={onSelfFocus} />);
    const opener = screen.getByRole('button', { name: 'Open details' });

    await user.click(opener);
    const dialog = screen.getByRole('dialog', { name: 'Details' });

    expect(onSelfFocus).toHaveBeenCalledTimes(1);
    expect(within(dialog).getByRole('textbox', { name: 'Remarks' })).toHaveFocus();

    await user.click(within(dialog).getByRole('button', { name: 'Close' }));

    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(opener).toHaveFocus();
  });

  it('closing after the invoker left the document does not throw and focuses nothing stale', async () => {
    const user = userEvent.setup();
    const onSelfFocus = vi.fn<(focused: Element | null) => void>();
    render(<ReturnPage onSelfFocus={onSelfFocus} />);
    const opener = screen.getByRole('button', { name: 'Open details' });
    await user.click(opener);
    const dialog = screen.getByRole('dialog', { name: 'Details' });

    await user.click(within(dialog).getByRole('button', { name: 'Remove opener' }));
    expect(opener).not.toBeInTheDocument();
    await user.click(within(dialog).getByRole('button', { name: 'Close' }));

    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(document.activeElement).toBe(document.body);
    expect(screen.getByRole('button', { name: 'Elsewhere' })).not.toHaveFocus();
  });
});

describe('Dialog under the keyboard scope', () => {
  it('Tab and Shift+Tab still wrap at both ends of a window whose content registers a key scope', async () => {
    const user = userEvent.setup();
    const onUnbound = vi.fn<(key: CommandKey) => void>();
    render(<KeyedPage onUnbound={onUnbound} />);
    await user.click(screen.getByRole('button', { name: 'Open keyed window' }));
    const dialog = screen.getByRole('dialog', { name: 'Keyed window' });
    expect(within(dialog).getByRole('textbox', { name: 'Filter' })).toHaveFocus();

    const forward = await tabFrom(user, 4);
    const backward = await tabFrom(user, 4, true);

    expect(forward.map(nameOf)).toEqual(['Apply', 'Clear', 'Filter', 'Apply']);
    expect(backward.map(nameOf)).toEqual(['Filter', 'Clear', 'Apply', 'Filter']);
    expect([...forward, ...backward].filter((element) => !dialog.contains(element)).map(nameOf)).toEqual([]);
    expect(onUnbound).not.toHaveBeenCalled();
  });

  it('F12 and Escape close the window through its scope, and focus returns to the invoker each time', async () => {
    const user = userEvent.setup();
    const onUnbound = vi.fn<(key: CommandKey) => void>();
    render(<KeyedPage onUnbound={onUnbound} />);
    const opener = screen.getByRole('button', { name: 'Open keyed window' });

    await user.click(opener);
    const dialog = screen.getByRole('dialog', { name: 'Keyed window' });
    await user.tab();
    expect(within(dialog).getByRole('button', { name: 'Apply' })).toHaveFocus();
    await user.keyboard('{F12}');

    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(opener).toHaveFocus();

    await user.click(opener);
    expect(within(screen.getByRole('dialog', { name: 'Keyed window' })).getByRole('textbox', { name: 'Filter' })).toHaveFocus();
    await user.keyboard('{Escape}');

    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(opener).toHaveFocus();
    expect(onUnbound).not.toHaveBeenCalled();
  });
});
