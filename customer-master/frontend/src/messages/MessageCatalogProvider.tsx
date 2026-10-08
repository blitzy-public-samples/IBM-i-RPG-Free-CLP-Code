/**
 * Message texts (client): the browser's message catalog and its substitution
 * rule.
 *
 * What it replaces. On the 5250 every text lived in the message file CUSTMSGF,
 * built by the 17 `ADDMSGD` commands of 5250_Subfile/CRTMSGF.CLLE:12-46, and
 * SndMsgPgmQ (Service_Pgms/SRV_MSG.RPGLE:69-125) handed QMHSNDPM a message id
 * plus message data, which the system resolved against the file and
 * substituted into `&1`. Here the catalog is data served by the public
 * `GET /api/messages` (the backend's `messages/messages.properties`, read by
 * `MessageCatalog.java`): one JSON object of message id to text, in which the
 * source `&1` is written `{0}`. This provider loads that object once and
 * `useMessages().format(code, args)` turns a code into its text, so a message
 * raised by the browser itself reads exactly as one raised by the server.
 *
 * Contract:
 * - `MessageCatalogProvider` is mounted once in `src/App.tsx`, directly inside
 *   the `QueryClientProvider` and above `ToastProvider`, `KeyScopeProvider`,
 *   the router and `AuthProvider`. It therefore loads before sign-in; the
 *   endpoint needs no credentials.
 * - It renders its children at once. Nothing waits for the catalog: until it
 *   arrives (and for good if it cannot be loaded) `ready` is `false` and
 *   `format` returns the code it was given, so a message never vanishes.
 * - `format` is referentially stable while the catalog is unchanged, so
 *   consumers such as `useProblemPresenter` may list it in dependency arrays.
 * - {@link formatMessage} is the substitution rule on its own, a pure
 *   function, shared with any caller that already holds a template.
 *
 * Constraints:
 * - The bundle carries no message text and no list of message ids: every text
 *   comes from the server, and an id is looked up only when a caller asks.
 * - Loads once: react-query owns the request and its state (no `useState`, no
 *   `useEffect`, no `fetch` of its own here). An infinite stale time and
 *   garbage-collection time keep the first result for the page's lifetime, and
 *   react-query's de-duplication of the in-flight request means StrictMode's
 *   double mount sends one request, not two.
 * - A failed load (network error, or a non-2xx `ApiError` from the transport)
 *   is not retried, not thrown, not shown and not logged; the codes stand in
 *   for the texts. A broken catalog must never take the screens down with it.
 * - Layer rule: imports `react`, `@tanstack/react-query` and `../api/messages`
 *   only, never `components/`, `errors/`, `features/`, `auth/` or `keyboard/`.
 *   `errors/useProblemPresenter.ts` and the screens import this file.
 *
 * Test guidance: a component that calls `useMessages()` must render inside a
 * `QueryClientProvider` and this provider. The default MSW handler for
 * `/api/messages` (src/test/handlers.ts) answers with the test catalog.
 *
 * @example Wiring (src/App.tsx)
 * ```tsx
 * <QueryClientProvider client={queryClient}>
 *   <MessageCatalogProvider>
 *     <ToastProvider>…</ToastProvider>
 *   </MessageCatalogProvider>
 * </QueryClientProvider>
 * ```
 *
 * @example Raising a client-side message
 * ```tsx
 * const { format } = useMessages();
 * publish({ kind: 'alert', text: format(code, [typedOption]) });
 * ```
 */
import { createContext, useCallback, useContext, useMemo } from 'react';
import type { ReactNode } from 'react';
import { useQuery } from '@tanstack/react-query';
import { messagesApi } from '../api/messages';
import type { MessageCatalogData } from '../api/messages';

/**
 * A placeholder: `{` + one or more ASCII digits + `}`. Without the `u` flag
 * `\d` is `[0-9]` only, which matches the server's `\{(\d+)\}`.
 *
 * Sharing one global expression is safe: `String.prototype.replace` resets
 * `lastIndex` to 0 before it scans with a global expression.
 */
const PLACEHOLDER = /\{(\d+)\}/g;

/** The react-query key of the catalog; one entry for the whole page. */
const MESSAGES_QUERY_KEY = ['messages'] as const;

/**
 * Applies the catalog's substitution rule to one template.
 *
 * The rule, identical to the server's `MessageCatalog`:
 * - Each `{n}` (n = one or more decimal digits, 0-based) is replaced by
 *   `String(args[n])`, in one left-to-right pass.
 * - A `{n}` with no argument (`n` at or beyond `args.length`, or a hole in the
 *   array) stays exactly as written.
 * - Text inserted from an argument is never scanned again, so an argument that
 *   itself contains `{1}` appears verbatim.
 * - The replacement is literal: `$&`, `$1` or `$$` in an argument are inserted
 *   as typed (a replacer function is used, never a replacement string).
 * - It is not ICU and not `java.text.MessageFormat`. Apostrophes need no
 *   escaping and are kept, and no other syntax (`{0,number}`, `''`) has any
 *   meaning. Arguments are not trimmed.
 *
 * Pure: no side effects and no access to the loaded catalog.
 *
 * @param template the raw text with its `{n}` placeholders
 * @param args the substitution values for `{0}`, `{1}`, …; defaults to none
 * @returns the template with every matched placeholder replaced
 *
 * @example
 * ```ts
 * formatMessage('{0} of {1}', ['a', 'b']); // 'a of b'
 * formatMessage('{0} and {1}', ['a']);     // 'a and {1}'
 * formatMessage("it's {0}", ['$&']);       // "it's $&"
 * ```
 */
export function formatMessage(template: string, args: ReadonlyArray<string | number> = []): string {
  return template.replace(PLACEHOLDER, (placeholder: string, digits: string): string => {
    // Leading zeros read as on the server (`{01}` is argument 1); an index too
    // large for any array simply finds no argument and keeps its placeholder.
    const index = Number(digits);
    if (index >= args.length) {
      return placeholder;
    }
    const arg = args[index];
    return arg === undefined ? placeholder : String(arg);
  });
}

/** What `useMessages()` returns. */
export interface MessageCatalogValue {
  /**
   * Returns the catalog text of `code` with `args` substituted by
   * {@link formatMessage}. Returns `code` itself while the catalog is not
   * loaded, when it failed to load, and for a code the catalog does not hold.
   */
  format(code: string, args?: ReadonlyArray<string | number>): string;
  /** `true` once the catalog has loaded; it stays `false` after a failure. */
  ready: boolean;
}

/** `null` outside a provider, which `useMessages` reports as an error. */
const MessageCatalogContext = createContext<MessageCatalogValue | null>(null);

/**
 * Loads the message catalog once and provides `format` and `ready` to every
 * descendant. Renders `children` immediately, never a placeholder of its own.
 */
export function MessageCatalogProvider({ children }: { children: ReactNode }) {
  const query = useQuery({
    queryKey: MESSAGES_QUERY_KEY,
    // An arrow, so react-query's function context is never passed to getAll.
    queryFn: () => messagesApi.getAll(),
    // Loaded once for the page: never stale, never collected.
    staleTime: Infinity,
    gcTime: Infinity,
    // A failed load is final. No retry, and no refetch on focus or reconnect
    // either: without data a query counts as stale, so these two options keep
    // "no retry" true whatever defaults the surrounding QueryClient carries.
    retry: false,
    refetchOnWindowFocus: false,
    refetchOnReconnect: false,
  });

  // Undefined until the first success. react-query's structural sharing keeps
  // the same object across renders, which keeps `format` below stable.
  const catalog: MessageCatalogData | undefined = query.data;
  const ready = query.isSuccess;

  const format = useCallback(
    (code: string, args?: ReadonlyArray<string | number>): string => {
      // Own properties only: a plain JSON object inherits `toString`,
      // `constructor` and the like, which are not messages.
      if (catalog !== undefined && Object.prototype.hasOwnProperty.call(catalog, code)) {
        const template = catalog[code];
        if (typeof template === 'string') {
          return formatMessage(template, args ?? []);
        }
      }
      return code;
    },
    [catalog],
  );

  const value = useMemo<MessageCatalogValue>(() => ({ format, ready }), [format, ready]);

  return <MessageCatalogContext value={value}>{children}</MessageCatalogContext>;
}

/**
 * Returns the catalog's `format` and `ready`.
 *
 * @throws Error when called outside `MessageCatalogProvider`, as
 *   `useFunctionKeys` does outside `KeyScopeProvider`; a missing provider is a
 *   wiring mistake, not a state to render around.
 */
export function useMessages(): MessageCatalogValue {
  const value = useContext(MessageCatalogContext);
  if (value === null) {
    throw new Error('useMessages must be used within MessageCatalogProvider');
  }
  return value;
}
