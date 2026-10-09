/**
 * Function keys and keyboard scope: the dispatcher half (AAP 0.4.4).
 *
 * The 5250 sent one attention identifier (AID) byte per key press
 * [Copy_Mbrs/AIDBYTES.RPGLE:3-35], and only the screen on top could be keyed.
 * In the browser the search page stays mounted beneath the detail dialog, and
 * the detail dialog beneath the State picker, so key handling is owned here,
 * centrally: each screen registers a scope with `useFunctionKeys`, and this
 * provider decides which scope receives a key. Mount it once, near the root,
 * inside the toast provider so the caller can pass the toast `clear`:
 *
 * ```tsx
 * <KeyScopeProvider onBeforeCommand={useToasts().clear}>…</KeyScopeProvider>
 * ```
 *
 * The provider owns the application's only document-level keydown listener,
 * registered in the capture phase so it runs before React's root listener.
 *
 * - **Topmost only.** Only the most recently pushed scope receives keys; every
 *   scope beneath it is suspended until the scopes above it are removed.
 *   Stopping propagation in the capture phase also keeps a dispatched key from
 *   a component's own `onKeyDown`, so each keydown runs at most one handler.
 * - **Bound key.** The default action is prevented and propagation stopped,
 *   then `onBeforeCommand(key)` runs, then the scope's handler.
 * - **Unbound function key** (F12 reached by Escape included). Prevented and
 *   stopped, then `onBeforeCommand(key)`, then the scope's `onUnbound(key)`,
 *   where every screen shows DEM0003 "Key is not active now" and changes
 *   nothing. Preventing F3, F5, F6 and F12 is what keeps the browser's find,
 *   reload, address-bar and developer-tools defaults away, where the browser
 *   allows a page to do so.
 * - **Pass-through.** Nothing is prevented or dispatched for a key
 *   `toCommandKey` does not map (Tab, the arrow keys, Home, End, Backspace,
 *   Delete, printable characters), a modifier chord, a key an IME is composing
 *   with, Enter off the targets `isEnterCommandTarget` accepts, an unbound
 *   Enter, PageUp or PageDown, or any key while no scope is registered, so
 *   focus navigation and text entry work as in any web form.
 * - **Focus.** The stack never moves focus. `components/Dialog.tsx` moves focus
 *   into a window when it opens and back to the invoking element when it
 *   closes, so focus and key handling return to the scope beneath together.
 * - **Errors.** An exception thrown by a handler is not caught here. The key
 *   has already been prevented, and the browser reports the error as any
 *   uncaught exception in an event listener; swallowing it would hide a defect.
 */
import { useEffect, useLayoutEffect, useMemo, useRef } from 'react';
import type { JSX, ReactNode } from 'react';

import { KeyScopeContext } from './useFunctionKeys';
import type { CommandKey, FunctionKey, KeyScope, KeyScopeRegistry } from './useFunctionKeys';

export interface KeyScopeProviderProps {
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

const FUNCTION_KEY_INDEX: ReadonlyMap<string, number> = new Map<string, number>(
  FUNCTION_KEYS.map((name, index) => [name, index]),
);

/** The text-entry input types on which Enter is a command. */
const TEXT_ENTRY_INPUT_TYPES: ReadonlySet<string> = new Set([
  'text',
  'search',
  'tel',
  'email',
  'password',
  'number',
]);

const IME_PROCESS_KEY_CODE = 229;

/**
 * Maps an unmodified keydown's `key` to the command key it stands for, or
 * `null` when the key is not a command key and must pass through:
 * `'F1'`…`'F24'` (physical F13–F24 keys included), `'Enter'`, `'PageUp'` and
 * `'PageDown'` map to themselves, and `'Escape'` maps to `'F12'`. Modifier
 * chords never reach this function; the dispatcher lets them pass first.
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

function isFunctionKey(key: CommandKey): key is FunctionKey {
  return key !== 'Enter' && key !== 'PageUp' && key !== 'PageDown';
}

/**
 * The Enter target rule. Enter is a command only when the event target is a
 * text field or is exactly the scope's own container. The text fields are a
 * text-entry `<input>`, read-only ones included (`HTMLInputElement.type`
 * reads a missing or unknown `type` as `text`, which covers the option
 * fields), and a read-only `<textarea>`: the protected value
 * `components/FormField.tsx` renders so a long value wraps. A read-only
 * textarea holds one protected value and can take no new line, so it has no
 * native Enter action to keep: Enter on a protected field (a Display window's
 * fields, a confirmation panel's, a form's Customer Id) does what Enter on
 * its window's container does. On a button, link, checkbox, radio,
 * `<select>`, an editable `<textarea>` or anything else Enter keeps its
 * native action, such as a button's click or a new line.
 */
function isEnterCommandTarget(target: EventTarget | null, scope: KeyScope): boolean {
  if (target instanceof HTMLInputElement && TEXT_ENTRY_INPUT_TYPES.has(target.type)) {
    return true;
  }
  if (target instanceof HTMLTextAreaElement && target.readOnly) {
    return true;
  }
  const container = scope.getContainer();
  return container !== null && target === container;
}

/**
 * Applies the keyboard scope contract to one keydown, against the scope stack
 * as it stands at that moment.
 */
function dispatchKeyDown(
  event: KeyboardEvent,
  stack: readonly KeyScope[],
  onBeforeCommand: ((key: CommandKey) => void) | undefined,
): void {
  // An IME owns the key while it composes text: input methods use Enter and,
  // for Japanese, F6–F10 to build it. `keyCode` is deprecated, but 229 is
  // still the only signal some browsers give for the keydown that starts or
  // ends a composition.
  if (event.isComposing || event.keyCode === IME_PROCESS_KEY_CODE) {
    return;
  }

  // Modifier chords keep their browser and system behaviour (Ctrl+F5, Alt+F4
  // and the like): with Shift, Ctrl, Alt or Meta held the key, Shift+F1–F24
  // and Shift+Escape included, is neither prevented nor dispatched, and
  // Shift+Tab still moves focus back.
  if (event.shiftKey || event.ctrlKey || event.altKey || event.metaKey) {
    return;
  }

  const key = toCommandKey(event.key);
  if (key === null) {
    return;
  }

  const scope = stack[stack.length - 1];
  if (scope === undefined) {
    return;
  }

  if (key === 'Enter' && !isEnterCommandTarget(event.target, scope)) {
    return;
  }

  // Each repeated keydown of a held key dispatches like any other, so a screen
  // guards against a second request while one is in flight, as
  // `CustomerDetailDialog` does with its in-flight gate.
  const handler = scope.getBinding(key);
  if (handler !== undefined) {
    event.preventDefault();
    event.stopPropagation();
    onBeforeCommand?.(key);
    handler();
    return;
  }

  // An unbound Enter, PageUp or PageDown keeps its native behaviour.
  if (!isFunctionKey(key)) {
    return;
  }

  // An unbound function key is prevented too, so the browser's own action for
  // it stays away, and the scope answers it.
  event.preventDefault();
  event.stopPropagation();
  onBeforeCommand?.(key);
  scope.onUnbound(key);
}

/**
 * Gives `useFunctionKeys` its registry through `KeyScopeContext` and renders
 * only its children.
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

  // Read by the listener at keydown time; written only in a layout effect,
  // never during render.
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
