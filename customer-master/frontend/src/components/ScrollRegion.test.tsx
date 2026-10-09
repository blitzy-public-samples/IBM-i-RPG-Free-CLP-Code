/**
 * Component tests for {@link ScrollRegion}. jsdom lays nothing out and has no
 * `ResizeObserver`, so the tests that need overflow install a controllable
 * observer and give the box the scroll and client widths a browser would
 * measure. Attributes and roles are asserted; markup is never snapshotted.
 */
import { act, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { ScrollRegion } from './ScrollRegion';

/** The observers the component created, in order, as the stubbed global records them. */
const observers: FakeResizeObserver[] = [];

/** A `ResizeObserver` whose notifications the test sends with {@link notify}. */
class FakeResizeObserver {
  readonly observed: Element[] = [];
  disconnected = false;
  private readonly callback: ResizeObserverCallback;

  constructor(callback: ResizeObserverCallback) {
    this.callback = callback;
    observers.push(this);
  }

  observe(target: Element): void {
    this.observed.push(target);
  }

  unobserve(target: Element): void {
    this.observed.splice(this.observed.indexOf(target), 1);
  }

  disconnect(): void {
    this.disconnected = true;
    this.observed.length = 0;
  }

  /** Delivers one notification, as the browser does after a size change. */
  notify(): void {
    act(() => {
      this.callback([], this as unknown as ResizeObserver);
    });
  }
}

/** Gives `box` the widths layout would measure: its content `scrollWidth` and its own `clientWidth`. */
function setWidths(box: HTMLElement, scrollWidth: number, clientWidth: number): void {
  Object.defineProperty(box, 'scrollWidth', { configurable: true, value: scrollWidth });
  Object.defineProperty(box, 'clientWidth', { configurable: true, value: clientWidth });
}

function onlyObserver(): FakeResizeObserver {
  expect(observers).toHaveLength(1);
  const [observer] = observers;
  if (observer === undefined) {
    throw new Error('ScrollRegion created no ResizeObserver');
  }
  return observer;
}

function renderRegion(className?: string) {
  const view = render(
    <ScrollRegion labelledBy="list-caption" className={className}>
      <table>
        <caption id="list-caption">Customers</caption>
        <tbody>
          <tr>
            <td>AACOCA JVVEHMAG</td>
          </tr>
        </tbody>
      </table>
    </ScrollRegion>,
  );
  const table = screen.getByRole('table', { name: 'Customers' });
  const box = table.parentElement;
  if (box === null) {
    throw new Error('The table has no box around it');
  }
  return { ...view, box, table };
}

afterEach(() => {
  vi.unstubAllGlobals();
  observers.length = 0;
});

describe('ScrollRegion', () => {
  it('without ResizeObserver renders a plain scroll box: no role, no name and no tab stop', () => {
    expect(globalThis.ResizeObserver).toBeUndefined();

    const { box } = renderRegion();

    expect(box).toHaveClass('scroll-region');
    expect(box).not.toHaveAttribute('role');
    expect(box).not.toHaveAttribute('aria-labelledby');
    expect(box).not.toHaveAttribute('tabindex');
    expect(screen.queryByRole('region')).not.toBeInTheDocument();
  });

  it('adds a given class after scroll-region', () => {
    const { box } = renderRegion('state-picker__list');

    expect(box).toHaveAttribute('class', 'scroll-region state-picker__list');
  });

  it('observes the box and the table it holds, and stays plain while the table fits', () => {
    vi.stubGlobal('ResizeObserver', FakeResizeObserver);

    const { box, table } = renderRegion();
    const observer = onlyObserver();

    expect(observer.observed).toEqual([box, table]);
    setWidths(box, 600, 600);
    observer.notify();

    expect(box).not.toHaveAttribute('role');
    expect(box).not.toHaveAttribute('tabindex');
  });

  it('while the table overflows, is a tab stop and a region named by the caption; plain again once it fits', () => {
    vi.stubGlobal('ResizeObserver', FakeResizeObserver);

    const { box } = renderRegion();
    const observer = onlyObserver();

    setWidths(box, 623, 358);
    observer.notify();

    expect(screen.getByRole('region', { name: 'Customers' })).toBe(box);
    expect(box).toHaveAttribute('aria-labelledby', 'list-caption');
    expect(box).toHaveAttribute('tabindex', '0');

    setWidths(box, 736, 736);
    observer.notify();

    expect(screen.queryByRole('region')).not.toBeInTheDocument();
    expect(box).not.toHaveAttribute('aria-labelledby');
    expect(box).not.toHaveAttribute('tabindex');
  });

  it('disconnects its observer on unmount', () => {
    vi.stubGlobal('ResizeObserver', FakeResizeObserver);

    const { unmount } = renderRegion();
    const observer = onlyObserver();
    expect(observer.disconnected).toBe(false);

    unmount();

    expect(observer.disconnected).toBe(true);
  });
});
