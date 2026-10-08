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
 *   for a request that never reached the server;
 * - a 401 calls the handler registered with `onUnauthorized` exactly once,
 *   telling it whether the call carried per-call credentials (a sign-in
 *   trial), and still rejects with its `ApiError`;
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
import { basicAuth, catalog, customerDetail, DEFAULT_ERROR_ID, messageText, problem } from '../test/handlers';
import { onUnauthorized, request, setCredentials } from './client';
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

afterEach(() => {
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
    stub('put', '/api/customers/:custId', () =>
      problem(409, 'DEM1002', { current: { ...customerDetail, version: 1 }, instance: '/api/customers/AAAD' }),
    );

    const call = request('/api/customers/AAAD', { method: 'PUT', body: { ...customerDetail, version: 0 } });
    await expect(call).rejects.toBeInstanceOf(ApiError);
    const error = await rejectionOf(call);

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
    stub('post', '/api/customers/review', () =>
      problem(422, 'DEM0502', {
        args: ['Name'],
        errors: [{ field: 'name', code: 'DEM0502', message: messageText('DEM0502', ['Name']) }],
        instance: '/api/customers/review',
      }),
    );

    const error = await rejectionOf(
      request('/api/customers/review', { method: 'POST', body: { purpose: 'EDIT', ...customerDetail, name: '' } }),
    );

    expect(error.status).toBe(422);
    expect(error.problem.code).toBe('DEM0502');
    expect(error.problem.detail).toBe('Name: Must not be blank');
    expect(error.problem.args).toEqual(['Name']);
    expect(error.problem.errors?.[0]?.field).toBe('name');
    expect(error.problem.errors).toEqual([{ field: 'name', code: 'DEM0502', message: 'Name: Must not be blank' }]);
    expect(fieldErrors(error.problem).map((entry) => entry.field)).toEqual(['name']);
  });

  it('keeps stateAccepted on a review failure after the State rule passed', async () => {
    stub('post', '/api/customers/review', () => problem(502, 'APP0502', { stateAccepted: 'NV' }));

    const error = await rejectionOf(
      request('/api/customers/review', { method: 'POST', body: { purpose: 'EDIT', ...customerDetail, state: 'NV' } }),
    );

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

  it('rejects a request that never reached the server with ApiError status 0 and DEM9999, without the 401 hook', async () => {
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
    stub('post', '/api/customers', () => problem(403, 'APP0403'));
    stub('get', '/api/customers/:custId', () => problem(404, 'DEM0599'));
    stub('put', '/api/customers/:custId', () => problem(409, 'DEM1001'));
    stub('get', '/api/customers', () => htmlPage(500));

    const statuses = [
      (await rejectionOf(request('/api/customers', { method: 'POST', body: {} }))).status,
      (await rejectionOf(request('/api/customers/AAAD'))).status,
      (await rejectionOf(request('/api/customers/AAAD', { method: 'PUT', body: {} }))).status,
      (await rejectionOf(request('/api/customers'))).status,
    ];

    expect(statuses).toEqual([403, 404, 409, 500]);
    expect(onUnauth).not.toHaveBeenCalled();
  });
});

// ---------------------------------------------------------------------------
// No DOM effect
// ---------------------------------------------------------------------------

describe('document', () => {
  it('is left unchanged by successful and failed calls alike', async () => {
    registerUnauthorized(vi.fn<() => void>());
    stub('get', '/api/customers', () => htmlPage(500));
    stub('post', '/api/customers/review', () =>
      problem(422, 'DEM0503', {
        errors: [{ field: 'state', code: 'DEM0503', message: messageText('DEM0503') }],
        instance: '/api/customers/review',
      }),
    );
    stub('get', '/api/states', () => HttpResponse.error());
    const bodyBefore = document.body.innerHTML;
    const documentBefore = document.documentElement.outerHTML;

    // Success: the default public catalog handler.
    await expect(request('/api/messages')).resolves.toEqual(catalog);
    // 401 from the default session handler, 500 HTML, 422 problem, network failure.
    const failures = await Promise.allSettled([
      request('/api/session'),
      request('/api/customers'),
      request('/api/customers/review', { method: 'POST', body: { purpose: 'ADD' } }),
      request('/api/states'),
    ]);

    expect(failures.map((outcome) => outcome.status)).toEqual(['rejected', 'rejected', 'rejected', 'rejected']);
    expect(document.body.innerHTML).toBe(bodyBefore);
    expect(document.documentElement.outerHTML).toBe(documentBefore);
  });
});

