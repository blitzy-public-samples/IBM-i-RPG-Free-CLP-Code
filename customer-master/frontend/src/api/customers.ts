/**
 * Typed calls to the customers resource: search, read, review, add, update.
 *
 * What it replaces. On the 5250, PMTCUSTR read the list through its `ItemCur`
 * cursor (5250_Subfile/PMTCUSTR.SQLRPGLE:208-223) and called MTNCUSTR as a
 * program with the customer id and a function code (`CustDsp`,
 * 5250_Subfile/PMTCUSTR.SQLRPGLE:76-90; 5250_Subfile/MTNCUSTR.SQLRPGLE:45-48),
 * which read, edited and wrote CUSTMAST itself. Here each of those steps is one
 * stateless HTTP request; the server keeps no cursor, no lock and no
 * conversation between them.
 *
 * | Call     | Request                           | Success                              |
 * |----------|-----------------------------------|--------------------------------------|
 * | `search` | `GET /api/customers?…`            | 200 `SearchResponse`                 |
 * | `get`    | `GET /api/customers/{custId}`     | 200 `CustomerResponse`               |
 * | `review` | `POST /api/customers/review`      | 200 `ReviewResponse`                 |
 * | `add`    | `POST /api/customers`             | 201 `CustomerResponse`, version 0    |
 * | `update` | `PUT /api/customers/{custId}`     | 200 `CustomerResponse`, version + 1  |
 *
 * Constraints:
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
 * - Errors. Nothing here catches. Every non-2xx response rejects with the
 *   `ApiError` of `./client`, which the calling feature hands to
 *   `errors/useProblemPresenter.ts`.
 * - Cancellation. Only the reads, `search` and `get`, take an `AbortSignal`,
 *   which a query passes on so a read nobody waits for any more is aborted;
 *   abandoned before its answer arrived, it rejects with the signal's
 *   reason, while an error answer that arrived still rejects with its
 *   `ApiError`. `review`, `add` and `update` take none: a write is never
 *   cancelled, because once sent it may commit.
 * - Layer rule. The only runtime import is `request` from `./client`; types
 *   come from the generated `./schema` as type-only imports. Nothing is
 *   imported from `components/`, `errors/`, `features/` or `auth/`, and
 *   nothing here renders, touches the DOM or keeps state.
 * - API types. Features import the customer types from this module, never
 *   from `./schema` directly.
 *
 * @example
 * ```ts
 * const page = await customersApi.search({ name: 'NIBH' });
 * const customer = await customersApi.get('AAAD');
 * const reviewed = await customersApi.review({ purpose: 'EDIT', ...draft });
 * const saved = await customersApi.update(customer.custId, {
 *   ...reviewed.customer,
 *   version: customer.version,
 * });
 * ```
 */
import { request } from './client';
import type { components, operations } from './schema';

// ---------------------------------------------------------------------------
// Types (aliases of the generated OpenAPI schemas)
// ---------------------------------------------------------------------------

/** The generated OpenAPI component schemas. */
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

// ---------------------------------------------------------------------------
// Field names
// ---------------------------------------------------------------------------

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

/** One of the nine customer data field names. */
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

/** The path of one customer; the id is percent-encoded, so no value can leave the path segment. */
function customerPath(custId: string): string {
  return `/api/customers/${encodeURIComponent(custId)}`;
}

// ---------------------------------------------------------------------------
// The calls
// ---------------------------------------------------------------------------

/**
 * The customers resource. Each method sends one request through `./client`
 * (HTTP Basic, `X-Requested-With`) and resolves with the parsed body; a
 * non-2xx response rejects with `ApiError`, uncaught.
 */
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
   * `signal`, when given, aborts the read once its list is no longer wanted
   * (replaced by a new search, reset, or left by an unmounted owner); a call
   * abandoned before its answer arrived then rejects with the signal's
   * reason, not an `ApiError`, while an error answer that arrived keeps its
   * `ApiError`.
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
   * `signal`, when given, aborts the read once nobody waits for it (the
   * window that asked closed); a call abandoned before its answer arrived
   * then rejects with the signal's reason, not an `ApiError`, while an
   * error answer that arrived keeps its `ApiError`.
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
