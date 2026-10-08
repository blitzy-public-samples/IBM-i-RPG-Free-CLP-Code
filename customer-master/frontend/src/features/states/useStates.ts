/**
 * The data-access hook behind the USA State prompt window (`StatePicker`).
 *
 * What it replaces. PMTSTATER kept one SQL cursor per screen cycle:
 * `ProcessSearchCriteria` closed it, built `DESCLike` from the "Name Contains"
 * entry and opened `DataCur` (5250_Subfile/PMTSTATER.SQLRPGLE:388-410),
 * `SflLoadAll` fetched every matching row into the subfile (:352-374), and
 * `SflClear` emptied the subfile (:588-596). The program ran that cycle
 * once on entry, sorted by name (`Init` sets `SortbyName`, :442-448, then
 * :171-177), again whenever Enter applied new criteria or F7 changed the
 * order (:196-203, :189-191 after the toggle at :262-273), and F5 cleared
 * the list until the next Enter
 * (:257-260; PMTSTATED shows the subfile only when it holds records,
 * 5250_Subfile/PMTSTATED.DSPF:82-83). Here each cycle is one stateless
 * `GET /api/states?nameContains=&sort=name|code` through {@link statesApi},
 * and the hook keeps only what the browser owns: which query is current and
 * the rows it returned.
 *
 * Behaviour:
 * - {@link UseStatesResult.search} always starts a new request, even for the
 *   same filter and order, because every call takes a new sequence number
 *   that is part of the query key. After F5 the next Enter must search again,
 *   as PMTSTATER forces with `NewSearchCriteria` (:257-260).
 * - {@link UseStatesResult.clear} drops the current query: `rows` becomes
 *   empty, `applied` becomes `null`, and nothing is requested until the next
 *   `search`.
 * - The filter is sent exactly as typed. Trimming, uppercasing and the
 *   `rpad(upper(name), 30) LIKE '%…%'` match are the server's, so the client
 *   never repeats them; the rows keep the server's order (by `name` or by
 *   `state`) and are never re-sorted here.
 * - Only the current query is shown, and only its failure is reported. A
 *   response that arrives late for an older query lands in that query's own
 *   cache entry, never in `rows` or `error`. The request itself is not
 *   cancelled (`statesApi.list` takes no abort signal, and react-query does
 *   not cancel a fetch whose observer went away), so a late rejection still
 *   settles its own cache entry but reaches no callback.
 * - Nothing is shared across picker openings: every query key carries an
 *   identity of the mounted hook, a new one per mount, so a fresh mount
 *   sends its own request, as PMTSTATER reopened its cursor each time it was
 *   called, and never joins a request a closed picker left in flight;
 *   `gcTime: 0` and the default `staleTime` of 0 drop each entry once it is
 *   unobserved. No retries and no refetch on focus or reconnect, so each
 *   request the picker asks for is sent exactly once.
 * - Errors are not presented here. A failed request (400 APP0400 for an
 *   unknown sort or an over-long filter, 401, 500 DEM9999, or the synthetic
 *   DEM9999 of `api/client.ts`) is passed unchanged to the latest `onError`
 *   once, and exposed as `error`; the picker shows it through
 *   `errors/useProblemPresenter`. `onError` is called only while the failed
 *   request is still the current one and the hook is still mounted. A request
 *   made obsolete before it failed (by `clear`, by a newer `search`, or by
 *   the picker closing and unmounting the hook) reports nothing, so it can
 *   neither publish an alert over the screen beneath nor move focus in a
 *   newer list. The 401 handling of `api/client.ts` sits below this hook and
 *   signs out on every 401, current or not.
 *
 * Constraints:
 * - Layer rule: imports come only from `react`, `@tanstack/react-query` and
 *   `api/states`; the generated `api/schema.d.ts` is reached only through the
 *   `api/states` aliases. Nothing here renders, holds message text or touches
 *   the DOM.
 * - React rules: no effect sets state and no ref is read or written during
 *   render. The refs are written by `search` and `clear` and by effects that
 *   only assign them, and read in the query function; `search` and `clear`
 *   depend on refs and state setters only, so their identities are stable
 *   for the lifetime of the component.
 * - Requires a `QueryClientProvider` above the calling component.
 *
 * @example
 * ```tsx
 * // In StatePicker: one request for all states, by name, when the picker opens.
 * const { rows, loading, applied, search, clear } = useStates({
 *   initial: { nameContains: '', sort: 'name' },
 *   onError: (error) => present(error, { setFieldErrors, focusField }),
 * });
 *
 * // Enter with a changed filter:  search(filter, applied?.sort ?? 'name');
 * // F7 toggles the order:         search(filter, sort === 'name' ? 'code' : 'name');
 * // F5 empties the list:          clear();
 * ```
 */
import { useCallback, useEffect, useRef, useState } from 'react';
import { skipToken, useQuery } from '@tanstack/react-query';
import { statesApi } from '../../api/states';
import type { StateResponse, StateSort } from '../../api/states';

// ---------------------------------------------------------------------------
// Public types
// ---------------------------------------------------------------------------

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
   * Called once for each failed request that is still the current one, with
   * the rejection unchanged (normally an `ApiError`). The callback of the
   * latest render is used. Not called for a request that succeeds, nor for
   * one that `clear`, a newer `search` or unmounting made obsolete before it
   * failed.
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

// ---------------------------------------------------------------------------
// Internals
// ---------------------------------------------------------------------------

/**
 * The hook's only state.
 *
 * - `query`: the current query, or `null` when there is none.
 * - `seq`: the sequence number of the last `search` (0 for the query issued
 *   on mount). Numbers come from a counter that only increases, so a query
 *   key is never reused within a hook instance and an unchanged filter still
 *   fetches.
 */
type HookState = { query: StateQuery | null; seq: number };

/**
 * The rows shown when there are none. Frozen, so no caller can fill it, and
 * shared, so `rows` keeps one reference while the list is empty. Typed as the
 * mutable `rows` member so callers can pass it wherever a list of states is
 * expected; any attempt to change it throws instead of corrupting it.
 */
const EMPTY = Object.freeze<StateResponse[]>([]) as StateResponse[];

/** The first element of every state-list query key. */
const QUERY_SCOPE = 'states';

/**
 * The last identity {@link nextInstanceId} issued. Module state, written only
 * by that function, which runs once per mounted hook instance.
 */
let lastInstanceId = 0;

/**
 * A new hook instance identity, unique for the life of the page: each
 * opening of the picker takes its own. `useId` is not used: React promises
 * its value unique only among the components mounted together (and derives
 * it from the position in the tree when it hydrates markup), not a new value
 * for every mount, which a reopened picker needs. It is a number, so the
 * query key stays JSON-hashable.
 *
 * Called only as the lazy initialiser of the hook's identity state, once per
 * mount; StrictMode's second call merely skips a number.
 */
function nextInstanceId(): number {
  lastInstanceId += 1;
  return lastInstanceId;
}

// ---------------------------------------------------------------------------
// The hook
// ---------------------------------------------------------------------------

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

  // Read only in handlers and in the query function, never during render.
  // `seqRef`: the last sequence number handed out; only `search` advances it.
  const seqRef = useRef(0);
  // `currentRef`: the sequence number of the request whose failure may still
  // be reported, or `null` when there is none (after `clear`, or before the
  // first `search` without an initial query). Written only by `search` and
  // `clear`; the argument is used on mount only, as `initial` is.
  const currentRef = useRef<number | null>(initial === null ? null : 0);
  const mountedRef = useRef(true);
  const onErrorRef = useRef(onError);

  // Keep the latest callback for failures that arrive later; this effect only assigns the ref.
  useEffect(() => {
    onErrorRef.current = onError;
  }, [onError]);

  // Failures arriving after unmount reach no callback. Setting true again on
  // mount keeps StrictMode's simulated unmount and remount working.
  useEffect(() => {
    mountedRef.current = true;
    return () => {
      mountedRef.current = false;
    };
  }, []);

  const { data, error, isFetching } = useQuery({
    queryKey: [QUERY_SCOPE, instance, query?.nameContains ?? null, query?.sort ?? null, seq],
    enabled: query !== null,
    // `query` and `seq` are consts of this render, so the function below sees
    // `query` narrowed to non-null; react-query runs the function of the
    // render that changed the key, and `seq` is part of that key, so it
    // always fetches the query that key describes and knows which request it
    // is. `skipToken` keeps the type honest for the cleared state, which
    // `enabled: false` already never fetches.
    queryFn:
      query === null
        ? skipToken
        : async () => {
            try {
              return await statesApi.list(query.nameContains, query.sort);
            } catch (failure) {
              // Reported only while this is still the current request of a
              // mounted hook; rethrown either way, so react-query records the
              // failure in this request's own cache entry.
              if (mountedRef.current && currentRef.current === seq) {
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
