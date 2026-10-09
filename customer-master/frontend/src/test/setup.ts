/**
 * The Vitest setup file, which `vite.config.ts` lists in `test.setupFiles` so
 * that Vitest runs it before every test file.
 *
 * - `@testing-library/jest-dom/vitest` adds the jest-dom matchers to `expect`
 *   and to its `Assertion` types, so specs use them without importing them.
 * - MSW listens with `onUnhandledFrame: 'error'`: a request no handler answers
 *   fails the test that made it instead of reaching a network.
 * - Vitest runs with `globals: false`, so Testing Library registers no
 *   automatic cleanup and `cleanup()` is called here after each test.
 *   {@link closeIdleConnections} then closes every connection the test left
 *   open without a request on it, and `server.resetHandlers()` drops the
 *   test's overrides and also restores the handlers' fixture store.
 *
 * Vitest runs `afterEach` hooks in reverse order of registration, so a spec's
 * own `afterEach` runs before the one here: a spec that settles its pending
 * requests there has done so before the tree is unmounted and the idle
 * connections are closed.
 *
 * MSW does not patch `setTimeout`, so a spec that installs fake timers must
 * advance them (`vi.advanceTimersByTimeAsync`) for a handler that answers
 * after `delay()`.
 */
import { subscribe, unsubscribe } from 'node:diagnostics_channel';
import type { Socket } from 'node:net';
import '@testing-library/jest-dom/vitest';
import { cleanup } from '@testing-library/react';
import { afterAll, afterEach, beforeAll } from 'vitest';
import { server } from './server';

// jsdom lays nothing out and implements no `scrollIntoView`, which components
// under test call to bring part of a screen into view. The no-op only fills
// the gap, so a spec can still spy on the calls; a browser keeps its own
// implementation.
Element.prototype.scrollIntoView ??= () => {};

/** The `fetch` in place before {@link installRelativeFetch} wrapped it; `undefined` while no wrapper is installed. */
let fetchBeforeGuard: typeof globalThis.fetch | undefined;

/**
 * Resolves a root-relative URL string against the document's location, as the
 * browser does; the API client only calls root-relative paths (`/api/...`).
 * Absolute URL strings, `URL` objects and `Request` objects already carry an
 * origin and pass through unchanged.
 */
function resolveRelative(input: RequestInfo | URL): RequestInfo | URL {
  return typeof input === 'string' && input.startsWith('/') ? new URL(input, window.location.href).href : input;
}

/**
 * Wraps the current `globalThis.fetch` so that relative URLs work under jsdom.
 *
 * Under jsdom, `fetch` is Node's undici, which has no document to resolve
 * against and rejects a relative URL. Resolving relative URLs is not part of
 * MSW's documented API, and any resolution its interceptor performs is an
 * internal detail, so the guard resolves root-relative paths itself against
 * `window.location.href`: the same location MSW matches the handlers' paths
 * against, so every default handler still matches.
 *
 * It is installed after `server.listen()`, so it wraps whatever `fetch` MSW
 * leaves in place. `init` passes through untouched, so the method, the body,
 * the abort signal and headers such as `Authorization` and `X-Requested-With`
 * reach MSW exactly as the API client sent them.
 */
function installRelativeFetch(): void {
  const next = globalThis.fetch;
  fetchBeforeGuard = next;
  globalThis.fetch = (input: RequestInfo | URL, init?: RequestInit): Promise<Response> =>
    next(resolveRelative(input), init);
}

/** Puts back the `fetch` {@link installRelativeFetch} wrapped, so the file's teardown leaves the global as it found it. */
function uninstallRelativeFetch(): void {
  if (fetchBeforeGuard !== undefined) {
    globalThis.fetch = fetchBeforeGuard;
    fetchBeforeGuard = undefined;
  }
}

// Aborting a read that is on the wire destroys its connection, and when that
// connection closes, the undici that Node 24 bundles (7.x) opens a new one to
// the origin with nothing to send on it. MSW's socket interception passes a
// connection that carries no request through to the real network, where
// `localhost:3000` refuses it a moment later; a request sent on that pooled
// connection meanwhile, by the same spec or by the next, fails as a network
// error instead of reaching its handler. The connections are observed
// through the diagnostics channels undici publishes, and the teardown closes
// every one that carried no request ({@link closeIdleConnections}).

/** A connection undici connected or wrote a request on, tracked until it closes. */
interface TrackedConnection {
  /** Whether undici has written a request on it. */
  carried: boolean;
  /** Resolves once the socket has emitted `close`. */
  readonly closed: Promise<void>;
}

/** Every tracked connection that has not closed yet; each removes itself on `close`. */
const openConnections = new Map<Socket, TrackedConnection>();

/** Connection attempts undici began that have neither connected nor failed yet. */
let connecting = 0;

/** Waits for {@link connecting} to reach zero. */
const connectWaiters: Array<() => void> = [];

/**
 * The entry of `socket`, made on first sight. The `close` listener is added
 * after undici's own, which undici attaches before it publishes the socket,
 * so by the time an entry's `closed` resolves, any connection attempt that
 * closing started has begun and is counted in {@link connecting}.
 */
function track(socket: Socket): TrackedConnection {
  const known = openConnections.get(socket);
  if (known !== undefined) {
    return known;
  }
  const connection: TrackedConnection = {
    carried: false,
    closed: new Promise<void>((resolve) => {
      socket.once('close', () => {
        openConnections.delete(socket);
        resolve();
      });
    }),
  };
  openConnections.set(socket, connection);
  return connection;
}

function onBeforeConnect(): void {
  connecting += 1;
}

function onConnectSettled(): void {
  connecting = Math.max(0, connecting - 1);
  if (connecting === 0) {
    for (const wake of connectWaiters.splice(0)) {
      wake();
    }
  }
}

/** `undici:client:connected`: the attempt's socket, which has carried no request yet. */
function onConnected(message: unknown): void {
  const { socket } = message as { socket: Socket };
  track(socket);
  onConnectSettled();
}

function onSendHeaders(message: unknown): void {
  const { socket } = message as { socket: Socket };
  track(socket).carried = true;
}

const CONNECTION_CHANNELS: ReadonlyArray<readonly [string, (message: unknown) => void]> = [
  ['undici:client:beforeConnect', onBeforeConnect],
  ['undici:client:connected', onConnected],
  ['undici:client:connectError', onConnectSettled],
  ['undici:client:sendHeaders', onSendHeaders],
];

/**
 * Waits until every connection attempt has connected or failed; destroys each
 * open connection that never carried a request; and waits for every
 * destroyed connection to close, an aborted read's included, because closing
 * one can start a new attempt. Repeats until no attempt is pending and no
 * connection is closing, so it returns with {@link connecting} at zero and
 * only connections that carried a request still open. It cuts no request
 * short: a connection a request was written on is never destroyed here.
 */
async function closeIdleConnections(): Promise<void> {
  for (;;) {
    if (connecting > 0) {
      await new Promise<void>((resolve) => {
        connectWaiters.push(resolve);
      });
    }
    const closing: Array<Promise<void>> = [];
    for (const [socket, connection] of openConnections) {
      if (!connection.carried && !socket.destroyed) {
        socket.destroy();
      }
      if (socket.destroyed) {
        closing.push(connection.closed);
      }
    }
    if (closing.length === 0 && connecting === 0) {
      return;
    }
    await Promise.all(closing);
  }
}

beforeAll(() => {
  server.listen({ onUnhandledFrame: 'error' });
  installRelativeFetch();
  for (const [channel, listener] of CONNECTION_CHANNELS) {
    subscribe(channel, listener);
  }
});

afterEach(async () => {
  // Unmount first, while the test's handlers still exist, so an effect that
  // fires during unmount cannot hit an unhandled request. Unmounting can
  // abort a pending read, so the idle connections are closed after it; then
  // reset the handlers (and with them the fixture store) for the next test.
  cleanup();
  await closeIdleConnections();
  server.resetHandlers();
});

afterAll(() => {
  // Teardown in reverse order of setup: stop observing connections, remove
  // the guard, then stop MSW.
  for (const [channel, listener] of CONNECTION_CHANNELS) {
    unsubscribe(channel, listener);
  }
  uninstallRelativeFetch();
  server.close();
});
