/**
 * The states resource: the typed call behind the State picker.
 *
 * What it replaces. On the 5250, the State prompt was the program PMTSTATER,
 * called with the state field as its one parameter (`PmtState
 * extpgm('PMTSTATER')`, 5250_Subfile/PMTCUSTR.SQLRPGLE:87-90, and the
 * `pState` parameter of 5250_Subfile/PMTSTATER.SQLRPGLE:52-54). Its cursor
 * `DataCur` read STATES with `upper(NAME) like '%<filter>%'` and ordered the
 * rows by NAME or by STATE as F7 toggled them
 * (5250_Subfile/PMTSTATER.SQLRPGLE:150-162,262-273,395-400). Here the same
 * read is one stateless request:
 *
 * `GET /api/states?nameContains=<filter>&sort=name|code` → 200 `[{state, name}]`
 *
 * - `nameContains` is the "Name Contains" entry, at most 10 characters as in
 *   the PMTSTATED field SC_NAME (5250_Subfile/PMTSTATED.DSPF:88). It is sent
 *   exactly as given: the server trims and uppercases it and matches it
 *   anywhere in the uppercased name, and a blank value returns all 58 rows.
 *   An over-long value is answered 400 APP0400.
 * - `sort` is the F7 order: `name` (the order the picker opens with) or
 *   `code`. Any other value is answered 400 APP0400.
 * - Option 1, which returned the code to the caller, is the picker's own
 *   `onSelect(code)`; nothing about it reaches the API.
 *
 * Constraints:
 * - Layer rule: the only runtime import is {@link request} from `./client`,
 *   and types come from the generated `./schema` as type-only imports. Nothing
 *   is imported from `components/`, `errors/`, `features/` or `auth/`; nothing
 *   here renders, touches the DOM or keeps state.
 * - API types: `features/states/*` take {@link StateResponse} and
 *   {@link StateSort} from here and never import `schema.d.ts` themselves.
 * - Errors are not caught. A failed call rejects with the `ApiError` that
 *   `./client` built (400 APP0400, 401 APP0401, 500 DEM9999, or the synthetic
 *   DEM9999 for a request that never reached the server), and the State
 *   picker passes it to `errors/useProblemPresenter.ts`.
 * - No caching. The server's `StateService` holds the states in memory; the
 *   picker loads the list once when it opens and asks again only when Enter
 *   applies a changed filter or F7 changes the order, as PMTSTATER reopened
 *   its cursor.
 *
 * @example
 * ```ts
 * // The picker opens: all states, by name.
 * const all = await statesApi.list('', 'name');
 *
 * // Filter "car" applied with Enter, then F7: North and South Carolina by code.
 * const carolinas = await statesApi.list('car', 'code');
 * ```
 */
import { request } from './client';
import type { components, operations } from './schema';

// ---------------------------------------------------------------------------
// Public types
// ---------------------------------------------------------------------------

/**
 * One row of the state list: alias of the schema `StateResponse`, the two
 * columns of STATES (5250_Subfile/States.sql:8-13).
 *
 * - `state`: the two-character code, such as `NY`; what the picker returns.
 * - `name`: the name as stored, in mixed case, such as `New York`.
 */
export type StateResponse = components['schemas']['StateResponse'];

/** The query parameters of `GET /api/states`, as the OpenAPI snapshot declares them. */
type ListStatesQuery = NonNullable<operations['listStates']['parameters']['query']>;

/**
 * The order of the state list, toggled by F7: `name` sorts by state name
 * ("By Name"), `code` by state code ("By Code"). Derived from the snapshot's
 * `sort` enum, so a change to the contract fails the type check here.
 */
export type StateSort = NonNullable<ListStatesQuery['sort']>;

// ---------------------------------------------------------------------------
// The resource
// ---------------------------------------------------------------------------

/** The typed calls of the states resource. */
export const statesApi = {
  /**
   * The states whose name contains `nameContains`, in `sort` order.
   *
   * @param nameContains the "Name Contains" entry as the user typed it, at
   *   most 10 characters; blank for all 58 states. Neither trimmed nor
   *   uppercased here, because the server applies the shared normalization.
   * @param sort `name` or `code`, the F7 order
   * @returns the matching rows; an empty array when no name matches
   * @throws ApiError (as a rejection) for every failed call, unhandled here
   */
  list(nameContains: string, sort: StateSort): Promise<StateResponse[]> {
    // `satisfies` ties the parameter names to the snapshot's `listStates`
    // operation, so a renamed or removed parameter fails `tsc -b`.
    const query = { nameContains, sort } satisfies ListStatesQuery;
    return request<StateResponse[]>('/api/states', { query });
  },
};
