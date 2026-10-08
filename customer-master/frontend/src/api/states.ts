/**
 * The states resource: the typed call behind the State picker,
 * `GET /api/states?nameContains=&sort=name|code` → 200 `[{state, name}]`.
 *
 * - The server filters and orders, as PMTSTATER's name-contains read and F7
 *   toggle did (5250_Subfile/PMTSTATER.SQLRPGLE:150-162,262-273,395-400): it
 *   trims and uppercases `nameContains` and matches it anywhere in the
 *   uppercased name. A filter over the 10 characters of the PMTSTATED field
 *   (5250_Subfile/PMTSTATED.DSPF:88), or a `sort` other than `name` or `code`,
 *   is answered 400 APP0400.
 * - Errors are not caught: a failed call rejects with the `ApiError` that
 *   `./client` built, and the State picker hands it to `useProblemPresenter`.
 * - No caching: every call is a new request, which the server answers from
 *   STATES, and the picker asks again only when Enter applies a changed filter
 *   or F7 changes the order.
 */
import { request } from './client';
import type { components, operations } from './schema';

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

export const statesApi = {
  /**
   * The states whose name contains `nameContains`, in `sort` order.
   *
   * @param nameContains the "Name Contains" entry as the user typed it, at
   *   most 10 characters; blank for all 58 states. Neither trimmed nor
   *   uppercased here, because the server applies the shared normalization.
   * @param sort `name` or `code`, the F7 order
   * @param signal aborts the read once its list is no longer wanted (the
   *   picker cleared, searched again or closed)
   * @returns the matching rows; an empty array when no name matches
   * @throws ApiError (as a rejection) for every failed call, unhandled here;
   *   the signal's reason instead when `signal` abandoned the call before
   *   its answer arrived
   */
  list(nameContains: string, sort: StateSort, signal?: AbortSignal): Promise<StateResponse[]> {
    // `satisfies` ties the parameter names to the snapshot's `listStates`
    // operation, so a renamed or removed parameter fails `tsc -b`.
    const query = { nameContains, sort } satisfies ListStatesQuery;
    return request<StateResponse[]>('/api/states', { query, signal });
  },
};
