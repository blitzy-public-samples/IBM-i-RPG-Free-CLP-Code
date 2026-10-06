// Vite 8 build and dev-server configuration, plus the Vitest 5 test block, for
// the Customer Master frontend.
//
// `defineConfig` comes from `vitest/config` rather than `vite`: it accepts the
// same Vite options and also types the `test` block, so this one file serves
// `vite`, `vite build`, `vite preview` and `vitest run`. It is type-checked by
// `tsc -b` through tsconfig.node.json.
import { defineConfig } from 'vitest/config';
import react from '@vitejs/plugin-react';

// The browser reaches the API only through its own origin: nginx proxies
// `/api` to the `app` service in Compose, and this proxy does the same for the
// Vite dev and preview servers. The backend has no CORS configuration, so a
// direct cross-origin call from the dev server would be refused.
//
// The target is the API's default host port (`API_PORT`, 8081), published by
// docker-compose.yml. The `/api` prefix is forwarded unchanged because the
// backend serves `/api/**`, so there is no rewrite. Request headers, among them
// `Authorization` (HTTP Basic) and `X-Requested-With` (on which the API omits
// its `WWW-Authenticate` challenge, so the browser never shows its native
// sign-in prompt), pass through untouched, as with nginx.
//
// Vite copies each proxy entry before using it, so the same object can safely
// back both servers.
const apiProxy = {
  '/api': {
    target: 'http://localhost:8081',
  },
};

export default defineConfig({
  plugins: [react()],

  // `npm run dev`: the SPA on the default dev port, API calls proxied.
  server: {
    proxy: apiProxy,
  },

  // `npm run preview` serves the built `dist/` and must behave like nginx in
  // Compose, so it proxies `/api` the same way.
  preview: {
    proxy: apiProxy,
  },

  // No `base` and no `build.outDir` override: the SPA is served at `/`, and
  // frontend/Dockerfile copies the default `dist/` into the nginx image.
  // Styles are plain CSS (src/styles/tokens.css, global.css) imported from
  // main.tsx, so there is no CSS-modules or PostCSS configuration either.

  test: {
    // Component and hook tests render into a DOM; jsdom provides it.
    environment: 'jsdom',

    // The shared frontend test base: jest-dom matchers, the MSW server with its
    // default handlers (including /api/messages), handler reset and Testing
    // Library cleanup after each test.
    setupFiles: ['src/test/setup.ts'],

    // Specs import `describe`, `it`, `expect` and `vi` from 'vitest'
    // explicitly. Without globals, Testing Library cannot register its own
    // automatic cleanup, which is why src/test/setup.ts calls `cleanup`.
    globals: false,

    // Specs are colocated with the unit under test. Only src/ is collected, so
    // the Playwright suite in customer-master/e2e is never picked up.
    include: ['src/**/*.test.{ts,tsx}'],

    // Every `vi.spyOn` is restored after each test, so no spy leaks between
    // tests or files.
    restoreMocks: true,

    // Collected only when `--coverage` is passed (`npm test -- --coverage`).
    // The generated OpenAPI types, the test base and the specs themselves are
    // not application code and stay out of the report.
    coverage: {
      provider: 'v8',
      include: ['src/**'],
      exclude: ['src/api/schema.d.ts', 'src/test/**', 'src/**/*.test.*'],
    },
  },
});
