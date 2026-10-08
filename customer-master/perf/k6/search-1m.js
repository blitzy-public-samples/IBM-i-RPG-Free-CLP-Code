/*
 * k6 load test of the customer search API over 1,000,000 customer rows.
 *
 * It measures, on the target, the claim in 5250_Subfile/README.md:42 that the IBM i search showed
 * "no discernable performance hit" with 1 million records, a claim never measured (discrepancy D9
 * in docs/deviations-and-open-questions.md); docs/performance/search-benchmark.md records the
 * results. The operations are the target form of the PMTCUSTR ItemCur cursor
 * [5250_Subfile/PMTCUSTR.SQLRPGLE:208-223]: the Inquiry first page loaded on entry
 * [5250_Subfile/PMTCUSTR.SQLRPGLE:251-256], its filters, PageDown
 * [5250_Subfile/PMTCUSTR.SQLRPGLE:287-299] as the opaque nextCursor of GET /api/customers, and
 * opening a customer (option 5) as get-by-id.
 *
 * Run order, from customer-master/
 *   1. docker compose --profile tools run --rm generator --count=1000000
 *   2. docker compose --profile perf run --rm k6
 *   Without step 1, setup() aborts with "Data set too small", so a run on the 300 seed rows can
 *   never produce false evidence.
 *
 * Credentials
 *   K6_USER and K6_PASSWORD name an INQUIRY user for HTTP Basic, with demo-only fallbacks; the
 *   script never prints, tags or logs either value.
 *
 * Network
 *   The script imports only k6's built-in modules and formats its text summary itself, so a run
 *   needs network access only to K6_BASE_URL: customer-api directly (http://app:8080 inside
 *   Compose), not nginx.
 *
 * Linux bind-mount caveat
 *   The grafana/k6 image runs as uid 12345, which cannot write perf/results/ on the host when the
 *   checkout's user owns it, so the Compose k6 service runs as root (user "0:0"). A plain
 *   docker run of the image needs --user 0:0, or perf/results/ writable by that uid (for example
 *   chmod o+w perf/results), or writing the summary file fails.
 */

import http from 'k6/http';
import { check } from 'k6';
import exec from 'k6/execution';
import encoding from 'k6/encoding';
import { Trend } from 'k6/metrics';

const BASE_URL = (__ENV.K6_BASE_URL || 'http://app:8080').replace(/\/+$/, '');
// Demo-only fallbacks: the Compose defaults of the inquiry user. Compose passes the real values.
const USER = __ENV.K6_USER || 'inquiry';
const PASSWORD = __ENV.K6_PASSWORD || 'inquiry-demo';

/** The UI page size, the source subfile page (SFLPAG 12). Sent on every search request. */
const PAGE_SIZE = 12;
/** The deep page of the mix: page 50 holds rows 589-600, far below the 9,999-row cap. */
const DEEP_PAGE = 50;
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

function customerUrl(custId) {
  return `${BASE_URL}/api/customers/${encodeURIComponent(custId)}`;
}

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

function itemsOf(body) {
  return body && Array.isArray(body.items) ? body.items : [];
}

function nextCursorOf(body) {
  return body && typeof body.nextCursor === 'string' && body.nextCursor !== '' ? body.nextCursor : null;
}

export const options = {
  scenarios: {
    // At 300 ms per request about 6 VUs sustain 20 iterations/s; maxVUs 100 absorbs slowdowns up
    // to about 5 s per request. Slower responses drop iterations, which k6 reports as
    // dropped_iterations. No sleep(): the arrival rate alone sets the pace.
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
    op: 'first_page',
    name: 'GET /api/customers',
    trend: opFirstPage,
    search: true,
    target: () => ({ url: searchUrl({}) }),
  },
  {
    op: 'name_prefix_1',
    name: 'GET /api/customers?name={1}',
    trend: opNamePrefix1,
    search: true,
    target: (data, round) => ({ url: searchUrl({ name: pick(LETTERS, round) }) }),
  },
  {
    op: 'name_prefix_3',
    name: 'GET /api/customers?name={3}',
    trend: opNamePrefix3,
    search: true,
    target: (data, round) => ({ url: searchUrl({ name: pick(data.namePrefixes, round) }) }),
  },
  {
    op: 'city_prefix_3',
    name: 'GET /api/customers?city={3}',
    trend: opCityPrefix3,
    search: true,
    target: (data, round) => ({ url: searchUrl({ city: pick(data.cityPrefixes, round) }) }),
  },
  {
    op: 'state_ca',
    name: 'GET /api/customers?state=CA',
    trend: opStateCa,
    search: true,
    target: () => ({ url: searchUrl({ state: 'CA' }) }),
  },
  {
    op: 'include_inactive',
    name: 'GET /api/customers?includeInactive=true',
    trend: opIncludeInactive,
    search: true,
    target: () => ({ url: searchUrl({ includeInactive: 'true' }) }),
  },
  {
    op: 'deep_page',
    name: 'GET /api/customers?cursor={page50}',
    trend: opDeepPage,
    search: true,
    target: (data) => ({ url: searchUrl({ cursor: data.deepCursor }) }),
  },
  {
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
      exec.test.abort(TOO_SMALL);
    }
    deepCursor = cursor;
    body = okBody(http.get(searchUrl({ cursor }), params('setup', 'setup GET /api/customers?cursor={chain}')));
  }
  const deepItems = itemsOf(body);
  collectIds(deepItems);

  // Data-set guard: the seed rows (226 active of 300) end the chain on page 19, so the loop above
  // aborts at page 20, never here. It rejects a failed or empty page 50 and empty prefix/id pools.
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

const PASS_MARK = '\u2713';
const FAIL_MARK = '\u2717';
const GROUP_MARK = '\u2588';
const DETAIL_MARK = '\u21b3';
const CHECK_INDENT = ' '.repeat(5);
const METRIC_INDENT = ' '.repeat(3);
const BYTE_UNITS = ['B', 'kB', 'MB', 'GB', 'TB', 'PB', 'EB', 'ZB', 'YB'];
const FIXED_TIME_UNITS = {
  s: { unit: 's', coef: 0.001 },
  ms: { unit: 'ms', coef: 1 },
  us: { unit: '\u00b5s', coef: 1000 },
};

/**
 * Exporting handleSummary suppresses k6's default stdout summary, so this rebuilds it in the
 * layout of k6's legacy end-of-test summary, as jslib k6-summary 0.1.0 textSummary prints it with
 * indent ' ' and no colors. Absent fields never throw: a missing value prints as n/a and a missing
 * check count as 0.
 */
function textSummary(data) {
  const summaryOptions = data.options || {};
  const trendStats = Array.isArray(summaryOptions.summaryTrendStats)
    ? summaryOptions.summaryTrendStats
    : options.summaryTrendStats;
  return groupLines(CHECK_INDENT, data.root_group || {})
    .concat(metricLines(data.metrics || {}, trendStats, summaryOptions.summaryTimeUnit))
    .join('\n');
}

function groupLines(indent, group) {
  const lines = [];
  let inner = indent;
  if (group.name) {
    lines.push(`${indent}${GROUP_MARK} ${group.name}`, '');
    inner = `${indent}  `;
  }
  const checks = Array.isArray(group.checks) ? group.checks : [];
  for (const result of checks) {
    lines.push(checkLine(inner, result || {}));
  }
  if (checks.length > 0) {
    lines.push('');
  }
  for (const child of Array.isArray(group.groups) ? group.groups : []) {
    lines.push(...groupLines(inner, child || {}));
  }
  return lines;
}

function checkLine(indent, result) {
  const passes = result.passes || 0;
  const fails = result.fails || 0;
  if (fails === 0) {
    return `${indent}${PASS_MARK} ${result.name}`;
  }
  const percent = Math.floor((100 * passes) / (passes + fails));
  return (
    `${indent}${FAIL_MARK} ${result.name}\n` +
    `${indent} ${DETAIL_MARK}  ${percent}% \u2014 ${PASS_MARK} ${passes} / ${FAIL_MARK} ${fails}`
  );
}

function metricLines(metrics, trendStats, timeUnit) {
  const rows = Object.keys(metrics).map((name) => {
    const metric = metrics[name] || {};
    const values = metric.values || {};
    const brace = name.indexOf('{');
    const trend = metric.type === 'trend';
    return {
      name,
      metric,
      trend,
      indent: brace >= 0 ? '  ' : '',
      label: brace >= 0 ? `{ ${name.substring(brace + 1, name.length - 1)} }` : name,
      cells: trend
        ? trendStats.map((stat) =>
            stat === 'count' ? plainNumber(values[stat]) : formatValue(values[stat], metric, timeUnit)
          )
        : valueCells(metric, values, timeUnit),
    };
  });
  const labelWidth = Math.max(0, ...rows.map((row) => displayWidth(row.indent + row.label)));
  const trendWidths = columnWidths(rows.filter((row) => row.trend));
  const valueWidths = columnWidths(rows.filter((row) => !row.trend));
  const padded = (cell, width) => cell + ' '.repeat(width - displayWidth(cell));

  rows.sort((a, b) => compareMetricNames(a.name, b.name));
  return rows.map((row) => {
    let shown;
    if (row.trend) {
      shown = row.cells.map((cell, i) => `${trendStats[i]}=${padded(cell, trendWidths[i])}`).join(' ');
    } else if (row.cells.length === 2) {
      shown = `${padded(row.cells[0], valueWidths[0])} ${row.cells[1]}`;
    } else {
      shown = row.cells.map((cell, i) => padded(cell, valueWidths[i])).join(' ');
    }
    const dots = '.'.repeat(labelWidth - displayWidth(row.label) - displayWidth(row.indent) + 3);
    return `${METRIC_INDENT}${row.indent}${thresholdMark(row.metric)} ${row.label}${dots}: ${shown}`;
  });
}

function columnWidths(rows) {
  const widths = [];
  for (const row of rows) {
    row.cells.forEach((cell, i) => {
      widths[i] = Math.max(widths[i] || 0, displayWidth(cell));
    });
  }
  return widths;
}

function compareMetricNames(a, b) {
  const baseA = a.split('{', 1)[0];
  const baseB = b.split('{', 1)[0];
  return baseA.localeCompare(baseB) || a.substring(baseA.length).localeCompare(b.substring(baseB.length));
}

function thresholdMark(metric) {
  const thresholds = metric.thresholds;
  if (!thresholds) {
    return ' ';
  }
  return Object.keys(thresholds).every((key) => thresholds[key] && thresholds[key].ok) ? PASS_MARK : FAIL_MARK;
}

function valueCells(metric, values, timeUnit) {
  const format = (value) => formatValue(value, metric, timeUnit);
  switch (metric.type) {
    case 'counter':
      return [format(values.count), `${format(values.rate)}/s`];
    case 'gauge':
      return [format(values.value), `min=${format(values.min)}`, `max=${format(values.max)}`];
    case 'rate':
      return [
        format(values.rate),
        `${PASS_MARK} ${plainNumber(values.passes)}`,
        `${FAIL_MARK} ${plainNumber(values.fails)}`,
      ];
    default:
      return ['[no data]'];
  }
}

function formatValue(value, metric, timeUnit) {
  if (typeof value !== 'number') {
    return 'n/a';
  }
  if (metric.type === 'rate') {
    return `${(Math.trunc(value * 100 * 100) / 100).toFixed(2)}%`;
  }
  if (metric.contains === 'data') {
    return formatBytes(value);
  }
  if (metric.contains === 'time') {
    return formatDuration(value, timeUnit);
  }
  return trimmedFixed(value, 6);
}

function plainNumber(value) {
  return typeof value === 'number' ? String(value) : 'n/a';
}

function formatBytes(bytes) {
  if (bytes < 10) {
    return `${bytes} B`;
  }
  const exponent = Math.floor(Math.log(bytes) / Math.log(1000));
  const scaled = Math.floor((bytes / Math.pow(1000, exponent)) * 10 + 0.5) / 10;
  return `${scaled.toFixed(scaled < 10 ? 1 : 0)} ${BYTE_UNITS[exponent | 0]}`;
}

function formatDuration(ms, timeUnit) {
  if (Object.prototype.hasOwnProperty.call(FIXED_TIME_UNITS, timeUnit)) {
    const fixed = FIXED_TIME_UNITS[timeUnit];
    return (ms * fixed.coef).toFixed(2) + fixed.unit;
  }
  if (ms === 0) {
    return '0s';
  }
  if (ms < 0.001) {
    return `${Math.trunc(ms * 1e6)}ns`;
  }
  if (ms < 1) {
    return `${truncatedFixed(ms * 1e3, 2)}\u00b5s`;
  }
  if (ms < 1000) {
    return `${truncatedFixed(ms, 2)}ms`;
  }
  let text = `${truncatedFixed((ms % 60000) / 1000, ms > 60000 ? 0 : 2)}s`;
  let rest = Math.trunc(ms / 60000);
  if (rest < 1) {
    return text;
  }
  text = `${rest % 60}m${text}`;
  rest = Math.trunc(rest / 60);
  return rest < 1 ? text : `${rest}h${text}`;
}

function trimmedFixed(value, decimals) {
  return parseFloat(value.toFixed(decimals)).toString();
}

function truncatedFixed(value, decimals) {
  const scale = Math.pow(10, decimals);
  return trimmedFixed(Math.trunc(scale * value) / scale, decimals);
}

/** Display columns of uncolored text as k6's summary counts them: code points after NFKC. */
function displayWidth(text) {
  return Array.from(text.normalize('NFKC')).length;
}

/**
 * Keeps the text summary on stdout and writes the summary file, so the export also works without
 * the discouraged --summary-export flag.
 *
 * When Compose also passes --summary-export=/perf/results/search-1m-summary.json, k6's summary
 * wrapper stores the legacy export in this same output map under the same path, replacing the
 * entry below. Exactly one file is therefore written: in the legacy format with the flag, p95 at
 * metrics.http_req_duration['p(95)'], and in the handleSummary format without it, p95 at
 * metrics.http_req_duration.values['p(95)']. The median (p50) is 'med' in both.
 */
export function handleSummary(data) {
  return {
    stdout: textSummary(data),
    '/perf/results/search-1m-summary.json': JSON.stringify(data, null, 2),
  };
}
