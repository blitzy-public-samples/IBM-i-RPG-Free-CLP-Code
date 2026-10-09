/**
 * ScrollRegion: the box a list table scrolls sideways in when it is wider
 * than the space it has, so the page itself never scrolls sideways
 * (`.scroll-region` in `src/styles/global.css`). The customer and state
 * lists keep their columns whole at a minimum width, which a phone-width
 * screen or window cannot always give them.
 *
 * While its content overflows, the box is a named, focusable region
 * (`role="region"`, `aria-labelledby`, `tabIndex={0}`), so a keyboard user
 * can reach it with Tab and scroll it with the arrow keys even when it holds
 * no control, and assistive technology announces what scrolls. While
 * everything fits, it is a plain box and adds no tab stop. A click on plain
 * text inside an overflowing region focuses the region, as it focuses any
 * focusable box.
 *
 * Overflow is tracked with a `ResizeObserver` on the box and on each element
 * it holds when it mounts, which reports the first size on its own. Where the
 * browser has no `ResizeObserver` the box stays plain and still scrolls.
 *
 * @example
 * ```tsx
 * <ScrollRegion labelledBy={captionId}>
 *   <table className="results-table">
 *     <caption id={captionId}>Customers</caption>
 *   </table>
 * </ScrollRegion>
 * ```
 */
import { useLayoutEffect, useRef, useState } from 'react';
import type { ReactNode } from 'react';

export interface ScrollRegionProps {
  /** Id of the element that names the region while it overflows, normally the table's caption. */
  labelledBy: string;
  /** Extra class names, added after `scroll-region`. */
  className?: string;
  /** The content that may be wider than the box, normally one table. */
  children: ReactNode;
}

/** Renders `children` in a sideways-scrolling box that becomes a focusable region while it overflows. */
export function ScrollRegion({ labelledBy, className, children }: ScrollRegionProps) {
  const boxRef = useRef<HTMLDivElement>(null);
  const [overflowing, setOverflowing] = useState(false);

  useLayoutEffect(() => {
    const box = boxRef.current;
    if (box === null || typeof ResizeObserver === 'undefined') {
      return undefined;
    }
    // The box's size changes with the viewport, its content's with the font
    // size; either can start or end the overflow.
    const observer = new ResizeObserver(() => {
      setOverflowing(box.scrollWidth > box.clientWidth);
    });
    observer.observe(box);
    for (const child of Array.from(box.children)) {
      observer.observe(child);
    }
    return () => observer.disconnect();
  }, []);

  return (
    <div
      ref={boxRef}
      className={className ? `scroll-region ${className}` : 'scroll-region'}
      role={overflowing ? 'region' : undefined}
      aria-labelledby={overflowing ? labelledBy : undefined}
      tabIndex={overflowing ? 0 : undefined}
    >
      {children}
    </div>
  );
}
