/**
 * FunctionKeyBar: the visible function-key legend of every screen and dialog.
 *
 * It replaces the 5250 footer field `SFT_KEYS` (`COLOR(BLU)`, row 23 of
 * PMTCUSTD [5250_Subfile/PMTCUSTD.DSPF:125-132] and row 15 of the MTNCUSTD
 * window [5250_Subfile/MTNCUSTD.DSPF:139-146]), which each program's
 * `BldFkeyText` filled by concatenating its key legends
 * [5250_Subfile/PMTCUSTR.SQLRPGLE:675-701], [5250_Subfile/MTNCUSTR.SQLRPGLE:638-649],
 * [5250_Subfile/PMTSTATER.SQLRPGLE:113-118]. On the 5250 the legend was only
 * text; here every legend entry is also a button, because some browsers and
 * operating systems keep F3, F5, F6 or F12 for themselves even after
 * `preventDefault`, so a click must always be able to do what the key does.
 *
 * This is the visible half of the keyboard contract. The dispatch half is
 * `src/keyboard/KeyScopeProvider.tsx` with `useFunctionKeys`, which owns the
 * one `keydown` listener. To keep mouse and keyboard identical, a caller
 * passes as `onPress` the very function it registered with `useFunctionKeys`
 * for that key, and builds both from one list:
 *
 * @example
 * ```tsx
 * const keys: FunctionKeyBarItem[] = [
 *   { key: 'F3', label: 'F3=Exit', onPress: exit },
 *   { key: 'F9', label: includeInactive ? 'F9=Exclude Inactive' : 'F9=Include Inactive', onPress: toggleInactive },
 *   { key: 'F12', label: 'F12=Cancel', onPress: exit },
 * ];
 * useFunctionKeys({ F3: exit, F9: toggleInactive, F12: exit }, { onUnbound });
 * return <FunctionKeyBar keys={keys} />;
 * ```
 *
 * Contract:
 * - **Labels come from callers.** This file holds no message or legend text;
 *   each screen supplies its source legends (for example `F5=Reset` on the
 *   search page, `F5=Refresh` in the detail dialog, `F7=By Code` /
 *   `F7=By Name` in the State picker). Labels are rendered exactly as given,
 *   in the order given, with nothing added, so each button's accessible name
 *   equals its label. The only fixed text is the toolbar's accessible name.
 * - **One entry per key.** A bar lists each command key at most once; the key
 *   is the React key of its button.
 * - **Shortcut hint.** Each button carries `aria-keyshortcuts` from
 *   `ariaKeyShortcuts`, so F12 advertises `F12 Escape`, matching what the
 *   provider accepts.
 * - **Focus stays where it is.** Pressing a key button, a disabled key or the
 *   gap between keys with the mouse does not move focus away from the field
 *   that has it, as the 5250 cursor stayed in its field when a
 *   function key was pressed (RTNCSRLOC [5250_Subfile/PMTCUSTD.DSPF:81],
 *   [5250_Subfile/MTNCUSTD.DSPF:58]). This matters for `F4=Prompt+`, which
 *   opens the State picker only while focus is on the State field.
 * - **Native keyboard behaviour.** The buttons stay in the Tab order, and the
 *   bar intercepts no key: there is no roving focus and no key handler, since
 *   the keyboard contract never captures Tab or the arrow keys. Enter or Space
 *   on a focused button is the button's own click, which the provider leaves
 *   alone.
 * - **Messages.** A click is a user action: `ToastProvider`
 *   (`components/ToastRegion.tsx`) clears the current messages in a
 *   capture-phase `click` listener before `onPress` runs, so a message that
 *   `onPress` raises, such as DEM0003, is shown fresh.
 * - **Keys a mode does not offer.** Screens normally omit them, as PMTCUSTR
 *   adds `F6=Add` only in Maintenance mode [5250_Subfile/PMTCUSTR.SQLRPGLE:688-691].
 *   An entry with `disabled: true` renders as a disabled button instead.
 *
 * Presentation comes from the `.fkey-bar` and `.fkey-bar__key` classes in
 * `src/styles/global.css` (loaded by `src/main.tsx`); no component library is
 * used. Layer rule: components import nothing from `api/`, `errors/` or
 * `features/`; this file imports only `../keyboard/useFunctionKeys`.
 */
import type { MouseEvent } from 'react';
import { ariaKeyShortcuts } from '../keyboard/useFunctionKeys';
import type { CommandKey } from '../keyboard/useFunctionKeys';

/** One legend entry of a {@link FunctionKeyBar}. */
export type FunctionKeyBarItem = {
  /** The command key, listed at most once per bar. */
  key: CommandKey;
  /** The source legend, rendered verbatim as the button text, e.g. `F3=Exit`. */
  label: string;
  /**
   * The handler the screen registered with `useFunctionKeys` for `key`. It is
   * called with no arguments, exactly as a key press calls it.
   */
  onPress: () => void;
  /** Renders the button disabled, so it cannot be pressed. */
  disabled?: boolean;
};

/**
 * Keeps focus on the element that already has it when the bar is pressed with
 * the mouse. Cancelling `mousedown` stops only the focus change; the `click`,
 * and therefore `onPress`, still fires.
 */
function preventFocusSteal(event: MouseEvent<HTMLElement>): void {
  event.preventDefault();
}

/**
 * Renders the function-key legend as a toolbar of buttons, one per entry, in
 * the order given. The toolbar is rendered even when `keys` is empty, so a
 * screen's layout does not shift.
 */
export function FunctionKeyBar({ keys }: { keys: ReadonlyArray<FunctionKeyBarItem> }) {
  return (
    // The toolbar cancels mousedown too, because a press that misses every
    // enabled button would otherwise blur the focused field to <body>: a press
    // in the gap between keys, or on a disabled key, which browsers give no
    // mouse events of its own (global.css sets `pointer-events: none` on a
    // disabled key, so that press lands here instead).
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
          // onPress is called with no arguments, as the key dispatch calls it,
          // rather than passed as the click handler directly: a handler with an
          // optional parameter must never receive the click event in it.
          onClick={() => item.onPress()}
          onMouseDown={preventFocusSteal}
        >
          {item.label}
        </button>
      ))}
    </div>
  );
}
