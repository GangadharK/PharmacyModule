import { Navigate, Outlet } from 'react-router-dom'
import { hasAtLeastRole, type Role } from '@/types/api'
import { useAuthStore } from '@/store/auth.store'
import { SessionSplash } from '@/components/layout/SessionSplash'

/**
 * Role gate for a group of routes.
 *
 * Mirrors the backend's `@PreAuthorize` so the UI and the API agree on who can
 * reach a screen. A signed-in user who lands here without the role is sent to the
 * dashboard rather than shown a "403" page: the nav already hides these links, so
 * arriving at one means a stale bookmark or a guess, and a dead-end screen would
 * help nobody. The API enforces the same rule regardless - this is about not
 * showing a user a page whose every request will fail.
 *
 * The `isRestoring` wait is the same reason `ProtectedRoute` has one. The role
 * lives in the user object that arrives with the session, so checking before the
 * silent refresh completes would read an empty role list and bounce a legitimate
 * super admin off `/settings/users` on a page refresh. That is the same
 * refresh-bounces-you-out bug the auth guard exists to prevent, one level down.
 */
export function RoleRoute({ required }: { required: Role }) {
  const roles = useAuthStore((state) => state.user?.roles ?? [])
  const isRestoring = useAuthStore((state) => state.isRestoring)

  if (isRestoring) return <SessionSplash />
  if (!hasAtLeastRole(roles, required)) return <Navigate to="/dashboard" replace />

  return <Outlet />
}
