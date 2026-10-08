/**
 * Browser identity for the Customer Master SPA: who is signed in, with which
 * roles, and therefore which screen mode the UI shows. The mode comes only
 * from the roles `GET /api/session` reports, never from a URL or parameter.
 * The API enforces the roles (401 APP0401, 403 APP0403), so nothing held here
 * is a security decision; a 403 reaches the calling feature's presenter.
 *
 * - Credentials live in `api/client.ts`'s memory only, so a reload signs the
 *   user out. They are never cleared in an effect cleanup or on unmount:
 *   React StrictMode mounts, unmounts and remounts in development, which
 *   would otherwise wipe a valid sign-in.
 * - The username is sent exactly as typed (no trim, no uppercase). The
 *   server's user store looks it up case-insensitively, and the session
 *   reports the configured spelling, which is what the UI shows.
 */
import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState } from 'react';
import type { ReactNode } from 'react';
import { useNavigate } from 'react-router-dom';
import { useQueryClient } from '@tanstack/react-query';
import type { Query } from '@tanstack/react-query';
import { onUnauthorized, setCredentials } from '../api/client';
import { sessionApi } from '../api/session';
import type { Role as SessionRole, SessionResponse } from '../api/session';

/**
 * A role the API grants: `INQUIRY` (source mode `I`, general users) or
 * `MAINTENANCE` (source mode `M`, Sales), which implies `INQUIRY`. The one
 * definition lives in `api/session.ts`; this alias lets `auth/` consumers
 * import it from here. There is deliberately no Selection role.
 */
export type Role = SessionRole;

/**
 * The screen mode the UI shows, derived from the roles by {@link deriveMode}:
 * `'MAINTENANCE'` offers 2=Edit, 5=Display and F6=Add, `'INQUIRY'` offers
 * 5=Display only. Selection is a picker context, not a mode of the session.
 */
export type Mode = 'INQUIRY' | 'MAINTENANCE';

/** The signed-in principal as the server reported it. */
export type AuthSession = { username: string; roles: Role[] };

/** Whether a user is signed in. */
export type AuthStatus = 'signed-out' | 'signed-in';

/** What {@link useAuth} returns. */
export interface AuthContextValue {
  /** `'signed-in'` once a sign-in succeeded, until sign-out or a 401 that refuses the stored credentials. */
  status: AuthStatus;
  /** The principal's name as the server spells it; `null` when signed out. */
  username: string | null;
  /** The roles the server reported; empty when signed out. */
  roles: Role[];
  /** The screen mode the roles give; `null` when signed out or without a known role. */
  mode: Mode | null;
  /**
   * Whether the session satisfies `r`, mirroring the server's role hierarchy:
   * `MAINTENANCE` satisfies both roles, `INQUIRY` only itself.
   */
  hasRole(r: Role): boolean;
  /**
   * Authenticates `username`/`password` with `GET /api/session` and, on
   * success, stores the credentials and the reported session.
   *
   * - Resolves `true` when this attempt stored its credentials and session.
   * - Resolves `false`, storing nothing, when a newer `signIn`, a sign-out or
   *   a 401 sign-out superseded the attempt, or `signal` was aborted (before
   *   the call or while it was pending), whatever the server answered.
   *   An already aborted `signal` sends no request.
   * - Rejects with the client's `ApiError` unchanged (401 APP0401 for bad
   *   credentials, status 0 when no HTTP response arrived) when the attempt
   *   failed while it was still current; nothing is stored then.
   *
   * @param signal aborted by the caller that abandons the attempt, such as a
   *   sign-in page that unmounts
   */
  signIn(username: string, password: string, signal?: AbortSignal): Promise<boolean>;
  /** Forgets the credentials and cached server data, then routes to `/sign-in`. */
  signOut(): void;
}

const SIGN_IN_PATH = '/sign-in';

/**
 * The first query-key element under which `MessageCatalogProvider` caches the
 * catalog (`['messages']`). The catalog is public and fetched once before
 * sign-in, so it survives sign-out; every other cached query is user data.
 */
const CATALOG_QUERY_KEY = 'messages';

/** The roles of a signed-out user; one shared instance keeps the context value's identity stable. */
const NO_ROLES: Role[] = [];

const KNOWN_ROLES: readonly Role[] = ['INQUIRY', 'MAINTENANCE'];

const AuthContext = createContext<AuthContextValue | null>(null);

/** Whether `value` is one of the {@link KNOWN_ROLES}. */
function isRole(value: unknown): value is Role {
  return value === 'INQUIRY' || value === 'MAINTENANCE';
}

/**
 * The known roles among `values`, each at most once, in the order of
 * {@link KNOWN_ROLES}. Anything else (an unknown role name, a non-string) is
 * dropped, so a role the client does not understand can never widen what the
 * UI offers.
 *
 * @example toRoles(['MAINTENANCE', 'X', 'MAINTENANCE']) // ['MAINTENANCE']
 */
export function toRoles(values: readonly unknown[]): Role[] {
  const present = new Set(values.filter(isRole));
  return KNOWN_ROLES.filter((role) => present.has(role));
}

/**
 * The screen mode `roles` give: `'MAINTENANCE'` when they include
 * `MAINTENANCE`, otherwise `'INQUIRY'` when they include `INQUIRY`, otherwise
 * `null`.
 */
export function deriveMode(roles: readonly Role[]): Mode | null {
  if (roles.includes('MAINTENANCE')) {
    return 'MAINTENANCE';
  }
  if (roles.includes('INQUIRY')) {
    return 'INQUIRY';
  }
  return null;
}

/**
 * Whether `roles` satisfy `required`, mirroring the server's `RoleHierarchy`
 * (`MAINTENANCE` implies `INQUIRY`): `INQUIRY` is satisfied by either role,
 * `MAINTENANCE` only by itself.
 */
export function roleSatisfied(roles: readonly Role[], required: Role): boolean {
  if (required === 'INQUIRY') {
    return roles.includes('INQUIRY') || roles.includes('MAINTENANCE');
  }
  return roles.includes('MAINTENANCE');
}

/**
 * The session a successful `GET /api/session` describes. The server's
 * spelling of the principal wins (its user store matches a username in any
 * case); the typed name stands in only when the body carries none. A body
 * without a `roles` array yields no roles, so the UI offers nothing beyond
 * what the server confirmed.
 */
function toSession(body: unknown, typedUsername: string): AuthSession {
  const record = typeof body === 'object' && body !== null ? (body as Record<string, unknown>) : {};
  const username = typeof record.username === 'string' && record.username !== '' ? record.username : typedUsername;
  const roles = Array.isArray(record.roles) ? toRoles(record.roles) : [];
  return { username, roles };
}

/**
 * Whether a cached query holds user data rather than the public message
 * catalog; sign-out cancels and removes exactly these. The key's first
 * element may be absent (`noUncheckedIndexedAccess`), which also counts as
 * user data.
 */
function isUserData(query: Query): boolean {
  return query.queryKey[0] !== CATALOG_QUERY_KEY;
}

/**
 * Holds the signed-in session for everything below it and registers the
 * client's 401 handler. Renders no UI of its own.
 *
 * @param children the routes (`AppRoutes`) and providers that read {@link useAuth}
 * @param initialSession test seam: seeds the signed-in state only. It never
 *   stores credentials; a test that needs authenticated calls passes the
 *   matching credentials to `setCredentials` itself before rendering.
 */
export function AuthProvider({ children, initialSession }: { children: ReactNode; initialSession?: AuthSession }) {
  const [session, setSession] = useState<AuthSession | null>(() =>
    initialSession ? { username: initialSession.username, roles: toRoles(initialSession.roles) } : null,
  );
  const queryClient = useQueryClient();
  const navigate = useNavigate();
  // Each signIn and each clearSession takes the next generation, and only the
  // attempt still holding the current one may store its result. Read and
  // written in callbacks only, never during render.
  const generationRef = useRef(0);

  /**
   * Forgets the credentials and the session, then drops every cached server
   * response except the public catalog: in-flight queries are cancelled first
   * so none can repopulate the cache with the previous user's data. Taking a
   * generation first supersedes every pending sign-in attempt, so neither a
   * sign-out nor a 401 sign-out can be undone by an older attempt's answer.
   */
  const clearSession = useCallback(() => {
    generationRef.current += 1;
    setCredentials(null);
    setSession(null);
    void queryClient.cancelQueries({ predicate: isUserData });
    queryClient.removeQueries({ predicate: isUserData });
  }, [queryClient]);

  const signIn = useCallback(async (username: string, password: string, signal?: AbortSignal): Promise<boolean> => {
    if (signal?.aborted) {
      // Abandoned before it began: no request, and no generation taken, so a
      // pending attempt that is still current stays current.
      return false;
    }
    const generation = ++generationRef.current;
    const isCurrent = () => generation === generationRef.current && signal?.aborted !== true;
    // The candidate credentials authenticate this one call only; they are
    // stored after the server accepted them, and only by the current attempt.
    let response: SessionResponse;
    try {
      response = await sessionApi.get({ username, password });
    } catch (error) {
      // A rejection (401 APP0401, a network failure) reaches the attempt that
      // still owns it unchanged; a superseded or abandoned one drops it.
      if (isCurrent()) {
        throw error;
      }
      return false;
    }
    if (!isCurrent()) {
      return false;
    }
    setCredentials({ username, password });
    setSession(toSession(response, username));
    return true;
  }, []);

  const signOut = useCallback(() => {
    clearSession();
    void navigate(SIGN_IN_PATH, { replace: true });
  }, [clearSession, navigate]);

  // The one 401 handler: nothing else registers with onUnauthorized. It closes
  // over the current sign-in status and is registered again whenever that
  // status changes; the cleanup unregisters the previous handler only (it
  // never touches the credentials).
  const signedIn = session !== null;
  useEffect(
    () =>
      onUnauthorized(({ perCallCredentials, superseded }) => {
        if (perCallCredentials || superseded) {
          // A refused sign-in trial refused nothing stored, and an obsolete
          // 401 refused credentials a sign-out or a newer sign-in already
          // replaced, so the session and the stored credentials stay. signIn
          // reports a trial's refusal to the attempt that still owns it, or
          // drops it; the caller still receives either 401 as its ApiError.
          return;
        }
        if (signedIn) {
          // The stored credentials stopped working (changed password, removed
          // user): sign out exactly as the user would.
          clearSession();
          void navigate(SIGN_IN_PATH, { replace: true });
        } else {
          // Nothing is signed in: only make sure no credentials are stored,
          // and stay where the user is, so the sign-in page keeps its `from`.
          setCredentials(null);
        }
      }),
    [signedIn, clearSession, navigate],
  );

  const value = useMemo<AuthContextValue>(() => {
    const roles = session?.roles ?? NO_ROLES;
    return {
      status: session ? 'signed-in' : 'signed-out',
      username: session?.username ?? null,
      roles,
      mode: deriveMode(roles),
      hasRole: (r: Role) => roleSatisfied(roles, r),
      signIn,
      signOut,
    };
  }, [session, signIn, signOut]);

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

/**
 * The session, its mode and the sign-in and sign-out actions.
 *
 * @throws Error when called outside an {@link AuthProvider}, a wiring mistake
 *   that must fail loudly rather than render a signed-out UI
 */
export function useAuth(): AuthContextValue {
  const context = useContext(AuthContext);
  if (context === null) {
    throw new Error('useAuth must be used inside <AuthProvider>');
  }
  return context;
}
