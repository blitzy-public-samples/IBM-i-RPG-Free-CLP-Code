/**
 * The typed call for the public message catalog, `GET /api/messages`, which
 * serves the CUSTMSGF texts (5250_Subfile/CRTMSGF.CLLE:12-46) as data.
 *
 * `messages/MessageCatalogProvider.tsx` loads the catalog once at application
 * start and owns its cache and the `{n}` substitution, so this module holds no
 * message text, no fallback catalog, no substitution and no cache: each
 * {@link messagesApi.getAll} call is one request.
 */
import { request } from './client';
import type { operations } from './schema';

/**
 * The whole message catalog: message id (`DEM0000`, `APP0400`, ...) to its
 * text, with `{0}`, `{1}`, ... marking the substitution points.
 *
 * An alias of the generated OpenAPI `200` body of `getMessages`, so it stays
 * in step with the committed snapshot. A lookup is typed `string | undefined`,
 * because the set of keys is data, not a compile-time list.
 */
export type MessageCatalogData =
  operations['getMessages']['responses'][200]['content']['application/json'];

const MESSAGES_PATH = '/api/messages';

export const messagesApi = {
  /**
   * Fetches the whole catalog with one `GET /api/messages`.
   *
   * Resolves with the catalog object as the server sent it, whose 22 keys are
   * the 17 CUSTMSGF ids and five APP keys. It needs no sign-in, which lets the
   * provider load it before the user signs in; `client.ts` adds credentials,
   * as for every call, only when a user is signed in.
   *
   * @returns the catalog, id to text
   * @throws ApiError (as a rejection) for a non-2xx answer, such as 401
   *   APP0401 when stored credentials are no longer valid, and with status 0
   *   when no response arrives
   */
  getAll(): Promise<MessageCatalogData> {
    return request<MessageCatalogData>(MESSAGES_PATH);
  },
};
