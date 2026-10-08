/**
 * App: the provider tree of the Customer Master SPA, mounted once by
 * `src/main.tsx`.
 *
 * What it replaces. On IBM i the interactive programs ran inside a job that
 * supplied, around every screen, the same four services:
 *
 * | Job service (source)                                                    | Here                     |
 * |-------------------------------------------------------------------------|--------------------------|
 * | Message file CUSTMSGF, built by ADDMSGD [5250_Subfile/CRTMSGF.CLLE:12-46] | `MessageCatalogProvider` |
 * | Program message queue and message subfile, filled by SndMsgPgmQ (QMHSNDPM) and emptied by ClrMsgPgmQ (QMHRMVPM `*ALL`) [Service_Pgms/SRV_MSG.RPGLE:69-177] | `ToastProvider` and its one `ToastRegion` |
 * | Attention identifier (AID) bytes F01–F24, PageUp, PageDown, Enter [Copy_Mbrs/AIDBYTES.RPGLE:3-35] | `KeyScopeProvider`       |
 * | A menu calling PMTCUSTR with its mode, and the program's Init and close-down lifecycle [5250_Subfile/PMTCUSTR.SQLRPGLE:76-79,706-767] | `BrowserRouter`, `AuthProvider`, `AppRoutes` |
 *
 * Each becomes a React provider mounted exactly once, here. Screens mount and
 * unmount beneath it, which replaces the program lifecycle (open the display
 * file, run Init, return with LR off); nothing above the screens holds
 * per-screen state.
 *
 * Nesting, outermost first. Every position is a constraint, not a preference:
 *
 * 1. `QueryClientProvider`: `MessageCatalogProvider`, `AuthProvider` (which
 *    drops cached user data on sign-out) and the feature hooks all use
 *    react-query, so the client sits above all of them.
 * 2. `MessageCatalogProvider`: above the router and `AuthProvider`, so the
 *    public `GET /api/messages` is requested once at application start,
 *    before sign-in, and `SignInPage` can already show catalog texts. The
 *    bundle carries no message text of its own.
 * 3. `ToastProvider`: above the key scope (the bridge below needs
 *    `useToasts`) and above every screen and dialog. It renders the one
 *    live-region host (`role="status"` and `role="alert"`) after its
 *    children, so no screen or dialog renders a second one and no text is
 *    announced twice.
 * 4. `KeyScopeWithToastReset` → `KeyScopeProvider`: the application's only
 *    document `keydown` listener (capture phase) and the scope stack that
 *    search, the detail dialog, both pickers and the host form register with
 *    `useFunctionKeys`.
 * 5. `BrowserRouter`: above `AuthProvider`, whose 401 handler and sign-out
 *    call `useNavigate()` to route to `/sign-in`.
 * 6. `AuthProvider`: above `AppRoutes`, so `RequireRole`, `SignInPage` and
 *    every screen can call `useAuth()`.
 *
 * Message lifetime. A toast lasts until the user's next action, as the 5250
 * cleared its message subfile once per screen cycle. Two events end a cycle:
 * a click anywhere (mouse, or Enter or Space on a button), which
 * `ToastProvider` handles itself, and a command key (Enter in a field, a
 * function key, Escape, PageUp or PageDown), which `KeyScopeProvider` reports
 * through `onBeforeCommand` just before the screen's handler runs. Wiring that
 * callback to the toast `clear` is the one piece of behaviour this file owns:
 * the previous messages disappear first, then the handler may publish new
 * ones.
 *
 * Deliberately absent, because another layer owns each of them:
 * - Credentials and storage: `AuthProvider` keeps credentials in memory, in
 *   `api/client.ts`; nothing here reads or writes Web Storage or cookies.
 * - HTTP: every request goes through `api/client.ts`, never `fetch` here.
 * - Message texts and key listeners: owned by the catalog and the key scope
 *   providers.
 * - Stylesheets: `src/main.tsx` imports `styles/tokens.css` and
 *   `styles/global.css`.
 * - React Query devtools: not a dependency of this project.
 *
 * @example
 * ```tsx
 * // src/main.tsx
 * createRoot(document.getElementById('root')!).render(
 *   <StrictMode>
 *     <App />
 *   </StrictMode>,
 * );
 * ```
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
    },
    mutations: {
      retry: false,
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
 * The application root: every application-wide provider, in the order the
 * module documentation gives, around the route table.
 *
 * Renders no DOM of its own beyond what the providers render (the toast
 * region) and the current route's screen.
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
