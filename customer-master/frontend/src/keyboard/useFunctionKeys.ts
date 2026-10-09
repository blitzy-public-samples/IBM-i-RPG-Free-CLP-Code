/**
 * Function keys and keyboard scope: the contract half.
 *
 * On the 5250 the workstation reported every attention key as an AID byte
 * [Copy_Mbrs/AIDBYTES.RPGLE:3-35], and each display file declared which of
 * them the screen accepted: the search screen `CA03 CF04 CA05 CA06 CA09 CA12`
 * [5250_Subfile/PMTCUSTD.DSPF:34-39], the detail window `CF04 CA05 CA12`
 * [5250_Subfile/MTNCUSTD.DSPF:33-35] and the state window
 * `CF03 CF05 CF07 CF12` [5250_Subfile/PMTSTATED.DSPF:68-71]. In the browser
 * each screen or dialog instead registers a *key scope* with
 * {@link useFunctionKeys}: its bindings are the keys it enables, and its
 * `onUnbound` callback answers every other function key (each screen shows
 * DEM0003 "Key is not active now" there; this module raises no message).
 * Home (AID x'F8') is a navigation key here, and the 5250 mouse AIDs
 * ME00–ME14 have no counterpart: a click is a click.
 *
 * `KeyScopeProvider.tsx` owns the one document-level `keydown` listener and the
 * stack of scopes; its documentation sets out the dispatch rules. The scope
 * types and {@link KeyScopeContext} live in this file so the import direction
 * stays one-way (`KeyScopeProvider.tsx` → this file) and no cycle exists. This
 * module imports nothing but React.
 */
import { createContext, useContext, useLayoutEffect, useRef } from 'react';
import type { RefObject } from 'react';

/** The 24 function keys, by their UI Events `key` names (AID F01–F24). */
export type FunctionKey =
  | 'F1'
  | 'F2'
  | 'F3'
  | 'F4'
  | 'F5'
  | 'F6'
  | 'F7'
  | 'F8'
  | 'F9'
  | 'F10'
  | 'F11'
  | 'F12'
  | 'F13'
  | 'F14'
  | 'F15'
  | 'F16'
  | 'F17'
  | 'F18'
  | 'F19'
  | 'F20'
  | 'F21'
  | 'F22'
  | 'F23'
  | 'F24';

/**
 * A key a scope can bind: a function key, Enter, PageUp (RollDown) or PageDown
 * (RollUp). Escape is deliberately absent: the provider delivers it as `'F12'`,
 * so a screen binds F12 once and both keys cancel.
 */
export type CommandKey = FunctionKey | 'Enter' | 'PageUp' | 'PageDown';

/**
 * A scope's enabled keys and their handlers. A key that is absent, or set to
 * `undefined`, is unbound: an unbound function key goes to
 * {@link FunctionKeyOptions.onUnbound}, while an unbound Enter, PageUp or
 * PageDown keeps its native browser behaviour.
 */
export type KeyBindings = Partial<Record<CommandKey, () => void>>;

export interface FunctionKeyOptions {
  /**
   * Called with a function key the scope does not bind while the scope is
   * topmost. Required, because every screen must answer a key it does not
   * enable (with DEM0003) rather than let it fall through to the browser.
   */
  onUnbound: (key: CommandKey) => void;
  /**
   * Whether the scope is registered. Defaults to `true`. Switching it to
   * `false` removes the scope, handing keys to the scope beneath; switching it
   * back to `true` pushes a fresh scope on top of the stack.
   */
  active?: boolean;
  /**
   * The scope's own container element. Enter pressed while this exact element
   * has focus counts as a command, in addition to Enter in a text input.
   */
  containerRef?: RefObject<HTMLElement | null>;
}

/**
 * One registered scope as the provider sees it. The methods read the scope's
 * latest bindings, `onUnbound` and container at keydown time, so a re-render
 * with new handlers takes effect without re-registering. Internal: used by
 * KeyScopeProvider.
 */
export interface KeyScope {
  getBinding(key: CommandKey): (() => void) | undefined;
  onUnbound(key: CommandKey): void;
  getContainer(): HTMLElement | null;
}

/** The scope stack's registration API. Internal: used by KeyScopeProvider. */
export interface KeyScopeRegistry {
  /**
   * Appends `scope` to the stack, making it topmost, and returns a function
   * that removes that same scope wherever it then sits in the stack.
   */
  push(scope: KeyScope): () => void;
}

/**
 * Carries the provider's {@link KeyScopeRegistry}; `null` outside a
 * `KeyScopeProvider`. Internal: used by KeyScopeProvider.
 */
export const KeyScopeContext = createContext<KeyScopeRegistry | null>(null);

interface LatestScopeState {
  readonly bindings: KeyBindings;
  readonly onUnbound: (key: CommandKey) => void;
  readonly containerRef: RefObject<HTMLElement | null> | undefined;
}

/**
 * Registers the calling component as a key scope while it is mounted and
 * `active`, replacing a display file's key enablement with per-scope bindings.
 *
 * ```tsx
 * const containerRef = useRef<HTMLDivElement>(null);
 * useFunctionKeys(
 *   { Enter: review, F4: prompt, F5: reload, F12: close },
 *   { onUnbound: () => publish({ kind: 'alert', text: format('DEM0003') }), containerRef },
 * );
 * ```
 *
 * - **Latest handlers, stable position.** New `bindings`, `onUnbound` or
 *   `containerRef` values on a re-render are picked up in place, and the scope
 *   is never re-pushed: only mounting, unmounting and `active` transitions
 *   push or remove it.
 * - **Registered in a layout effect.** The provider listens in the document's
 *   capture phase, ahead of React, so a scope must exist as soon as its
 *   component is committed; a passive effect could miss a keydown arriving
 *   between commit and effect flush. Under StrictMode the mount, cleanup,
 *   mount sequence pushes, removes and pushes again, leaving exactly one scope.
 * - **Ordering caveat.** The stack is ordered by activation (push) time, and
 *   React runs child effects before parent effects, so a parent and a child
 *   scope mounted in the same commit register child first and the parent ends
 *   up topmost. In the application, dialogs and pickers open on user actions,
 *   after the scope beneath them is registered, so the order is right; tests
 *   must likewise mount stacked scopes in separate steps.
 *
 * @param bindings The keys this scope enables; see {@link KeyBindings}.
 * @param options See {@link FunctionKeyOptions}.
 * @throws Error when rendered outside a `KeyScopeProvider`, so component tests
 *   of any consumer must render inside `<KeyScopeProvider>`.
 */
export function useFunctionKeys(bindings: KeyBindings, options: FunctionKeyOptions): void {
  const registry = useContext(KeyScopeContext);
  if (registry === null) {
    throw new Error('useFunctionKeys must be used inside <KeyScopeProvider>');
  }

  const { onUnbound, containerRef } = options;
  const active = options.active ?? true;

  // The scope's getters run in the provider's native listener, outside React,
  // so they read a ref. It is written only in a layout effect, declared before
  // the registration effect so a newly pushed scope sees this render's handlers.
  const latestRef = useRef<LatestScopeState>({ bindings, onUnbound, containerRef });
  useLayoutEffect(() => {
    latestRef.current = { bindings, onUnbound, containerRef };
  });

  useLayoutEffect(() => {
    if (!active) {
      return undefined;
    }
    const scope: KeyScope = {
      getBinding: (key) => latestRef.current.bindings[key],
      onUnbound: (key) => {
        latestRef.current.onUnbound(key);
      },
      getContainer: () => latestRef.current.containerRef?.current ?? null,
    };
    return registry.push(scope);
  }, [active, registry]);
}

/**
 * The `aria-keyshortcuts` value for a command key: UI Events `key` names,
 * with a space-separated alternative where the provider also accepts one.
 *
 * - `'F12'` → `'F12 Escape'`, because Escape runs the F12 binding.
 * - Any other key → its own name, for example `'F3'`, `'F13'`, `'F24'`,
 *   `'Enter'`, `'PageDown'`.
 */
export function ariaKeyShortcuts(key: CommandKey): string {
  if (key === 'F12') {
    return 'F12 Escape';
  }
  return key;
}
