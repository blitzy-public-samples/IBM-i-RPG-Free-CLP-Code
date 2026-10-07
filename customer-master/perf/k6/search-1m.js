/*
 * search-1m.js - k6 load test of the customer search API over 1,000,000 customer rows.
 *
 * Purpose
 *   Re-measures, on the target search API, the claim in 5250_Subfile/README.md:42 that the
 *   IBM i search showed "no discernable performance hit" with 1 million records. The claim was
 *   never measured (discrepancy D9 in docs/deviations-and-open-questions.md), so this script
 *   produces the measured evidence instead.
 *
 * What it exercises
 *   The target form of the PMTCUSTR ItemCur cursor [5250_Subfile/PMTCUSTR.SQLRPGLE:208-223]:
 *   name and city prefix LIKE, exact state, the active filter (F9 includes inactive rows),
 *   ORDER BY NAME, CITY, STATE, and 12-row pages fetched with look-ahead. The Inquiry first page
 *   loads on entry [5250_Subfile/PMTCUSTR.SQLRPGLE:251-256] and PageDown continues the cursor
 *   [5250_Subfile/PMTCUSTR.SQLRPGLE:287-299]; here PageDown is the opaque nextCursor of
 *   GET /api/customers. One get-by-id operation stands for opening a customer (option 5).
 *   The script calls customer-api directly (http://app:8080 inside Compose), not through nginx.
 *
 * Run order, from customer-master/
 *   1. docker compose --profile tools run --rm generator --count=1000000
 *   2. docker compose --profile perf run --rm k6
 *   Step 1 replaces the 300 seed rows with 1,000,000 generated rows. Without it, setup() aborts
 *   with "Data set too small", so a seed-only run can never produce false evidence.
 *
 * Environment
 *   K6_BASE_URL  API base URL; default http://app:8080 (the Compose service name).
 *   K6_USER      User to sign in as (HTTP Basic, role INQUIRY); default: the Compose demo
 *                inquiry user.
 *   K6_PASSWORD  That user's password; default: the Compose demo inquiry password.
 *   The script never prints, tags or logs the credential values.
 *
 * Load model and verdict
 *   One scenario, search_mix: a constant arrival rate of 20 requests per second for 2 minutes,
 *   one request per iteration, rotating the eight operations of the mix in equal shares.
 *   The verdict is exactly two thresholds: http_req_duration p(95) < 300 ms and
 *   http_req_failed rate < 1%. The op_* trends report latency per operation and carry no
 *   threshold.
 *
 * Output
 *   The summary is written to /perf/results/search-1m-summary.json, which is
 *   customer-master/perf/results/ on the host (bind mount ./perf:/perf) and is git-ignored.
 *   Results are recorded in customer-master/docs/performance/search-benchmark.md as measured
 *   numbers only.
 *
 * Summary formats
 *   With Compose's --summary-export flag the file is in the legacy export format, with p95 at
 *   metrics.http_req_duration['p(95)']. Without the flag it is the handleSummary format, with
 *   p95 at metrics.http_req_duration.values['p(95)']. Median (p50) is 'med' in both.
 *
 * Known limitation
 *   The jslib.k6.io import below is fetched when k6 starts (both k6 run and k6 inspect), so the
 *   container needs outbound HTTPS.
 *
 * Linux bind-mount caveat
 *   The grafana/k6 image runs as uid 12345, so perf/results/ on the host must be writable by
 *   that uid (for example chmod o+w perf/results), or writing the summary file fails.
 */

import http from 'k6/http';
import { check } from 'k6';
import exec from 'k6/execution';
import encoding from 'k6/encoding';
import { Trend } from 'k6/metrics';
// Exporting handleSummary suppresses k6's default stdout summary; the pinned textSummary keeps it.
import { textSummary } from 'https://jslib.k6.io/k6-summary/0.1.0/index.js';

// --- Configuration ---------------------------------------------------------------------------

const BASE_URL = (__ENV.K6_BASE_URL || 'http://app:8080').replace(/\/+$/, '');
// Demo-only fallbacks: the Compose defaults of the inquiry user. Compose passes the real values.
const USER = __ENV.K6_USER || 'inquiry';
const PASSWORD = __ENV.K6_PASSWORD || 'inquiry-demo';

/** The UI page size, the source subfile page (SFLPAG 12). Sent on every search request. */
const PAGE_SIZE = 12;
/** The deep page of the mix: page 50 holds rows 589-600, far below the 9,999-row cap. */
const DEEP_PAGE = 50;
/** Pool of one-letter name prefixes. */
const LETTERS = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ';
const PREFIX_3 = /^[A-Z]{3}$/;
const CUST_ID = /^[A-Z0-9]{4}$/;
const TOO_SMALL =
  'Data set too small: load 1,000,000 rows first with docker compose --profile tools run --rm generator --count=1000000';

// Built once; the value is only ever placed in the Authorization header.
const AUTHORIZATION = 'Basic ' + encoding.b64encode(`${USER}:${PASSWORD}`);

/**
 * Request parameters shared by every call. The stable `name` tag is mandatory: URLs that carry a
 * cursor or a custId are unique, and without it every one would become its own metric series.
 * X-Requested-With suppresses the WWW-Authenticate challenge on a 401.
 */
function params(op, name) {
  return {
    headers: {
      Authorization: AUTHORIZATION,
      'X-Requested-With': 'XMLHttpRequest',
      Accept: 'application/json',
    },
    timeout: '30s',
    tags: { op, name },
  };
}

/**
 * GET /api/customers URL: always size=12, plus each supplied query value, URL-encoded.
 * A cursor is passed through exactly as received from nextCursor; it is opaque and is never
 * decoded, inspected or constructed here.
 */
function searchUrl(query) {
  const parts = [`size=${PAGE_SIZE}`];
  const values = query || {};
  for (const key of Object.keys(values)) {
    const value = values[key];
    if (value !== undefined && value !== null) {
      parts.push(`${encodeURIComponent(key)}=${encodeURIComponent(String(value))}`);
    }
  }
  return `${BASE_URL}/api/customers?${parts.join('&')}`;
}

/** GET /api/customers/{custId} URL, with the id segment URL-encoded. */
function customerUrl(custId) {
  return `${BASE_URL}/api/customers/${encodeURIComponent(custId)}`;
}

/** Rotates through a pool (array or string) by the iteration's round number. */
function pick(pool, round) {
  return pool[round % pool.length];
}

/** The parsed JSON body of a 200 response, or null for any other status or a non-JSON body. */
function okBody(res) {
  if (res.status !== 200) {
    return null;
  }
  try {
    return res.json();
  } catch (e) {
    return null;
  }
}

/** The items array of a search response, or [] when the response carries none. */
function itemsOf(body) {
  return body && Array.isArray(body.items) ? body.items : [];
}

/** The next-page cursor of a search response, or null at the bottom of the list. */
function nextCursorOf(body) {
  return body && typeof body.nextCursor === 'string' && body.nextCursor !== '' ? body.nextCursor : null;
}

// --- Options ---------------------------------------------------------------------------------

export const options = {
  scenarios: {
    // At 300 ms or less per request about 6 concurrent VUs suffice; maxVUs absorbs slowdowns so
    // dropped_iterations stays 0. No sleep(): the arrival rate alone sets the pace.
    search_mix: {
      executor: 'constant-arrival-rate',
      rate: 20,
      timeUnit: '1s',
      duration: '2m',
      preAllocatedVUs: 20,
      maxVUs: 100,
    },
  },
  // Exactly the two thresholds of the benchmark verdict; any other would change it.
  thresholds: {
    http_req_duration: ['p(95)<300'],
    http_req_failed: ['rate<0.01'],
  },
  setupTimeout: '2m',
  // 'med' is the p50 the benchmark record needs, alongside p(95) and max.
  summaryTrendStats: ['min', 'med', 'avg', 'p(90)', 'p(95)', 'p(99)', 'max', 'count'],
};

// --- Per-operation latency (reporting only, no thresholds) -----------------------------------

const opFirstPage = new Trend('op_first_page', true);
const opNamePrefix1 = new Trend('op_name_prefix_1', true);
const opNamePrefix3 = new Trend('op_name_prefix_3', true);
const opCityPrefix3 = new Trend('op_city_prefix_3', true);
const opStateCa = new Trend('op_state_ca', true);
const opIncludeInactive = new Trend('op_include_inactive', true);
const opDeepPage = new Trend('op_deep_page', true);
const opGetById = new Trend('op_get_by_id', true);

/**
 * The operation mix, in order. Iteration i runs OPS[i % 8], so each operation gets an equal,
 * deterministic share (about 300 calls in 2 minutes). Each target(data, round) returns the URL
 * and, for get-by-id, the id the response must carry.
 */
const OPS = [
  {
    // The Inquiry first page: no filter.
    op: 'first_page',
    name: 'GET /api/customers',
    trend: opFirstPage,
    search: true,
    target: () => ({ url: searchUrl({}) }),
  },
  {
    // One-letter name prefix, rotating A..Z.
    op: 'name_prefix_1',
    name: 'GET /api/customers?name={1}',
    trend: opNamePrefix1,
    search: true,
    target: (data, round) => ({ url: searchUrl({ name: pick(LETTERS, round) }) }),
  },
  {
    // Three-letter name prefix sampled from real rows in setup().
    op: 'name_prefix_3',
    name: 'GET /api/customers?name={3}',
    trend: opNamePrefix3,
    search: true,
    target: (data, round) => ({ url: searchUrl({ name: pick(data.namePrefixes, round) }) }),
  },
  {
    // Three-letter city prefix sampled from real rows in setup().
    op: 'city_prefix_3',
    name: 'GET /api/customers?city={3}',
    trend: opCityPrefix3,
    search: true,
    target: (data, round) => ({ url: searchUrl({ city: pick(data.cityPrefixes, round) }) }),
  },
  {
    // Exact state.
    op: 'state_ca',
    name: 'GET /api/customers?state=CA',
    trend: opStateCa,
    search: true,
    target: () => ({ url: searchUrl({ state: 'CA' }) }),
  },
  {
    // F9: inactive rows included, no other filter.
    op: 'include_inactive',
    name: 'GET /api/customers?includeInactive=true',
    trend: opIncludeInactive,
    search: true,
    target: () => ({ url: searchUrl({ includeInactive: 'true' }) }),
  },
  {
    // Page 50, reached in setup() through the nextCursor chain (PageDown 49 times).
    op: 'deep_page',
    name: 'GET /api/customers?cursor={page50}',
    trend: opDeepPage,
    search: true,
    target: (data) => ({ url: searchUrl({ cursor: data.deepCursor }) }),
  },
  {
    // Open one customer (option 5), with an id seen in setup().
    op: 'get_by_id',
    name: 'GET /api/customers/{custId}',
    trend: opGetById,
    search: false,
    target: (data, round) => {
      const id = pick(data.custIds, round);
      return { url: customerUrl(id), id };
    },
  },
];

// --- Setup: credentials, data pools and the deep-page cursor ---------------------------------

/**
 * Runs once before the load. Its requests (1 session, 26 letters, page 1 and 49 cursor hops:
 * about 77) are tagged op=setup and count toward the global http_req_duration and
 * http_req_failed, about 3% of roughly 2,477 requests; they also serve as warm-up.
 *
 * Returns plain JSON data: { namePrefixes, cityPrefixes, custIds, deepCursor }.
 */
export function setup() {
  // Credentials check: anything but 200 means the run would measure only rejections.
  const session = http.get(`${BASE_URL}/api/session`, params('setup', 'setup GET /api/session'));
  if (session.status !== 200) {
    const reason = session.status === 0 ? ` (no HTTP response, k6 error code ${session.error_code})` : '';
    exec.test.abort(
      `GET ${BASE_URL}/api/session returned status ${session.status}${reason}: ` +
        'check K6_BASE_URL, and that K6_USER and K6_PASSWORD name an INQUIRY user'
    );
  }

  // Prefix and id pools, sampled from the first page of each one-letter name search, so every
  // prefix the load sends matches real rows.
  const namePrefixes = new Set();
  const cityPrefixes = new Set();
  const custIds = new Set();
  const collectIds = (items) => {
    for (const item of items) {
      if (item && typeof item.custId === 'string' && CUST_ID.test(item.custId)) {
        custIds.add(item.custId);
      }
    }
  };
  for (const letter of LETTERS.split('')) {
    const res = http.get(searchUrl({ name: letter }), params('setup', 'setup GET /api/customers?name={letter}'));
    const items = itemsOf(okBody(res));
    for (const item of items) {
      const name = item && typeof item.name === 'string' ? item.name.substring(0, 3) : '';
      if (PREFIX_3.test(name)) {
        namePrefixes.add(name);
      }
      const city = item && typeof item.city === 'string' ? item.city.substring(0, 3) : '';
      if (PREFIX_3.test(city)) {
        cityPrefixes.add(city);
      }
    }
    collectIds(items);
  }

  // Deep-page cursor: page 1 with no filter, then follow nextCursor 49 times, so the 49th hop
  // fetches page 50. deepCursor is the cursor that requests page 50.
  let body = okBody(http.get(searchUrl({}), params('setup', 'setup GET /api/customers')));
  let deepCursor = null;
  for (let page = 2; page <= DEEP_PAGE; page++) {
    const cursor = nextCursorOf(body);
    if (cursor === null) {
      // The chain ended (non-200, non-JSON, or bottom of the list) before page 50.
      exec.test.abort(TOO_SMALL);
    }
    deepCursor = cursor;
    body = okBody(http.get(searchUrl({ cursor }), params('setup', 'setup GET /api/customers?cursor={chain}')));
  }
  const deepItems = itemsOf(body);
  collectIds(deepItems);

  // Data-set guard: the 300 seed rows end the chain at page 25 and abort here.
  if (deepCursor === null || deepItems.length === 0 || namePrefixes.size === 0 || cityPrefixes.size === 0 ||
      custIds.size === 0) {
    exec.test.abort(TOO_SMALL);
  }

  return {
    namePrefixes: Array.from(namePrefixes),
    cityPrefixes: Array.from(cityPrefixes),
    custIds: Array.from(custIds),
    deepCursor,
  };
}

// --- The load: one request per iteration -----------------------------------------------------

export default function (data) {
  const i = exec.scenario.iterationInTest;
  const operation = OPS[i % OPS.length];
  const target = operation.target(data, Math.floor(i / OPS.length));

  const res = http.get(target.url, params(operation.op, operation.name));
  operation.trend.add(res.timings.duration);

  // JSON is parsed only inside the checks, so a non-JSON body (for example an HTML error page)
  // fails a check instead of throwing.
  const checks = { 'status is 200': (r) => r.status === 200 };
  if (operation.search) {
    checks['items is an array'] = (r) => {
      try {
        return Array.isArray(r.json().items);
      } catch (e) {
        return false;
      }
    };
  } else {
    checks['custId matches'] = (r) => {
      try {
        return r.json().custId === target.id;
      } catch (e) {
        return false;
      }
    };
  }
  check(res, checks, { op: operation.op });
}

// --- Summary ---------------------------------------------------------------------------------

/**
 * Keeps the text summary on stdout and writes the summary file, so the export also works without
 * the discouraged --summary-export flag.
 *
 * When Compose also passes --summary-export=/perf/results/search-1m-summary.json, k6's summary
 * wrapper stores the legacy export in this same output map under the same path, replacing the
 * entry below. Exactly one file is therefore written: in the legacy format with the flag, in the
 * handleSummary format without it (paths to p95 as in the header).
 */
export function handleSummary(data) {
  return {
    stdout: textSummary(data, { indent: ' ', enableColors: false }),
    '/perf/results/search-1m-summary.json': JSON.stringify(data, null, 2),
  };
}
