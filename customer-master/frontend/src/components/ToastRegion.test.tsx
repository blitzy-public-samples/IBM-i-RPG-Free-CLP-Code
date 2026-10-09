/**
 * Component tests for the focus-keeping effect of {@link ToastRegion}: when
 * the message list goes from empty to holding a message, a control focused
 * inside an open window is scrolled to the nearest edge of view, and focus
 * anywhere else is left alone. jsdom lays nothing out, so the tests spy on
 * `scrollIntoView` (src/test/setup.ts fills it in) and assert the calls, the
 * element each was made on and its options. Roles are asserted; markup is
 * never snapshotted.
 */
import { useEffect } from 'react';
import type { ReactElement, ReactNode } from 'react';
import { act, render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import type { MockInstance } from 'vitest';
import { ToastProvider, useToasts } from './ToastRegion';

type ToastApi = ReturnType<typeof useToasts>;

/** Hands the provider's `{ publish, clear }` out through an effect, never by assigning during render. */
function ApiProbe({ onApi }: { onApi: (api: ToastApi) => void }): ReactElement | null {
  const api = useToasts();
  useEffect(() => {
    onApi(api);
  }, [api, onApi]);
  return null;
}

/** Renders `screenContent` inside `ToastProvider`, as src/App.tsx mounts it, and returns the toast API. */
function renderWithToasts(screenContent: ReactNode): ToastApi {
  const apis: ToastApi[] = [];
  const record = (api: ToastApi) => {
    apis.push(api);
  };
  render(
    <ToastProvider>
      <ApiProbe onApi={record} />
      {screenContent}
    </ToastProvider>,
  );
  const api = apis.at(-1);
  if (api === undefined) {
    throw new Error('ToastProvider handed out no API');
  }
  return api;
}

/** Publishes one message inside `act`, so the region and its layout effect have committed on return. */
function publishAlert(api: ToastApi, text: string): void {
  act(() => {
    api.publish({ kind: 'alert', text });
  });
}

function spyOnScrollIntoView(): MockInstance<Element['scrollIntoView']> {
  return vi.spyOn(Element.prototype, 'scrollIntoView');
}

describe('ToastRegion focus in a window', () => {
  it('scrolls the control focused in an open window into view on the first message only', () => {
    const api = renderWithToasts(
      <dialog open aria-label="USA States">
        <label htmlFor="opt-ar">Option for Arkansas</label>
        <input id="opt-ar" />
      </dialog>,
    );
    const option = screen.getByLabelText('Option for Arkansas');
    option.focus();
    const scrollIntoView = spyOnScrollIntoView();

    publishAlert(api, '9 is not a valid option at this time.');

    expect(screen.getByRole('alert')).toHaveTextContent('9 is not a valid option at this time.');
    expect(scrollIntoView).toHaveBeenCalledTimes(1);
    expect(scrollIntoView).toHaveBeenCalledWith({ block: 'nearest', inline: 'nearest' });
    expect(scrollIntoView.mock.contexts[0]).toBe(option);

    // A further message leaves the window as the first one did: no scroll.
    publishAlert(api, 'Key is not active now');

    expect(screen.getByRole('alert')).toHaveTextContent('Key is not active now');
    expect(scrollIntoView).toHaveBeenCalledTimes(1);

    // Once the next action has cleared the list, the next message is a first one again.
    act(() => {
      api.clear();
    });
    expect(scrollIntoView).toHaveBeenCalledTimes(1);

    publishAlert(api, '9 is not a valid option at this time.');

    expect(scrollIntoView).toHaveBeenCalledTimes(2);
    expect(scrollIntoView.mock.contexts[1]).toBe(option);
  });

  it('leaves focus outside every open window alone, so the page never scrolls', () => {
    const api = renderWithToasts(
      <>
        <label htmlFor="name">Name starts with:</label>
        <input id="name" />
      </>,
    );
    const pageField = screen.getByLabelText('Name starts with:');
    const scrollIntoView = spyOnScrollIntoView();

    pageField.focus();
    expect(document.activeElement).toBe(pageField);
    publishAlert(api, 'Use F4 only if + is on field');
    act(() => {
      api.clear();
    });

    // Nothing focused: the body holds focus.
    pageField.blur();
    expect(document.activeElement).toBe(document.body);
    publishAlert(api, 'Key is not active now');

    expect(screen.getByRole('alert')).toHaveTextContent('Key is not active now');
    expect(scrollIntoView).not.toHaveBeenCalled();
  });

  it('keeps both live regions rendered, empty, before any message', () => {
    renderWithToasts(null);

    expect(screen.getByRole('status')).toBeEmptyDOMElement();
    expect(screen.getByRole('alert')).toBeEmptyDOMElement();
  });
});
