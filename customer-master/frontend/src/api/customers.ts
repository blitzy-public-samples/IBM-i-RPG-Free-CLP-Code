/**
 * Typed calls to the customers resource. Each call is one stateless request:
 * the server keeps no cursor, lock or conversation between calls.
 *
 * - Trust boundary. A write body holds only what the API accepts from a
 *   caller: the nine customer data fields, plus `purpose` on review and
 *   `version` on update. It never carries `custId` (the server allocates it on
 *   add, and the path names it on update), `chgTime`, `chgUser`, a row version
 *   other than `version`, a role or a mode. Bodies are therefore rebuilt from
 *   {@link CUSTOMER_FIELD_NAMES} at runtime and never spread from the caller's
 *   object: the conflict dialog's "Re-apply my changes" starts from the
 *   `current` customer of a 409, a `CustomerResponse` that does carry
 *   `custId`, `chgTime` and `chgUser`, and the API rejects any unknown
 *   property with 400 APP0400.
 * - Cancellation. Only the reads, `search` and `get`, take an `AbortSignal`.
 *   A read abandoned before its answer arrived rejects with the signal's
 *   reason, not an `ApiError`; an error answer that arrived keeps its
 *   `ApiError`. `review`, `add` and `update` take none: a write is never
 *   cancelled, because once sent it may commit.
 * - Errors. Nothing here catches: every non-2xx response rejects with the
 *   `ApiError` of `./client`.
 * - API types. Features import the customer types from this module, never
 *   from `./schema` directly.
 */
import { request } from './client';
import type { components, operations } from './schema';

type Schemas = components['schemas'];

/**
 * The nine editable customer fields, each optional and nullable as the API
 * binds them: `active`, `name`, `addr`, `city`, `state`, `zip`, `acctPhone`,
 * `acctMgr`, `corpPhone`. Body of `POST /api/customers`.
 */
export type CustomerFields = Schemas['CustomerFields'];

/** Body of `PUT /api/customers/{custId}`: the nine fields plus the `version` read. */
export type CustomerUpdateRequest = Schemas['CustomerUpdateRequest'];

/** Body of `POST /api/customers/review`: `purpose` (`ADD` or `EDIT`) plus the nine fields. */
export type ReviewRequest = Schemas['ReviewRequest'];

/**
 * A passed review: the normalized (and, when the address service ran,
 * standardized) values to confirm, whether standardization ran, and the
 * confirmation notice, DEM0000 for EDIT or DEM0009 for ADD.
 */
export type ReviewResponse = Schemas['ReviewResponse'];

/** A stored customer: the nine fields, `custId`, `chgTime`, `chgUser` and `version`. */
export type CustomerResponse = Schemas['CustomerResponse'];

/** One search result row: `custId`, `name`, `city`, `state`, `zip5`, `active`. */
export type CustomerSummaryResponse = Schemas['CustomerSummaryResponse'];

/**
 * One page of search results. `nextCursor` is `null` on the last page and on
 * the page that reaches the 9,999-row cap (`limitReached`, notice DEM0006);
 * `notice` is DEM0002 when nothing matches.
 */
export type SearchResponse = Schemas['SearchResponse'];

/** A non-error message carried in a success payload: `{ code, message }`. */
export type Notice = Schemas['Notice'];

/** Why a review runs: `'ADD'` (notice DEM0009) or `'EDIT'` (notice DEM0000). */
export type ReviewPurpose = ReviewRequest['purpose'];

/**
 * The query of `GET /api/customers`, as the OpenAPI operation `searchCustomers`
 * declares it:
 *
 * - `name`, `city`: "starts with" entries of at most 13 characters; blank
 *   means no filter.
 * - `state`: a two-letter code; blank means every state, any other length is
 *   400 DEM0007.
 * - `includeInactive`: the F9 toggle; `false` when absent.
 * - `size`: rows per page, 1–100; the 12 of the 5250 subfile when absent.
 * - `cursor`: the opaque `nextCursor` of the previous page; absent for the
 *   first page.
 */
export type CustomerSearchParams = NonNullable<operations['searchCustomers']['parameters']['query']>;

/**
 * The nine JSON property names of the customer data fields, in screen order:
 * Active, Name, Address, City, State, ZIP, Account Manager Phone, Account
 * Manager Name, Corporate Phone (5250_Subfile/MTNCUSTD.DSPF:61-120). The
 * field rules run and report in this order, and every write body is built
 * from exactly these keys.
 */
export const CUSTOMER_FIELD_NAMES = [
  'active',
  'name',
  'addr',
  'city',
  'state',
  'zip',
  'acctPhone',
  'acctMgr',
  'corpPhone',
] as const satisfies readonly (keyof CustomerFields)[];

export type CustomerFieldName = (typeof CUSTOMER_FIELD_NAMES)[number];

/** Rows per search page: SFLPAG 12 of PMTCUSTD (5250_Subfile/PMTCUSTR.SQLRPGLE:134). */
const DEFAULT_PAGE_SIZE = 12;

/**
 * A new object holding only the nine customer data fields of `source`.
 *
 * A field whose value is `undefined` is left out, so it is absent from the
 * JSON body; an add or ADD review without `active` therefore lets the server
 * default it to `Y`. A `null` is kept and sent as `null`, which the server
 * binds as absent too. Every other key of `source` (`custId`, `chgTime`,
 * `chgUser`, `version`, `purpose`, anything else) is never copied.
 */
function pickFields(source: Partial<CustomerFields>): CustomerFields {
  const fields: CustomerFields = {};
  for (const name of CUSTOMER_FIELD_NAMES) {
    const value = source[name];
    if (value !== undefined) {
      fields[name] = value;
    }
  }
  return fields;
}

/** Percent-encodes the id, so no value can leave the path segment. */
function customerPath(custId: string): string {
  return `/api/customers/${encodeURIComponent(custId)}`;
}

/** The customers resource; each method resolves with the parsed response body. */
export const customersApi = {
  /**
   * One page of customers matching the filters, in name, city, state, id
   * order.
   *
   * Every filter is sent as given, an empty string included (blank means no
   * filter on the server); `undefined` members are left out of the query.
   *
   * Rejects with 400 APP0400 (cursor, size, entry length) or 400 DEM0007
   * (`state` neither blank nor two characters), with `errors` naming the
   * parameter.
   *
   * `signal` aborts the read once its list is no longer wanted; see
   * Cancellation in the module comment.
   */
  search(params: CustomerSearchParams = {}, signal?: AbortSignal): Promise<SearchResponse> {
    return request<SearchResponse>('/api/customers', {
      query: {
        name: params.name,
        city: params.city,
        state: params.state,
        includeInactive: params.includeInactive ?? false,
        size: params.size ?? DEFAULT_PAGE_SIZE,
        cursor: params.cursor,
      },
      signal,
    });
  },

  /**
   * The stored customer with its `version`.
   *
   * Rejects with 404 DEM0599 when the customer no longer exists, and 400
   * APP0400 when `custId` is not four characters of A–Z and 0–9.
   *
   * `signal` aborts the read once nobody waits for it; see Cancellation in
   * the module comment.
   */
  get(custId: string, signal?: AbortSignal): Promise<CustomerResponse> {
    return request<CustomerResponse>(customerPath(custId), { signal });
  },

  /**
   * Runs the nine field rules in screen order, then address standardization
   * when it is enabled, without saving anything. Only `purpose` and the nine
   * fields of `body` are sent.
   *
   * Resolves with the values to confirm and notice DEM0000 (EDIT) or DEM0009
   * (ADD). Rejects with 422 DEM0501, DEM0502, DEM0503 or DEM9898, or 502
   * APP0502 when the address service fails; a failure after the State rule
   * passed carries `stateAccepted` in its problem.
   */
  review(body: ReviewRequest): Promise<ReviewResponse> {
    return request<ReviewResponse>('/api/customers/review', {
      method: 'POST',
      body: { purpose: body.purpose, ...pickFields(body) },
    });
  },

  /**
   * Adds a customer under the next id the server allocates (the first on a
   * fresh database is `EEEF`). Only the nine fields of `fields` are sent;
   * an absent `active` is stored as `Y`.
   *
   * Resolves with the stored customer at `version` 0. Rejects with 422 on a
   * field rule, 409 DEM1001 when a data load holds the table, and 503 APP0503
   * when no customer ids are left.
   */
  add(fields: CustomerFields): Promise<CustomerResponse> {
    return request<CustomerResponse>('/api/customers', {
      method: 'POST',
      body: pickFields(fields),
    });
  },

  /**
   * Changes a customer, conditional on `body.version` still being the stored
   * one. Only the nine fields and `version` of `body` are sent, so a
   * `CustomerResponse` with edits applied may be passed as it is.
   *
   * Resolves with the stored customer at `version + 1`. Rejects with 422 on a
   * field rule, 404 DEM0599 when the customer no longer exists, 409 DEM1002
   * with `current` (the customer as now stored) when someone else changed it,
   * 409 DEM1001 when its row stays locked, and 400 APP0400 without `version`.
   */
  update(custId: string, body: CustomerUpdateRequest): Promise<CustomerResponse> {
    return request<CustomerResponse>(customerPath(custId), {
      method: 'PUT',
      body: { ...pickFields(body), version: body.version },
    });
  },
} as const;
