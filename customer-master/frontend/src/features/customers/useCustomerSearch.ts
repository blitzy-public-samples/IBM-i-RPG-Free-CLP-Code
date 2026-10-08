/**
 * useCustomerSearch: the customer search list, its loaded pages and its
 * paging. The hook behind `CustomerSearchPanel` (search page and Customer
 * picker alike).
 *
 * What it replaces. PMTCUSTR kept a 9,999-record expanding subfile fed by the
 * `ItemCur` cursor (5250_Subfile/PMTCUSTR.SQLRPGLE:208-223):
 *
 *   PMTCUSTR                                   Here
 *   SflClear + SflFirstPage on new criteria    `search(criteria)`: a new
 *     (:261-285, :532-545)                       generation, first page only
 *   SflFillPage on PageDown, 12 records plus   `next()`: one keyset page of
 *     one look-ahead fetch (:287-299, :556-600)  12 through `nextCursor`
 *   Subfile pages already written              the loaded pages, kept as the
 *                                                client-side cursor stack, so
 *                                                PageUp needs no request
 *   SFLEND(*MORE), PMTCUSTD :88                `more` ("More..." / "Bottom")
 *   MAXSFLRECDS 9999 and DEM0006 (:180, :290)  `limitReached`; the server
 *                                                cuts the list and sends the
 *                                                notice, re-sent by `next()`
 *   DEM0002 sets NewSearchCriteria (:539-541)  `pendingNewSearch`
 *   LastSearchCriteria (:150-154, :627)        `applied`
 *   Enter with nothing to process shows the    `toLastLoaded()`
 *     last loaded page,
 *     ((RcdsInSfl-1) div 12) * 12 + 1 (:506-513)
 *   SC_CSR_RCD on the first invalid or last    `showPageOf(custId)`
 *     processed option (:484-486, :667-674)
 *   ReadByKey + UpdSflRecd after an edit       `replaceRow(summary)`: the row
 *     (:454-457)                                 changes in place, unsorted
 *
 * The server holds no cursor between requests: each page is a stateless
 * `GET /api/customers` with `size=12` and the opaque `nextCursor` of the
 * page before it (keyset pagination). The infinite query's `pageParams` are
 * the cursor stack, so moving back is a local index change and no backward
 * keyset query exists.
 *
 * Constraints:
 * - Criteria are sent exactly as shown on screen (the filter inputs already
 *   uppercase them as typed); the server trims, normalizes and validates
 *   them, so DEM0007 and the 9,999 cap are server rules, never decided here.
 * - Rows are never reordered or filtered client-side; the server's order
 *   (name, city, state, id) is the list's order.
 * - Messages. The hook holds no message text and renders nothing. A notice
 *   in a page (DEM0002 on an empty first page, DEM0006 on the page that
 *   reaches 9,999 rows) goes to `onNotice`, and a rejected request (an
 *   `ApiError`, such as 400 DEM0007) goes to `onError` uninspected. A
 *   response that arrives after its list was replaced by `search` or
 *   `reset`, or after the owner unmounted, reaches neither callback.
 * - No effect sets state. Requests start from event handlers (`search`,
 *   `next`) or, for Inquiry's load on open (PMTCUSTR :252-256), from the
 *   lazily initialised request that `initial` seeds; the query fetches it on
 *   mount.
 * - Paging while a page loads. No action is refused while a request is
 *   pending, and the latest explicit navigation decides the page shown:
 *   `search`, `reset`, `previous`, `toLastLoaded`, `showPageOf` and a
 *   `next` that moves each take a new navigation token. A `next` load that
 *   completes after any of them keeps its page with the loaded pages (the
 *   cursor stack, so a later `next` reaches it with no request) but leaves
 *   the page shown alone. A `next` at the deepest loaded page while that page
 *   loads joins the load, with no second request, and asks for its page
 *   again.
 * - One list per instance. Every mounted instance (the search page, each
 *   opening of a picker) keys its queries with an identity of its own, so
 *   two panels showing the same criteria under one `QueryClient` never share
 *   loaded pages, page loads or notices, and a reopened picker starts its
 *   first search from page 1.
 * - Must run under a `QueryClientProvider`.
 * - Layer rule: imports only `api/customers`, React and TanStack Query.
 *
 * @example
 * ```tsx
 * const list = useCustomerSearch({
 *   initial: mode === 'inquiry' ? { name: '', city: '', state: '', includeInactive: false } : null,
 *   onNotice: (notice) => publish({ kind: 'status', text: notice.message }),
 *   onError: (error) => present(error, { setFieldErrors, focusField }),
 * });
 * // Enter: list.search({ ...typed, includeInactive }); PageDown:
 * if ((await list.next()) === 'none') publish({ kind: 'alert', text: format('DEM0003') });
 * <ResultsTable rows={list.page} ... />
 * ```
 */
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { useInfiniteQuery } from '@tanstack/react-query';
import type { DefaultError, InfiniteData } from '@tanstack/react-query';
import { customersApi } from '../../api/customers';
import type { CustomerSummaryResponse, Notice, SearchResponse } from '../../api/customers';

// ---------------------------------------------------------------------------
// Public types
// ---------------------------------------------------------------------------

/** Rows per page: SFLPAG(0012) of PMTCUSTD, `SFLPAGESIZE` of PMTCUSTR (:134). */
export const SEARCH_PAGE_SIZE = 12;

/**
 * The criteria of one search, as shown on screen: the PMTCUSTD `SC_NAME`,
 * `SC_CITY` and `SC_STATE` entries and the F9 include-inactive toggle.
 */
export interface SearchCriteria {
  /** "Name starts with:", at most 13 characters; blank means no name filter. */
  name: string;
  /** "City starts with:", at most 13 characters; blank means no city filter. */
  city: string;
  /** "State +", blank (every state) or a two-letter code; any other length is 400 DEM0007. */
  state: string;
  /** F9: inactive customers are listed too when true. */
  includeInactive: boolean;
}

/** Options of {@link useCustomerSearch}. */
export interface UseCustomerSearchOptions {
  /**
   * Criteria whose first page loads on mount (Inquiry mode, PMTCUSTR
   * :252-256). `null` or absent: no list until the first `search` (Maintenance
   * and Selection wait for Enter). Read on the first render only.
   */
  initial?: SearchCriteria | null;
  /** Receives each notice a page carries: DEM0002 (nothing matches) and DEM0006 (9,999 rows). */
  onNotice: (notice: Notice) => void;
  /** Receives each failed request's rejection, an `ApiError` as `api/client.ts` raises it. */
  onError: (error: unknown) => void;
}

/**
 * What one PageDown ({@link UseCustomerSearchResult.next}) did:
 *
 * - `'moved'`: showed the next page, already loaded (no request);
 * - `'loaded'`: fetched the next page and showed it; a call made while that
 *   page was loading joined the load and resolves the same way;
 * - `'bottom'`: the page stays: no further rows; also when the fetch failed
 *   (the error went to `onError`), the list was replaced meanwhile, or a
 *   newer navigation (`previous`, `toLastLoaded`, `showPageOf`, a `next`
 *   that moved) took over while the page loaded, in which case the fetched
 *   page is kept with the loaded pages and a later `next` shows it with no
 *   request;
 * - `'limit'`: the list stops at 9,999 rows; DEM0006 was sent to `onNotice`
 *   again (PMTCUSTR :290-292) and the page stays;
 * - `'none'`: there is no list (no rows loaded); the caller sends DEM0003
 *   (PMTCUSTR :294-297).
 */
export type NextOutcome = 'moved' | 'loaded' | 'bottom' | 'limit' | 'none';

/** The list state and actions {@link useCustomerSearch} returns. */
export interface UseCustomerSearchResult {
  /** Criteria of the current list (LastSearchCriteria); `null` when no search is pending or loaded. */
  applied: SearchCriteria | null;
  /** The loaded pages in order, with {@link replaceRow} replacements applied. */
  pages: CustomerSummaryResponse[][];
  /** Rows of the page at {@link position}; empty when there is no list. */
  page: CustomerSummaryResponse[];
  /** 0-based index of the page shown. */
  position: number;
  /** Rows loaded so far over every page (RcdsInSfl). */
  loadedCount: number;
  /** At least one row is loaded. */
  hasList: boolean;
  /** Rows follow the page shown: "More..." when true, "Bottom" when false (SFLEND(*MORE)). */
  more: boolean;
  /** The deepest loaded page ended the list at 9,999 rows. */
  limitReached: boolean;
  /**
   * PMTCUSTR `NewSearchCriteria`: the next Enter must search. True with no
   * request, after a failed request (such as DEM0007), and after a first page
   * that matched nothing (DEM0002).
   */
  pendingNewSearch: boolean;
  /** A request (first or next page) is in flight. */
  loading: boolean;
  /**
   * The request in flight is PageDown's next page ({@link next}); while
   * {@link loading} is true and this is false, a first page is loading.
   */
  loadingNext: boolean;
  /** Starts a new list from its first page, even for criteria identical to {@link applied}. */
  search: (criteria: SearchCriteria) => void;
  /**
   * PageDown: shows the next page, loading it when needed. With `'moved'` or
   * `'loaded'` the page shown is the one after the page shown at the call.
   */
  next: () => Promise<NextOutcome>;
  /** PageUp: shows the previous loaded page; false at the first page or with no list. */
  previous: () => boolean;
  /** Shows the last page loaded so far. */
  toLastLoaded: () => void;
  /** Shows the loaded page that holds `custId`; no change when no loaded page holds it. */
  showPageOf: (custId: string) => void;
  /** Empties the list; the next Enter searches (F5, or a State changed through F4). */
  reset: () => void;
  /** Replaces the row with `summary.custId` wherever it is loaded, in place, until the next `search` or `reset`. */
  replaceRow: (summary: CustomerSummaryResponse) => void;
}

// ---------------------------------------------------------------------------
// Internals
// ---------------------------------------------------------------------------

/**
 * One list: its criteria and a generation number. Every `search` and `reset`
 * takes a new generation, so even identical criteria start a fresh list, and
 * a response is current only while its generation is the latest one.
 */
interface SearchRequest {
  readonly criteria: SearchCriteria;
  readonly generation: number;
}

/** The pages of one list and the cursor each was requested with (`null` for the first page). */
type SearchData = InfiniteData<SearchResponse, string | null>;

/**
 * Query key of one list: the identity of the hook instance that owns it
 * ({@link nextInstanceId}), then its criteria and generation. `undefined`
 * members while no list is requested (the query is then disabled).
 */
type SearchQueryKey = readonly ['customers', 'search', number, SearchCriteria | undefined, number | undefined];

/** Shared empty values, so an empty list keeps a stable identity across renders. */
const EMPTY_PAGE: CustomerSummaryResponse[] = [];
const EMPTY_OVERRIDES: ReadonlyMap<string, CustomerSummaryResponse> = new Map();

/**
 * The last identity {@link nextInstanceId} issued. Module state, written only
 * by that function, which runs once per mounted hook instance.
 */
let lastInstanceId = 0;

/**
 * A new hook instance identity, unique for the life of the page. Each
 * mounted instance (a search page, one opening of a picker) takes its own.
 * Generations count from zero in every instance, so without it two lists
 * with the same criteria and generation would share one query, its loaded
 * pages and its page loads. `useId` is not used: React promises its value
 * unique only among the components mounted together (and derives it from the
 * position in the tree when it hydrates markup), not a new value for every
 * mount, which a reopened picker needs. It is a number, so the query key
 * stays JSON-hashable.
 *
 * Called only as the lazy initialiser of the hook's identity state, once per
 * mount; StrictMode's second call merely skips a number.
 */
function nextInstanceId(): number {
  lastInstanceId += 1;
  return lastInstanceId;
}

function searchQueryKey(instanceId: number, request: SearchRequest | null): SearchQueryKey {
  return ['customers', 'search', instanceId, request?.criteria, request?.generation];
}

/**
 * The pages a user can page through: the first page always (it is the list,
 * even when empty), later pages only when they hold rows. A continuation page
 * comes back empty only when the look-ahead row changed between requests; it
 * holds no rows, so it adds no page and the list simply ends ("Bottom").
 */
function browsablePages(data: SearchData | undefined): SearchResponse[] {
  if (data === undefined) {
    return [];
  }
  return data.pages.filter((response, index) => index === 0 || response.items.length > 0);
}

// ---------------------------------------------------------------------------
// The hook
// ---------------------------------------------------------------------------

/**
 * The customer search list: criteria, loaded pages, the page shown and
 * PageDown/PageUp, as PMTCUSTR's subfile kept them. See the module comment
 * for the source mapping and the constraints.
 */
export function useCustomerSearch(options: UseCustomerSearchOptions): UseCustomerSearchResult {
  const { initial = null, onNotice, onError } = options;

  // This instance's identity, fixed for its lifetime and part of every query
  // key it uses, so two panels, or two openings of a picker, never share a list.
  const [instanceId] = useState(nextInstanceId);
  // The list requested; seeded from `initial` so Inquiry loads on open without an effect.
  const [request, setRequest] = useState<SearchRequest | null>(() =>
    initial === null ? null : { criteria: { ...initial }, generation: 0 },
  );
  // Requested page index; clamped to the loaded pages when read (see `position`).
  const [positionIndex, setPositionIndex] = useState(0);
  // Rows replaced after an edit, keyed by customer id.
  const [overrides, setOverrides] = useState<ReadonlyMap<string, CustomerSummaryResponse>>(EMPTY_OVERRIDES);

  // Read only in handlers and in the query function, never during render.
  const generationRef = useRef(0);
  const mountedRef = useRef(true);
  const callbacksRef = useRef({ onNotice, onError });
  // The navigation token: every action that sets the page shown takes the next one.
  const navigationRef = useRef(0);
  const nextInFlightRef = useRef<Promise<NextOutcome> | null>(null);
  // The token of the latest PageDown waiting for the load in flight; the
  // loaded page is shown only while no other navigation has taken a newer one.
  const nextNavigationRef = useRef(0);

  // Keep the latest callbacks for responses that arrive later; this effect only assigns the ref.
  useEffect(() => {
    callbacksRef.current = { onNotice, onError };
  }, [onNotice, onError]);

  // Responses arriving after unmount reach no callback. Setting true again on
  // mount keeps StrictMode's simulated unmount and remount working.
  useEffect(() => {
    mountedRef.current = true;
    return () => {
      mountedRef.current = false;
    };
  }, []);

  const query = useInfiniteQuery<SearchResponse, DefaultError, SearchData, SearchQueryKey, string | null>({
    queryKey: searchQueryKey(instanceId, request),
    queryFn: async ({ queryKey, pageParam }): Promise<SearchResponse> => {
      const [, , , criteria, generation] = queryKey;
      if (criteria === undefined || generation === undefined) {
        // The query is disabled without a request, so this cannot be reached.
        throw new Error('useCustomerSearch: no search is requested');
      }
      const isCurrent = (): boolean => mountedRef.current && generationRef.current === generation;
      let response: SearchResponse;
      try {
        response = await customersApi.search({
          name: criteria.name,
          city: criteria.city,
          state: criteria.state,
          includeInactive: criteria.includeInactive,
          size: SEARCH_PAGE_SIZE,
          cursor: pageParam ?? undefined,
        });
      } catch (error) {
        if (isCurrent()) {
          callbacksRef.current.onError(error);
        }
        throw error;
      }
      if (response.notice !== null && isCurrent()) {
        callbacksRef.current.onNotice(response.notice);
      }
      return response;
    },
    enabled: request !== null,
    initialPageParam: null,
    getNextPageParam: (last: SearchResponse) => last.nextCursor ?? undefined,
    // A failed search is reported once and shown as such (DEM0007 is not transient).
    retry: false,
    // A list changes only through `search`, `next` and `reset`: never refetched
    // behind the user's back, and dropped as soon as a new generation replaces it.
    staleTime: Infinity,
    gcTime: 0,
  });

  const { data, isError, isFetching, isFetchingNextPage, fetchNextPage } = query;

  const browsable = useMemo(() => browsablePages(data), [data]);
  const pages = useMemo(
    () =>
      browsable.map((response) =>
        overrides.size === 0 ? response.items : response.items.map((row) => overrides.get(row.custId) ?? row),
      ),
    [browsable, overrides],
  );

  const pageCount = pages.length;
  // Clamped: after `next()` loads a page, its index may be set before the
  // query's new data reaches this render; the current page stays until then.
  const position = Math.min(positionIndex, Math.max(pageCount - 1, 0));
  const page = pages[position] ?? EMPTY_PAGE;
  const loadedCount = pages.reduce((count, items) => count + items.length, 0);
  const hasList = loadedCount > 0;
  const deepest = data?.pages.at(-1);
  const more = position < pageCount - 1 || (deepest !== undefined && deepest.nextCursor !== null);
  const limitReached = deepest?.limitReached ?? false;
  const firstPage = data?.pages[0];
  const pendingNewSearch = request === null || isError || (firstPage !== undefined && firstPage.items.length === 0);

  const search = useCallback((criteria: SearchCriteria) => {
    generationRef.current += 1;
    navigationRef.current += 1;
    nextInFlightRef.current = null;
    setRequest({ criteria: { ...criteria }, generation: generationRef.current });
    setPositionIndex(0);
    setOverrides(EMPTY_OVERRIDES);
  }, []);

  const reset = useCallback(() => {
    generationRef.current += 1;
    navigationRef.current += 1;
    nextInFlightRef.current = null;
    setRequest(null);
    setPositionIndex(0);
    setOverrides(EMPTY_OVERRIDES);
  }, []);

  const next = useCallback((): Promise<NextOutcome> => {
    if (!hasList || deepest === undefined) {
      return Promise.resolve('none');
    }
    if (position < pageCount - 1) {
      // Also while a load is in flight: a PageUp may have left loaded pages
      // after the one shown, and this newer navigation takes over from it.
      navigationRef.current += 1;
      setPositionIndex(position + 1);
      return Promise.resolve('moved');
    }
    // At the deepest loaded page while its successor loads: this PageDown
    // joins that load and requests nothing more, and as the latest navigation
    // it has the page shown when it arrives.
    const inFlight = nextInFlightRef.current;
    if (inFlight !== null) {
      nextNavigationRef.current = navigationRef.current;
      return inFlight;
    }
    if (deepest.limitReached) {
      if (deepest.notice !== null) {
        callbacksRef.current.onNotice(deepest.notice);
      }
      return Promise.resolve('limit');
    }
    if (deepest.nextCursor === null) {
      return Promise.resolve('bottom');
    }

    const generation = generationRef.current;
    nextNavigationRef.current = navigationRef.current;
    const loading = (async (): Promise<NextOutcome> => {
      const result = await fetchNextPage();
      if (!mountedRef.current || generationRef.current !== generation) {
        // The list was replaced or reset meanwhile; its new state stands.
        return 'bottom';
      }
      const loaded = browsablePages(result.data).length;
      if (result.isFetchNextPageError || loaded <= pageCount) {
        // Failed (already sent to onError) or no further rows: stay.
        return 'bottom';
      }
      if (navigationRef.current !== nextNavigationRef.current) {
        // A newer navigation took over while the page loaded. The page stays
        // with the loaded pages, and the page that navigation showed stands.
        return 'bottom';
      }
      setPositionIndex(loaded - 1);
      return 'loaded';
    })();
    nextInFlightRef.current = loading;
    const settle = () => {
      if (nextInFlightRef.current === loading) {
        nextInFlightRef.current = null;
      }
    };
    loading.then(settle, settle);
    return loading;
  }, [hasList, deepest, position, pageCount, fetchNextPage]);

  const previous = useCallback((): boolean => {
    if (position > 0) {
      navigationRef.current += 1;
      setPositionIndex(position - 1);
      return true;
    }
    return false;
  }, [position]);

  const toLastLoaded = useCallback(() => {
    // A navigation even when that page is already shown: a load still in
    // flight then leaves it shown.
    navigationRef.current += 1;
    setPositionIndex(Math.max(pageCount - 1, 0));
  }, [pageCount]);

  const showPageOf = useCallback(
    (custId: string) => {
      const index = pages.findIndex((items) => items.some((row) => row.custId === custId));
      if (index >= 0) {
        navigationRef.current += 1;
        setPositionIndex(index);
      }
    },
    [pages],
  );

  const replaceRow = useCallback((summary: CustomerSummaryResponse) => {
    setOverrides((current) => {
      const updated = new Map(current);
      updated.set(summary.custId, { ...summary });
      return updated;
    });
  }, []);

  return {
    applied: request?.criteria ?? null,
    pages,
    page,
    position,
    loadedCount,
    hasList,
    more,
    limitReached,
    pendingNewSearch,
    loading: isFetching,
    loadingNext: isFetchingNextPage,
    search,
    next,
    previous,
    toLastLoaded,
    showPageOf,
    reset,
    replaceRow,
  };
}
