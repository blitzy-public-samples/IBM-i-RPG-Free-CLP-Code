/**
 * The typed call for the message catalog: `GET /api/messages`.
 *
 * Part of the shared component "Message texts (client)", with
 * `messages/MessageCatalogProvider.tsx`, which calls {@link messagesApi}
 * once at application start and applies the `{n}` substitution rule. Every
 * message the SPA shows, client-raised ones included, is looked up there.
 *
 * What it replaces. On the 5250 the texts lived in the message file CUSTMSGF,
 * built by 17 `ADDMSGD` commands (5250_Subfile/CRTMSGF.CLLE:12-46), and
 * SndMsgPgmQ resolved a message id against it when it sent the message. Here
 * the catalog is data: the server's `messages/messages.properties`, keyed by
 * the same CUSTMSGF ids (DEM0000..DEM9999) plus the APP keys, is served whole
 * as one JSON object of id to text, and the browser keeps that object.
 *
 * What this module does, and nothing else:
 * - Requests the catalog through `./client`'s {@link request}, with a
 *   relative path, so the call stays on the page's own origin.
 * - Sends no credentials of its own. The endpoint is public and the provider
 *   loads it before sign-in; `client.ts` leaves `Authorization` out while no
 *   user is signed in, so the request is anonymous then.
 *
 * Constraints:
 * - No message text, no fallback catalog and no substitution here. The bundle
 *   carries no text at all; a code that is not in the loaded catalog is the
 *   provider's concern.
 * - No cache. The provider loads once (react-query with an infinite stale
 *   time), so each call of {@link messagesApi.getAll} is one request.
 * - Layer rule: only `./client` (at run time) and the generated `./schema`
 *   (types only) are imported; nothing from `components/`, `errors/`,
 *   `features/` or `auth/`. Nothing here renders, touches the DOM or keeps
 *   state.
 * - Failures are the transport's: a non-2xx answer or a lost connection
 *   rejects with `ApiError`, unchanged.
 *
 * @example
 * ```ts
 * // In MessageCatalogProvider, through react-query:
 * useQuery({ queryKey: ['messages'], queryFn: () => messagesApi.getAll(), staleTime: Infinity });
 *
 * // The resolved value maps each message id to its catalog text:
 * const catalog = await messagesApi.getAll();
 * const template = catalog['DEM0004']; // string | undefined (noUncheckedIndexedAccess)
 * ```
 */
import { request } from './client';
import type { operations } from './schema';

/**
 * The whole message catalog: message id (`DEM0000`, `APP0400`, ...) to its
 * text, with `{0}`, `{1}`, ... marking the substitution points.
 *
 * Alias of the generated OpenAPI type of the `200` body of `getMessages`,
 * which is `{ [key: string]: string }`, so it stays in step with the
 * committed snapshot. A lookup is typed `string | undefined`, because the
 * set of keys is data, not a compile-time list.
 */
export type MessageCatalogData =
  operations['getMessages']['responses'][200]['content']['application/json'];

/** The catalog endpoint, relative so it stays on the page's origin. */
const MESSAGES_PATH = '/api/messages';

/** Typed calls of the message catalog endpoint. */
export const messagesApi = {
  /**
   * Fetches the whole catalog with one `GET /api/messages`.
   *
   * Resolves with the catalog object as the server sent it (22 keys today:
   * the 17 CUSTMSGF ids and five APP keys). It needs no sign-in; credentials,
   * when a user is signed in, are added by `client.ts` as for every call.
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
