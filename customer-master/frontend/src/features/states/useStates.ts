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
 *   same filter and order, because every call bumps a sequence number that is
 *   part of the query key. After F5 the next Enter must search again, as
 *   PMTSTATER forces with `NewSearchCriteria` (:257-260).
 * - {@link UseStatesResult.clear} drops the current query: `rows` becomes
 *   empty, `applied` becomes `null`, and nothing is requested until the next
 *   `search`.
 * - The filter is sent exactly as typed. Trimming, uppercasing and the
 *   `rpad(upper(name), 30) LIKE '%…%'` match are the server's, so the client
 *   never repeats them; the rows keep the server's order (by `name` or by
 *   `state`) and are never re-sorted here.
 * - Only the current query is shown. A response that arrives late for an
 *   older query lands in that query's own cache entry, never in `rows`.
 * - Nothing is cached across picker openings: `gcTime: 0` and the default
 *   `staleTime` of 0 make a fresh mount request again, as PMTSTATER reopened
 *   its cursor each time it was called. No retries and no refetch on focus or
 *   reconnect, so each request the picker asks for is sent exactly once.
 * - Errors are not presented here. A failed request (400 APP0400 for an
 *   unknown sort or an over-long filter, 401, 500 DEM9999, or the synthetic
 *   DEM9999 of `api/client.ts`) is passed unchanged to `onError` once and
 *   exposed as `error`; the picker shows it through `errors/useProblemPresenter`.
 *
 * Constraints:
 * - Layer rule: imports come only from `react`, `@tanstack/react-query` and
 *   `api/states`; the generated `api/schema.d.ts` is reached only through the
 *   `api/states` aliases. Nothing here renders, holds message text or touches
 *   the DOM.
 * - React rules: no effect sets state and no ref is read or written during
 *   render; `search` and `clear` use the functional state updater only, so
 *   their identities are stable for the lifetime of the component.
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
import { useCallback, useState } from 'react';
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
   * Called once for each failed request, with the rejection unchanged
   * (normally an `ApiError`). Not called for a request that succeeds.
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
 * - `seq`: bumped by every `search` and never decreased, so a query key is
 *   never reused within a hook instance and an unchanged filter still fetches.
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
  const [{ query, seq }, setState] = useState<HookState>(() => ({
    query: options?.initial ?? null,
    seq: 0,
  }));
  const onError = options?.onError;

  const { data, error, isFetching } = useQuery({
    queryKey: [QUERY_SCOPE, query?.nameContains ?? null, query?.sort ?? null, seq],
    enabled: query !== null,
    // `query` is a const of this render, so the function below sees it
    // narrowed to non-null; react-query runs the function of the render that
    // changed the key, so it always fetches the query that key describes.
    // `skipToken` keeps the type honest for the cleared state, which
    // `enabled: false` already never fetches.
    queryFn:
      query === null
        ? skipToken
        : async () => {
            try {
              return await statesApi.list(query.nameContains, query.sort);
            } catch (failure) {
              onError?.(failure);
              throw failure;
            }
          },
    retry: false,
    gcTime: 0,
    refetchOnWindowFocus: false,
    refetchOnReconnect: false,
  });

  const search = useCallback((nameContains: string, sort: StateSort): void => {
    setState((prev) => ({ query: { nameContains, sort }, seq: prev.seq + 1 }));
  }, []);

  const clear = useCallback((): void => {
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
