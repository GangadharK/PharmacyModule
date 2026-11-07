import { Navigate, Outlet, useLocation } from 'react-router-dom'
import { useAuthStore } from '@/store/auth.store'
import { SessionSplash } from '@/components/layout/SessionSplash'

/**
 * Auth gate for everything under the AppShell.
 *
 * Both guards must respect `isRestoring`. The access token is in memory only, so
 * a page load begins with none; until the refresh cookie has been tried, "no
 * token" is ambiguous between *signed out* and *not yet restored*. Redirecting on
 * that ambiguity throws a signed-in pharmacist back to the login form on every
 * single refresh. The splash is the honest state for those few hundred
 * milliseconds.
 */
export function ProtectedRoute() {
  const isAuthenticated = useAuthStore((state) => state.accessToken !== null)
  const isRestoring = useAuthStore((state) => state.isRestoring)
  const location = useLocation()

  if (isRestoring) return <SessionSplash />
  if (!isAuthenticated) {
    const next = `${location.pathname}${location.search}`
    return <Navigate to={`/login?next=${encodeURIComponent(next)}`} replace />
  }

  return <Outlet />
}

/**
 * Keeps a signed-in user off the login page. Without it, following a stale
 * /login bookmark while authenticated shows a login form that silently does
 * nothing, because the submit handler would replace the session instead of
 * reporting that one is already open.
 */
export function PublicOnlyRoute() {
  const isAuthenticated = useAuthStore((state) => state.accessToken !== null)
  const isRestoring = useAuthStore((state) => state.isRestoring)

  if (isRestoring) return <SessionSplash />
  if (isAuthenticated) return <Navigate to="/dashboard" replace />

  return <Outlet />
}
