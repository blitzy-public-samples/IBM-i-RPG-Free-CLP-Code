/**
 * App: the application-wide providers, each mounted exactly once, around the
 * route table. Nesting, outermost first; every position is a constraint:
 *
 * 1. `QueryClientProvider`: `MessageCatalogProvider`, `AuthProvider` (which
 *    drops cached user data on sign-out) and the feature hooks use react-query.
 * 2. `MessageCatalogProvider`: above the router and `AuthProvider`, so the
 *    public `GET /api/messages` loads once at start, before sign-in, and
 *    `SignInPage` can show catalog texts.
 * 3. `ToastProvider`: above the key scope (the bridge below needs `useToasts`)
 *    and above every screen and dialog. It renders the one live-region host,
 *    so no screen renders a second and no text is announced twice.
 * 4. `KeyScopeWithToastReset` → `KeyScopeProvider`: the only document
 *    `keydown` listener and the scope stack.
 * 5. `BrowserRouter`: above `AuthProvider`, whose 401 handler and sign-out
 *    navigate to `/sign-in`.
 * 6. `AuthProvider`: above `AppRoutes`, so the guard and the screens can call
 *    `useAuth()`.
 *
 * Message lifetime. A toast lasts until the user's next action, as the 5250
 * cleared its message subfile once per screen cycle. A click is handled by
 * `ToastProvider` itself; a command key is reported by `KeyScopeProvider`'s
 * `onBeforeCommand` just before the screen's handler. Wiring that callback to
 * the toast `clear` is the one behaviour this file owns, so the previous
 * messages disappear before the handler publishes new ones.
 */
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { BrowserRouter } from 'react-router-dom';
import type { ReactNode } from 'react';
import { MessageCatalogProvider } from './messages/MessageCatalogProvider';
import { AuthProvider } from './auth/AuthProvider';
import { KeyScopeProvider } from './keyboard/KeyScopeProvider';
import { ToastProvider, useToasts } from './components/ToastRegion';
import { AppRoutes } from './routes';

/**
 * The page's one react-query client.
 *
 * - **No automatic retries.** The API answers every failure with
 *   problem+json: a 400, 401, 403, 404, 409 or 422 is a decision, not a
 *   transient fault, and a retried 401 would only repeat the sign-out. A
 *   failure is presented once, by the feature that made the call.
 * - **No refetch on window focus or reconnect.** The 5250 screens refreshed
 *   only on an explicit user action (Enter, F5, PageDown); a background
 *   refetch would change the list or the form behind the user's back.
 * - **Sent whatever the browser reports (`networkMode: 'always'`).** Offline,
 *   a read or write is still sent, so its failure reaches `api/client.ts` as
 *   the synthetic DEM9999 `ApiError` and the owning feature presents it
 *   once. Nothing is paused and replayed on reconnect: a retry is the user's
 *   next Enter, PageDown or option.
 *
 * Created at module level rather than during render, so the render stays
 * pure (react-hooks purity rules) and StrictMode's double render or a
 * remount never discards the cache.
 */
const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      retry: false,
      refetchOnWindowFocus: false,
      refetchOnReconnect: false,
      networkMode: 'always',
    },
    mutations: {
      retry: false,
      networkMode: 'always',
    },
  },
});

/**
 * Mounts `KeyScopeProvider` with the toast `clear` as its `onBeforeCommand`,
 * so every dispatched command key, bound or unbound, starts a new message
 * cycle before its handler runs.
 *
 * It must render inside `ToastProvider`, because `useToasts()` throws outside
 * it. `clear` is stable for the provider's lifetime and ignores the key it is
 * given, so it is passed as it is, with no wrapper of its own. Private to this
 * file: the key scope is mounted nowhere else.
 */
function KeyScopeWithToastReset({ children }: { children: ReactNode }) {
  const { clear } = useToasts();
  return <KeyScopeProvider onBeforeCommand={clear}>{children}</KeyScopeProvider>;
}

/**
 * The application root: the providers listed above, nested in that order
 * around `AppRoutes`. It renders no DOM of its own beyond the toast region
 * and the current screen.
 */
export function App() {
  return (
    <QueryClientProvider client={queryClient}>
      <MessageCatalogProvider>
        <ToastProvider>
          <KeyScopeWithToastReset>
            <BrowserRouter>
              <AuthProvider>
                <AppRoutes />
              </AuthProvider>
            </BrowserRouter>
          </KeyScopeWithToastReset>
        </ToastProvider>
      </MessageCatalogProvider>
    </QueryClientProvider>
  );
}
