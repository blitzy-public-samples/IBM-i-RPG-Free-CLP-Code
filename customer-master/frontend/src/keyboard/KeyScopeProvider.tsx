/**
 * Function keys and keyboard scope: the dispatcher half (AAP 0.4.4).
 *
 * On the 5250 the workstation sent the program one attention identifier (AID)
 * byte per key press, F01–F24, PageDown/RollUp, PageUp/RollDown and Enter
 * [Copy_Mbrs/AIDBYTES.RPGLE:3-35], and each display file enabled a subset of
 * them: `CA03 CF04 CA05 CA06 CA09 CA12` on the search screen
 * [5250_Subfile/PMTCUSTD.DSPF:34-39], `CF04 CA05 CA12` on the detail window
 * [5250_Subfile/MTNCUSTD.DSPF:33-35] and `CF03 CF05 CF07 CF12` on the state
 * window [5250_Subfile/PMTSTATED.DSPF:68-71]. Only the screen on top could be
 * keyed. In the browser the search page stays mounted beneath the detail
 * dialog, and the detail dialog beneath the State picker, so key handling is
 * owned here, centrally: each screen registers a *scope* with
 * `useFunctionKeys` (its bindings are the keys it enables), and this provider
 * decides which scope receives a key.
 *
 * Mount it once, near the root, inside the toast provider so the caller can
 * pass the toast `clear` function:
 *
 * ```tsx
 * <KeyScopeProvider onBeforeCommand={useToasts().clear}>…</KeyScopeProvider>
 * ```
 *
 * It depends on nothing but React and `./useFunctionKeys`: no router, toasts or
 * message catalog. It renders no DOM of its own.
 *
 * **The keyboard scope contract.** The provider owns the application's only
 * document-level keydown listener, registered in the capture phase so it runs
 * before React's root listener and before any element handler. Each keydown
 * is handled in this order:
 *
 * 1. **IME composition passes through.** While `isComposing` is set, or the key
 *    reports the composition key code 229, nothing happens: input methods use
 *    Enter and, for Japanese, F6–F10 to build text.
 * 2. **Chords pass through.** With Shift, Ctrl, Alt or Meta held nothing
 *    happens, so Ctrl+F5, Alt+F4 and every browser or system shortcut keep
 *    working: Shift+F1–F24, Shift+Enter, Shift+PageUp, Shift+PageDown and
 *    Shift+Escape are never prevented and never dispatched, and Shift+Tab
 *    moves focus back as usual.
 * 3. **Command keys.** F1–F24 by their `key` names, physical F13–F24 keys
 *    included; Escape arrives as F12, so one F12 binding cancels for both
 *    keys; Enter, PageUp and PageDown arrive as themselves. Every other key
 *    (Tab, the arrow keys, Home, End, Backspace, Delete, printable
 *    characters) is never prevented and never dispatched, so focus
 *    navigation and text entry work as in any web form. Home (AID x'F8') is
 *    a navigation key here, and the 5250 mouse AIDs ME00–ME14 have no
 *    counterpart.
 * 4. **No scope, no action.** With an empty stack nothing happens.
 * 5. **Topmost only.** Only the most recently pushed scope receives keys; every
 *    scope beneath it is suspended until the scopes above it are removed. A
 *    keydown runs at most one handler.
 * 6. **Enter target rule.** Enter is a command only when the event target is a
 *    text-entry `<input>` (`text`, `search`, `tel`, `email`, `password` or
 *    `number`; an input without a `type` is `text`, which covers the option
 *    fields, and read-only inputs count, so Enter closes a Display dialog) or
 *    is exactly the scope's own container. On a button, link, checkbox, radio,
 *    `<select>`, `<textarea>` or anything else Enter keeps its native action,
 *    such as a button's click.
 * 7. **Bound key.** The default action is prevented and propagation stopped;
 *    then `onBeforeCommand(key)` runs, then the scope's handler. Each
 *    repeated keydown of a held key (`repeat`) dispatches like any other, so
 *    a screen guards against a second request while one is in flight, as
 *    `CustomerDetailDialog` does with its in-flight gate.
 * 8. **Unbound function key** (F12 reached by Escape included). Prevented and
 *    stopped, then `onBeforeCommand(key)`, then the scope's `onUnbound(key)`,
 *    where every screen shows DEM0003 "Key is not active now" and changes
 *    nothing (AAP 0.3.8). Preventing F3, F5, F6 and F12 is what keeps the
 *    browser's find, reload, address-bar and developer-tools defaults away,
 *    where the browser allows a page to do so.
 * 9. **Unbound Enter, PageUp or PageDown** keeps its native behaviour.
 *
 * Consequences worth knowing:
 *
 * - Stopping propagation in the document capture phase also keeps the event
 *   from React's root listener, so a component's own `onKeyDown` never runs
 *   for a key this provider dispatched. That is the "at most one handler" rule.
 * - The stack never moves focus. `components/Dialog.tsx` moves focus into a
 *   window when it opens and back to the invoking element when it closes, so
 *   focus and key handling return to the scope beneath together.
 * - Bindings are read through the scope's getters at keydown time, so a
 *   screen that re-renders with new handlers is served the new ones at once.
 * - An exception thrown by a handler is not caught here. The key has already
 *   been prevented, and the browser reports the error as any uncaught
 *   exception in an event listener, where the application's error reporting
 *   sees it; swallowing it would hide a defect.
 */
import { useEffect, useLayoutEffect, useMemo, useRef } from 'react';
import type { JSX, ReactNode } from 'react';

import { KeyScopeContext } from './useFunctionKeys';
import type { CommandKey, FunctionKey, KeyScope, KeyScopeRegistry } from './useFunctionKeys';

/** Props of {@link KeyScopeProvider}. */
export interface KeyScopeProviderProps {
  /** The application, or the part of it whose screens register key scopes. */
  children: ReactNode;
  /**
   * Called with every key the provider dispatches, bound or unbound, just
   * before the scope's handler or `onUnbound` runs, each repeated keydown of
   * a held key included. Not called for keys that pass through. The
   * application wires it to the toast `clear`, so each command starts a new
   * message cycle as the 5250 cleared its message subfile per screen I/O. Its
   * identity may change on every render without re-adding the document
   * listener.
   */
  onBeforeCommand?: (key: CommandKey) => void;
}

/** The function keys in AID order, F01 to F24. */
const FUNCTION_KEYS: readonly FunctionKey[] = [
  'F1',
  'F2',
  'F3',
  'F4',
  'F5',
  'F6',
  'F7',
  'F8',
  'F9',
  'F10',
  'F11',
  'F12',
  'F13',
  'F14',
  'F15',
  'F16',
  'F17',
  'F18',
  'F19',
  'F20',
  'F21',
  'F22',
  'F23',
  'F24',
];

/** Zero-based position of each function key name in {@link FUNCTION_KEYS}. */
const FUNCTION_KEY_INDEX: ReadonlyMap<string, number> = new Map<string, number>(
  FUNCTION_KEYS.map((name, index) => [name, index]),
);

/**
 * Input types on which Enter is a command. These are the text-entry types a
 * form field or option field can have; `HTMLInputElement.type` reads a missing
 * or unknown `type` attribute as `text`.
 */
const TEXT_ENTRY_INPUT_TYPES: ReadonlySet<string> = new Set([
  'text',
  'search',
  'tel',
  'email',
  'password',
  'number',
]);

/** The key code browsers report for a keydown that belongs to an IME. */
const IME_PROCESS_KEY_CODE = 229;

/**
 * Maps an unmodified keydown's `key` to the command key it stands for, or
 * `null` when the key is not a command key and must pass through.
 *
 * `'F1'`…`'F24'` map to themselves, physical F13–F24 keys included, and
 * `'Escape'` maps to `'F12'`; `'Enter'`, `'PageUp'` and `'PageDown'` map to
 * themselves. Modifier chords never reach this function: the dispatcher lets
 * them pass through first (step 2).
 */
function toCommandKey(key: string): CommandKey | null {
  switch (key) {
    case 'Escape':
      return 'F12';
    case 'Enter':
    case 'PageUp':
    case 'PageDown':
      return key;
    default:
      break;
  }
  const index = FUNCTION_KEY_INDEX.get(key);
  if (index === undefined) {
    return null;
  }
  return FUNCTION_KEYS[index] ?? null;
}

/** Whether `key` is one of F1–F24 rather than Enter, PageUp or PageDown. */
function isFunctionKey(key: CommandKey): key is FunctionKey {
  return key !== 'Enter' && key !== 'PageUp' && key !== 'PageDown';
}

/**
 * The Enter target rule: Enter is a command on a text-entry input or on the
 * scope's own container element, and native everywhere else.
 */
function isEnterCommandTarget(target: EventTarget | null, scope: KeyScope): boolean {
  if (target instanceof HTMLInputElement && TEXT_ENTRY_INPUT_TYPES.has(target.type)) {
    return true;
  }
  const container = scope.getContainer();
  return container !== null && target === container;
}

/**
 * Applies the keyboard scope contract (steps 1–9 in the module documentation)
 * to one keydown, against the scope stack as it stands at that moment.
 */
function dispatchKeyDown(
  event: KeyboardEvent,
  stack: readonly KeyScope[],
  onBeforeCommand: ((key: CommandKey) => void) | undefined,
): void {
  // 1. An IME owns the key while it composes text. `keyCode` is deprecated,
  //    but 229 is still the only signal some browsers give for the keydown
  //    that starts or ends a composition.
  if (event.isComposing || event.keyCode === IME_PROCESS_KEY_CODE) {
    return;
  }

  // 2. Modifier chords keep their browser and system behaviour: with Shift,
  //    Ctrl, Alt or Meta held, the key is neither prevented nor dispatched.
  if (event.shiftKey || event.ctrlKey || event.altKey || event.metaKey) {
    return;
  }

  // 3. Only command keys go further; everything else is untouched.
  const key = toCommandKey(event.key);
  if (key === null) {
    return;
  }

  // 4 and 5. The topmost scope alone receives the key.
  const scope = stack[stack.length - 1];
  if (scope === undefined) {
    return;
  }

  // 6. Enter on a button, link, checkbox, select or textarea stays native.
  if (key === 'Enter' && !isEnterCommandTarget(event.target, scope)) {
    return;
  }

  // 7. A bound key runs its handler, a repeated (held) keydown included.
  const handler = scope.getBinding(key);
  if (handler !== undefined) {
    event.preventDefault();
    event.stopPropagation();
    onBeforeCommand?.(key);
    handler();
    return;
  }

  // 9. An unbound Enter, PageUp or PageDown keeps its native behaviour.
  if (!isFunctionKey(key)) {
    return;
  }

  // 8. An unbound function key is answered by the scope (DEM0003), and its
  //    browser default (find, reload, devtools) is suppressed.
  event.preventDefault();
  event.stopPropagation();
  onBeforeCommand?.(key);
  scope.onUnbound(key);
}

/**
 * Owns the key scope stack and the one document keydown listener, and gives
 * `useFunctionKeys` its registry through `KeyScopeContext`. Mount it exactly
 * once; it renders only its children.
 *
 * - The stack lives in a ref: pushing and removing scopes never re-renders.
 * - The registry is created once, so consumers' registration effects never
 *   re-run because of the provider.
 * - The listener is added once on mount and removed on unmount, with the same
 *   function and the capture flag, so StrictMode's mount, cleanup, mount
 *   sequence leaves exactly one listener.
 */
export function KeyScopeProvider({ children, onBeforeCommand }: KeyScopeProviderProps): JSX.Element {
  const stackRef = useRef<KeyScope[]>([]);

  // The latest callback, read by the listener at keydown time, so a new
  // identity on each render (an inline arrow, say) never re-adds the listener.
  // Written only in a layout effect, never during render.
  const onBeforeCommandRef = useRef(onBeforeCommand);
  useLayoutEffect(() => {
    onBeforeCommandRef.current = onBeforeCommand;
  });

  const registry = useMemo<KeyScopeRegistry>(
    () => ({
      push(scope: KeyScope): () => void {
        stackRef.current.push(scope);
        let removed = false;
        // Removes this scope by identity wherever it now sits, so a scope in
        // the middle of the stack can leave first. A second call is a no-op.
        return () => {
          if (removed) {
            return;
          }
          removed = true;
          const stack = stackRef.current;
          const index = stack.indexOf(scope);
          if (index !== -1) {
            stack.splice(index, 1);
          }
        };
      },
    }),
    [],
  );

  useEffect(() => {
    function onKeyDown(event: KeyboardEvent): void {
      dispatchKeyDown(event, stackRef.current, onBeforeCommandRef.current);
    }
    document.addEventListener('keydown', onKeyDown, true);
    return () => {
      document.removeEventListener('keydown', onKeyDown, true);
    };
  }, []);

  return <KeyScopeContext value={registry}>{children}</KeyScopeContext>;
}
