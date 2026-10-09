/**
 * Message texts (client): the browser's message catalog and its substitution
 * rule. It replaces the CUSTMSGF message file and SndMsgPgmQ's `&1`
 * substitution; the catalog is served by the public `GET /api/messages`, with
 * the source `&1` written `{0}`.
 *
 * - Children render at once; until the catalog loads, and for good after a
 *   failure, `ready` is `false` and `format` returns the code it was given.
 * - `format` is referentially stable while the catalog is unchanged.
 * - Loads once: infinite stale and garbage-collection times, and react-query
 *   de-duplicates the in-flight request, so StrictMode's double mount sends
 *   one request.
 * - A failed load is final and quiet: not retried, thrown, shown or logged.
 * - The bundle carries no message text and no list of message ids.
 * - Imports only `react`, `@tanstack/react-query` and `../api/messages`.
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

const MESSAGES_QUERY_KEY = ['messages'] as const;

/**
 * Applies the catalog's substitution rule, identical to the server's
 * `MessageCatalog`, to one template:
 * - Each `{n}` (0-based decimal digits) becomes `String(args[n])`, in one
 *   left-to-right pass.
 * - A `{n}` with no argument (beyond `args.length`, or a hole) stays as
 *   written.
 * - Inserted text is never scanned again, so an argument's own `{1}` stays.
 * - Replacement is literal: a replacer function inserts `$&`, `$1` or `$$` as
 *   typed.
 * - Not ICU or `java.text.MessageFormat`: apostrophes need no escaping, no
 *   other syntax has meaning, and arguments are not trimmed.
 *
 * Pure: no side effects and no access to the loaded catalog.
 *
 * @param template the raw text with its `{n}` placeholders
 * @param args the substitution values for `{0}`, `{1}`, …; defaults to none
 * @returns the template with every matched placeholder replaced
 * @example formatMessage('{0} and {1}', ['a']); // 'a and {1}'
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

const MessageCatalogContext = createContext<MessageCatalogValue | null>(null);

/** Loads the catalog and provides `format` and `ready` to its descendants. */
export function MessageCatalogProvider({ children }: { children: ReactNode }) {
  const query = useQuery({
    queryKey: MESSAGES_QUERY_KEY,
    queryFn: () => messagesApi.getAll(),
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
 * @throws Error when called outside `MessageCatalogProvider` (a wiring mistake)
 */
export function useMessages(): MessageCatalogValue {
  const value = useContext(MessageCatalogContext);
  if (value === null) {
    throw new Error('useMessages must be used within MessageCatalogProvider');
  }
  return value;
}
