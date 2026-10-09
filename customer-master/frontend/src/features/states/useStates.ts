/**
 * The data-access hook behind the USA State prompt window (`StatePicker`).
 *
 * It replaces PMTSTATER's cursor cycle: `ProcessSearchCriteria` opened
 * `DataCur` for the "Name Contains" entry, `SflLoadAll` loaded every match
 * and `SflClear` emptied the subfile (5250_Subfile/PMTSTATER.SQLRPGLE:388-410,
 * :352-374, :588-596). Here each cycle is one stateless
 * `GET /api/states?nameContains=&sort=name|code` through {@link statesApi},
 * and the hook keeps only the current query and its rows.
 *
 * - {@link UseStatesResult.search} always issues a new request, even for an
 *   unchanged filter and order: each call takes a new sequence number in the
 *   query key, so after F5 the next Enter searches again, as
 *   `NewSearchCriteria` forces (:257-260).
 * - {@link UseStatesResult.clear} drops the current query; nothing is
 *   requested until the next `search`.
 * - The filter is sent as typed. The server trims, uppercases and matches it
 *   and orders the rows; the hook never re-sorts them.
 * - A request made obsolete by `clear`, a newer `search` or unmounting is
 *   aborted through react-query's signal and reports nothing. An answer that
 *   arrived for the current request is still reported: a 401 whose sign-out
 *   cancelled the query reaches `onError`.
 * - Every query key carries an identity of the mounted hook, so nothing is
 *   shared across picker openings, and `gcTime: 0` drops each entry once it
 *   is unobserved.
 * - In production each opening's `initial` query and each `search` send
 *   exactly one request: no retries, no refetch on focus or reconnect. In
 *   development, StrictMode also aborts the initial load at its simulated
 *   unmount and starts it again on remount; the aborted load reports nothing.
 * - Errors are not presented here: each failure of a current request is
 *   passed unchanged, once, to the latest `onError`. Every 401 a state
 *   request receives goes through `api/client.ts` to `AuthProvider`'s 401
 *   handler, whether or not this hook still reports it. That handler signs
 *   out unless the 401 refused a sign-in trial's credentials or a
 *   `superseded` identity, one a sign-out or newer sign-in already replaced.
 * - Requires a `QueryClientProvider` above the calling component.
 *
 * @example
 * ```tsx
 * const { rows, loading, applied, search, clear } = useStates({
 *   initial: { nameContains: '', sort: 'name' },
 *   onError: (error) => present(error, { setFieldErrors, focusField }),
 * });
 * search(filter, 'code'); // Enter with new criteria, or F7
 * clear();                // F5
 * ```
 */
import { useCallback, useEffect, useRef, useState } from 'react';
import { skipToken, useQuery } from '@tanstack/react-query';
import { statesApi } from '../../api/states';
import type { StateResponse, StateSort } from '../../api/states';

/**
 * One state-list query: the "Name Contains" entry as typed (blank for all 58
 * states) and the F7 order.
 */
export type StateQuery = { nameContains: string; sort: StateSort };

/** Options of {@link useStates}. */
export interface UseStatesOptions {
  /**
   * The query issued when the hook mounts; read on mount only. `null` or
   * absent means no request until the first `search`. The picker passes
   * `{ nameContains: '', sort: 'name' }`, PMTSTATER's initial load.
   */
  initial?: StateQuery | null;
  /**
   * Called with the rejection (normally an `ApiError`) of each request that
   * fails while it is still the current one.
   */
  onError?: (error: unknown) => void;
}

/** What {@link useStates} returns. */
export interface UseStatesResult {
  /**
   * The rows of the current query, in response order; empty when cleared,
   * while the current query is pending, or when it failed. The empty value is
   * one shared frozen array, so its reference is stable across renders.
   */
  rows: StateResponse[];
  /** Whether the current query is being fetched. Always `false` after `clear()`. */
  loading: boolean;
  /** The rejection of the current query, or `null`. Always `null` after `clear()`. */
  error: unknown;
  /** The last query requested, already while it is pending; `null` after `clear()`. */
  applied: StateQuery | null;
  /** Requests the states matching `nameContains` in `sort` order; a new request on every call. */
  search(nameContains: string, sort: StateSort): void;
  /** Empties the list and forgets the current query; no request is made. */
  clear(): void;
}

/**
 * `seq` starts at 0 on mount and only increases, so no query key is reused
 * and an unchanged filter still fetches.
 */
type HookState = { query: StateQuery | null; seq: number };

/**
 * The shared empty `rows`. Frozen, so any attempt to fill it throws; typed as
 * the mutable `rows` member so callers can pass it wherever a list of states
 * is expected.
 */
const EMPTY = Object.freeze<StateResponse[]>([]) as StateResponse[];

const QUERY_SCOPE = 'states';

/** The last identity {@link nextInstanceId} issued. */
let lastInstanceId = 0;

/**
 * A new hook identity, unique for the life of the page, so each opening of
 * the picker takes its own. Not `useId`: React keeps that unique only among
 * the components mounted together, not new for every mount. A number keeps
 * the query key JSON-hashable. Runs once per mount as a lazy state
 * initialiser; StrictMode's second call only skips a number.
 */
function nextInstanceId(): number {
  lastInstanceId += 1;
  return lastInstanceId;
}

/**
 * Loads, reloads and clears the USA state list for the State picker.
 *
 * @param options the query to issue on mount, and the error callback
 * @returns the current rows and status, plus `search` and `clear`
 */
export function useStates(options?: UseStatesOptions): UseStatesResult {
  const initial = options?.initial ?? null;
  const [{ query, seq }, setState] = useState<HookState>(() => ({ query: initial, seq: 0 }));
  const onError = options?.onError;
  // This mount's own part of every query key ({@link nextInstanceId}), so a
  // reopened picker never shares a cache entry, or an in-flight request whose
  // failure only its closed predecessor could report, with an earlier opening.
  const [instance] = useState(nextInstanceId);

  const seqRef = useRef(0);
  // The sequence number of the request whose failure may still be reported,
  // or `null` when there is none.
  const currentRef = useRef<number | null>(initial === null ? null : 0);
  const mountedRef = useRef(true);
  const onErrorRef = useRef(onError);

  useEffect(() => {
    onErrorRef.current = onError;
  }, [onError]);

  // Set again on mount, so reporting resumes after StrictMode's simulated
  // unmount and remount.
  useEffect(() => {
    mountedRef.current = true;
    return () => {
      mountedRef.current = false;
    };
  }, []);

  const { data, error, isFetching } = useQuery({
    queryKey: [QUERY_SCOPE, instance, query?.nameContains ?? null, query?.sort ?? null, seq],
    enabled: query !== null,
    // react-query runs the function of the render that changed the key, so
    // its `query` and `seq` describe that key's request. `skipToken` types
    // the cleared state, which `enabled: false` already never fetches, so
    // `query` is non-null inside without an unreachable throw.
    queryFn:
      query === null
        ? skipToken
        : async ({ signal }) => {
            try {
              return await statesApi.list(query.nameContains, query.sort, signal);
            } catch (failure) {
              // Reported only for the current request of a mounted hook, and
              // never for the abort's own rejection: an aborted request is
              // obsolete, including the one StrictMode's simulated unmount
              // aborts after `mountedRef` is true again. An answer that did
              // arrive, such as a 401 whose sign-out cancelled this query, is
              // still reported. Rethrown either way, so react-query settles
              // this request's own cache entry.
              const abandoned = signal.aborted && failure === signal.reason;
              if (mountedRef.current && currentRef.current === seq && !abandoned) {
                onErrorRef.current?.(failure);
              }
              throw failure;
            }
          },
    retry: false,
    gcTime: 0,
    refetchOnWindowFocus: false,
    refetchOnReconnect: false,
  });

  const search = useCallback((nameContains: string, sort: StateSort): void => {
    seqRef.current += 1;
    const next = seqRef.current;
    currentRef.current = next;
    setState({ query: { nameContains, sort }, seq: next });
  }, []);

  const clear = useCallback((): void => {
    currentRef.current = null;
    setState((prev) => ({ query: null, seq: prev.seq }));
  }, []);

  return {
    rows: query === null ? EMPTY : (data ?? EMPTY),
    loading: query !== null && isFetching,
    error: query === null ? null : (error ?? null),
    applied: query,
    search,
    clear,
  };
}
