/**
 * useCustomerSearch: the customer search list, its loaded pages and its
 * paging. The hook behind `CustomerSearchPanel` (search page and Customer
 * picker alike).
 *
 * It replaces PMTCUSTR's 9,999-record expanding subfile fed by the `ItemCur`
 * cursor (5250_Subfile/PMTCUSTR.SQLRPGLE:208-223). The server holds no cursor
 * between requests: each page is a stateless `GET /api/customers` with
 * `size=12` and the opaque `nextCursor` of the page before it. The infinite
 * query's `pageParams` are the client-side cursor stack, so PageUp needs no
 * request and no backward keyset query exists.
 *
 * Constraints:
 * - Criteria are sent exactly as shown on screen; the server trims,
 *   normalizes and validates them, so DEM0007 and the 9,999 cap are server
 *   rules, never decided here.
 * - Rows are never reordered or filtered client-side; the server's order
 *   (name, city, state, id) is the list's order.
 * - Messages. The hook holds no message text, renders nothing and passes
 *   rejections on uninspected. A response that arrives after its list was
 *   replaced by `search` or `reset`, or after the owner unmounted, reaches
 *   neither callback.
 * - Cancellation. react-query's abort signal goes to `customersApi.search`,
 *   so a replaced or unmounted list has its request aborted once it loses its
 *   last observer; neither the abort's rejection nor that list's notice
 *   reaches a callback. An answer that did arrive is still reported while its
 *   list is current: a 401 whose sign-out cancelled the query reaches
 *   `onError` as APP0401. A page load of the current list is never aborted:
 *   navigation keeps its query observed.
 * - No effect sets state. Requests start from `search` and `next` or, for
 *   Inquiry's load on open, from the lazily initialised request `initial`
 *   seeds, which the query fetches on mount.
 * - Paging while a page loads. No action is refused while a request is
 *   pending; the latest explicit navigation decides the page shown. `search`,
 *   `reset`, `previous`, `toLastLoaded`, `showPageOf` and a `next` that moves
 *   each take a new navigation token, and a `next` load completing after one
 *   of them keeps its page in the cursor stack without showing it. A `next`
 *   at the deepest loaded page while that page loads joins the load, so no
 *   second fetch cancels the first.
 * - One list per instance ({@link nextInstanceId}): two panels showing the
 *   same criteria under one `QueryClient` never share a list or its notices,
 *   and a reopened picker starts its first search from page 1.
 * - Must run under a `QueryClientProvider`.
 * - Layer rule: imports only `api/customers`, React and TanStack Query.
 */
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { useInfiniteQuery } from '@tanstack/react-query';
import type { DefaultError, InfiniteData } from '@tanstack/react-query';
import { customersApi } from '../../api/customers';
import type { CustomerSummaryResponse, Notice, SearchResponse } from '../../api/customers';

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

export interface UseCustomerSearchResult {
  /**
   * Criteria of the current list (LastSearchCriteria, PMTCUSTR :150-154,
   * :627); `null` when no search is pending or loaded.
   */
  applied: SearchCriteria | null;
  /** The loaded pages in order, with {@link replaceRow} replacements applied. */
  pages: CustomerSummaryResponse[][];
  /** Rows of the page at {@link position}; empty when there is no list. */
  page: CustomerSummaryResponse[];
  /** Page shown, 0-based. */
  position: number;
  /** Rows loaded so far over every page (RcdsInSfl). */
  loadedCount: number;
  hasList: boolean;
  /** Rows follow the page shown: "More..." when true, "Bottom" when false (SFLEND(*MORE), PMTCUSTD :88). */
  more: boolean;
  /**
   * The deepest loaded page ended the list at 9,999 rows (MAXSFLRECDS 9999,
   * PMTCUSTR :180, :290): the server cut the list there and sent DEM0006.
   */
  limitReached: boolean;
  /**
   * PMTCUSTR `NewSearchCriteria`: the next Enter must search. True with no
   * request, after a failed request (such as DEM0007), and after a first page
   * that matched nothing (DEM0002, :539-541).
   */
  pendingNewSearch: boolean;
  loading: boolean;
  /**
   * The request in flight is PageDown's next page ({@link next}); while
   * {@link loading} is true and this is false, a first page is loading.
   */
  loadingNext: boolean;
  /**
   * Starts a new list from its first page, even for criteria identical to
   * {@link applied} (SflClear + SflFirstPage, PMTCUSTR :261-285, :532-545).
   */
  search: (criteria: SearchCriteria) => void;
  /**
   * PageDown (SflFillPage: 12 records plus one look-ahead, PMTCUSTR :287-299,
   * :556-600): shows the next page, loading it when needed. With `'moved'` or
   * `'loaded'` the page shown is the one after the page shown at the call.
   */
  next: () => Promise<NextOutcome>;
  /** PageUp: shows the previous loaded page; false at the first page or with no list. */
  previous: () => boolean;
  /**
   * Shows the last page loaded so far: the preserved source defect of Enter
   * with nothing to process, ((RcdsInSfl-1) div 12) * 12 + 1 (PMTCUSTR
   * :506-513).
   */
  toLastLoaded: () => void;
  /**
   * Shows the loaded page that holds `custId` (SC_CSR_RCD on the first invalid
   * or last processed option, PMTCUSTR :484-486, :667-674); no change when no
   * loaded page holds it.
   */
  showPageOf: (custId: string) => void;
  /** Empties the list; the next Enter searches (F5, or a State changed through F4). */
  reset: () => void;
  /**
   * Replaces the row with `summary.custId` wherever it is loaded, until the
   * next `search` or `reset`. As with ReadByKey + UpdSflRecd after an edit
   * (PMTCUSTR :454-457), the row changes in place and is not re-sorted.
   */
  replaceRow: (summary: CustomerSummaryResponse) => void;
}

/**
 * One list: its criteria and a generation number. Every `search` and `reset`
 * takes a new generation, so even identical criteria start a fresh list, and
 * a response is current only while its generation is the latest one.
 */
interface SearchRequest {
  readonly criteria: SearchCriteria;
  readonly generation: number;
}

type SearchData = InfiniteData<SearchResponse, string | null>;

/**
 * Query key of one list: the identity of the hook instance that owns it
 * ({@link nextInstanceId}), then its criteria and generation. `undefined`
 * members while no list is requested (the query is then disabled).
 */
type SearchQueryKey = readonly ['customers', 'search', number, SearchCriteria | undefined, number | undefined];

/** Stable identities across renders. */
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

/**
 * The customer search list: criteria, loaded pages, the page shown and
 * PageDown/PageUp, as PMTCUSTR's subfile kept them. The module comment states
 * the constraints; a member of {@link UseCustomerSearchResult} that replaces a
 * PMTCUSTR or PMTCUSTD element cites it.
 */
export function useCustomerSearch(options: UseCustomerSearchOptions): UseCustomerSearchResult {
  const { initial = null, onNotice, onError } = options;

  const [instanceId] = useState(nextInstanceId);
  const [request, setRequest] = useState<SearchRequest | null>(() =>
    initial === null ? null : { criteria: { ...initial }, generation: 0 },
  );
  // Requested page index; clamped to the loaded pages when read (see `position`).
  const [positionIndex, setPositionIndex] = useState(0);
  const [overrides, setOverrides] = useState<ReadonlyMap<string, CustomerSummaryResponse>>(EMPTY_OVERRIDES);

  // Never read during render.
  const generationRef = useRef(0);
  const mountedRef = useRef(true);
  const callbacksRef = useRef({ onNotice, onError });
  // The navigation token: every action that sets the page shown takes the next one.
  const navigationRef = useRef(0);
  const nextInFlightRef = useRef<Promise<NextOutcome> | null>(null);
  // The token of the latest PageDown waiting for the load in flight; the
  // loaded page is shown only while no other navigation has taken a newer one.
  const nextNavigationRef = useRef(0);

  // The latest callbacks, for responses that arrive later.
  useEffect(() => {
    callbacksRef.current = { onNotice, onError };
  }, [onNotice, onError]);

  // Set true again on mount for StrictMode's simulated unmount and remount.
  useEffect(() => {
    mountedRef.current = true;
    return () => {
      mountedRef.current = false;
    };
  }, []);

  const query = useInfiniteQuery<SearchResponse, DefaultError, SearchData, SearchQueryKey, string | null>({
    queryKey: searchQueryKey(instanceId, request),
    queryFn: async ({ queryKey, pageParam, signal }): Promise<SearchResponse> => {
      const [, , , criteria, generation] = queryKey;
      if (criteria === undefined || generation === undefined) {
        // Unreachable: the query is disabled without a request.
        throw new Error('useCustomerSearch: no search is requested');
      }
      const isCurrent = (): boolean => mountedRef.current && generationRef.current === generation;
      let response: SearchResponse;
      try {
        response = await customersApi.search(
          {
            name: criteria.name,
            city: criteria.city,
            state: criteria.state,
            includeInactive: criteria.includeInactive,
            size: SEARCH_PAGE_SIZE,
            cursor: pageParam ?? undefined,
          },
          signal,
        );
      } catch (error) {
        // `abandoned` marks the abort's own rejection, which StrictMode's
        // simulated unmount also causes after `mountedRef` is true again, so
        // `isCurrent` alone cannot drop it. An answer that did arrive is never
        // `signal.reason`: a 401 whose sign-out cancelled this very query is
        // still reported.
        const abandoned = signal.aborted && error === signal.reason;
        if (isCurrent() && !abandoned) {
          callbacksRef.current.onError(error);
        }
        throw error;
      }
      if (response.notice !== null && isCurrent() && !signal.aborted) {
        callbacksRef.current.onNotice(response.notice);
      }
      return response;
    },
    enabled: request !== null,
    initialPageParam: null,
    getNextPageParam: (last: SearchResponse) => last.nextCursor ?? undefined,
    // No retry: a rejection such as DEM0007 is not transient.
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
        return 'bottom';
      }
      const loaded = browsablePages(result.data).length;
      if (result.isFetchNextPageError || loaded <= pageCount) {
        // Failed, or no page with rows was added.
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
