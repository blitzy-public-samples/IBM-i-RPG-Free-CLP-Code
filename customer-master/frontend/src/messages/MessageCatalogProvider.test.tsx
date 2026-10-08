/**
 * Tests of the client message catalog: `./MessageCatalogProvider.tsx`
 * (`MessageCatalogProvider`, `useMessages`, `formatMessage`).
 *
 * What it replaces. On the 5250 every text lived in the message file CUSTMSGF,
 * built by the 17 `ADDMSGD` commands of 5250_Subfile/CRTMSGF.CLLE:12-46, and
 * the system substituted the message data into `&1` when the program sent a
 * message id. Here the catalog is data served by `GET /api/messages`: the
 * backend's `messages.properties`, with `&1` written `{0}`, three typo fixes
 * (DEM0007, DEM0009, DEM1002) and five APP keys. The browser loads it once and
 * `format(code, args)` turns a code into its text.
 *
 * What is pinned down here:
 * - **Loads once.** One `GET /api/messages` for the page, under StrictMode's
 *   double mount, re-renders, children mounted and unmounted, and the whole
 *   provider mounted again over the same `QueryClient`. The request is
 *   anonymous, as before sign-in.
 * - **Never blocks.** Children render at once; until the catalog arrives
 *   `ready` is `false` and `format` returns the code it was given.
 * - **Substitution parity with the server.** `{n}` is replaced literally in
 *   one left-to-right pass, exactly as the backend's `MessageCatalog.text`
 *   (asserted with the same literal strings as `MessageCatalogIT`): no
 *   `MessageFormat` quoting, no `$` replacement patterns, no re-scan of
 *   inserted text, a placeholder without an argument kept as written.
 * - **Unknown codes** (inherited `Object` members included) read as the code.
 * - **Stable `format`** while the catalog is unchanged.
 * - **Quiet failure.** A failed load is not retried, not refetched on focus
 *   or reconnect, not thrown and not logged; the codes stand in for the texts.
 * - **Wiring.** `useMessages` outside the provider throws, naming it.
 *
 * Every request is answered by MSW (`../test/server`, started by
 * `../test/setup.ts` with `onUnhandledFrame: 'error'`); the default handler of
 * `/api/messages` serves the 22-key `catalog` fixture of `../test/handlers`.
 * Requests are counted through MSW's `request:start` life-cycle event, so the
 * default handler stays in place for the tests that do not override it. Each
 * test builds its own `QueryClient`, so no catalog survives from one test to
 * the next.
 */
import { StrictMode } from 'react';
import type { ReactElement, ReactNode } from 'react';
import { act, render, renderHook, screen, waitFor } from '@testing-library/react';
import type { RenderResult } from '@testing-library/react';
import { focusManager, onlineManager, QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { http, HttpResponse } from 'msw/http';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { server } from '../test/server';
import { catalog, problem } from '../test/handlers';
import { formatMessage, MessageCatalogProvider, useMessages } from './MessageCatalogProvider';

// ---------------------------------------------------------------------------
// Harness
// ---------------------------------------------------------------------------

/** The catalog endpoint the provider loads, relative as the SPA calls it. */
const MESSAGES_PATH = '/api/messages';

/** Every `GET /api/messages` that reached MSW in the current test, in order. */
const catalogRequests: Request[] = [];

/** Every hold the current test made ({@link holdCatalog}); `afterEach` settles them, so no held request outlives its test. */
const holds: HeldCatalog[] = [];

/** MSW `request:start` listener: records the catalog requests, ignores every other route. */
function recordCatalogRequest({ request }: { request: Request }): void {
  if (request.method === 'GET' && new URL(request.url).pathname === MESSAGES_PATH) {
    catalogRequests.push(request);
  }
}

beforeEach(() => {
  catalogRequests.length = 0;
  server.events.on('request:start', recordCatalogRequest);
});

afterEach(async () => {
  // A request a failed test left held is released and settled here, while
  // the tree is still mounted (this hook runs before the setup file's
  // cleanup) and its handler is still in place, so nothing it resolves can
  // reach the next test.
  for (const held of holds.splice(0)) {
    await held.settle();
  }
  server.events.removeListener('request:start', recordCatalogRequest);
});

/**
 * The text of a catalog entry as the fixture (and so the server) holds it.
 * Fails the test when the fixture lacks the code, rather than comparing
 * against `undefined` (`noUncheckedIndexedAccess`).
 */
function catalogText(code: string): string {
  const text = catalog[code];
  if (text === undefined) {
    throw new Error(`The test catalog holds no ${code}`);
  }
  return text;
}

/** A fresh client for one test: no shared cache, and no retry by default. */
function newQueryClient(): QueryClient {
  return new QueryClient({ defaultOptions: { queries: { retry: false } } });
}

/** `ui` inside the providers the catalog needs, optionally under StrictMode as a whole. */
function catalogTree(client: QueryClient, ui: ReactNode, strict: boolean): ReactElement {
  const tree = (
    <QueryClientProvider client={client}>
      <MessageCatalogProvider>{ui}</MessageCatalogProvider>
    </QueryClientProvider>
  );
  return strict ? <StrictMode>{tree}</StrictMode> : tree;
}

/** Options of {@link renderWithCatalog}. */
interface CatalogRenderOptions {
  /** The client to render with; a fresh {@link newQueryClient} by default. */
  client?: QueryClient;
  /** Wraps the whole tree, providers included, in `<StrictMode>`. */
  strict?: boolean;
}

/** What {@link renderWithCatalog} returns. */
interface CatalogView {
  /** The client the tree was rendered with, for a later render over the same cache. */
  client: QueryClient;
  /** Testing Library's result of the first render. */
  result: RenderResult;
  /** Renders `ui` in place of the current children, over the same client and providers. */
  rerender(ui: ReactNode): void;
}

/** Renders `ui` inside `QueryClientProvider` and `MessageCatalogProvider`. */
function renderWithCatalog(ui: ReactNode, { client = newQueryClient(), strict = false }: CatalogRenderOptions = {}): CatalogView {
  const result = render(catalogTree(client, ui, strict));
  return {
    client,
    result,
    rerender: (next: ReactNode): void => {
      result.rerender(catalogTree(client, next, strict));
    },
  };
}

/** The `wrapper` of `renderHook`: the same providers as {@link renderWithCatalog}. */
function catalogWrapper(client: QueryClient = newQueryClient()): (props: { children: ReactNode }) => ReactElement {
  return function CatalogWrapper({ children }: { children: ReactNode }): ReactElement {
    return catalogTree(client, children, false);
  };
}

/** Props of {@link Probe}. */
interface ProbeProps {
  /** Prefix of the probe's test ids; `probe` by default. */
  label?: string;
  /** The message id the probe formats. */
  code: string;
  /** The substitution values passed to `format`. */
  args?: ReadonlyArray<string | number>;
}

/** A consumer of `useMessages()` that shows `ready` and `format(code, args)`. */
function Probe({ label = 'probe', code, args }: ProbeProps): ReactElement {
  const { format, ready } = useMessages();
  return (
    <div data-testid={label}>
      <span data-testid={`${label}-ready`}>{String(ready)}</span>
      <span data-testid={`${label}-text`}>{format(code, args)}</span>
    </div>
  );
}

/** The `ready` a probe shows: `'true'` or `'false'`. */
function readyOf(label = 'probe'): string | null {
  return screen.getByTestId(`${label}-ready`).textContent;
}

/** The text a probe shows, exactly. */
function textOf(label = 'probe'): string | null {
  return screen.getByTestId(`${label}-text`).textContent;
}

/** A promise the test resolves itself; releasing it again changes nothing. */
function deferred(): { promise: Promise<void>; release: () => void } {
  let resolvePromise: () => void = () => undefined;
  const promise = new Promise<void>((resolve) => {
    resolvePromise = resolve;
  });
  return { promise, release: () => resolvePromise() };
}

/** The handle {@link holdCatalog} returns. */
interface HeldCatalog {
  /** Lets the held catalog request be answered. Calling it again changes nothing. */
  readonly release: () => void;
  /**
   * Releases the hold, then waits until the held resolver has returned its
   * answer and `client` has nothing left in flight, so the catalog has
   * reached the cache and its consumers have rendered it. When no request
   * reached the resolver, it waits for `client` alone. Calling it again
   * changes nothing.
   */
  readonly settle: () => Promise<void>;
}

/**
 * Holds every `GET /api/messages` until `release()` is called, as a slow
 * server would, then answers it with the `catalog` fixture. The hold is
 * registered in {@link holds} when it is made, so `afterEach` settles it even
 * when the test fails before its own `release()`.
 *
 * @param client the client whose catalog query the held request answers
 */
function holdCatalog(client: QueryClient): HeldCatalog {
  const gate = deferred();
  const answered = deferred();
  let reached = false;
  server.use(
    http.get(MESSAGES_PATH, async () => {
      reached = true;
      try {
        await gate.promise;
        return HttpResponse.json({ ...catalog });
      } finally {
        // Runs once the response above is built: the answer has been produced.
        answered.release();
      }
    }),
  );
  const held: HeldCatalog = {
    release: gate.release,
    settle: async () => {
      gate.release();
      if (reached) {
        await act(async () => {
          await answered.promise;
        });
      }
      // react-query hands the settled query to its observers on a later
      // task; `waitFor` lets that render run outside any act warning.
      await waitFor(() => expect(client.isFetching()).toBe(0));
    },
  };
  holds.push(held);
  return held;
}

/** `useMessages()` rendered as a hook, returned once the catalog has loaded. */
async function loadedMessages() {
  const { result } = renderHook(() => useMessages(), { wrapper: catalogWrapper() });
  await waitFor(() => expect(result.current.ready).toBe(true));
  return result;
}

// ---------------------------------------------------------------------------
// Loading
// ---------------------------------------------------------------------------

describe('MessageCatalogProvider loading', () => {
  it('requests the catalog once for the page, whatever mounts and re-renders follow', async () => {
    const view = renderWithCatalog(<Probe label="first" code="DEM0003" />, { strict: true });
    await waitFor(() => expect(textOf('first')).toBe(catalogText('DEM0003')));
    expect(readyOf('first')).toBe('true');

    // Three re-renders over the same client.
    for (const option of ['X', 'Y', 'Z']) {
      view.rerender(<Probe label="first" code="DEM0004" args={[option]} />);
      expect(textOf('first')).toBe(`${option} is not a valid option at this time.`);
    }

    // A second consumer mounted later reads the loaded catalog at once.
    view.rerender([<Probe key="first" label="first" code="DEM0003" />, <Probe key="second" label="second" code="DEM0005" />]);
    expect(readyOf('second')).toBe('true');
    expect(textOf('second')).toBe(catalogText('DEM0005'));

    // The first consumer unmounted, then mounted again beside the second.
    view.rerender([<Probe key="second" label="second" code="DEM0005" />]);
    expect(screen.queryByTestId('first')).not.toBeInTheDocument();
    view.rerender([<Probe key="first" label="first" code="DEM0003" />, <Probe key="second" label="second" code="DEM0005" />]);
    expect(readyOf('first')).toBe('true');
    expect(textOf('first')).toBe(catalogText('DEM0003'));

    // The whole provider unmounted and mounted again over the same client: the
    // page's cache still holds the catalog, which is never stale.
    view.result.unmount();
    renderWithCatalog(<Probe label="third" code="DEM0006" />, { client: view.client, strict: true });
    expect(readyOf('third')).toBe('true');
    expect(textOf('third')).toBe(catalogText('DEM0006'));

    await act(async () => {});
    expect(catalogRequests).toHaveLength(1);
  });

  it('loads anonymously, as before sign-in, and serves every catalog id as its own template', async () => {
    const result = await loadedMessages();
    await act(async () => {});

    expect(catalogRequests).toHaveLength(1);
    const [sent] = catalogRequests;
    expect(sent?.headers.get('authorization')).toBeNull();

    const entries = Object.entries(catalog);
    expect(entries).toHaveLength(22);
    for (const [code, template] of entries) {
      // Without arguments every placeholder stays as written.
      expect(result.current.format(code)).toBe(template);
    }
  });

  it('renders its children at once and shows codes until the catalog arrives', async () => {
    const client = newQueryClient();
    const held = holdCatalog(client);

    renderWithCatalog(
      [
        <Probe key="plain" label="plain" code="DEM0003" />,
        <Probe key="withArgs" label="withArgs" code="DEM0004" args={['X']} />,
      ],
      { client },
    );

    // Nothing waits for the catalog: the children are already in the document.
    expect(screen.getByTestId('plain')).toBeInTheDocument();
    expect(screen.getByTestId('withArgs')).toBeInTheDocument();
    expect(readyOf('plain')).toBe('false');
    expect(textOf('plain')).toBe('DEM0003');
    expect(textOf('withArgs')).toBe('DEM0004');

    // The request is under way but unanswered: still the codes.
    await waitFor(() => expect(catalogRequests).toHaveLength(1));
    await act(async () => {});
    expect(readyOf('plain')).toBe('false');
    expect(textOf('plain')).toBe('DEM0003');

    held.release();
    await waitFor(() => expect(readyOf('plain')).toBe('true'));
    expect(readyOf('withArgs')).toBe('true');
    expect(textOf('plain')).toBe(catalogText('DEM0003'));
    expect(textOf('withArgs')).toBe('X is not a valid option at this time.');
    expect(catalogRequests).toHaveLength(1);
  });
});

// ---------------------------------------------------------------------------
// Substitution: the same rule as the server's MessageCatalog.text
// ---------------------------------------------------------------------------

describe('useMessages().format after the catalog has loaded', () => {
  it('substitutes {0} into the CUSTMSGF texts exactly as the server does', async () => {
    const { format } = (await loadedMessages()).current;

    expect(format('DEM0004', ['X'])).toBe('X is not a valid option at this time.');
    expect(format('DEM0502', ['Name'])).toBe('Name: Must not be blank');
    expect(format('DEM9898', ['Address Not Found.'])).toBe('USPS: Address Not Found.');
    expect(format('DEM0501', ['Active Status'])).toBe('Active Status: Must be Y or N');
    // The literal cases of MessageCatalogIT.literalSubstitution.
    expect(format('DEM9898', ["Can't find it"])).toBe("USPS: Can't find it");
    expect(format('DEM9898', ['{0}'])).toBe('USPS: {0}');
    expect(format('DEM0000')).toBe('Press Enter to update. F12 to Cancel.');
  });

  it('keeps apostrophes, which MessageFormat would read as quotes', async () => {
    const { format } = (await loadedMessages()).current;

    expect(format('APP0400', ["name can't be read"])).toBe("Request is not valid: name can't be read");
    expect(format('DEM9898', ["NIBH L'LOR COMPANY"])).toBe("USPS: NIBH L'LOR COMPANY");
  });

  it('keeps a placeholder that has no argument', async () => {
    const { format } = (await loadedMessages()).current;

    expect(format('DEM0502')).toBe('{0}: Must not be blank');
    expect(format('DEM0502', [])).toBe('{0}: Must not be blank');
    expect(format('DEM9898')).toBe('USPS: {0}');
  });

  it('serves the corrected CUSTMSGF texts verbatim', async () => {
    const { format } = (await loadedMessages()).current;

    // CRTMSGF.CLLE: "State selection field in invalid."
    expect(format('DEM0007')).toBe('State selection field is invalid.');
    // CRTMSGF.CLLE: "Press Enter to add.  Press F12 to cancel" (two spaces)
    expect(format('DEM0009')).toBe('Press Enter to add. Press F12 to cancel');
    // CRTMSGF.CLLE: "Someone else changed record.  Rewiew data."
    expect(format('DEM1002')).toBe('Someone else changed record. Review data.');
    // Carried although nothing raises it.
    expect(format('DEM0008')).toBe('Use F4 only in field followed by +');
  });
});

describe('formatMessage', () => {
  it('gives the same results as format for the catalog templates', () => {
    expect(formatMessage(catalogText('DEM0004'), ['X'])).toBe('X is not a valid option at this time.');
    expect(formatMessage(catalogText('DEM0502'), ['Name'])).toBe('Name: Must not be blank');
    expect(formatMessage(catalogText('DEM9898'), ['Address Not Found.'])).toBe('USPS: Address Not Found.');
  });

  it('needs no escaping for apostrophes in the template or the arguments', () => {
    expect(formatMessage("Can't find {0}", ["NIBH L'LOR COMPANY"])).toBe("Can't find NIBH L'LOR COMPANY");
    expect(formatMessage("'{0}'", ['quoted'])).toBe("'quoted'");
    expect(formatMessage("it''s {0}", ['x'])).toBe("it''s x");
  });

  it('keeps every placeholder whose argument is absent', () => {
    expect(formatMessage('{0}: Must not be blank')).toBe('{0}: Must not be blank');
    expect(formatMessage('{0} and {1}', ['a'])).toBe('a and {1}');
    expect(formatMessage('{2}', ['a', 'b'])).toBe('{2}');
    // A hole in the array is an absent argument too.
    const sparse: string[] = ['a'];
    sparse[2] = 'c';
    expect(formatMessage('{0}|{1}|{2}', sparse)).toBe('a|{1}|c');
    // An index too large for any array, as the server keeps one too large for an int.
    expect(formatMessage('{99999999999}', ['a'])).toBe('{99999999999}');
  });

  it('inserts arguments literally and never scans inserted text again', () => {
    expect(formatMessage('{0} {1}', ['$&', '{1}'])).toBe('$& {1}');
    expect(formatMessage('{0}', ['$1 $$ $` $\''])).toBe('$1 $$ $` $\'');
    expect(formatMessage('{0}', ['a\\b'])).toBe('a\\b');
    expect(formatMessage('{0}{1}', ['{1}', 'b'])).toBe('{1}b');
  });

  it('replaces every occurrence in one left-to-right pass', () => {
    expect(formatMessage('{0}-{0}', ['a'])).toBe('a-a');
    expect(formatMessage('{1} before {0}', ['a', 'b'])).toBe('b before a');
    expect(formatMessage('{{0}}', ['x'])).toBe('{x}');
  });

  it('renders numbers as text and keeps arguments untrimmed', () => {
    expect(formatMessage('{0}', [7])).toBe('7');
    expect(formatMessage('{0} of {1}', [0, 12])).toBe('0 of 12');
    expect(formatMessage('[{0}]', ['  A  '])).toBe('[  A  ]');
    expect(formatMessage('[{0}]', [''])).toBe('[]');
  });

  it('reads the index as decimal ASCII digits, as the server does', () => {
    // Leading zeros: the server parses "01" as argument 1.
    expect(formatMessage('{01}', ['a', 'b'])).toBe('b');
    // Anything else is plain text: no sign, no spaces, no letters, no
    // non-ASCII digits (ARABIC-INDIC DIGIT ZERO), no unbalanced braces.
    expect(formatMessage('{-1} { 0 } {a} {\u0660} {0 0}', ['x'])).toBe('{-1} { 0 } {a} {\u0660} {0 0}');
    expect(formatMessage('{0', ['x'])).toBe('{0');
    expect(formatMessage('0}', ['x'])).toBe('0}');
    expect(formatMessage('', ['x'])).toBe('');
    expect(formatMessage('No placeholder.', ['x'])).toBe('No placeholder.');
  });
});

// ---------------------------------------------------------------------------
// Unknown codes and stability
// ---------------------------------------------------------------------------

describe('useMessages().format for codes the catalog does not hold', () => {
  it('returns the code itself, with or without arguments', async () => {
    const { format } = (await loadedMessages()).current;

    expect(format('XYZ9999')).toBe('XYZ9999');
    expect(format('XYZ9999', ['a'])).toBe('XYZ9999');
    expect(format('')).toBe('');
    // Codes are case-sensitive, as the catalog keys are.
    expect(format('dem0003')).toBe('dem0003');
  });

  it('reads own entries only, never members a plain object inherits', async () => {
    const { format } = (await loadedMessages()).current;

    for (const inherited of ['toString', 'constructor', 'hasOwnProperty', 'valueOf', '__proto__']) {
      expect(format(inherited)).toBe(inherited);
    }
  });

  it('treats a served entry that is not text as unknown', async () => {
    server.use(http.get(MESSAGES_PATH, () => HttpResponse.json({ DEM0003: 7, DEM0004: null, DEM0005: 'Use F4 only if + is on field' })));
    const { format } = (await loadedMessages()).current;

    expect(format('DEM0003')).toBe('DEM0003');
    expect(format('DEM0004', ['X'])).toBe('DEM0004');
    expect(format('DEM0005')).toBe('Use F4 only if + is on field');
  });
});

describe('useMessages() identity', () => {
  it('keeps format the same function across re-renders while the catalog is unchanged', async () => {
    const { result, rerender } = renderHook(() => useMessages(), { wrapper: catalogWrapper() });
    await waitFor(() => expect(result.current.ready).toBe(true));
    const loadedFormat = result.current.format;

    rerender();
    rerender();

    expect(result.current.format).toBe(loadedFormat);
    expect(result.current.ready).toBe(true);
    await act(async () => {});
    expect(catalogRequests).toHaveLength(1);
  });
});

// ---------------------------------------------------------------------------
// Failure and wiring
// ---------------------------------------------------------------------------

/** Failed loads: a problem from the server, and an answer that never arrives. */
const failures: ReadonlyArray<readonly [string, () => Response]> = [
  ['a 500 DEM9999 problem', () => problem(500, 'DEM9999', { instance: MESSAGES_PATH })],
  ['a 503 with an HTML body from a proxy', () => HttpResponse.html('<html><body>Down</body></html>', { status: 503 })],
  ['a lost connection', () => HttpResponse.error()],
];

describe('MessageCatalogProvider after a failed load', () => {
  it.each(failures)('stays quiet after %s: codes stand in, nothing is retried or refetched', async (_kind, respond) => {
    const consoleError = vi.spyOn(console, 'error');
    const consoleWarn = vi.spyOn(console, 'warn');
    server.use(http.get(MESSAGES_PATH, () => respond()));
    // react-query's own defaults (three retries, refetch on focus and on
    // reconnect), so the provider's options alone must keep the load final.
    const client = new QueryClient();

    const view = renderWithCatalog(<Probe code="DEM0003" />, { client });
    await waitFor(() => expect(client.getQueryCache().getAll().map((query) => query.state.status)).toEqual(['error']));
    await act(async () => {});

    expect(screen.getByTestId('probe')).toBeInTheDocument();
    expect(readyOf()).toBe('false');
    expect(textOf()).toBe('DEM0003');
    expect(client.isFetching()).toBe(0);
    expect(catalogRequests).toHaveLength(1);

    // Neither a regained focus nor a reconnect loads it again.
    try {
      await act(async () => {
        focusManager.setFocused(false);
        focusManager.setFocused(true);
        onlineManager.setOnline(false);
        onlineManager.setOnline(true);
      });
      await act(async () => {});
      expect(client.isFetching()).toBe(0);
      expect(catalogRequests).toHaveLength(1);
      expect(readyOf()).toBe('false');
      expect(textOf()).toBe('DEM0003');
    } finally {
      // The two managers are react-query module singletons. Unmount first, so
      // putting back their defaults (focus read from the document, online)
      // cannot start a request that would land in the next test.
      view.result.unmount();
      focusManager.setFocused(undefined);
      onlineManager.setOnline(true);
    }

    // Not thrown, not shown, not logged.
    expect(consoleError).not.toHaveBeenCalled();
    expect(consoleWarn).not.toHaveBeenCalled();
  });

  it('keeps format returning codes for every message, arguments or not', async () => {
    server.use(http.get(MESSAGES_PATH, () => problem(500, 'DEM9999', { instance: MESSAGES_PATH })));
    const client = newQueryClient();
    const { result } = renderHook(() => useMessages(), { wrapper: catalogWrapper(client) });
    await waitFor(() => expect(client.getQueryCache().getAll().map((query) => query.state.status)).toEqual(['error']));
    await act(async () => {});

    expect(result.current.ready).toBe(false);
    expect(result.current.format('DEM0004', ['X'])).toBe('DEM0004');
    expect(result.current.format('DEM9999')).toBe('DEM9999');
    expect(catalogRequests).toHaveLength(1);
  });
});

describe('useMessages outside MessageCatalogProvider', () => {
  it('throws an error that names the missing provider', () => {
    // React reports the render error to the console as well; silence it. The
    // spy is restored after the test (`restoreMocks` in vite.config.ts).
    vi.spyOn(console, 'error').mockImplementation(() => undefined);

    expect(() => renderHook(() => useMessages())).toThrow(/MessageCatalogProvider/);
    expect(catalogRequests).toHaveLength(0);
  });
});
