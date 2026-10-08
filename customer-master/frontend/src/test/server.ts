/**
 * The shared MSW server for the Vitest suites.
 *
 * Part of the frontend test base, between `handlers.ts` (the default route
 * handlers and fixtures it is built from) and `setup.ts` (its lifecycle). It
 * intercepts every HTTP call a component or hook makes under Vitest, so no
 * test reaches a network.
 *
 * Importing it only creates the instance. `setup.ts` owns the lifecycle: it
 * calls `listen()` before the suite, `resetHandlers()` after each test, and
 * `close()` after the suite; nothing here starts interception.
 *
 * A test overrides a route for itself with `server.use()`, for example:
 *
 * ```ts
 * server.use(http.put('/api/customers/:custId', () => problem(409, 'DEM1002', { current })));
 * ```
 *
 * The `resetHandlers()` in `setup.ts` drops that override, so the next test
 * sees the defaults again. Default handlers live in `handlers.ts` only.
 *
 * MSW 3 still serves `setupServer` from `msw/node`; `http` and `HttpResponse`
 * come from `msw/http`, which only the handlers and the tests import.
 */
import { setupServer } from 'msw/node';
import { handlers } from './handlers';

export const server = setupServer(...handlers);
