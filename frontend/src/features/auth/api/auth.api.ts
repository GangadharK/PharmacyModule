import { apiClient, isApiRequestError } from '@/lib/api'
import { useAuthStore } from '@/store/auth.store'
import { toUser, type SessionUserDto, type User } from '@/types/api'

/**
 * Auth network calls.
 *
 * All three go through `ApiClient` with `anonymous: true`. "Anonymous" means
 * "do not send a bearer token" - it does not mean "omit credentials". The
 * refresh cookie is HttpOnly and unreadable from JS by design, so these calls
 * rely on `credentials: 'include'` to have the browser attach it.
 *
 * The api module exists so the components never touch DTO shapes, and so the
 * `toUser` normalisation happens in exactly one place.
 */

export interface Credentials {
  username: string
  password: string
}

/** A live session, already normalised for the store. */
export interface Session {
  accessToken: string
  user: User
}

/**
 * Exchange credentials for an access token.
 *
 * @throws {ApiRequestError} BAD_CREDENTIALS / ACCOUNT_DEACTIVATED, or NETWORK_ERROR
 */
export async function login({ username, password }: Credentials): Promise<Session> {
  const response = await apiClient.post<import('@/types/api').LoginResponse>(
    '/auth/login',
    { username, password },
    { anonymous: true },
  )
  return { accessToken: response.accessToken, user: toUser(response.user) }
}

/**
 * The current user, for re-reading a session.
 *
 * `/auth/me` returns a bare `SessionUser` rather than a `TokenResponse` wrapper,
 * so it needs no token handling here - but it does need a bearer token, so this
 * one is deliberately not anonymous.
 */
export async function fetchMe(): Promise<User> {
  return toUser(await apiClient.get<SessionUserDto>('/auth/me'))
}

/**
 * End the session server-side.
 *
 * Never throws. Logout is idempotent on the server by design - clearing a cookie
 * that is already gone is a success, not a failure - so a user clicking "sign
 * out" with an expired token should still end up signed out, and a network blip
 * on the way out must not leave the button spinning forever.
 */
export async function logout(): Promise<void> {
  try {
    await apiClient.post<void>('/auth/logout', undefined, { anonymous: true })
  } catch {
    // Intentionally swallowed; the local session is cleared regardless.
  }
}

/**
 * One in-flight restore, shared by every caller.
 *
 * This is not premature deduplication. React 19 StrictMode double-invokes
 * effects in development, and refresh tokens ROTATE: presenting a token that has
 * already been replaced is treated as a stolen-token replay, which the backend
 * answers by revoking every session for that user.
 *
 * Two concurrent restores would therefore log the user out on most page loads,
 * and only in dev, and only sometimes - the worst shape of bug to report. A
 * module-level promise makes the second invocation await the first instead of
 * issuing a second request. The module is evaluated once per page load, so this
 * is genuinely per-load, not per-component.
 */
let restoreInFlight: Promise<Session | null> | null = null

/**
 * Attempt to restore a session from the HttpOnly refresh cookie.
 *
 * Resolves to `null` when there is nothing to restore - no cookie, an expired
 * one, or one the server has revoked. Never throws: "not signed in" is a normal
 * outcome of opening the app, not an exceptional one, and the caller needs to
 * render the login form rather than an error.
 */
export function restoreSession(): Promise<Session | null> {
  if (restoreInFlight) return restoreInFlight

  restoreInFlight = (async () => {
    try {
      const response = await apiClient.post<import('@/types/api').LoginResponse>(
        '/auth/refresh',
        undefined,
        { anonymous: true },
      )
      const session: Session = { accessToken: response.accessToken, user: toUser(response.user) }
      useAuthStore.getState().setSession(session.accessToken, session.user)
      return session
    } catch (error) {
      /*
       * "Nobody is signed in" arrives as 400 BUSINESS_RULE_VIOLATION ("No refresh
       * token was presented"), not 401 - the backend raises a BusinessRuleException
       * for a missing cookie. So the absence of a session is detected by status
       * class, not by a single code.
       *
       * Warn only on a genuine fault: a 5xx means the backend is broken, and
       * status 0 is a NetworkError, meaning the request never arrived. Both are
       * worth a developer looking. Every 4xx is an expected outcome of opening the
       * app, and logging those would put a false alarm in the console of every
       * anonymous visitor.
       */
      const isFault =
        !isApiRequestError(error) || error.status === 0 || error.status >= 500
      if (isFault) {
        // A silent restore is invisible by design, so a genuine fault would
        // otherwise leave no trace at all. Deliberate exception to no-console.
        // oxlint-disable-next-line no-console
        console.warn('[auth] session restore failed:', error)
      }
      /*
       * Only clear a session that is still the one we were asked about.
       *
       * A blind logout() here is a stale-response clobber: if a session was
       * established while this request was in flight, the failure of an
       * *older* request must not sign the newer session out. The restore and a
       * real sign-in are independent requests racing for the same slot, and last
       * writer wins is the wrong rule - a restore that started first has the
       * least claim to the final state.
       */
      if (!useAuthStore.getState().accessToken) {
        useAuthStore.getState().logout()
      }
      return null
    } finally {
      // Cleared so a later deliberate sign-out/sign-in cycle can restore again.
      // Success keeps the resolved value cached only for the current tick.
      restoreInFlight = null
    }
  })()

  return restoreInFlight
}
