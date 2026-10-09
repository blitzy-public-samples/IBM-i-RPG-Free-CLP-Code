/**
 * The shared MSW server, built from the default handlers. Importing it only
 * creates the instance and starts no interception: `setup.ts` owns the
 * lifecycle, calling `listen()` before the suite, `resetHandlers()` after each
 * test and `close()` after the suite.
 */
import { setupServer } from 'msw/node';
import { handlers } from './handlers';

export const server = setupServer(...handlers);
