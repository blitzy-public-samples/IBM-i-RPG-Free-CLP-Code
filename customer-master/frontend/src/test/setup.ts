/**
 * The Vitest setup file: the lifecycle half of the frontend test base.
 *
 * `vite.config.ts` lists this file in `test.setupFiles`, so Vitest runs it in
 * every test file's jsdom context before that file's specs. It exports
 * nothing; importing it has these effects only:
 *
 * - **Matchers.** `@testing-library/jest-dom/vitest` adds the jest-dom
 *   matchers (`toBeInTheDocument`, `toHaveAttribute`, `toHaveFocus`, ...) to
 *   Vitest's `expect` and to its TypeScript `Assertion` types, so every spec
 *   type-checks them without importing anything itself.
 * - **No network.** The one MSW server (`./server`, built from the default
 *   handlers in `./handlers`) intercepts every request. It listens with
 *   `onUnhandledFrame: 'error'`, so a request no handler answers is rejected
 *   and the test that made it fails, instead of reaching a network.
 * - **Isolation.** After each test, Testing Library's `cleanup()` unmounts
 *   whatever the test rendered, then `server.resetHandlers()` drops the
 *   test's `server.use(...)` overrides and, through the handlers' own reset,
 *   restores the fixture customer store. Every test therefore starts from an
 *   empty document, the default handlers and the seed data.
 * - **Same-origin fetch.** Relative URLs such as `/api/messages` resolve
 *   against jsdom's `location`, as they do in the browser
 *   ({@link installRelativeFetch}).
 *
 * Vitest runs with `globals: false`, so the hooks are imported from `vitest`,
 * and Testing Library cannot register its automatic cleanup (it only does so
 * when a global `afterEach` exists), which is why `cleanup()` is called here.
 *
 * MSW 3 notes for spec authors:
 * - The 2.x `onUnhandledRequest` option is now `onUnhandledFrame`; it covers
 *   HTTP requests and WebSocket connections alike.
 * - MSW no longer patches `setTimeout`. A spec that installs fake timers must
 *   advance them (`vi.advanceTimersByTimeAsync`) for a handler that answers
 *   after `delay()`.
 * - `request.headers.get('cookie')` returns `null` in a handler; the API uses
 *   no cookies in any case (HTTP Basic credentials travel in `Authorization`).
 */
import '@testing-library/jest-dom/vitest';
import { cleanup } from '@testing-library/react';
import { afterAll, afterEach, beforeAll } from 'vitest';
import { server } from './server';

/** The `fetch` in place before {@link installRelativeFetch} wrapped it; `undefined` while no wrapper is installed. */
let fetchBeforeGuard: typeof globalThis.fetch | undefined;

/**
 * Resolves a relative request URL against the document's location, as the
 * browser does for the SPA's same-origin calls; any other input is returned
 * as it is.
 *
 * Only a string starting with `/` is relative here: the API client always
 * calls root-relative paths (`/api/...`). Absolute URL strings, `URL` objects
 * and `Request` objects already carry an origin and pass through unchanged.
 */
function resolveRelative(input: RequestInfo | URL): RequestInfo | URL {
  return typeof input === 'string' && input.startsWith('/') ? new URL(input, window.location.href).href : input;
}

/**
 * Wraps the current `globalThis.fetch` so that relative URLs work under jsdom.
 *
 * Why. In the browser `fetch('/api/customers')` is same-origin: nginx (or the
 * Vite proxy) forwards it to the API. Under Vitest's jsdom environment
 * `fetch` is Node's own (undici), which has no document to resolve against
 * and rejects a relative URL with `TypeError: Failed to parse URL from
 * /api/...` (msw issue #1625). With msw 3.0.2 the fetch interceptor it
 * installs (@mswjs/interceptors 0.45.7) still resolves relative strings
 * against `location` itself, so today this guard changes nothing. It is kept
 * because that resolution is an implementation detail of the interceptor,
 * not part of MSW's documented API: should an MSW upgrade stop resolving, or
 * intercept below `fetch`, relative URLs would reach undici unresolved and
 * every suite would fail on its first API call.
 *
 * The guard mirrors the browser: it resolves the path against
 * `window.location.href` (`http://localhost:3000/` under Vitest), the same
 * location MSW matches the handlers' relative paths (`/api/messages`, ...)
 * against, so every default handler still matches.
 *
 * It runs after `server.listen()`, so it wraps whatever `fetch` MSW leaves in
 * place. `init` is passed through untouched, so the method, the body, the
 * abort signal and headers such as `Authorization` and `X-Requested-With`
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
