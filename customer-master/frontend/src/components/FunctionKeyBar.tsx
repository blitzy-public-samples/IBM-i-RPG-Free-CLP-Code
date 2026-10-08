/**
 * FunctionKeyBar: the visible function-key legend of every screen and dialog.
 * It replaces the 5250 footer `SFT_KEYS` [5250_Subfile/PMTCUSTD.DSPF:125-132],
 * [5250_Subfile/MTNCUSTD.DSPF:139-146], which each program's `BldFkeyText`
 * filled [5250_Subfile/PMTCUSTR.SQLRPGLE:675-701],
 * [5250_Subfile/MTNCUSTR.SQLRPGLE:638-649], [5250_Subfile/PMTSTATER.SQLRPGLE:113-118].
 * Every legend entry is also a button, because some browsers and operating
 * systems keep F3, F5, F6 or F12 even after `preventDefault`.
 *
 * It is the visible half of the keyboard contract, whose one `keydown` listener
 * `KeyScopeProvider` and `useFunctionKeys` own. A caller passes as `onPress`
 * the very function it registered with `useFunctionKeys` for that key, building
 * both from one list (see the example). The bar intercepts no key: its buttons
 * stay in the Tab order with no roving focus, and Enter or Space on a focused
 * button is the button's own click, which the provider leaves alone.
 *
 * A mouse press on a key, a disabled key or the gap between keys keeps focus
 * on the field that has it, as the 5250 cursor stayed in its field (RTNCSRLOC
 * [5250_Subfile/PMTCUSTD.DSPF:81], [5250_Subfile/MTNCUSTD.DSPF:58]);
 * `F4=Prompt+` needs that, as it opens the State picker only while State has
 * focus.
 *
 * @example
 * ```tsx
 * const keys: FunctionKeyBarItem[] = [{ key: 'F3', label: 'F3=Exit', onPress: exit }];
 * useFunctionKeys({ F3: exit }, { onUnbound });
 * return <FunctionKeyBar keys={keys} />;
 * ```
 */
import type { MouseEvent } from 'react';
import { ariaKeyShortcuts } from '../keyboard/useFunctionKeys';
import type { CommandKey } from '../keyboard/useFunctionKeys';

/**
 * One legend entry. Callers supply every legend; this file holds no legend
 * text.
 */
export type FunctionKeyBarItem = {
  /**
   * The command key, listed at most once per bar, since it is the button's
   * React key. `ariaKeyShortcuts` derives the button's `aria-keyshortcuts`
   * from it, so F12 advertises `F12 Escape`.
   */
  key: CommandKey;
  /**
   * The caller's source legend, e.g. `F3=Exit`, rendered verbatim, so it is
   * the button's accessible name.
   */
  label: string;
  /**
   * The handler the screen registered with `useFunctionKeys` for `key`. It is
   * called with no arguments, exactly as a key press calls it.
   */
  onPress: () => void;
  /**
   * Screens normally omit a key their mode does not offer, as PMTCUSTR adds
   * `F6=Add` only in Maintenance mode [5250_Subfile/PMTCUSTR.SQLRPGLE:688-691];
   * `disabled` renders it as a disabled button instead.
   */
  disabled?: boolean;
};

/**
 * Cancelling `mousedown` stops only the focus change; the `click`, and
 * therefore `onPress`, still fires.
 */
function preventFocusSteal(event: MouseEvent<HTMLElement>): void {
  event.preventDefault();
}

/**
 * Renders the entries in the order given, and the toolbar even when `keys` is
 * empty, so a screen's layout does not shift.
 */
export function FunctionKeyBar({ keys }: { keys: ReadonlyArray<FunctionKeyBarItem> }) {
  return (
    // The toolbar cancels mousedown too, or a press that misses every enabled
    // button would blur the focused field to <body>: one in the gap between
    // keys, or on a disabled key, which gets no mouse events (global.css sets
    // `pointer-events: none` on it, so the press lands here).
    <div
      className="fkey-bar"
      role="toolbar"
      aria-label="Function keys"
      onMouseDown={preventFocusSteal}
    >
      {keys.map((item) => (
        <button
          type="button"
          key={item.key}
          className="fkey-bar__key"
          aria-keyshortcuts={ariaKeyShortcuts(item.key)}
          disabled={item.disabled}
          // Wrapped, not passed directly: a handler with an optional parameter
          // must never receive the click event in it.
          onClick={() => item.onPress()}
          onMouseDown={preventFocusSteal}
        >
          {item.label}
        </button>
      ))}
    </div>
  );
}
