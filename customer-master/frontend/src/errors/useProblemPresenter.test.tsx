/**
 * Tests of `useProblemPresenter().present(error, options)` along the real path
 * `present` -> `useToasts().publish` -> the `role="alert"` region of
 * `ToastRegion`. The only request is the catalog fetch, answered by the default
 * MSW handler of `../test/handlers`.
 */
import { useEffect } from 'react';
import type { ReactElement } from 'react';
import { act, render, screen, waitFor, within } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { MockInstance } from 'vitest';
import { ApiError, syntheticProblem } from '../api/problem';
import type { FieldError, Problem } from '../api/problem';
import { ToastProvider } from '../components/ToastRegion';
import { MessageCatalogProvider, useMessages } from '../messages/MessageCatalogProvider';
import { useProblemPresenter } from './useProblemPresenter';
import type { PresentOptions, ProblemPresenter } from './useProblemPresenter';

// Catalog texts (AAP 0.7.3, corrections included), as `detail` carries them.
const NAME_BLANK = 'Name: Must not be blank';
const ADDRESS_NOT_FOUND = 'USPS: Address Not Found.';
const RECORD_CHANGED = 'Someone else changed record. Review data.';
const SIGN_IN_REQUIRED = 'Sign in required.';
const NOT_AUTHORIZED = 'You are not authorized to perform this action.';
const CUSTOMER_DELETED = 'Customer deleted. Exit & redo search.';
const CUSTOMER_LOCKED = 'Customer being updated by another user or job.';
const PROGRAM_ERROR = 'Program Error! Please contact IT now.';

/** The correlation id of the 500 case; the server logs it, the user never sees it. */
const ERROR_ID = '0b9f6a3e-5c1d-4e2f-8a7b-9c0d1e2f3a4b';

/** The HTTP reason phrases the server puts in `title` for the statuses used here. */
const REASON_PHRASES: Readonly<Record<number, string>> = {
  401: 'Unauthorized',
  403: 'Forbidden',
  404: 'Not Found',
  409: 'Conflict',
  422: 'Unprocessable Entity',
  500: 'Internal Server Error',
};

type Present = ProblemPresenter['present'];

type PresenterSink = (present: Present) => void;

/**
 * Calls the hook under test and hands its `present` out through an effect,
 * never by assigning to an outer variable during render. `ready` shows when
 * the catalog has loaded, so a test never presents against the bare codes.
 */
function Probe({ onPresenter }: { onPresenter: PresenterSink }): ReactElement {
  const { present } = useProblemPresenter();
  const { ready } = useMessages();
  useEffect(() => {
    onPresenter(present);
  }, [present, onPresenter]);
  return <span data-testid="catalog-ready">{String(ready)}</span>;
}

interface Harness {
  /** Runs the latest `present` the probe was handed, inside `act`. */
  present(error: unknown, options?: PresentOptions): void;
  readonly presenters: readonly Present[];
  /** Re-renders the same tree over the same client, with `onPresenter` (the default sink unless given). */
  rerender(onPresenter?: PresenterSink): void;
}

/**
 * Renders the probe inside the real providers, in the nesting order of
 * `src/App.tsx`, and waits until the catalog from `GET /api/messages` has
 * loaded. Each call builds its own `QueryClient`, so no catalog survives from
 * one test to the next.
 */
async function setup(): Promise<Harness> {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const presenters: Present[] = [];
  const record: PresenterSink = (present) => {
    presenters.push(present);
  };

  const tree = (onPresenter: PresenterSink): ReactElement => (
    <QueryClientProvider client={queryClient}>
      <MessageCatalogProvider>
        <ToastProvider>
          <Probe onPresenter={onPresenter} />
        </ToastProvider>
      </MessageCatalogProvider>
    </QueryClientProvider>
  );

  const view = render(tree(record));
  await waitFor(() => expect(screen.getByTestId('catalog-ready')).toHaveTextContent('true'));

  return {
    present(error: unknown, options?: PresentOptions): void {
      const latest = presenters.at(-1);
      if (latest === undefined) {
        throw new Error('The probe never received a presenter from useProblemPresenter');
      }
      act(() => {
        latest(error, options);
      });
    },
    presenters,
    rerender(onPresenter: PresenterSink = record): void {
      view.rerender(tree(onPresenter));
    },
  };
}

/** An `ApiError` as `api/client.ts` builds it from a problem+json response; case-specific members go in `extra`. */
function apiError(status: number, code: string, detail: string, extra: Partial<Problem> = {}): ApiError {
  return new ApiError(status, {
    type: `urn:customer-master:problem:${code}`,
    title: REASON_PHRASES[status] ?? '',
    status,
    detail,
    code,
    args: [],
    instance: '/api/customers',
    ...extra,
  });
}

const alertRegion = (): HTMLElement => screen.getByRole('alert');

const statusRegion = (): HTMLElement => screen.getByRole('status');

/**
 * Asserts that the alert region holds exactly one toast and that it carries
 * `text`, and that the status region stayed empty.
 */
function expectSingleAlert(text: string): void {
  const alerts = within(alertRegion());
  expect(alerts.getByText(text)).toBeInTheDocument();
  expect(alerts.getAllByText(text)).toHaveLength(1);
  expect(alertRegion().childElementCount).toBe(1);
  expect(statusRegion().textContent).toBe('');
}

/** The position of a mock's first call among all mock calls; fails when it was never called. */
function firstCallOrder(fn: { readonly mock: { readonly invocationCallOrder: readonly number[] } }): number {
  const [order] = fn.mock.invocationCallOrder;
  if (order === undefined) {
    throw new Error('The mock was never called');
  }
  return order;
}

/** Fresh field callbacks, typed by the presenter's own contract. */
function fieldCallbacks() {
  return {
    setFieldErrors: vi.fn<NonNullable<PresentOptions['setFieldErrors']>>(),
    focusField: vi.fn<NonNullable<PresentOptions['focusField']>>(),
  };
}

/** The customer as now stored, as a 409 DEM1002 problem's `current` carries it (seed row AAAD after a change). */
function storedCustomer(): NonNullable<Problem['current']> {
  return {
    custId: 'AAAD',
    name: "NIBH L'LOR COMPANY",
    addr: 'P.O. BOX 103, 9218 VIVAMUS AVENUE',
    city: 'AUBURN',
    state: 'CA',
    zip: '15762-0001',
    corpPhone: '(415) 007-0420',
    acctMgr: 'NORMAN, ABBOT R.',
    acctPhone: '(757) 158-0941',
    active: 'Y',
    chgTime: '2026-10-05T14:03:09Z',
    chgUser: 'sales',
    version: 1,
  };
}

/**
 * `console.error` is where React reports an update outside `act` and where
 * MSW reports a request no handler answered; neither may happen here.
 */
let consoleError: MockInstance<typeof console.error>;

beforeEach(() => {
  consoleError = vi.spyOn(console, 'error');
});

afterEach(() => {
  expect(consoleError).not.toHaveBeenCalled();
});

describe('useProblemPresenter: 422 field errors', () => {
  it('highlights the fields, focuses the first after the highlight, and shows one alert', async () => {
    const harness = await setup();
    const errors: FieldError[] = [{ field: 'name', code: 'DEM0502', message: NAME_BLANK }];
    const { setFieldErrors, focusField } = fieldCallbacks();

    harness.present(apiError(422, 'DEM0502', NAME_BLANK, { args: ['Name'], errors }), { setFieldErrors, focusField });

    expect(setFieldErrors).toHaveBeenCalledExactlyOnceWith(errors);
    expect(focusField).toHaveBeenCalledExactlyOnceWith('name');
    expect(firstCallOrder(setFieldErrors)).toBeLessThan(firstCallOrder(focusField));
    expectSingleAlert(NAME_BLANK);
  });

  it('passes the four DEM9898 address errors in server order and focuses addr', async () => {
    const harness = await setup();
    const errors: FieldError[] = ['addr', 'city', 'state', 'zip'].map((field) => ({
      field,
      code: 'DEM9898',
      message: ADDRESS_NOT_FOUND,
    }));
    const { setFieldErrors, focusField } = fieldCallbacks();

    harness.present(
      apiError(422, 'DEM9898', ADDRESS_NOT_FOUND, {
        args: ['Address Not Found.'],
        errors,
        instance: '/api/customers/review',
        stateAccepted: 'CA',
      }),
      { setFieldErrors, focusField },
    );

    expect(setFieldErrors).toHaveBeenCalledExactlyOnceWith(errors);
    expect(setFieldErrors.mock.calls[0]?.[0].map((error) => error.field)).toEqual(['addr', 'city', 'state', 'zip']);
    expect(focusField).toHaveBeenCalledExactlyOnceWith('addr');
    expect(firstCallOrder(setFieldErrors)).toBeLessThan(firstCallOrder(focusField));
    expectSingleAlert(ADDRESS_NOT_FOUND);
  });

  it('shows only the alert when no callbacks are passed, and never throws', async () => {
    const harness = await setup();
    const errors: FieldError[] = [{ field: 'name', code: 'DEM0502', message: NAME_BLANK }];

    expect(() => {
      harness.present(apiError(422, 'DEM0502', NAME_BLANK, { args: ['Name'], errors }));
    }).not.toThrow();

    expectSingleAlert(NAME_BLANK);
  });

  it('does not focus a field when focusField is passed without setFieldErrors', async () => {
    const harness = await setup();
    const errors: FieldError[] = [{ field: 'name', code: 'DEM0502', message: NAME_BLANK }];
    const { focusField } = fieldCallbacks();

    harness.present(apiError(422, 'DEM0502', NAME_BLANK, { args: ['Name'], errors }), { focusField });

    expect(focusField).not.toHaveBeenCalled();
    expectSingleAlert(NAME_BLANK);
  });
});

// A stale update: UpdateRecd's DEM1002 branch showed its message and re-read the row for review.
describe('useProblemPresenter: 409 DEM1002', () => {
  it('hands current to onConflict and publishes no toast', async () => {
    const harness = await setup();
    const current = storedCustomer();
    const onConflict = vi.fn<NonNullable<PresentOptions['onConflict']>>();
    const { setFieldErrors, focusField } = fieldCallbacks();

    harness.present(apiError(409, 'DEM1002', RECORD_CHANGED, { current, instance: '/api/customers/AAAD' }), {
      setFieldErrors,
      focusField,
      onConflict,
    });

    expect(onConflict).toHaveBeenCalledOnce();
    expect(onConflict.mock.calls[0]?.[0]).toBe(current);
    expect(alertRegion().textContent).toBe('');
    expect(statusRegion().textContent).toBe('');
    expect(screen.queryByText(RECORD_CHANGED)).toBeNull();
    expect(setFieldErrors).not.toHaveBeenCalled();
    expect(focusField).not.toHaveBeenCalled();
  });

  it('shows the DEM1002 alert when no onConflict is passed', async () => {
    const harness = await setup();

    harness.present(apiError(409, 'DEM1002', RECORD_CHANGED, { current: storedCustomer(), instance: '/api/customers/AAAD' }));

    expectSingleAlert(RECORD_CHANGED);
  });

  it('falls back to the alert when the problem carries no current', async () => {
    const harness = await setup();
    const onConflict = vi.fn<NonNullable<PresentOptions['onConflict']>>();

    harness.present(apiError(409, 'DEM1002', RECORD_CHANGED, { instance: '/api/customers/AAAD' }), { onConflict });

    expect(onConflict).not.toHaveBeenCalled();
    expectSingleAlert(RECORD_CHANGED);
  });
});

describe('useProblemPresenter: alerts by status', () => {
  it.each([
    { status: 401, code: 'APP0401', detail: SIGN_IN_REQUIRED, extra: {} },
    { status: 403, code: 'APP0403', detail: NOT_AUTHORIZED, extra: {} },
    { status: 404, code: 'DEM0599', detail: CUSTOMER_DELETED, extra: { instance: '/api/customers/AAAD' } },
    { status: 409, code: 'DEM1001', detail: CUSTOMER_LOCKED, extra: { instance: '/api/customers/AAAD' } },
    { status: 500, code: 'DEM9999', detail: PROGRAM_ERROR, extra: { errorId: ERROR_ID } },
  ] satisfies ReadonlyArray<{ status: number; code: string; detail: string; extra: Partial<Problem> }>)(
    '$status $code shows exactly one alert with its detail and nothing internal',
    async ({ status, code, detail, extra }) => {
      const harness = await setup();
      const { setFieldErrors, focusField } = fieldCallbacks();
      const onConflict = vi.fn<NonNullable<PresentOptions['onConflict']>>();

      harness.present(apiError(status, code, detail, extra), { setFieldErrors, focusField, onConflict });

      expectSingleAlert(detail);
      // No `errors` member: nothing to highlight or focus, and only DEM1002 opens the compare dialog.
      expect(setFieldErrors).not.toHaveBeenCalled();
      expect(focusField).not.toHaveBeenCalled();
      expect(onConflict).not.toHaveBeenCalled();
      // Neither the catalog key nor the 500's correlation id reaches the user.
      expect(document.body.textContent).not.toContain(code);
      expect(document.body.textContent).not.toContain(ERROR_ID);
    },
  );
});

describe('useProblemPresenter: synthetic DEM9999', () => {
  it.each([
    { label: 'a response without a problem body', error: () => new ApiError(500, syntheticProblem(500)), hidden: [] },
    { label: 'a call that received no HTTP response', error: () => new ApiError(0, syntheticProblem(0)), hidden: [] },
    { label: 'an Error that is not an ApiError', error: () => new Error('boom'), hidden: ['boom'] },
    { label: 'a thrown string', error: () => 'boom', hidden: ['boom'] },
  ] satisfies ReadonlyArray<{ label: string; error: () => unknown; hidden: readonly string[] }>)(
    'shows the catalog text of DEM9999 for $label',
    async ({ error, hidden }) => {
      const harness = await setup();

      harness.present(error());

      expectSingleAlert(PROGRAM_ERROR);
      // The text is the one GET /api/messages served, not the bare key.
      expect(document.body.textContent).not.toContain('DEM9999');
      for (const text of hidden) {
        expect(document.body.textContent).not.toContain(text);
      }
    },
  );
});

// Features list `present` in their dependency arrays, so it must stay referentially stable.
describe('useProblemPresenter: stable present', () => {
  it('hands out the same present on re-render once the catalog has loaded', async () => {
    const harness = await setup();
    const loaded = harness.presenters.at(-1);
    const handedOut = harness.presenters.length;
    expect(loaded).toBeDefined();

    // The same sink must not re-run the effect; a new sink forces it and must receive the same `present`.
    harness.rerender();
    expect(harness.presenters).toHaveLength(handedOut);

    const sink = vi.fn<PresenterSink>();
    harness.rerender(sink);
    expect(sink).toHaveBeenCalledOnce();
    expect(sink.mock.calls[0]?.[0]).toBe(loaded);
    expect(harness.presenters).toHaveLength(handedOut);
  });
});
