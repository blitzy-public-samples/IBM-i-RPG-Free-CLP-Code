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
 *   `server.resetHandlers()` then drops the test's overrides and also restores
 *   the handlers' fixture store.
 *
 * MSW does not patch `setTimeout`, so a spec that installs fake timers must
 * advance them (`vi.advanceTimersByTimeAsync`) for a handler that answers
 * after `delay()`.
 */
import '@testing-library/jest-dom/vitest';
import { cleanup } from '@testing-library/react';
import { afterAll, afterEach, beforeAll } from 'vitest';
import { server } from './server';

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

beforeAll(() => {
  server.listen({ onUnhandledFrame: 'error' });
  installRelativeFetch();
});

afterEach(() => {
  // Unmount first, while the test's handlers still exist, so an effect that
  // fires during unmount cannot hit an unhandled request; then reset the
  // handlers (and with them the fixture store) for the next test.
  cleanup();
  server.resetHandlers();
});

afterAll(() => {
  // Teardown in reverse order of setup: remove the guard, then stop MSW.
  uninstallRelativeFetch();
  server.close();
});
