/**
 * Tests of the client transport: `./client.ts` (`request`, `setCredentials`,
 * `onUnauthorized`) with the error types of `./problem.ts` (`ApiError`,
 * `isApiError`, `syntheticProblem`, `fieldErrors`).
 *
 * What is pinned down here is the contract every typed call in
 * `./customers.ts`, `./states.ts`, `./session.ts` and `./messages.ts` relies
 * on, and that replaces the 5250 program-call plumbing of PMTCUSTR
 * (5250_Subfile/PMTCUSTR.SQLRPGLE:83-90), its message-queue errors (SndMsgPgmQ,
 * Service_Pgms/SRV_MSG.RPGLE) and SQLProblem's escape message
 * (Service_Pgms/SRV_SQL.SQLRPGLE):
 *
 * - every call sends `X-Requested-With: XMLHttpRequest` and, once credentials
 *   are known, `Authorization: Basic <base64 of the UTF-8 user:password>`;
 * - every non-2xx response rejects with `ApiError {status, problem}`, the
 *   problem parsed from `application/problem+json`, or the synthetic DEM9999
 *   problem for any other body (an HTML page from a proxy) and, under status 0,
 *   for a call that received no HTTP response, whose server outcome is unknown;
 * - a 401 calls the handler registered with `onUnauthorized` exactly once,
 *   telling it whether the call carried per-call credentials (a sign-in
 *   trial), and still rejects with its `ApiError`;
 * - a GET whose `signal` aborts before its answer arrived (before the call or
 *   while it is pending) or while a 2xx body is read rejects with the
 *   signal's reason and never with an `ApiError`; a non-2xx answer that did
 *   arrive rejects with its `ApiError` even when the signal aborts while its
 *   body is read or inside the 401 hook, which runs once for an arrived 401
 *   only; a POST or PUT carrying a signal is refused with a TypeError before
 *   any request;
 * - nothing is rendered: no call changes the document.
 *
 * Every request is answered by MSW (`../test/server`, started by
 * `../test/setup.ts` with `onUnhandledFrame: 'error'`), so the suite is
 * offline. A test overrides a route for itself with `server.use(...)`, which
 * `setup.ts` resets after each test; the client's own module state (stored
 * credentials, the 401 handler) is reset in this file's `afterEach`, so every
 * test passes in any order.
 */
import { afterEach, describe, expect, it, vi } from 'vitest';
import { http, HttpResponse } from 'msw/http';
import { server } from '../test/server';
import { basicAuth, catalog, customerDetail, DEFAULT_ERROR_ID, messageText, problem, users } from '../test/handlers';
import type { CustomerFieldsFixture, CustomerResponseFixture, Role } from '../test/handlers';
import { onUnauthorized, request, setCredentials } from './client';
import type { Credentials } from './client';
import { ApiError, fieldErrors, isApiError, syntheticProblem } from './problem';

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

/** The MSW route methods the stubs below override. */
type Method = 'get' | 'post' | 'put' | 'all';

/** One request as a stubbed route received it. */
interface Seen {
  readonly method: string;
  readonly url: URL;
  readonly headers: Headers;
  readonly body: string;
}

/**
 * Overrides `method path` for the current test: records every request the
 * route receives, then answers it with `respond()`.
 *
 * @returns the list the route appends each received request to
 */
function stub(method: Method, path: string, respond: () => Response): Seen[] {
  const seen: Seen[] = [];
  server.use(
    http[method](path, async ({ request: incoming }) => {
      seen.push({
        method: incoming.method,
        url: new URL(incoming.url),
        headers: incoming.headers,
        body: await incoming.text(),
      });
      return respond();
    }),
  );
  return seen;
}

/** The one request a stub received; fails the test unless exactly one arrived. */
function only(seen: readonly Seen[]): Seen {
  expect(seen).toHaveLength(1);
  const [first] = seen;
  if (first === undefined) {
    throw new Error('No request reached the stubbed route');
  }
  return first;
}

/**
 * The `ApiError` a call rejects with. Fails the test when the call resolves
 * or rejects with anything else, so each case can then assert on a narrowed
 * `ApiError` rather than on `unknown`.
 */
async function rejectionOf(call: Promise<unknown>): Promise<ApiError> {
  try {
    await call;
  } catch (error: unknown) {
    if (isApiError(error)) {
      return error;
    }
    throw new Error(`Expected an ApiError rejection, got ${String(error)}`, { cause: error });
  }
  throw new Error('Expected the call to reject with an ApiError, but it resolved');
}

/** The unregister functions of every `onUnauthorized` registration a test made. */
const registrations: Array<() => void> = [];

/** Registers `handler` for 401 responses and remembers its unregister function for `afterEach`. */
function registerUnauthorized(handler: Parameters<typeof onUnauthorized>[0]): () => void {
  const unregister = onUnauthorized(handler);
  registrations.push(unregister);
  return unregister;
}

/** Base64 of the UTF-8 bytes of `text`, computed independently of the client's `TextEncoder` + `btoa` path. */
function utf8Base64(text: string): string {
  return Buffer.from(text, 'utf8').toString('base64');
}

/** An HTML error page, as a proxy in front of the API sends it. */
function htmlPage(status: number): Response {
  return new HttpResponse('<html><body>Bad Gateway</body></html>', {
    status,
    headers: { 'Content-Type': 'text/html' },
  });
}

/**
 * The credentials of the demo user in the shared `users` fixture that holds
 * `role`: `sales` for MAINTENANCE, `inquiry` for INQUIRY.
 *
 * @throws Error when no demo user holds `role`
 */
function demoUser(role: Role): Credentials {
  const user = users.find((candidate) => candidate.roles.includes(role));
  if (user === undefined) {
    throw new Error(`No demo user holds the ${role} role`);
  }
  return { username: user.username, password: user.password };
}

/** The demo user the API lets review, add and update. */
const MAINTENANCE_USER = demoUser('MAINTENANCE');

/** The demo user the API lets read only; its writes are refused with 403 APP0403. */
const INQUIRY_USER = demoUser('INQUIRY');

/**
 * The nine `CustomerFields` of a stored customer, the only data properties a
 * write body may carry. The `custId`, `chgTime`, `chgUser` and `version` of
 * the response are left out: the API's binder refuses each as an unknown
 * property (400 APP0400) before any rule runs, so a mocked 409, 422 or 502
 * for a body carrying one would be an exchange the server never makes. A PUT
 * adds `version` back and a review adds `purpose`. The keys are spelled out
 * rather than taken from the client's own field list, so the bodies sent here
 * stay an independent statement of the contract.
 */
function customerFields(customer: CustomerResponseFixture): CustomerFieldsFixture {
  return {
    active: customer.active,
    name: customer.name,
    addr: customer.addr,
    city: customer.city,
    state: customer.state,
    zip: customer.zip,
    acctPhone: customer.acctPhone,
    acctMgr: customer.acctMgr,
    corpPhone: customer.corpPhone,
  };
}

/**
 * Asserts that a stubbed write reached its route as one the API answers past
 * its security filter and body binder: sent by `user` with exactly `body`,
 * whose properties are the nine `CustomerFields` plus `version` on a PUT or
 * `purpose` on a review, and nothing else. Sent anonymously, or with any other
 * property, the same request would be answered 401 APP0401 or 400 APP0400
 * instead of the mocked outcome.
 */
function expectSentBy(sent: Seen, user: Credentials, body: object): void {
  expect(sent.headers.get('authorization')).toBe(basicAuth(user.username, user.password));
  expect(JSON.parse(sent.body)).toEqual(body);
  const bound = Object.keys(customerFields(customerDetail));
  if (sent.method === 'PUT') {
    bound.push('version');
  } else if (sent.url.pathname === '/api/customers/review') {
    bound.push('purpose');
  }
  expect(Object.keys(body).sort()).toEqual(bound.sort());
}

/**
 * Every hold {@link holdGet} made in the current test; `afterEach` abandons
 * their calls, releases them and awaits their settlement, so neither a held
 * request nor a call waiting on one outlives its test.
 */
const holds: HeldRequest[] = [];

afterEach(async () => {
  // A request a failed test left held is settled here, before the
  // credentials and the 401 handler are cleared. Its calls are abandoned
  // first, as the test itself does before releasing, so the client drops the
  // connection rather than completing it into the pool a later test draws
  // on; then the hold is released and both the calls' outcomes and the
  // route's answer are awaited. Its resolver never stays suspended, a call
  // the test no longer awaits is handled rather than reported as an
  // unhandled rejection, and nothing it answers reaches the next test. Every
  // hold is released before any is awaited, so a call waiting on two holds
  // cannot keep the teardown waiting.
  const pending = holds.splice(0);
  for (const held of pending) {
    held.abandon();
    held.release();
  }
  await Promise.all(pending.map((held) => held.settled()));
  // The client keeps its credentials and its 401 handler in module state;
  // clear both so no test sees another's.
  setCredentials(null);
  for (const unregister of registrations.splice(0)) {
    unregister();
  }
});

// ---------------------------------------------------------------------------
// Request headers and credentials
// ---------------------------------------------------------------------------

describe('request headers', () => {
  it('sends the stored Basic credentials, X-Requested-With and a problem+json Accept', async () => {
    setCredentials({ username: 'maint', password: 'maint-pw' });
    const seen = stub('get', '/api/session', () =>
      HttpResponse.json({ username: 'maint', roles: ['MAINTENANCE'] }),
    );

    const session = await request<{ username: string; roles: string[] }>('/api/session');

    expect(session).toEqual({ username: 'maint', roles: ['MAINTENANCE'] });
    const sent = only(seen);
    expect(sent.method).toBe('GET');
    expect(sent.headers.get('authorization')).toBe('Basic bWFpbnQ6bWFpbnQtcHc=');
    expect(sent.headers.get('authorization')).toBe(basicAuth('maint', 'maint-pw'));
    expect(sent.headers.get('x-requested-with')).toBe('XMLHttpRequest');
    expect(sent.headers.get('accept')).toContain('application/problem+json');
    expect(sent.headers.get('accept')).toContain('application/json');
    // A GET carries no body and so no Content-Type.
    expect(sent.headers.get('content-type')).toBeNull();
    expect(sent.body).toBe('');
  });

  it('encodes a non-Latin-1 password as UTF-8, and per-call credentials win over stored ones', async () => {
    setCredentials({ username: 'maint', password: 'maint-pw' });
    const seen = stub('get', '/api/session', () => HttpResponse.json({ username: 'u', roles: ['INQUIRY'] }));

    await request('/api/session', { credentials: { username: 'u', password: 'pässwörd' } });
    // The per-call credentials are not stored: the next call sends the stored ones again.
    await request('/api/session');

    expect(seen).toHaveLength(2);
    expect(seen[0]?.headers.get('authorization')).toBe(`Basic ${utf8Base64('u:pässwörd')}`);
    expect(seen[0]?.headers.get('authorization')).toBe(basicAuth('u', 'pässwörd'));
    expect(seen[1]?.headers.get('authorization')).toBe(`Basic ${utf8Base64('maint:maint-pw')}`);
  });

  it('sends no Authorization header while no credentials are known, as for the catalog before sign-in', async () => {
    const seen = stub('get', '/api/messages', () => HttpResponse.json({ ...catalog }));

    const messages = await request<Record<string, string>>('/api/messages');

    expect(messages).toEqual(catalog);
    const sent = only(seen);
    expect(sent.headers.has('authorization')).toBe(false);
    expect(sent.headers.get('x-requested-with')).toBe('XMLHttpRequest');
  });

  it('stops sending credentials after setCredentials(null)', async () => {
    const seen = stub('get', '/api/messages', () => HttpResponse.json({ ...catalog }));

    setCredentials({ username: 'sales', password: 'sales-demo' });
    await request('/api/messages');
    setCredentials(null);
    await request('/api/messages');

    expect(seen).toHaveLength(2);
    expect(seen[0]?.headers.get('authorization')).toBe(basicAuth('sales', 'sales-demo'));
    expect(seen[1]?.headers.has('authorization')).toBe(false);
  });

  it('keeps a copy of the stored credentials, so a later change to the caller object is not sent', async () => {
    const seen = stub('get', '/api/session', () => HttpResponse.json({ username: 'sales', roles: ['MAINTENANCE'] }));
    const typed = { username: 'sales', password: 'sales-demo' };

    setCredentials(typed);
    typed.password = 'changed-afterwards';
    await request('/api/session');

    expect(only(seen).headers.get('authorization')).toBe(basicAuth('sales', 'sales-demo'));
  });

  it('is accepted by the default session handler with demo credentials and refused with a wrong password', async () => {
    const session = await request('/api/session', { credentials: { username: 'sales', password: 'sales-demo' } });
    expect(session).toEqual({ username: 'sales', roles: ['MAINTENANCE'] });

    const refused = await rejectionOf(
      request('/api/session', { credentials: { username: 'sales', password: 'wrong' } }),
    );
    expect(refused.status).toBe(401);
    expect(refused.problem.code).toBe('APP0401');
    expect(refused.problem.detail).toBe(messageText('APP0401'));
  });
});

// ---------------------------------------------------------------------------
// URL, query and body
// ---------------------------------------------------------------------------

describe('request URL, query and body', () => {
  it('leaves undefined query values out and sends the others as strings, in insertion order', async () => {
    const seen = stub('get', '/api/customers', () =>
      HttpResponse.json({ items: [], nextCursor: null, limitReached: false, notice: null }),
    );

    await request('/api/customers', {
      query: { name: "L'LOR & CO", city: undefined, includeInactive: false, size: 12, cursor: undefined },
    });

    const { url } = only(seen);
    expect(url.pathname).toBe('/api/customers');
    expect([...url.searchParams.keys()]).toEqual(['name', 'includeInactive', 'size']);
    expect(url.searchParams.get('name')).toBe("L'LOR & CO");
    expect(url.searchParams.get('includeInactive')).toBe('false');
    expect(url.searchParams.get('size')).toBe('12');
    expect(url.searchParams.has('city')).toBe(false);
    expect(url.searchParams.has('cursor')).toBe(false);
  });

  it('sends the bare path when every query value is undefined', async () => {
    const seen = stub('get', '/api/states', () => HttpResponse.json([]));

    await request('/api/states', { query: { nameContains: undefined, sort: undefined } });

    expect(only(seen).url.search).toBe('');
  });

  it('sends a POST body as JSON with Content-Type application/json, dropping undefined members', async () => {
    const created = { ...customerDetail, custId: 'EEEF', chgUser: 'sales' };
    const seen = stub('post', '/api/customers', () => HttpResponse.json(created, { status: 201 }));
    setCredentials({ username: 'sales', password: 'sales-demo' });

    const fields = {
      name: 'ACME COMPANY',
      addr: '1 MAIN STREET',
      city: 'AUBURN',
      state: 'ME',
      zip: '04210',
      corpPhone: '(207) 555-0100',
      acctMgr: 'JANE DOE',
      acctPhone: '(207) 555-0101',
      active: undefined,
    };
    const response = await request('/api/customers', { method: 'POST', body: fields });

    expect(response).toEqual(created);
    const sent = only(seen);
    expect(sent.method).toBe('POST');
    expect(sent.headers.get('content-type')).toBe('application/json');
    expect(sent.headers.get('authorization')).toBe(basicAuth('sales', 'sales-demo'));
    const body: unknown = JSON.parse(sent.body);
    expect(body).toEqual({
      name: 'ACME COMPANY',
      addr: '1 MAIN STREET',
      city: 'AUBURN',
      state: 'ME',
      zip: '04210',
      corpPhone: '(207) 555-0100',
      acctMgr: 'JANE DOE',
      acctPhone: '(207) 555-0101',
    });
    // An absent `active` stays absent on the wire (not `null`), so the server defaults it to Y.
    expect(body).not.toHaveProperty('active');
  });

  it('sends a PUT with its JSON body to the path it was given', async () => {
    const seen = stub('put', '/api/customers/:custId', () =>
      HttpResponse.json({ ...customerDetail, version: 1 }),
    );

    const { custId: _custId, chgTime: _chgTime, chgUser: _chgUser, ...update } = customerDetail;
    await request('/api/customers/AAAD', { method: 'PUT', body: update });

    const sent = only(seen);
    expect(sent.method).toBe('PUT');
    expect(sent.url.pathname).toBe('/api/customers/AAAD');
    expect(sent.headers.get('content-type')).toBe('application/json');
    expect(JSON.parse(sent.body)).toEqual(update);
  });

  it('resolves undefined for a 2xx response with an empty body', async () => {
    stub('get', '/api/session', () => new HttpResponse(null, { status: 204 }));

    await expect(request('/api/session')).resolves.toBeUndefined();
  });

  it('rejects a 2xx body that is not JSON with the synthetic DEM9999 problem under its status', async () => {
    stub('get', '/api/messages', () => htmlPage(200));

    const error = await rejectionOf(request('/api/messages'));

    expect(error.status).toBe(200);
    expect(error.problem).toEqual(syntheticProblem(200));
  });

  it('rejects a path outside /api/ with a TypeError before any request is made', async () => {
    const seen = stub('all', '*', () => HttpResponse.json({}));

    await expect(request('/customers')).rejects.toBeInstanceOf(TypeError);
    await expect(request('https://elsewhere.example/api/session')).rejects.toThrow(/start with \/api\//);

    expect(seen).toHaveLength(0);
  });
});

// ---------------------------------------------------------------------------
// Error responses
// ---------------------------------------------------------------------------

describe('error responses', () => {
  it('parses a 409 DEM1002 problem+json body, current customer included, into an ApiError', async () => {
    const seen = stub('put', '/api/customers/:custId', () =>
      problem(409, 'DEM1002', { current: { ...customerDetail, version: 1 }, instance: '/api/customers/AAAD' }),
    );
    setCredentials(MAINTENANCE_USER);
    // The nine fields with the version read earlier, now stale on the server.
    const update = { ...customerFields(customerDetail), version: 0 };

    const call = request('/api/customers/AAAD', { method: 'PUT', body: update });
    await expect(call).rejects.toBeInstanceOf(ApiError);
    const error = await rejectionOf(call);

    expectSentBy(only(seen), MAINTENANCE_USER, update);
    expect(isApiError(error)).toBe(true);
    expect(error.name).toBe('ApiError');
    expect(error.status).toBe(409);
    expect(error.message).toBe('Someone else changed record. Review data.');
    expect(error.problem).toEqual({
      type: 'urn:customer-master:problem:DEM1002',
      title: 'Conflict',
      status: 409,
      instance: '/api/customers/AAAD',
      detail: 'Someone else changed record. Review data.',
      code: 'DEM1002',
      args: [],
      current: { ...customerDetail, version: 1 },
    });
  });

  it('keeps the field errors of a 422 problem in server order', async () => {
    const seen = stub('post', '/api/customers/review', () =>
      problem(422, 'DEM0502', {
        args: ['Name'],
        errors: [{ field: 'name', code: 'DEM0502', message: messageText('DEM0502', ['Name']) }],
        instance: '/api/customers/review',
      }),
    );
    setCredentials(MAINTENANCE_USER);
    // Active is Y, so the first rule to fail is the blank Name.
    const review = { purpose: 'EDIT', ...customerFields(customerDetail), name: '' };

    const error = await rejectionOf(request('/api/customers/review', { method: 'POST', body: review }));

    expectSentBy(only(seen), MAINTENANCE_USER, review);
    expect(error.status).toBe(422);
    expect(error.problem.code).toBe('DEM0502');
    expect(error.problem.detail).toBe('Name: Must not be blank');
    expect(error.problem.args).toEqual(['Name']);
    expect(error.problem.errors?.[0]?.field).toBe('name');
    expect(error.problem.errors).toEqual([{ field: 'name', code: 'DEM0502', message: 'Name: Must not be blank' }]);
    expect(fieldErrors(error.problem).map((entry) => entry.field)).toEqual(['name']);
  });

  it('keeps stateAccepted on a review failure after the State rule passed', async () => {
    const seen = stub('post', '/api/customers/review', () => problem(502, 'APP0502', { stateAccepted: 'NV' }));
    setCredentials(MAINTENANCE_USER);
    // Every field rule passes with the known State NV; the address service then fails.
    const review = { purpose: 'EDIT', ...customerFields(customerDetail), state: 'NV' };

    const error = await rejectionOf(request('/api/customers/review', { method: 'POST', body: review }));

    expectSentBy(only(seen), MAINTENANCE_USER, review);
    expect(error.status).toBe(502);
    expect(error.problem.code).toBe('APP0502');
    expect(error.problem.stateAccepted).toBe('NV');
    expect(error.problem.instance).toBe('/api/customers/review');
    // A 502 names no field.
    expect(fieldErrors(error.problem)).toEqual([]);
  });

  it('keeps the errorId of a 500 DEM9999 problem', async () => {
    stub('get', '/api/customers', () => problem(500, 'DEM9999', { instance: '/api/customers' }));

    const error = await rejectionOf(request('/api/customers'));

    expect(error.status).toBe(500);
    expect(error.problem.code).toBe('DEM9999');
    expect(error.problem.errorId).toBe(DEFAULT_ERROR_ID);
    expect(error.problem.detail).toBe(messageText('DEM9999'));
  });

  it('turns a 500 with an HTML body into the synthetic DEM9999 problem', async () => {
    stub('get', '/api/customers', () => htmlPage(500));

    const error = await rejectionOf(request('/api/customers'));

    expect(error.status).toBe(500);
    expect(error.problem.code).toBe('DEM9999');
    expect(error.problem.type).toBe('urn:customer-master:problem:DEM9999');
    expect(error.problem.detail).toBe('');
    expect(error.problem.title).toBe('');
    expect(error.problem.status).toBe(500);
    expect(error.problem).toEqual(syntheticProblem(500));
    // With no detail, the message names the catalog key instead.
    expect(error.message).toBe('DEM9999');
  });

  it('turns a JSON body not declared problem+json, a broken problem+json body, and one without a code into DEM9999', async () => {
    stub('get', '/api/customers/:custId', () => HttpResponse.json({ code: 'DEM0599' }, { status: 404 }));
    stub('get', '/api/states', () =>
      new HttpResponse('{"code": "APP0400"', {
        status: 400,
        headers: { 'Content-Type': 'application/problem+json' },
      }),
    );
    stub('get', '/api/session', () =>
      HttpResponse.json({ title: 'Bad Gateway' }, { status: 502, headers: { 'Content-Type': 'application/problem+json' } }),
    );

    expect((await rejectionOf(request('/api/customers/AAAD'))).problem).toEqual(syntheticProblem(404));
    expect((await rejectionOf(request('/api/states'))).problem).toEqual(syntheticProblem(400));
    expect((await rejectionOf(request('/api/session'))).problem).toEqual(syntheticProblem(502));
  });

  it('reads the problem+json media type case-insensitively and with parameters', async () => {
    stub('get', '/api/customers/:custId', () =>
      HttpResponse.json(
        { code: 'DEM0599', status: 404, detail: messageText('DEM0599') },
        { status: 404, headers: { 'Content-Type': 'Application/Problem+JSON; charset=utf-8' } },
      ),
    );

    const error = await rejectionOf(request('/api/customers/ZZZZ'));

    expect(error.problem.code).toBe('DEM0599');
    expect(error.problem.detail).toBe('Customer deleted. Exit & redo search.');
  });

  it('completes the members a problem body lacks and leaves malformed optional members out', async () => {
    stub('get', '/api/customers', () =>
      HttpResponse.json(
        { code: 'APP0400', args: [100], errors: [{ field: 'size' }], current: 'stale', errorId: 7 },
        { status: 400, headers: { 'Content-Type': 'application/problem+json' } },
      ),
    );

    const error = await rejectionOf(request('/api/customers', { query: { size: 101 } }));

    expect(error.problem).toEqual({
      type: 'urn:customer-master:problem:APP0400',
      title: '',
      status: 400,
      detail: '',
      code: 'APP0400',
      args: ['100'],
    });
  });

  it('rejects a call that received no HTTP response with ApiError status 0 and DEM9999, without the 401 hook', async () => {
    const onUnauth = vi.fn<() => void>();
    registerUnauthorized(onUnauth);
    stub('get', '/api/customers', () => HttpResponse.error());

    const error = await rejectionOf(request('/api/customers'));

    expect(error.status).toBe(0);
    expect(error.problem.code).toBe('DEM9999');
    expect(error.problem).toEqual(syntheticProblem(0));
    expect(onUnauth).not.toHaveBeenCalled();
  });
});


// ---------------------------------------------------------------------------
// 401 and the onUnauthorized hook
// ---------------------------------------------------------------------------

describe('401 responses and onUnauthorized', () => {
  it('calls the registered handler exactly once and rejects with the 401 ApiError', async () => {
    const onUnauth = vi.fn<() => void>();
    registerUnauthorized(onUnauth);
    stub('get', '/api/session', () => problem(401, 'APP0401', { instance: '/api/session' }));

    const error = await rejectionOf(request('/api/session'));

    expect(error.status).toBe(401);
    expect(error.problem.code).toBe('APP0401');
    expect(error.problem.detail).toBe('Sign in required.');
    expect(onUnauth).toHaveBeenCalledTimes(1);
  });

  it('calls the handler for the 401 of the default session handler when no credentials are sent', async () => {
    const onUnauth = vi.fn<() => void>();
    registerUnauthorized(onUnauth);

    const error = await rejectionOf(request('/api/session'));

    expect(error.status).toBe(401);
    expect(error.problem.code).toBe('APP0401');
    expect(onUnauth).toHaveBeenCalledTimes(1);
  });

  it('tells the handler whether the refused call carried per-call credentials, once per 401', async () => {
    const onUnauth = vi.fn<Parameters<typeof onUnauthorized>[0]>();
    registerUnauthorized(onUnauth);
    stub('get', '/api/session', () => problem(401, 'APP0401', { instance: '/api/session' }));

    // Stored credentials the server no longer accepts.
    setCredentials({ username: 'sales', password: 'changed-on-the-server' });
    await rejectionOf(request('/api/session'));
    expect(onUnauth).toHaveBeenCalledTimes(1);
    expect(onUnauth).toHaveBeenLastCalledWith({ perCallCredentials: false });

    // No credentials at all.
    setCredentials(null);
    await rejectionOf(request('/api/session'));
    expect(onUnauth).toHaveBeenCalledTimes(2);
    expect(onUnauth).toHaveBeenLastCalledWith({ perCallCredentials: false });

    // A sign-in trial: per-call credentials, refused while stored ones exist too.
    setCredentials({ username: 'inquiry', password: 'inquiry-demo' });
    const error = await rejectionOf(request('/api/session', { credentials: { username: 'sales', password: 'wrong' } }));
    expect(error.status).toBe(401);
    expect(onUnauth).toHaveBeenCalledTimes(3);
    expect(onUnauth).toHaveBeenLastCalledWith({ perCallCredentials: true });
  });

  it('no longer calls a handler after its unregister function ran', async () => {
    const onUnauth = vi.fn<() => void>();
    const unregister = registerUnauthorized(onUnauth);
    stub('get', '/api/session', () => problem(401, 'APP0401'));

    await rejectionOf(request('/api/session'));
    unregister();
    const error = await rejectionOf(request('/api/session'));

    expect(error.status).toBe(401);
    expect(onUnauth).toHaveBeenCalledTimes(1);
  });

  it('calls only the last registered handler, and a stale unregister leaves the newer one in place', async () => {
    const first = vi.fn<() => void>();
    const second = vi.fn<() => void>();
    const unregisterFirst = registerUnauthorized(first);
    registerUnauthorized(second);
    stub('get', '/api/session', () => problem(401, 'APP0401'));

    await rejectionOf(request('/api/session'));
    // As React StrictMode's second cleanup does: the first registration is no
    // longer the current one, so removing it must not remove the second.
    unregisterFirst();
    await rejectionOf(request('/api/session'));

    expect(first).not.toHaveBeenCalled();
    expect(second).toHaveBeenCalledTimes(2);
  });

  it('still rejects with the 401 ApiError when the handler throws', async () => {
    registerUnauthorized(() => {
      throw new Error('sign-out failed');
    });
    stub('get', '/api/session', () => problem(401, 'APP0401'));

    const error = await rejectionOf(request('/api/session'));

    expect(error.status).toBe(401);
    expect(error.problem.code).toBe('APP0401');
  });

  it('does not call the handler for 403, 404, 409 or 500 responses', async () => {
    const onUnauth = vi.fn<() => void>();
    registerUnauthorized(onUnauth);
    const added = stub('post', '/api/customers', () => problem(403, 'APP0403'));
    stub('get', '/api/customers/:custId', () => problem(404, 'DEM0599'));
    const updated = stub('put', '/api/customers/:custId', () => problem(409, 'DEM1001'));
    stub('get', '/api/customers', () => htmlPage(500));
    // The signed-in user may update; the add is tried with the INQUIRY user's
    // per-call credentials, the caller the API refuses an add with 403.
    setCredentials(MAINTENANCE_USER);
    const fields = customerFields(customerDetail);
    const update = { ...fields, version: 0 };

    const statuses = [
      (await rejectionOf(request('/api/customers', { method: 'POST', body: fields, credentials: INQUIRY_USER }))).status,
      (await rejectionOf(request('/api/customers/AAAD'))).status,
      (await rejectionOf(request('/api/customers/AAAD', { method: 'PUT', body: update }))).status,
      (await rejectionOf(request('/api/customers'))).status,
    ];

    expect(statuses).toEqual([403, 404, 409, 500]);
    expectSentBy(only(added), INQUIRY_USER, fields);
    expectSentBy(only(updated), MAINTENANCE_USER, update);
    expect(onUnauth).not.toHaveBeenCalled();
  });
});

// ---------------------------------------------------------------------------
// Aborting a read (RequestOptions.signal)
// ---------------------------------------------------------------------------

/** A request a route holds open: when it arrived, the release of its answer, and the calls waiting on it. */
interface HeldRequest {
  /** Resolves once the request reached the route. */
  readonly arrived: Promise<void>;
  /** Whether the route has answered the request. */
  answered(): boolean;
  /** Lets the held request answer with `respond()`; calling it again changes nothing. */
  release(): void;
  /**
   * Registers `call`, a client call waiting on this route, and returns it
   * unchanged. Its outcome is observed at once, so a rejection is handled
   * whenever it comes, before or after `release()`; the test still awaits
   * and asserts the call itself. `controller` is the one whose signal the
   * call carries, which `abandon()` aborts.
   */
  holding<T>(call: Promise<T>, controller?: AbortController): Promise<T>;
  /**
   * Aborts the controller of every call registered with `holding`, as a
   * test does before its own `release()`: the client then gives the call up
   * and drops its connection instead of reading an answer nobody awaits any
   * more, so no connection a failed test left open is reused by a later
   * test. A settled call is unaffected; calling it again changes nothing.
   */
  abandon(): void;
  /**
   * Resolves once every call registered with `holding` has settled and the
   * route has answered every request it took, at once when there is none: a
   * request arriving after `release()` is answered without waiting. It never
   * rejects.
   */
  settled(): Promise<void>;
}

/**
 * Overrides `GET path` for the current test: holds each request until
 * `release` is called, then answers it with `respond()`. A call that settles
 * while `answered()` is still false settled without waiting for the server.
 *
 * The hold is registered in {@link holds} when it is made, and a test passes
 * each call it makes on the route through `holding`, so the file's
 * `afterEach` abandons those calls, releases the hold and awaits
 * `settled()`, the calls' outcomes and the route's answers, even when the
 * test failed before its own `release()`.
 */
function holdGet(path: string, respond: () => Response): HeldRequest {
  let reached: () => void = () => undefined;
  const arrived = new Promise<void>((resolve) => {
    reached = resolve;
  });
  let open: () => void = () => undefined;
  const held = new Promise<void>((resolve) => {
    open = resolve;
  });
  // One promise per request the route took, resolved once the route has
  // answered that request, `respond()` throwing included.
  const answers: Array<Promise<void>> = [];
  // One promise per call registered with `holding`, fulfilled once that call
  // has settled, whichever way, and the controllers those calls carry.
  const calls: Array<Promise<unknown>> = [];
  const controllers: AbortController[] = [];
  let done = false;
  server.use(
    http.get(path, async () => {
      let answer: () => void = () => undefined;
      answers.push(
        new Promise<void>((resolve) => {
          answer = resolve;
        }),
      );
      reached();
      try {
        await held;
        done = true;
        return respond();
      } finally {
        answer();
      }
    }),
  );
  const hold: HeldRequest = {
    arrived,
    answered: () => done,
    release: () => open(),
    holding: (call, controller) => {
      calls.push(Promise.allSettled([call]));
      if (controller !== undefined) {
        controllers.push(controller);
      }
      return call;
    },
    abandon: () => {
      for (const controller of controllers) {
        controller.abort();
      }
    },
    settled: async () => {
      // The calls first: once they have settled, every request of theirs
      // that reached the route is in `answers`, which the route, released,
      // answers without waiting.
      await Promise.all(calls);
      await Promise.all(answers);
    },
  };
  holds.push(hold);
  return hold;
}

/**
 * The `AbortSignal` the client handed `fetch` on each call, in call order,
 * from a spy on the global `fetch` (restored after the test by
 * `restoreMocks`). The spy calls through, so MSW still answers.
 */
function fetchSignals(): () => Array<AbortSignal | null | undefined> {
  const spy = vi.spyOn(globalThis, 'fetch');
  return () => spy.mock.calls.map(([, init]) => init?.signal);
}

/** The value a call rejects with; fails the test when the call resolves. */
async function reasonOf(call: Promise<unknown>): Promise<unknown> {
  try {
    await call;
  } catch (error: unknown) {
    return error;
  }
  throw new Error('Expected the call to reject, but it resolved');
}

/**
 * Waits one macrotask, so the I/O and timers queued before it run first: a
 * request reaching the route has then been sent in full, and a late answer
 * has had its chance to reach a handler.
 */
function nextMacrotask(): Promise<void> {
  return new Promise((resolve) => {
    setTimeout(resolve, 0);
  });
}

describe('aborting a read', () => {
  it('aborting a pending GET rejects with the signal reason, not an ApiError, aborts its fetch and skips the 401 hook', async () => {
    const onUnauth = vi.fn<() => void>();
    registerUnauthorized(onUnauth);
    const signals = fetchSignals();
    // Had the read been waited for, its answer would have been a 401.
    const route = holdGet('/api/customers', () => problem(401, 'APP0401', { instance: '/api/customers' }));
    const controller = new AbortController();

    const call = route.holding(request('/api/customers', { query: { name: 'SLOWA' }, signal: controller.signal }), controller);
    await route.arrived;
    // The request is in flight: the route holds it and its answer is pending.
    await nextMacrotask();
    expect(signals()).toEqual([controller.signal]);
    controller.abort();
    const reason = await reasonOf(call);

    expect(reason).toBe(controller.signal.reason);
    expect(reason).toHaveProperty('name', 'AbortError');
    expect(isApiError(reason)).toBe(false);
    // The call stopped waiting: it settled while the server still held the request.
    expect(route.answered()).toBe(false);

    route.release();
    await vi.waitFor(() => expect(route.answered()).toBe(true));
    await nextMacrotask();
    expect(onUnauth).not.toHaveBeenCalled();
  });

  it('a signal aborted before the call rejects with its reason, the caller-given one included, and sends nothing', async () => {
    const seen = stub('get', '/api/states', () => HttpResponse.json([]));
    const aborted = new AbortController();
    aborted.abort();
    const superseded = new AbortController();
    const supersededReason = new Error('superseded by a newer search');
    superseded.abort(supersededReason);

    await expect(request('/api/states', { signal: aborted.signal })).rejects.toBe(aborted.signal.reason);
    await expect(request('/api/states', { signal: superseded.signal })).rejects.toBe(supersededReason);

    expect(aborted.signal.reason).toHaveProperty('name', 'AbortError');
    expect(seen).toHaveLength(0);
  });

  it('refuses a signal on POST and on PUT with a TypeError before any request is made', async () => {
    const seen = stub('all', '*', () => HttpResponse.json({}));
    const { signal } = new AbortController();

    await expect(
      request('/api/customers', { method: 'POST', body: { name: 'ACME' }, signal }),
    ).rejects.toBeInstanceOf(TypeError);
    await expect(
      request('/api/customers/AAAD', { method: 'PUT', body: { version: 0 }, signal }),
    ).rejects.toThrow(/Only a GET can be aborted; PUT \/api\/customers\/AAAD/);
    await expect(
      request('/api/customers/review', { method: 'POST', body: { purpose: 'ADD' }, signal }),
    ).rejects.toThrow(/Only a GET can be aborted; POST/);

    expect(seen).toHaveLength(0);
  });

  it('a GET with a signal that is never aborted resolves, and fails with its ApiError, as without one', async () => {
    const page = { items: [], nextCursor: null, limitReached: false, notice: null };
    const seen = stub('get', '/api/customers', () => HttpResponse.json(page));
    stub('get', '/api/customers/:custId', () => problem(404, 'DEM0599', { instance: '/api/customers/ZZZZ' }));
    const controller = new AbortController();

    await expect(request('/api/customers', { signal: controller.signal })).resolves.toEqual(page);
    const error = await rejectionOf(request('/api/customers/ZZZZ', { signal: controller.signal }));
    // Aborting once the calls have settled changes nothing about them.
    controller.abort();

    expect(only(seen).method).toBe('GET');
    expect(error.status).toBe(404);
    expect(error.problem.code).toBe('DEM0599');
  });

  /**
   * Sends a GET under `signal` to a route whose answer of `status` arrives
   * with its status and headers while its body never completes, aborts
   * `signal` once the client's fetch has resolved, so the abort lands while
   * the body is read rather than before, and returns what the call rejected
   * with. A `fetch` spy (restored after the test by `restoreMocks`) tells
   * when the status has arrived.
   */
  async function abortWhileBodyIsRead(status: number, controller: AbortController): Promise<unknown> {
    server.use(
      http.get('/api/session', () => {
        // Aborting the fetch fails the client's read of this unfinished
        // body, as in the browser.
        const body = new ReadableStream<Uint8Array>({
          start(stream) {
            stream.enqueue(new TextEncoder().encode(`{"status":${status},"code":"`));
          },
        });
        return new HttpResponse(body, {
          status,
          headers: { 'Content-Type': status < 300 ? 'application/json' : 'application/problem+json' },
        });
      }),
    );
    const fetchBefore = globalThis.fetch;
    let responded: () => void = () => undefined;
    const statusArrived = new Promise<void>((resolve) => {
      responded = resolve;
    });
    vi.spyOn(globalThis, 'fetch').mockImplementation(async (input, init) => {
      const response = await fetchBefore(input, init);
      responded();
      return response;
    });

    const call = request('/api/session', { signal: controller.signal });
    await statusArrived;
    await nextMacrotask();
    controller.abort();
    return reasonOf(call);
  }

  it('an abort while the body of a 200 is read rejects with the abort reason, not an ApiError', async () => {
    const onUnauth = vi.fn<() => void>();
    registerUnauthorized(onUnauth);
    const controller = new AbortController();

    const reason = await abortWhileBodyIsRead(200, controller);

    expect(reason).toBe(controller.signal.reason);
    expect(reason).toHaveProperty('name', 'AbortError');
    expect(isApiError(reason)).toBe(false);
    expect(onUnauth).not.toHaveBeenCalled();
  });

  it.each([
    { status: 401, hookCalls: 1 },
    { status: 404, hookCalls: 0 },
  ])(
    'an abort while the body of a $status is read rejects with its ApiError and the synthetic DEM9999; the 401 hook runs $hookCalls time(s)',
    async ({ status, hookCalls }) => {
      const onUnauth = vi.fn<() => void>();
      registerUnauthorized(onUnauth);
      const controller = new AbortController();

      const reason = await abortWhileBodyIsRead(status, controller);

      // The answer arrived, so it is the rejection; only its unread body is lost.
      expect(controller.signal.aborted).toBe(true);
      expect(isApiError(reason)).toBe(true);
      expect(reason).toBeInstanceOf(ApiError);
      expect(reason).toMatchObject({ status, problem: syntheticProblem(status) });
      // A 401 is an authentication decision that did arrive, so it stands.
      expect(onUnauth).toHaveBeenCalledTimes(hookCalls);
    },
  );

  it('a 401 whose unauthorized handler aborts the same signal still rejects with its APP0401 ApiError, the hook called once', async () => {
    const controller = new AbortController();
    // As AuthProvider's sign-out does: clearing the session cancels the
    // user's queries, which aborts the signal of the very read refused.
    const onUnauth = vi.fn<() => void>(() => {
      controller.abort();
    });
    registerUnauthorized(onUnauth);
    const seen = stub('get', '/api/customers', () => problem(401, 'APP0401', { instance: '/api/customers' }));

    const error = await rejectionOf(request('/api/customers', { query: { name: 'NIBH' }, signal: controller.signal }));

    expect(only(seen).method).toBe('GET');
    expect(controller.signal.aborted).toBe(true);
    expect(error).not.toBe(controller.signal.reason);
    expect(error.status).toBe(401);
    expect(error.problem.code).toBe('APP0401');
    expect(error.problem.detail).toBe('Sign in required.');
    expect(onUnauth).toHaveBeenCalledTimes(1);
  });
});

// ---------------------------------------------------------------------------
// No DOM effect
// ---------------------------------------------------------------------------

describe('document', () => {
  it('is left unchanged by successful and failed calls alike', async () => {
    registerUnauthorized(vi.fn<() => void>());
    stub('get', '/api/customers', () => htmlPage(500));
    const reviewed = stub('post', '/api/customers/review', () =>
      problem(422, 'DEM0503', {
        errors: [{ field: 'state', code: 'DEM0503', message: messageText('DEM0503') }],
        instance: '/api/customers/review',
      }),
    );
    stub('get', '/api/states', () => HttpResponse.error());
    // An add whose Active, Name, Address and City pass and whose State is no
    // known code: the State rule is the first to fail, with no stateAccepted.
    const review = { purpose: 'ADD', ...customerFields(customerDetail), state: 'XX' };
    const bodyBefore = document.body.innerHTML;
    const documentBefore = document.documentElement.outerHTML;

    // Success: the default public catalog handler.
    await expect(request('/api/messages')).resolves.toEqual(catalog);
    // 401 from the default session handler, 500 HTML, 422 problem, network failure.
    // Nothing is stored, so the session read stays anonymous; the review
    // carries the MAINTENANCE user's credentials for itself alone.
    const failures = await Promise.allSettled([
      request('/api/session'),
      request('/api/customers'),
      request('/api/customers/review', { method: 'POST', body: review, credentials: MAINTENANCE_USER }),
      request('/api/states'),
    ]);

    expect(failures.map((outcome) => outcome.status)).toEqual(['rejected', 'rejected', 'rejected', 'rejected']);
    expectSentBy(only(reviewed), MAINTENANCE_USER, review);
    expect(document.body.innerHTML).toBe(bodyBefore);
    expect(document.documentElement.outerHTML).toBe(documentBefore);
  });
});

