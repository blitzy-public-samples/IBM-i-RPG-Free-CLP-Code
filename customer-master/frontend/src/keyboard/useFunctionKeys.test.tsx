/**
 * Specs for the shared component "Function keys and keyboard scope"
 * (AAP 0.4.4): `useFunctionKeys` and `ariaKeyShortcuts`, dispatched by
 * `KeyScopeProvider`. No handler sets React state and every event goes through
 * act-wrapped `fireEvent` or `userEvent`, so no spec warns about act(); no spec
 * makes a request, so the MSW server from `src/test/setup.ts` stays idle.
 */
import { StrictMode, useRef } from 'react';
import type { JSX, KeyboardEvent as ReactKeyboardEvent } from 'react';
import { act, fireEvent, render, screen } from '@testing-library/react';
import type { RenderResult } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { Mock } from 'vitest';

import { KeyScopeProvider } from './KeyScopeProvider';
import { ariaKeyShortcuts, useFunctionKeys } from './useFunctionKeys';
import type { CommandKey, KeyBindings } from './useFunctionKeys';

interface ScopeProps {
  name: string;
  bindings: KeyBindings;
  onUnbound: (k: CommandKey) => void;
  active?: boolean;
  withContainer?: boolean;
  onButtonClick?: () => void;
  onInputKeyDown?: (event: ReactKeyboardEvent<HTMLInputElement>) => void;
}

/**
 * A minimal screen that registers one key scope and renders one control of
 * each kind the Enter rule distinguishes: a text input, a button, a checkbox,
 * a select, an editable textarea, a read-only textarea (a protected value, as
 * FormField renders it) and, with `withContainer`, the scope's own container.
 * `onButtonClick` observes the native-Enter rule and `onInputKeyDown` the
 * one-handler rule.
 */
function Scope({
  name,
  bindings,
  onUnbound,
  active,
  withContainer = false,
  onButtonClick,
  onInputKeyDown,
}: ScopeProps): JSX.Element {
  const containerRef = useRef<HTMLDivElement>(null);
  useFunctionKeys(bindings, { onUnbound, active, containerRef });
  return (
    <section aria-label={`${name} screen`}>
      <input type="text" aria-label={`${name} input`} onKeyDown={onInputKeyDown} />
      <button type="button" onClick={onButtonClick}>
        {`${name} button`}
      </button>
      <input type="checkbox" aria-label={`${name} checkbox`} />
      <select aria-label={`${name} select`} defaultValue="1">
        <option value="1">One</option>
        <option value="2">Two</option>
      </select>
      <textarea aria-label={`${name} textarea`} />
      <textarea aria-label={`${name} read-only textarea`} readOnly defaultValue="PROTECTED VALUE" />
      {withContainer ? <div tabIndex={-1} ref={containerRef} aria-label={`${name} container`} /> : null}
    </section>
  );
}

function Stack({
  scopes,
  onBeforeCommand,
}: {
  scopes: readonly ScopeProps[];
  onBeforeCommand?: (key: CommandKey) => void;
}): JSX.Element {
  return (
    <KeyScopeProvider onBeforeCommand={onBeforeCommand}>
      {scopes.map((scope) => (
        <Scope key={scope.name} {...scope} />
      ))}
    </KeyScopeProvider>
  );
}

interface MountedStack extends RenderResult {
  /** Re-renders the provider with exactly `scopes`, keeping keyed scopes in place. */
  show(scopes: readonly ScopeProps[]): void;
}

/**
 * Mounts `scopes` bottom first, each in its own `rerender` step. The provider
 * orders scopes by registration time, and React runs child effects before
 * parent effects, so scopes mounted in one commit would not register in visual
 * order. As in the application, where the detail dialog and the picker open on
 * user actions, each scope therefore registers after the one beneath it.
 */
function mountStack(scopes: readonly ScopeProps[], onBeforeCommand?: (key: CommandKey) => void): MountedStack {
  const result = render(<Stack scopes={scopes.slice(0, 1)} onBeforeCommand={onBeforeCommand} />);
  const show = (next: readonly ScopeProps[]): void => {
    result.rerender(<Stack scopes={next} onBeforeCommand={onBeforeCommand} />);
  };
  for (let count = 2; count <= scopes.length; count += 1) {
    show(scopes.slice(0, count));
  }
  return { ...result, show };
}

interface ScopeFixture {
  readonly props: ScopeProps;
  readonly handlers: Readonly<Partial<Record<CommandKey, Mock<() => void>>>>;
  readonly onUnbound: Mock<(key: CommandKey) => void>;
}

/**
 * Every binding logs `'<name>:<key>'` and `onUnbound` logs
 * `'<name>:unbound:<key>'` into the shared `calls` log, so the order of calls
 * across scopes is assertable.
 */
function scopeFixture(
  name: string,
  keys: readonly CommandKey[],
  calls: string[],
  extra: Partial<Omit<ScopeProps, 'name' | 'bindings' | 'onUnbound'>> = {},
): ScopeFixture {
  const handlers: Partial<Record<CommandKey, Mock<() => void>>> = {};
  for (const key of keys) {
    handlers[key] = vi.fn(() => {
      calls.push(`${name}:${key}`);
    });
  }
  const onUnbound = vi.fn((key: CommandKey) => {
    calls.push(`${name}:unbound:${key}`);
  });
  return { props: { name, bindings: { ...handlers }, onUnbound, ...extra }, handlers, onUnbound };
}

function mockFor(fixture: ScopeFixture, key: CommandKey): Mock<() => void> {
  const mock = fixture.handlers[key];
  if (mock === undefined) {
    throw new Error(`${fixture.props.name} does not bind ${key}`);
  }
  return mock;
}

function allMocks(fixture: ScopeFixture): Mock[] {
  return [...Object.values(fixture.handlers), fixture.onUnbound].filter(
    (mock): mock is Mock => mock !== undefined,
  );
}

function expectUntouched(fixture: ScopeFixture): void {
  for (const mock of allMocks(fixture)) {
    expect(mock).not.toHaveBeenCalled();
  }
}

/**
 * Fires one keydown at `target` and reports whether it was prevented.
 * `fireEvent` returns `dispatchEvent`'s result, which is `false` exactly when
 * a listener called `preventDefault()` on the cancelable event.
 */
function isPrevented(target: Element | Document, init: KeyboardEventInit): boolean {
  return !fireEvent.keyDown(target, init);
}

function inputOf(name: string): HTMLElement {
  return screen.getByLabelText(`${name} input`);
}

/** The search screen's keys [5250_Subfile/PMTCUSTD.DSPF:34-39], paging and Enter. */
const SEARCH_KEYS: readonly CommandKey[] = ['F3', 'F4', 'F5', 'F6', 'F9', 'F12', 'PageUp', 'PageDown', 'Enter'];

/** The detail window's keys [5250_Subfile/MTNCUSTD.DSPF:33-35] and Enter. */
const DETAIL_KEYS: readonly CommandKey[] = ['F4', 'F5', 'F12', 'Enter'];

/** The State picker's keys [5250_Subfile/PMTSTATED.DSPF:68-71], paging and Enter. */
const PICKER_KEYS: readonly CommandKey[] = ['F3', 'F5', 'F7', 'F12', 'Enter', 'PageUp', 'PageDown'];

const MAPPED_KEYS: readonly CommandKey[] = [
  'F3',
  'F4',
  'F5',
  'F6',
  'F7',
  'F9',
  'F12',
  'PageUp',
  'PageDown',
  'Enter',
];

/** F1–F24, the function-key AIDs of [Copy_Mbrs/AIDBYTES.RPGLE:3-35]; binding them all exposes any chord dispatch. */
const ALL_FUNCTION_KEYS: readonly CommandKey[] = [
  'F1', 'F2', 'F3', 'F4', 'F5', 'F6', 'F7', 'F8', 'F9', 'F10', 'F11', 'F12',
  'F13', 'F14', 'F15', 'F16', 'F17', 'F18', 'F19', 'F20', 'F21', 'F22', 'F23', 'F24',
];

const SHIFT_CHORD_KEYS: readonly CommandKey[] = ALL_FUNCTION_KEYS.slice(0, 12);

/**
 * Keys that are not commands and must reach the page untouched: focus
 * navigation, editing and printable characters.
 */
const PASS_THROUGH_KEYS: readonly { readonly label: string; readonly init: KeyboardEventInit }[] = [
  { label: 'Tab', init: { key: 'Tab' } },
  { label: 'Shift+Tab', init: { key: 'Tab', shiftKey: true } },
  { label: 'ArrowLeft', init: { key: 'ArrowLeft' } },
  { label: 'ArrowRight', init: { key: 'ArrowRight' } },
  { label: 'ArrowUp', init: { key: 'ArrowUp' } },
  { label: 'ArrowDown', init: { key: 'ArrowDown' } },
  { label: 'Home', init: { key: 'Home' } },
  { label: 'End', init: { key: 'End' } },
  { label: 'Backspace', init: { key: 'Backspace' } },
  { label: 'Delete', init: { key: 'Delete' } },
  { label: 'a', init: { key: 'a' } },
  { label: '1', init: { key: '1' } },
  { label: 'Space', init: { key: ' ' } },
];

let calls: string[];

beforeEach(() => {
  calls = [];
});

describe('keyboard scope contract (AAP 0.4.4): command keys are dispatched and prevented', () => {
  it.each(MAPPED_KEYS)('a bound %s calls exactly its own binding once and is prevented', (key) => {
    const fixture = scopeFixture('screen', MAPPED_KEYS, calls);
    mountStack([fixture.props]);

    expect(isPrevented(inputOf('screen'), { key })).toBe(true);

    expect(mockFor(fixture, key)).toHaveBeenCalledTimes(1);
    expect(fixture.onUnbound).not.toHaveBeenCalled();
    expect(calls).toEqual([`screen:${key}`]);
  });

  it('Escape runs the F12 binding and is prevented, so one F12 binding cancels for both keys', () => {
    const fixture = scopeFixture('screen', MAPPED_KEYS, calls);
    mountStack([fixture.props]);

    expect(isPrevented(inputOf('screen'), { key: 'Escape' })).toBe(true);

    expect(mockFor(fixture, 'F12')).toHaveBeenCalledTimes(1);
    expect(calls).toEqual(['screen:F12']);
  });

  it('an unbound Escape reaches onUnbound as F12 and is prevented', () => {
    const fixture = scopeFixture('screen', ['Enter'], calls);
    mountStack([fixture.props]);

    expect(isPrevented(inputOf('screen'), { key: 'Escape' })).toBe(true);

    expect(fixture.onUnbound).toHaveBeenCalledExactlyOnceWith('F12');
    expect(calls).toEqual(['screen:unbound:F12']);
  });

  it('keys typed through user-event ({F5}, {Escape}, {PageDown}) reach their bindings', async () => {
    const user = userEvent.setup();
    const fixture = scopeFixture('screen', MAPPED_KEYS, calls);
    mountStack([fixture.props]);

    await user.keyboard('{F5}');
    await user.keyboard('{Escape}');
    await user.keyboard('{PageDown}');

    expect(mockFor(fixture, 'F5')).toHaveBeenCalledTimes(1);
    expect(mockFor(fixture, 'F12')).toHaveBeenCalledTimes(1);
    expect(mockFor(fixture, 'PageDown')).toHaveBeenCalledTimes(1);
    expect(calls).toEqual(['screen:F5', 'screen:F12', 'screen:PageDown']);
  });

  it('an unbound function key is prevented and answered by onUnbound with its key, never by a binding', () => {
    const fixture = scopeFixture('screen', MAPPED_KEYS, calls);
    mountStack([fixture.props]);

    expect(isPrevented(inputOf('screen'), { key: 'F8' })).toBe(true);
    expect(isPrevented(document.body, { key: 'F24' })).toBe(true);

    expect(fixture.onUnbound.mock.calls).toEqual([['F8'], ['F24']]);
    expect(calls).toEqual(['screen:unbound:F8', 'screen:unbound:F24']);
  });

  it('a binding set to undefined is unbound: the function key goes to onUnbound', () => {
    const onUnbound = vi.fn((key: CommandKey) => {
      calls.push(`screen:unbound:${key}`);
    });
    mountStack([{ name: 'screen', bindings: { F5: undefined }, onUnbound }]);

    expect(isPrevented(inputOf('screen'), { key: 'F5' })).toBe(true);

    expect(onUnbound).toHaveBeenCalledExactlyOnceWith('F5');
  });

  it('onBeforeCommand runs with the key before each dispatched handler, bound or unbound', () => {
    const fixture = scopeFixture('screen', MAPPED_KEYS, calls);
    const onBeforeCommand = vi.fn((key: CommandKey) => {
      calls.push(`before:${key}`);
    });
    mountStack([fixture.props], onBeforeCommand);

    fireEvent.keyDown(inputOf('screen'), { key: 'F5' });
    fireEvent.keyDown(inputOf('screen'), { key: 'Enter' });
    fireEvent.keyDown(inputOf('screen'), { key: 'Escape' });
    fireEvent.keyDown(inputOf('screen'), { key: 'F8' });

    expect(calls).toEqual([
      'before:F5',
      'screen:F5',
      'before:Enter',
      'screen:Enter',
      'before:F12',
      'screen:F12',
      'before:F8',
      'screen:unbound:F8',
    ]);
  });

  it('onBeforeCommand is not called for keys that pass through', () => {
    const fixture = scopeFixture('screen', MAPPED_KEYS, calls);
    const onBeforeCommand = vi.fn();
    mountStack([fixture.props], onBeforeCommand);

    fireEvent.keyDown(inputOf('screen'), { key: 'Tab' });
    fireEvent.keyDown(inputOf('screen'), { key: 'a' });
    fireEvent.keyDown(inputOf('screen'), { key: 'F5', ctrlKey: true });
    fireEvent.keyDown(screen.getByRole('button', { name: 'screen button' }), { key: 'Enter' });

    expect(onBeforeCommand).not.toHaveBeenCalled();
    expect(calls).toEqual([]);
  });

  it.each(SHIFT_CHORD_KEYS)(
    'Shift+%s is a modifier chord: not prevented, no binding, no onUnbound, no onBeforeCommand',
    (key) => {
      const fixture = scopeFixture('screen', ALL_FUNCTION_KEYS, calls);
      const onBeforeCommand = vi.fn();
      mountStack([fixture.props], onBeforeCommand);

      expect(isPrevented(inputOf('screen'), { key, shiftKey: true })).toBe(false);
      expect(isPrevented(document.body, { key, shiftKey: true })).toBe(false);

      expectUntouched(fixture);
      expect(onBeforeCommand).not.toHaveBeenCalled();
      expect(calls).toEqual([]);
    },
  );

  it('unmodified F13 and F24 keys reach their own bindings once each and are prevented', () => {
    const fixture = scopeFixture('screen', ['F13', 'F24'], calls);
    mountStack([fixture.props]);

    expect(isPrevented(inputOf('screen'), { key: 'F13' })).toBe(true);
    expect(isPrevented(inputOf('screen'), { key: 'F24' })).toBe(true);

    expect(mockFor(fixture, 'F13')).toHaveBeenCalledTimes(1);
    expect(mockFor(fixture, 'F24')).toHaveBeenCalledTimes(1);
    expect(fixture.onUnbound).not.toHaveBeenCalled();
    expect(calls).toEqual(['screen:F13', 'screen:F24']);
  });

  it('a dispatched key runs at most one handler: the element under focus never sees it', () => {
    const onInputKeyDown = vi.fn();
    const fixture = scopeFixture('screen', MAPPED_KEYS, calls, { onInputKeyDown });
    mountStack([fixture.props]);

    fireEvent.keyDown(inputOf('screen'), { key: 'F5' });
    fireEvent.keyDown(inputOf('screen'), { key: 'F8' });
    expect(onInputKeyDown).not.toHaveBeenCalled();

    fireEvent.keyDown(inputOf('screen'), { key: 'a' });
    expect(onInputKeyDown).toHaveBeenCalledTimes(1);
    expect(calls).toEqual(['screen:F5', 'screen:unbound:F8']);
  });

  it('with no scope registered, a command key is neither prevented nor dispatched', () => {
    const onBeforeCommand = vi.fn();
    render(
      <KeyScopeProvider onBeforeCommand={onBeforeCommand}>
        <p>No screen is open.</p>
      </KeyScopeProvider>,
    );

    expect(isPrevented(document.body, { key: 'F5' })).toBe(false);
    expect(isPrevented(document.body, { key: 'Escape' })).toBe(false);
    expect(onBeforeCommand).not.toHaveBeenCalled();
  });
});

describe('keyboard scope contract (AAP 0.4.4): only the topmost scope receives keys', () => {
  function stackOfThree(): {
    search: ScopeFixture;
    detail: ScopeFixture;
    picker: ScopeFixture;
    mounted: MountedStack;
  } {
    const search = scopeFixture('search', SEARCH_KEYS, calls);
    const detail = scopeFixture('detail', DETAIL_KEYS, calls);
    const picker = scopeFixture('picker', PICKER_KEYS, calls);
    const mounted = mountStack([search.props, detail.props, picker.props]);
    return { search, detail, picker, mounted };
  }

  it('Enter in the picker input and F12 run only the picker handlers; detail and search stay suspended', () => {
    const { search, detail, picker } = stackOfThree();

    expect(isPrevented(inputOf('picker'), { key: 'Enter' })).toBe(true);
    expect(isPrevented(inputOf('picker'), { key: 'F12' })).toBe(true);
    expect(isPrevented(inputOf('picker'), { key: 'Escape' })).toBe(true);

    expect(mockFor(picker, 'Enter')).toHaveBeenCalledTimes(1);
    expect(mockFor(picker, 'F12')).toHaveBeenCalledTimes(2);
    expectUntouched(detail);
    expectUntouched(search);
    expect(calls).toEqual(['picker:Enter', 'picker:F12', 'picker:F12']);
  });

  it('keys pressed on a control of a suspended scope still go to the topmost scope', () => {
    const { search, detail, picker } = stackOfThree();

    fireEvent.keyDown(inputOf('search'), { key: 'Enter' });
    fireEvent.keyDown(inputOf('detail'), { key: 'F5' });

    expect(mockFor(picker, 'Enter')).toHaveBeenCalledTimes(1);
    expect(mockFor(picker, 'F5')).toHaveBeenCalledTimes(1);
    expectUntouched(detail);
    expectUntouched(search);
  });

  it('an unbound function key calls only the topmost onUnbound, even when a scope beneath binds it', () => {
    const { search, detail, picker } = stackOfThree();

    // F8 is bound nowhere; F6 is bound only on the search page beneath, and F4
    // on both the search page and the detail dialog.
    expect(isPrevented(inputOf('picker'), { key: 'F8' })).toBe(true);
    expect(isPrevented(inputOf('picker'), { key: 'F6' })).toBe(true);
    expect(isPrevented(inputOf('picker'), { key: 'F4' })).toBe(true);

    expect(picker.onUnbound.mock.calls).toEqual([['F8'], ['F6'], ['F4']]);
    expectUntouched(detail);
    expectUntouched(search);
    expect(calls).toEqual(['picker:unbound:F8', 'picker:unbound:F6', 'picker:unbound:F4']);
  });

  it('removing the picker hands F12 to the detail dialog, and removing the detail hands it to search', () => {
    const { search, detail, picker, mounted } = stackOfThree();

    mounted.show([search.props, detail.props]);
    fireEvent.keyDown(inputOf('detail'), { key: 'F12' });
    expect(mockFor(detail, 'F12')).toHaveBeenCalledTimes(1);
    expect(mockFor(search, 'F12')).not.toHaveBeenCalled();

    mounted.show([search.props]);
    fireEvent.keyDown(inputOf('search'), { key: 'F12' });
    expect(mockFor(search, 'F12')).toHaveBeenCalledTimes(1);

    expectUntouched(picker);
    expect(calls).toEqual(['detail:F12', 'search:F12']);
  });

  it('a scope removed from the middle of the stack leaves the topmost scope in place', () => {
    const { search, detail, picker, mounted } = stackOfThree();

    mounted.show([search.props, picker.props]);
    fireEvent.keyDown(inputOf('picker'), { key: 'F12' });

    mounted.show([search.props]);
    fireEvent.keyDown(inputOf('search'), { key: 'F12' });

    expectUntouched(detail);
    expect(calls).toEqual(['picker:F12', 'search:F12']);
  });

  it('new bindings on a re-render are used in place and never re-push the scope', () => {
    const search = scopeFixture('search', SEARCH_KEYS, calls);
    const detail = scopeFixture('detail', DETAIL_KEYS, calls);
    const mounted = mountStack([search.props, detail.props]);

    const newSearchF12 = vi.fn(() => {
      calls.push('search:new-F12');
    });
    const searchUpdated: ScopeProps = { ...search.props, bindings: { ...search.props.bindings, F12: newSearchF12 } };

    mounted.show([searchUpdated, detail.props]);
    fireEvent.keyDown(inputOf('detail'), { key: 'F12' });
    expect(mockFor(detail, 'F12')).toHaveBeenCalledTimes(1);
    expect(newSearchF12).not.toHaveBeenCalled();

    mounted.show([searchUpdated]);
    fireEvent.keyDown(inputOf('search'), { key: 'F12' });
    expect(newSearchF12).toHaveBeenCalledTimes(1);
    expect(mockFor(search, 'F12')).not.toHaveBeenCalled();

    expect(calls).toEqual(['detail:F12', 'search:new-F12']);
  });

  it('a new onUnbound on a re-render is used in place', () => {
    const fixture = scopeFixture('screen', ['F12'], calls);
    const mounted = mountStack([fixture.props]);
    const newOnUnbound = vi.fn((key: CommandKey) => {
      calls.push(`screen:new-unbound:${key}`);
    });

    mounted.show([{ ...fixture.props, onUnbound: newOnUnbound }]);
    fireEvent.keyDown(inputOf('screen'), { key: 'F6' });

    expect(newOnUnbound).toHaveBeenCalledExactlyOnceWith('F6');
    expect(fixture.onUnbound).not.toHaveBeenCalled();
  });

  it('active false hands keys to the scope beneath; active true makes the scope topmost again', () => {
    const search = scopeFixture('search', SEARCH_KEYS, calls);
    const detail = scopeFixture('detail', DETAIL_KEYS, calls);
    const mounted = mountStack([search.props, detail.props]);

    mounted.show([search.props, { ...detail.props, active: false }]);
    fireEvent.keyDown(inputOf('search'), { key: 'F12' });
    fireEvent.keyDown(inputOf('search'), { key: 'F7' });

    mounted.show([search.props, { ...detail.props, active: true }]);
    fireEvent.keyDown(inputOf('detail'), { key: 'F12' });
    fireEvent.keyDown(inputOf('detail'), { key: 'F7' });

    expect(calls).toEqual(['search:F12', 'search:unbound:F7', 'detail:F12', 'detail:unbound:F7']);
  });

  it('a scope that becomes active again is pushed on top, above a scope registered while it was inactive', () => {
    const search = scopeFixture('search', SEARCH_KEYS, calls);
    const detail = scopeFixture('detail', DETAIL_KEYS, calls);
    const picker = scopeFixture('picker', PICKER_KEYS, calls);
    const mounted = mountStack([search.props, { ...detail.props, active: false }]);

    mounted.show([search.props, { ...detail.props, active: false }, picker.props]);
    fireEvent.keyDown(inputOf('picker'), { key: 'F12' });

    mounted.show([search.props, { ...detail.props, active: true }, picker.props]);
    fireEvent.keyDown(inputOf('picker'), { key: 'F12' });

    expectUntouched(search);
    expect(calls).toEqual(['picker:F12', 'detail:F12']);
  });

  it('a scope mounted inactive receives nothing', () => {
    const fixture = scopeFixture('screen', MAPPED_KEYS, calls, { active: false });
    mountStack([fixture.props]);

    expect(isPrevented(inputOf('screen'), { key: 'F5' })).toBe(false);
    expect(isPrevented(inputOf('screen'), { key: 'F8' })).toBe(false);
    expectUntouched(fixture);
  });
});

describe('keyboard scope contract (AAP 0.4.4): navigation, text and chords pass through', () => {
  /** Mounts the first `depth` of search, detail and picker; returns them all. */
  function stackOfDepth(depth: number): { fixtures: ScopeFixture[]; topName: string } {
    const fixtures = [
      scopeFixture('search', SEARCH_KEYS, calls),
      scopeFixture('detail', DETAIL_KEYS, calls),
      scopeFixture('picker', PICKER_KEYS, calls),
    ].slice(0, depth);
    mountStack(fixtures.map((fixture) => fixture.props));
    const top = fixtures[fixtures.length - 1];
    if (top === undefined) {
      throw new Error(`no scope at depth ${depth}`);
    }
    return { fixtures, topName: top.props.name };
  }

  it.each([1, 2, 3])(
    'with %i scope(s) registered, Tab, Shift+Tab, arrows, Home, End, Backspace, Delete and printable keys are never prevented or dispatched',
    (depth) => {
      const { fixtures, topName } = stackOfDepth(depth);
      const targets: readonly (Element | Document)[] = [inputOf(topName), document.body];

      for (const target of targets) {
        for (const { label, init } of PASS_THROUGH_KEYS) {
          expect(isPrevented(target, init), `${label} prevented`).toBe(false);
        }
      }

      for (const fixture of fixtures) {
        expectUntouched(fixture);
      }
      expect(calls).toEqual([]);
    },
  );

  it('Tab and Shift+Tab move focus between controls while three scopes are registered', async () => {
    const user = userEvent.setup();
    stackOfDepth(3);
    const input = inputOf('picker');
    const button = screen.getByRole('button', { name: 'picker button' });
    act(() => {
      input.focus();
    });

    await user.tab();
    expect(button).toHaveFocus();

    await user.tab({ shift: true });
    expect(input).toHaveFocus();
    expect(calls).toEqual([]);
  });

  it('text typed into an input with scopes registered arrives unchanged', async () => {
    const user = userEvent.setup();
    stackOfDepth(3);
    const input = inputOf('picker');
    act(() => {
      input.focus();
    });

    await user.keyboard('New York 1');

    expect(input).toHaveValue('New York 1');
    expect(calls).toEqual([]);
  });

  it.each([
    { label: 'Ctrl+F5', init: { key: 'F5', ctrlKey: true } },
    { label: 'Alt+F4', init: { key: 'F4', altKey: true } },
    { label: 'Meta+F12', init: { key: 'F12', metaKey: true } },
    { label: 'Ctrl+Enter', init: { key: 'Enter', ctrlKey: true } },
    { label: 'Shift+F1', init: { key: 'F1', shiftKey: true } },
    { label: 'Shift+F12', init: { key: 'F12', shiftKey: true } },
    { label: 'Shift+Enter', init: { key: 'Enter', shiftKey: true } },
    { label: 'Shift+PageDown', init: { key: 'PageDown', shiftKey: true } },
    { label: 'Shift+Escape', init: { key: 'Escape', shiftKey: true } },
  ])('the chord $label keeps its browser behaviour: not prevented, nothing dispatched', ({ init }) => {
    const { fixtures } = stackOfDepth(3);

    expect(isPrevented(inputOf('picker'), init)).toBe(false);

    for (const fixture of fixtures) {
      expectUntouched(fixture);
    }
  });

  it('a keydown that belongs to an IME composition passes through, Enter and F7 included', () => {
    const fixture = scopeFixture('screen', MAPPED_KEYS, calls);
    mountStack([fixture.props]);

    expect(isPrevented(inputOf('screen'), { key: 'Enter', isComposing: true })).toBe(false);
    expect(isPrevented(inputOf('screen'), { key: 'F7', isComposing: true })).toBe(false);
    expect(isPrevented(inputOf('screen'), { key: 'Enter', keyCode: 229 })).toBe(false);

    expectUntouched(fixture);
  });
});

describe('keyboard scope contract (AAP 0.4.4): Enter is a command only in a text input or the scope container', () => {
  it('Enter on a button keeps its native click and does not run the Enter binding', async () => {
    const user = userEvent.setup();
    const onButtonClick = vi.fn();
    const fixture = scopeFixture('detail', DETAIL_KEYS, calls, { onButtonClick });
    mountStack([fixture.props]);
    const button = screen.getByRole('button', { name: 'detail button' });
    act(() => {
      button.focus();
    });

    await user.keyboard('{Enter}');

    expect(onButtonClick).toHaveBeenCalledTimes(1);
    expect(mockFor(fixture, 'Enter')).not.toHaveBeenCalled();
    expect(isPrevented(button, { key: 'Enter' })).toBe(false);
    expectUntouched(fixture);
  });

  it.each(['checkbox', 'select', 'textarea'])(
    'Enter on a %s keeps its native action: not prevented, nothing dispatched',
    (control) => {
      const fixture = scopeFixture('detail', DETAIL_KEYS, calls);
      mountStack([fixture.props]);

      expect(isPrevented(screen.getByLabelText(`detail ${control}`), { key: 'Enter' })).toBe(false);

      expectUntouched(fixture);
    },
  );

  it('Enter on a read-only textarea (a protected value) runs the Enter binding once and is prevented, the value unchanged', async () => {
    const user = userEvent.setup();
    const fixture = scopeFixture('detail', DETAIL_KEYS, calls, { withContainer: true });
    mountStack([fixture.props]);
    const readOnly = screen.getByLabelText<HTMLTextAreaElement>('detail read-only textarea');
    act(() => {
      readOnly.focus();
    });

    await user.keyboard('{Enter}');

    expect(mockFor(fixture, 'Enter')).toHaveBeenCalledTimes(1);
    expect(fixture.onUnbound).not.toHaveBeenCalled();
    expect(calls).toEqual(['detail:Enter']);
    expect(readOnly).toHaveValue('PROTECTED VALUE');
    expect(readOnly).toHaveFocus();
    expect(isPrevented(readOnly, { key: 'Enter' })).toBe(true);
    expect(calls).toEqual(['detail:Enter', 'detail:Enter']);
  });

  it('Enter on an editable textarea keeps its new line and runs no binding', async () => {
    const user = userEvent.setup();
    const fixture = scopeFixture('detail', DETAIL_KEYS, calls);
    mountStack([fixture.props]);
    const editable = screen.getByLabelText<HTMLTextAreaElement>('detail textarea');

    await user.type(editable, 'AB{Enter}C');

    expect(editable).toHaveValue('AB\nC');
    expectUntouched(fixture);
    expect(calls).toEqual([]);
  });

  it('Enter on the scope container itself runs the Enter binding and is prevented', () => {
    const fixture = scopeFixture('detail', DETAIL_KEYS, calls, { withContainer: true });
    mountStack([fixture.props]);

    expect(isPrevented(screen.getByLabelText('detail container'), { key: 'Enter' })).toBe(true);

    expect(mockFor(fixture, 'Enter')).toHaveBeenCalledTimes(1);
    expect(calls).toEqual(['detail:Enter']);
  });

  it('Enter in a text input runs the binding whether or not the scope has a container', () => {
    const fixture = scopeFixture('detail', DETAIL_KEYS, calls, { withContainer: true });
    mountStack([fixture.props]);

    expect(isPrevented(inputOf('detail'), { key: 'Enter' })).toBe(true);

    expect(calls).toEqual(['detail:Enter']);
  });

  it('Enter on a container of a suspended scope stays native', () => {
    const search = scopeFixture('search', SEARCH_KEYS, calls, { withContainer: true });
    const detail = scopeFixture('detail', DETAIL_KEYS, calls);
    mountStack([search.props, detail.props]);

    expect(isPrevented(screen.getByLabelText('search container'), { key: 'Enter' })).toBe(false);

    expectUntouched(search);
    expectUntouched(detail);
  });

  it('an unbound Enter in a text input keeps its native behaviour and is not passed to onUnbound', () => {
    const fixture = scopeFixture('screen', ['F12'], calls);
    mountStack([fixture.props]);

    expect(isPrevented(inputOf('screen'), { key: 'Enter' })).toBe(false);

    expect(fixture.onUnbound).not.toHaveBeenCalled();
    expect(calls).toEqual([]);
  });

  it('an unbound PageUp or PageDown keeps its native scrolling and is not passed to onUnbound', () => {
    const fixture = scopeFixture('screen', ['F12'], calls);
    mountStack([fixture.props]);

    expect(isPrevented(inputOf('screen'), { key: 'PageDown' })).toBe(false);
    expect(isPrevented(document.body, { key: 'PageUp' })).toBe(false);

    expect(fixture.onUnbound).not.toHaveBeenCalled();
    expect(calls).toEqual([]);
  });

  it('Enter during IME composition dispatches nothing and is not prevented', () => {
    const fixture = scopeFixture('screen', MAPPED_KEYS, calls);
    mountStack([fixture.props]);

    expect(isPrevented(inputOf('screen'), { key: 'Enter', isComposing: true })).toBe(false);

    expect(mockFor(fixture, 'Enter')).not.toHaveBeenCalled();
  });

  it('a held Enter in a text input dispatches every repeated keydown like the first: each prevented, each run', () => {
    const fixture = scopeFixture('screen', MAPPED_KEYS, calls);
    const onBeforeCommand = vi.fn((key: CommandKey) => {
      calls.push(`before:${key}`);
    });
    mountStack([fixture.props], onBeforeCommand);

    expect(isPrevented(inputOf('screen'), { key: 'Enter' })).toBe(true);
    expect(isPrevented(inputOf('screen'), { key: 'Enter', repeat: true })).toBe(true);
    expect(isPrevented(inputOf('screen'), { key: 'Enter', repeat: true })).toBe(true);

    expect(mockFor(fixture, 'Enter')).toHaveBeenCalledTimes(3);
    expect(onBeforeCommand.mock.calls).toEqual([['Enter'], ['Enter'], ['Enter']]);
    expect(calls).toEqual([
      'before:Enter',
      'screen:Enter',
      'before:Enter',
      'screen:Enter',
      'before:Enter',
      'screen:Enter',
    ]);
  });

  it('a held Enter on a button keeps its native action: no repeated keydown is prevented or dispatched', () => {
    const fixture = scopeFixture('detail', DETAIL_KEYS, calls);
    const onBeforeCommand = vi.fn();
    mountStack([fixture.props], onBeforeCommand);
    const button = screen.getByRole('button', { name: 'detail button' });

    expect(isPrevented(button, { key: 'Enter' })).toBe(false);
    expect(isPrevented(button, { key: 'Enter', repeat: true })).toBe(false);
    expect(isPrevented(button, { key: 'Enter', repeat: true })).toBe(false);

    expectUntouched(fixture);
    expect(onBeforeCommand).not.toHaveBeenCalled();
    expect(calls).toEqual([]);
  });
});

describe('keyboard scope contract (AAP 0.4.4): one listener and scopes tied to mounting', () => {
  it('under StrictMode one F12 keydown runs its handler exactly once (one listener, one scope)', () => {
    const fixture = scopeFixture('screen', MAPPED_KEYS, calls);
    const onBeforeCommand = vi.fn();
    render(
      <StrictMode>
        <KeyScopeProvider onBeforeCommand={onBeforeCommand}>
          <Scope {...fixture.props} />
        </KeyScopeProvider>
      </StrictMode>,
    );

    expect(isPrevented(inputOf('screen'), { key: 'F12' })).toBe(true);

    expect(mockFor(fixture, 'F12')).toHaveBeenCalledTimes(1);
    expect(onBeforeCommand).toHaveBeenCalledExactlyOnceWith('F12');
    expect(calls).toEqual(['screen:F12']);
  });

  it('under StrictMode, removing the only scope leaves the stack empty', () => {
    const fixture = scopeFixture('screen', MAPPED_KEYS, calls);
    const { rerender } = render(
      <StrictMode>
        <KeyScopeProvider>
          <Scope {...fixture.props} />
        </KeyScopeProvider>
      </StrictMode>,
    );

    rerender(
      <StrictMode>
        <KeyScopeProvider>
          <p>No screen is open.</p>
        </KeyScopeProvider>
      </StrictMode>,
    );

    expect(isPrevented(document.body, { key: 'F12' })).toBe(false);
    expectUntouched(fixture);
  });

  it('after the whole tree unmounts, the document listener is gone and F5 is not prevented', () => {
    const fixture = scopeFixture('screen', MAPPED_KEYS, calls);
    const { unmount } = mountStack([fixture.props]);

    unmount();

    expect(isPrevented(document.body, { key: 'F5' })).toBe(false);
    expect(isPrevented(document.body, { key: 'F8' })).toBe(false);
    expectUntouched(fixture);
  });

  it('useFunctionKeys throws outside a KeyScopeProvider', () => {
    const consoleError = vi.spyOn(console, 'error').mockImplementation(() => {});
    const fixture = scopeFixture('screen', MAPPED_KEYS, calls);

    try {
      expect(() => render(<Scope {...fixture.props} />)).toThrow(
        'useFunctionKeys must be used inside <KeyScopeProvider>',
      );
    } finally {
      consoleError.mockRestore();
    }
  });
});

describe('ariaKeyShortcuts: the aria-keyshortcuts value of each command key', () => {
  it.each<[CommandKey, string]>([
    ['F3', 'F3'],
    ['F12', 'F12 Escape'],
    ['F13', 'F13'],
    ['F24', 'F24'],
    ['Enter', 'Enter'],
    ['PageUp', 'PageUp'],
    ['PageDown', 'PageDown'],
  ])('%s is announced as "%s", listing every key the provider accepts for it', (key, expected) => {
    expect(ariaKeyShortcuts(key)).toBe(expected);
  });
});
