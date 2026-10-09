import { defineConfig } from 'vitest/config';
import react from '@vitejs/plugin-react';

// The backend has no CORS configuration, so the browser uses this same-origin
// proxy, like nginx in Compose; 8081 is docker-compose.yml's default API_PORT.
// Vite copies each proxy entry, so one object safely backs both servers.
const apiProxy = {
  '/api': {
    target: 'http://localhost:8081',
  },
};

export default defineConfig({
  plugins: [react()],

  server: {
    proxy: apiProxy,
  },

  // `npm run preview` serves the built `dist/` and must behave like nginx in
  // Compose, so it proxies `/api` the same way.
  preview: {
    proxy: apiProxy,
  },

  test: {
    environment: 'jsdom',
    setupFiles: ['src/test/setup.ts'],

    // Specs import `describe`, `it`, `expect` and `vi` from 'vitest'
    // explicitly. Without globals, Testing Library cannot register its own
    // automatic cleanup, which is why src/test/setup.ts calls `cleanup`.
    globals: false,

    include: ['src/**/*.test.{ts,tsx}'],
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
