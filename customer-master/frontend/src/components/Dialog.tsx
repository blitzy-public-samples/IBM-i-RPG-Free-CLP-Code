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
 * - a page beneath that cannot be keyed: while a window is the topmost one
 *   open, everything outside it except its own backdrop, the windows it is
 *   nested in and live regions (the shared toast host's among them) is
 *   `inert`; clicks dispatched beneath anyway (access keys, `click()`), live
 *   regions included, are stopped; and focus that still reaches something
 *   beneath that stays live (an enclosing window, a focusable wrapper, a
 *   control in a live region) is moved back into the window. The page's own
 *   inert state is restored when it closes;
 * - focus moved into the window when it opens, a Tab / Shift+Tab focus trap
 *   while it is open, and focus returned to the invoking element when it
 *   closes. Focus targets and the ends of the trap are the controls a user
 *   can actually reach: focusable (natively, as an editing host, or through
 *   a valid `tabindex`), enabled, rendered (not hidden by CSS, a `hidden` or
 *   `inert` subtree or a closed `details`), owned by this window rather than
 *   a nested one, and, for radios, the checked radio of a group, or every
 *   radio of a group with none checked, at its own place in the tab order.
 *
 * Because the keyboard scope stack never moves focus (KeyScopeProvider), this
 * component is the only place where focus enters and leaves a window, so
 * focus and key handling return to the screen beneath together.
 *
 * What it deliberately does not do:
 *
 * - It never calls `showModal()`. A top-layer dialog would paint above the
 *   single shared toast host (ToastRegion, mounted once outside every dialog)
 *   and make it inert, so this component applies `inert` itself and spares
 *   the toast host. Stacking comes from CSS instead: the backdrop sits at
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
   * Element to focus when the window opens. It is used when it lies in this
   * window (not in a window nested inside it) and is enabled, rendered and
   * focusable; a `tabindex="-1"` heading qualifies. Otherwise, without it,
   * or when it does not actually take focus, focus goes to the first
   * editable field in tab order, else the first tab stop, else the window
   * itself.
   */
  initialFocusRef?: RefObject<HTMLElement | null>;
  /** Window content: header, fields, function-key bar, nested windows. */
  children: ReactNode;
};

/**
 * Elements that may take focus: the natively focusable ones, editing hosts
 * and any element that carries a `tabindex`. Matching is necessary but not
 * sufficient; see {@link isFocusable} and {@link isFocusTarget}.
 */
const FOCUSABLE_SELECTOR = [
  'a[href]',
  'area[href]',
  'button',
  'input:not([type="hidden"])',
  'select',
  'textarea',
  'iframe',
  'audio[controls]',
  'video[controls]',
  'summary',
  '[contenteditable]',
  '[tabindex]',
].join(', ');

/**
 * A `tabindex` value the HTML rules for parsing integers accept: optional
 * leading whitespace, an optional sign, then a digit. Browsers ignore any
 * other value, so `<div tabindex="invalid">` is not focusable at all.
 */
const VALID_TABINDEX = /^[\t\n\f\r ]*[+-]?\d/;

/** The `tabindex` of `element` as browsers parse it, or null when it has no valid one. */
function parsedTabIndex(element: Element): number | null {
  const value = element.getAttribute('tabindex');
  return value !== null && VALID_TABINDEX.test(value) ? Number.parseInt(value, 10) : null;
}

/**
 * The editability `element` declares itself: true for `contenteditable`
 * `""`, `"true"` or `"plaintext-only"`, false for `"false"`, and null, which
 * inherits the parent's, without the attribute or with any other value. The
 * attribute is read because jsdom has no `isContentEditable`.
 */
function declaredEditable(element: Element): boolean | null {
  const value = element.getAttribute('contenteditable');
  if (value === null) {
    return null;
  }
  const state = value.toLowerCase();
  if (state === '' || state === 'true' || state === 'plaintext-only') {
    return true;
  }
  return state === 'false' ? false : null;
}

/** True when `element` is editable content, by its own declaration or the nearest ancestor's. */
function isEditableContent(element: Element | null): boolean {
  for (let node = element; node !== null; node = node.parentElement) {
    const declared = declaredEditable(node);
    if (declared !== null) {
      return declared;
    }
  }
  return false;
}

/**
 * True for an editing host: an element its own `contenteditable` makes
 * editable inside a parent that is not. Editable content nested in a host
 * belongs to the host's single tab stop.
 */
function isEditingHost(element: Element): boolean {
  return declaredEditable(element) === true && !isEditableContent(element.parentElement);
}

/** The summary of a `details` element: its first `summary` child, if any. */
function summaryOf(details: Element): Element | null {
  return Array.from(details.children).find((child) => child.localName === 'summary') ?? null;
}

/**
 * True when `element` can take focus at all: it carries a valid `tabindex`
 * ({@link parsedTabIndex}), is an editing host, or is natively focusable: a
 * link or image-map area with `href`, a button, an input other than a
 * hidden one, a select, a textarea, an iframe, audio or video with
 * controls, or the summary of its parent `details`. A hidden input never
 * takes focus, whatever its `tabindex`.
 */
function isFocusable(element: HTMLElement): boolean {
  if (element instanceof HTMLInputElement && element.type === 'hidden') {
    return false;
  }
  if (parsedTabIndex(element) !== null || isEditingHost(element)) {
    return true;
  }
  switch (element.localName) {
    case 'a':
    case 'area':
      return element.hasAttribute('href');
    case 'button':
    case 'input':
    case 'select':
    case 'textarea':
    case 'iframe':
      return true;
    case 'audio':
    case 'video':
      return element.hasAttribute('controls');
    case 'summary': {
      const parent = element.parentElement;
      return parent !== null && parent.localName === 'details' && summaryOf(parent) === element;
    }
    default:
      return false;
  }
}

/**
 * True when `element` lies in the content of a closed `details`, which is not
 * rendered. The summary of a closed `details`, and anything inside that
 * summary, stays rendered unless an outer closed `details` hides it in turn.
 */
function inClosedDetails(element: HTMLElement): boolean {
  let details = element.closest('details:not([open])');
  while (details !== null) {
    const summary = summaryOf(details);
    if (summary === null || !summary.contains(element)) {
      return true;
    }
    details = details.parentElement?.closest('details:not([open])') ?? null;
  }
  return false;
}

/**
 * True when `element` is rendered and visible, so `focus()` can reach it.
 * Where the engine has `checkVisibility()` it decides, `content-visibility`
 * included. Otherwise (jsdom) the computed style decides: no
 * `display: none` on the element or on any ancestor up to `dialog`, and the
 * element's own `visibility` neither `hidden` nor `collapse`. Box-based tests
 * such as `getClientRects()` are not used, because an engine without layout
 * reports no boxes for any element.
 */
function isRendered(element: HTMLElement, dialog: HTMLDialogElement): boolean {
  if (typeof element.checkVisibility === 'function') {
    return element.checkVisibility({ visibilityProperty: true });
  }
  const view = element.ownerDocument.defaultView;
  if (view === null) {
    return false;
  }
  const visibility = view.getComputedStyle(element).visibility;
  if (visibility === 'hidden' || visibility === 'collapse') {
    return false;
  }
  for (let node: Element | null = element; node !== null; node = node.parentElement) {
    if (view.getComputedStyle(node).display === 'none') {
      return false;
    }
    if (node === dialog) {
      break;
    }
  }
  return true;
}

/**
 * The image that draws an image-map area: an `img` whose `usemap` names the
 * area's `map` by name or id. An area has no box of its own.
 */
function imageOfArea(area: HTMLAreaElement): HTMLImageElement | null {
  const map = area.closest('map');
  if (map === null) {
    return null;
  }
  const names = [map.getAttribute('name') ?? '', map.id].filter((name) => name !== '');
  const image = Array.from(area.ownerDocument.querySelectorAll('img[usemap]')).find((candidate) => {
    const usemap = candidate.getAttribute('usemap') ?? '';
    const hash = usemap.indexOf('#');
    return hash !== -1 && names.includes(usemap.slice(hash + 1));
  });
  return image instanceof HTMLImageElement ? image : null;
}

/**
 * True when `element` is shown: outside every `hidden` or `inert` subtree,
 * outside the content of a closed `details`, and not hidden by CSS
 * ({@link isRendered}). An image-map area is shown when the image that draws
 * it is.
 */
function isShown(element: HTMLElement, dialog: HTMLDialogElement): boolean {
  if (element.closest('[hidden], [inert]') !== null || inClosedDetails(element)) {
    return false;
  }
  if (element instanceof HTMLAreaElement) {
    const image = imageOfArea(element);
    return image !== null && isShown(image, dialog);
  }
  return isRendered(element, dialog);
}

/**
 * True when `element` can receive focus from this window. It must belong to
 * `dialog` itself, not to a window nested inside it (a State picker opened
 * over the detail window owns its own focus); be focusable
 * ({@link isFocusable}) and enabled, a disabled fieldset included; and be
 * shown ({@link isShown}). The tab order is not considered here, so a
 * `tabindex="-1"` heading qualifies as a programmatic target;
 * {@link sequentialOrder} narrows to the tab order.
 */
function isFocusTarget(element: Element, dialog: HTMLDialogElement): element is HTMLElement {
  return (
    element instanceof HTMLElement &&
    element.isConnected &&
    element.closest('dialog') === dialog &&
    isFocusable(element) &&
    !element.matches(':disabled') &&
    isShown(element, dialog)
  );
}

/**
 * The place a focus target takes in sequential navigation: its `tabindex`,
 * or 0 for an element focusable without a valid one (a button, an editing
 * host, media with controls). Negative means outside the order.
 */
function sequentialIndex(element: HTMLElement): number {
  return parsedTabIndex(element) ?? 0;
}

/**
 * Sort key of a sequential index: positive values in ascending order, and 0
 * after all of them.
 */
function sequenceKey(element: HTMLElement): number {
  const index = sequentialIndex(element);
  return index > 0 ? index : Number.POSITIVE_INFINITY;
}

/** True for a radio button. */
function isRadio(element: Element): element is HTMLInputElement {
  return element instanceof HTMLInputElement && element.type === 'radio';
}

/**
 * True when `a` and `b` are radios of one group: the same non-empty name, the
 * same form owner and the same tree. Unnamed radios belong to no group.
 */
function sameRadioGroup(a: Element, b: Element): boolean {
  return (
    isRadio(a) &&
    isRadio(b) &&
    a.name !== '' &&
    a.name === b.name &&
    a.form === b.form &&
    a.getRootNode() === b.getRootNode()
  );
}

/**
 * True when `radio` is a stop in sequential navigation. Browsers stop at a
 * radio only when it is checked or no radio of its group is checked, so a
 * group with a checked radio is entered at that radio, wherever it sits in
 * the group, and an unchecked group at each of its radios. The group is
 * looked up in the whole tree, since radios outside the window can share it.
 */
function isRadioStop(radio: HTMLInputElement): boolean {
  if (radio.checked) {
    return true;
  }
  const root = radio.getRootNode();
  const scope = root instanceof ShadowRoot ? root : radio.ownerDocument;
  return !Array.from(scope.querySelectorAll('input')).some(
    (other) => other.checked && sameRadioGroup(radio, other),
  );
}

/**
 * The tab stops of `dialog`, one entry per element, in the order sequential
 * navigation visits them: positive `tabindex` values first, in ascending
 * order, then `tabindex` 0, each in DOM order. Only focus targets
 * ({@link isFocusTarget}) with a non-negative {@link sequentialIndex} count,
 * and of the radios only those that are stops ({@link isRadioStop}), so a
 * CSS-hidden control, the content of a closed `details`, a control taken
 * out of the tab order or an unchecked radio of a checked group never marks
 * either end of the trap.
 */
function sequentialOrder(dialog: HTMLDialogElement): HTMLElement[] {
  const stops = Array.from(dialog.querySelectorAll(FOCUSABLE_SELECTOR)).filter(
    (element): element is HTMLElement =>
      isFocusTarget(element, dialog) &&
      sequentialIndex(element) >= 0 &&
      (!isRadio(element) || isRadioStop(element)),
  );
  return [
    ...stops
      .filter((element) => sequentialIndex(element) > 0)
      .sort((a, b) => sequentialIndex(a) - sequentialIndex(b)),
    ...stops.filter((element) => sequentialIndex(element) === 0),
  ];
}

/**
 * The stops of `order` that sequential navigation reaches before and after
 * `target`, the focused element of `dialog`. A stop splits the order at its
 * own place. A focus target in sequence that is not a stop (an unchecked
 * radio of a checked group) splits it where its `tabindex` and DOM position
 * place it. Any other element (the window itself, a `tabindex="-1"`
 * heading) splits it by DOM position alone, because browsers move from such
 * an element to the next or previous stop in the document.
 */
function splitAround(
  order: HTMLElement[],
  target: Element,
  dialog: HTMLDialogElement,
): { before: HTMLElement[]; after: HTMLElement[] } {
  const index = order.findIndex((stop) => stop === target);
  if (index !== -1) {
    return { before: order.slice(0, index), after: order.slice(index + 1) };
  }
  if (isFocusTarget(target, dialog) && sequentialIndex(target) >= 0) {
    const key = sequenceKey(target);
    const isBefore = (stop: HTMLElement): boolean =>
      sequenceKey(stop) < key || (sequenceKey(stop) === key && precedes(stop, target));
    return { before: order.filter(isBefore), after: order.filter((stop) => !isBefore(stop)) };
  }
  return {
    before: order.filter((stop) => precedes(stop, target)),
    after: order.filter((stop) => follows(stop, target)),
  };
}

/**
 * True for a field the user can type into or change: an input other than a
 * hidden one, a select or a textarea that is enabled and, for inputs and
 * textareas, not read-only; or an editing host. Display mode renders every
 * field read-only, so it has no editable field and focus falls through to
 * the first tab stop.
 */
function isEditable(element: HTMLElement): boolean {
  if (element instanceof HTMLInputElement) {
    return element.type !== 'hidden' && !element.disabled && !element.readOnly;
  }
  if (element instanceof HTMLTextAreaElement) {
    return !element.disabled && !element.readOnly;
  }
  if (element instanceof HTMLSelectElement) {
    return !element.disabled;
  }
  return isEditingHost(element);
}

/**
 * Where focus goes when the window opens, in order of preference: the
 * owner's preferred element when it is a focus target of this window
 * ({@link isFocusTarget}), `tabindex="-1"` included; the first editable
 * field in tab order; the first tab stop; the window itself (it carries
 * `tabIndex={-1}` for exactly this fallback). A preferred element outside
 * the window, disabled, hidden or not focusable at all is passed over rather
 * than focused in vain. Each later entry is the fallback for an earlier one
 * that does not take focus ({@link focusFirst}).
 */
function initialFocusTargets(
  dialog: HTMLDialogElement,
  preferred: HTMLElement | null,
): HTMLElement[] {
  const order = sequentialOrder(dialog);
  const targets = [
    preferred !== null && isFocusTarget(preferred, dialog) ? preferred : undefined,
    order.find(isEditable),
    order[0],
    dialog,
  ];
  return targets.filter(
    (target, index): target is HTMLElement =>
      target !== undefined && targets.indexOf(target) === index,
  );
}

/**
 * Focuses the first of `targets` that actually takes focus. An element that
 * passes every check can still refuse it, for example when a focus handler
 * moves focus on or the engine judges rendering differently, so focus is
 * confirmed through `activeElement` before the next one is tried.
 */
function focusFirst(targets: HTMLElement[]): void {
  for (const target of targets) {
    target.focus();
    if (target.ownerDocument.activeElement === target) {
      return;
    }
  }
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
 * Live regions. Inert content is not announced, so a live region is never
 * made inert: the shared toast host, mounted outside every window, has to
 * keep announcing the messages of the window on top.
 */
const LIVE_REGION_SELECTOR = [
  '[aria-live="polite"]',
  '[aria-live="assertive"]',
  '[role="status"]',
  '[role="alert"]',
  '[role="log"]',
].join(', ');

/** Elements that never render, so making them inert would change nothing. */
const NOT_RENDERED = new Set(['script', 'style', 'template', 'link', 'meta', 'noscript']);

/** An open window, as the modality registry below knows it. */
type OpenWindow = {
  dialog: HTMLDialogElement;
  /** The window's own backdrop, which stays live so a press on it keeps focus. */
  backdrop: HTMLElement | null;
  /**
   * The element inside the window that last received focus while the window
   * was on top: where focus goes back to when something beneath takes it.
   */
  lastFocus: HTMLElement | null;
};

/**
 * Everything beneath the topmost window that isolating it touches:
 * `background`, made inert; `liveRegions`, kept live because inert content
 * is not announced; and `walked`, the containers around those live regions,
 * kept live so the live regions inside them stay live while their other
 * content becomes background.
 */
type Isolation = {
  background: Set<Element>;
  liveRegions: Set<Element>;
  walked: Set<Element>;
};

/**
 * Modality registry, shared by every window on the page: the open windows in
 * the order they registered, the window currently isolated (the topmost),
 * the elements it made inert (and only those), the elements carrying
 * {@link blockActivation}, the elements carrying {@link containFocus}, and
 * the observer that catches background elements added while a window is
 * open.
 */
const openWindows: OpenWindow[] = [];
let isolatedWindow: OpenWindow | undefined;
const madeInert = new Set<Element>();
const guarded = new Set<Element>();
const watched = new Set<Element>();
let backgroundObserver: MutationObserver | null = null;

/**
 * Capture-phase click listener on everything beneath the topmost window:
 * the background, and the live regions and walked containers that stay
 * live. `inert` keeps pointer presses and focus out of the background, but
 * browsers still run the access key of an inert element, script can still
 * call `click()` on one, and nothing at all stops either inside an element
 * that stays live; each dispatches a click, which this stops before it
 * reaches the element's handlers or its default action. A capture listener
 * on `document`, such as the toast host's clear-on-click, runs before this
 * one and still sees the click.
 */
function blockActivation(event: Event): void {
  event.preventDefault();
  event.stopPropagation();
}

/**
 * Capture-phase `focusin` listener on the elements beneath the topmost
 * window that can still take focus because they are never made inert: its
 * ancestors (an enclosing lower `<dialog>`, a focusable wrapper), live
 * regions and the containers walked around them. Focus that lands inside
 * the topmost window is remembered there; focus that lands anywhere else,
 * by script or by an access key, is moved back into it
 * ({@link recoverFocus}).
 */
function containFocus(event: Event): void {
  const top = isolatedWindow;
  const target = event.target;
  if (top === undefined || !top.dialog.isConnected || !(target instanceof Node)) {
    return;
  }
  if (top.dialog.contains(target)) {
    if (target instanceof HTMLElement) {
      top.lastFocus = target;
    }
    return;
  }
  recoverFocus(top);
}

/**
 * Moves focus back into `top`: to the element there that last had focus,
 * while it is still a focus target ({@link isFocusTarget}), else where the
 * window puts focus when it opens without a preferred element.
 */
function recoverFocus(top: OpenWindow): void {
  const last = top.lastFocus;
  const resume = last !== null && isFocusTarget(last, top.dialog) ? [last] : [];
  focusFirst([
    ...resume,
    ...initialFocusTargets(top.dialog, null).filter((target) => target !== last),
  ]);
}

/**
 * Moves `listener`, registered for the capture phase of `type`, off the
 * elements in `current` that are not in `next` and onto those in `next`
 * that lack it, so that `current` ends equal to `next`.
 */
function moveListener(
  current: Set<Element>,
  next: Set<Element>,
  type: string,
  listener: (event: Event) => void,
): void {
  for (const element of Array.from(current)) {
    if (!next.has(element)) {
      element.removeEventListener(type, listener, true);
      current.delete(element);
    }
  }
  for (const element of next) {
    if (!current.has(element)) {
      element.addEventListener(type, listener, true);
      current.add(element);
    }
  }
}

/**
 * The window on top: the most recently registered window still in the
 * document that contains no other open window. A window nested in another
 * (the State picker in the detail window) is on top even when both mount in
 * one commit, where the nested window's effects, and so its registration,
 * run first.
 */
function topmostWindow(): OpenWindow | undefined {
  const connected = openWindows.filter((entry) => entry.dialog.isConnected);
  return connected
    .slice()
    .reverse()
    .find((candidate) =>
      connected.every((other) => other === candidate || !candidate.dialog.contains(other.dialog)),
    );
}

/**
 * Adds `element` to the background of `isolation` unless it never renders
 * or is a live region, which is kept live instead. An element that contains
 * a live region is not taken whole: it is kept live as a walked container,
 * and its children are considered one by one, so everything around the live
 * region still becomes inert.
 */
function collectBackground(element: Element, isolation: Isolation): void {
  if (NOT_RENDERED.has(element.localName)) {
    return;
  }
  if (element.matches(LIVE_REGION_SELECTOR)) {
    isolation.liveRegions.add(element);
    return;
  }
  if (element.querySelector(LIVE_REGION_SELECTOR) === null) {
    isolation.background.add(element);
    return;
  }
  isolation.walked.add(element);
  for (const child of Array.from(element.children)) {
    collectBackground(child, isolation);
  }
}

/**
 * Everything beneath `top`: the elements beside its `<dialog>`, and beside
 * each ancestor of it up to the children of `<body>`, except its own
 * backdrop. No ancestor of the window is ever part of it, so for a window
 * nested in another the outer `<dialog>` stays live while the outer window's
 * other content, its backdrop included, becomes background.
 */
function isolationOf(top: OpenWindow): Isolation {
  const isolation: Isolation = { background: new Set(), liveRegions: new Set(), walked: new Set() };
  const body = top.dialog.ownerDocument.body;
  let node: Element = top.dialog;
  let parent = node.parentElement;
  while (node !== body && parent !== null) {
    for (const sibling of Array.from(parent.children)) {
      if (sibling !== node && sibling !== top.backdrop) {
        collectBackground(sibling, isolation);
      }
    }
    node = parent;
    parent = node.parentElement;
  }
  return isolation;
}

/**
 * The elements that carry {@link containFocus} while `top` is on top: the
 * outermost ancestor of its `<dialog>` below `<body>`, which covers every
 * other ancestor, the window itself and whatever else lies inside it;
 * `<body>` when it can take focus itself; the `<dialog>` when no ancestor
 * covers it; and each live region or walked container that none of these
 * contains. `focusin` bubbles, so an element inside another one listening
 * needs no listener of its own, and inert elements cannot take focus at
 * all.
 */
function focusHostsOf(top: OpenWindow, isolation: Isolation): Set<Element> {
  const body = top.dialog.ownerDocument.body;
  const hosts: Element[] = [top.dialog, ...isolation.liveRegions, ...isolation.walked];
  let outermost: Element | null = null;
  for (let node = top.dialog.parentElement; node !== null && node !== body; node = node.parentElement) {
    outermost = node;
  }
  if (outermost !== null) {
    hosts.push(outermost);
  }
  if (body !== null && isFocusable(body)) {
    hosts.push(body);
  }
  return new Set(hosts.filter((host) => !hosts.some((other) => other !== host && other.contains(host))));
}

/**
 * Makes everything beneath the topmost window inert, guards it and the live
 * regions and walked containers beneath against clicks
 * ({@link blockActivation}), sends focus that reaches anything beneath back
 * into the window ({@link containFocus}), and releases whatever no longer is
 * beneath it. Runs whenever a window registers or unregisters, and whenever
 * an element is added beside the topmost window, beside one of its
 * ancestors or inside a walked container. Only `inert` attributes added
 * here are ever removed, so an element the page made inert itself stays
 * inert, and windows may close in any order.
 */
function isolateTopmostWindow(): void {
  backgroundObserver?.disconnect();
  backgroundObserver = null;

  const top = topmostWindow();
  isolatedWindow = top;
  const isolation: Isolation =
    top === undefined
      ? { background: new Set(), liveRegions: new Set(), walked: new Set() }
      : isolationOf(top);
  const { background, liveRegions, walked } = isolation;
  for (const element of Array.from(madeInert)) {
    if (!background.has(element)) {
      element.removeAttribute('inert');
      madeInert.delete(element);
    }
  }
  for (const element of background) {
    if (!element.hasAttribute('inert')) {
      element.setAttribute('inert', '');
      madeInert.add(element);
    }
  }
  moveListener(guarded, new Set([...background, ...liveRegions, ...walked]), 'click', blockActivation);
  moveListener(
    watched,
    top === undefined ? new Set() : focusHostsOf(top, isolation),
    'focusin',
    containFocus,
  );

  if (top !== undefined && typeof MutationObserver === 'function') {
    const observer = new MutationObserver(isolateTopmostWindow);
    const body = top.dialog.ownerDocument.body;
    for (let node = top.dialog.parentElement; node !== null; node = node.parentElement) {
      observer.observe(node, { childList: true });
      if (node === body) {
        break;
      }
    }
    for (const container of walked) {
      observer.observe(container, { childList: true });
    }
    backgroundObserver = observer;
  }
}

/**
 * Registers an opened window and makes the page beneath it inert. The
 * returned function unregisters it and releases what only it covered; a
 * second call does nothing.
 */
function registerWindow(entry: OpenWindow): () => void {
  openWindows.push(entry);
  isolateTopmostWindow();
  return () => {
    const index = openWindows.indexOf(entry);
    if (index === -1) {
      return;
    }
    openWindows.splice(index, 1);
    isolateTopmostWindow();
  };
}

/**
 * Modal window: backdrop, `<dialog open aria-modal="true">`, an inert page
 * beneath, focus in on open, Tab trap while open, focus return on close.
 *
 * Modality is kept by a registry shared by every open window. While a window
 * is the topmost one open, every element beside it and beside each of its
 * ancestors up to `<body>` carries `inert`, plus a capture-phase click guard
 * for the access keys and `click()` calls that `inert` lets through; an
 * element added beneath while the window is open is covered too. The
 * window's own backdrop, its ancestors (the detail window around a nested
 * State picker) and live regions (the shared toast host's among them) stay
 * live, so nothing in them is made inert: the live regions and the
 * containers around them carry the click guard as well, and the registry
 * listens for `focusin` on these live elements (never on `document` or
 * `window`), remembers the last element focused inside the window, and
 * moves focus back there, or to where the window would put it on opening,
 * whenever it lands beneath. Nothing beneath can therefore keep focus or be
 * activated by pointer, access key or script. When the window closes or
 * unmounts, in whatever order windows close, the registry removes only the
 * `inert`, guards and listeners it added and hands modality to the window
 * beneath, if any, before focus returns to the invoker.
 *
 * Initial focus is owned here. The element that had focus when the window
 * opened (the invoking option field, button or State field) is captured while
 * the window first renders, before any child can move focus, and receives
 * focus again when the window closes or unmounts, provided it is still in the
 * document. After the children's own mount effects have run, the window
 * focuses `initialFocusRef` when that element is a usable target inside it,
 * else its first editable field in tab order, else its first tab stop, else
 * itself, moving down that list whenever an element does not actually take
 * focus, so that choice always wins at open. Owners
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
 *   <ScreenHeader id="state-picker" title="USA States" functionText="" user={username} />
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
  const backdropRef = useRef<HTMLDivElement>(null);

  // The invoker is read during the first render, not in an effect: child
  // effects run before this component's effects, and the detail form focuses
  // its first field from its own mount effect, so an effect would record
  // that field instead of the option field or button that opened the window,
  // and focus could never return there.
  const [invoker] = useState(captureInvoker);

  // Modality: the page beneath is inert while this window is on top. This
  // effect is declared before the two focus effects, and React runs both
  // setups and cleanups in declaration order, so the background is inert
  // before focus moves in and released before focus returns to the invoker,
  // which lives in that background.
  useEffect(() => {
    const dialog = dialogRef.current;
    if (dialog === null) {
      return;
    }
    return registerWindow({ dialog, backdrop: backdropRef.current, lastFocus: null });
  }, []);

  // Focus in. Runs after every child's mount effect, so it decides where
  // focus is when the window opens. A window with an open window nested in
  // it leaves focus to that window.
  useEffect(() => {
    const dialog = dialogRef.current;
    if (dialog === null || topmostWindow()?.dialog !== dialog) {
      return;
    }
    focusFirst(initialFocusTargets(dialog, initialFocusRef?.current ?? null));
  }, [initialFocusRef]);

  // Focus return when the window closes or unmounts, after the modality
  // cleanup above has released the background. A window opened over another
  // window (the State picker over the detail window) returns focus to the
  // field inside the window beneath, which keeps its own trap.
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
   * of the window's tab order, or in a window beneath the topmost one, which
   * hands focus to the window on top; every other key, Tab inside the range
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
    // Only the window on top holds focus. Should focus still sit on a window
    // beneath it (focus that could not be moved back), Tab sends it into the
    // window on top rather than trapping it here.
    const top = topmostWindow();
    if (top !== undefined && top.dialog !== dialog) {
      event.preventDefault();
      recoverFocus(top);
      return;
    }

    const order = sequentialOrder(dialog);
    if (order.length === 0) {
      event.preventDefault();
      dialog.focus();
      return;
    }

    // Browsers pass over the other radios of the focused radio's group, so
    // those never count as the next stop and are never wrapped to. Focus can
    // also sit outside the order, on the window itself after a press on its
    // padding, a `tabindex="-1"` heading or an unchecked radio a click
    // focused; splitAround places it as the browser would. The key is left
    // native while a reachable stop lies ahead in its direction; otherwise
    // focus wraps to the reachable stop at the other end, or stays put when
    // the focused radio's group holds every stop.
    const reachable = (stop: HTMLElement): boolean => !sameRadioGroup(stop, target);
    const { before, after } = splitAround(order, target, dialog);
    if (event.shiftKey ? before.some(reachable) : after.some(reachable)) {
      return;
    }
    event.preventDefault();
    const wrapTo = event.shiftKey ? [...order].reverse().find(reachable) : order.find(reachable);
    wrapTo?.focus();
  }

  return (
    <>
      <div
        ref={backdropRef}
        className="dialog__backdrop"
        aria-hidden="true"
        onMouseDown={keepFocus}
      />
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
