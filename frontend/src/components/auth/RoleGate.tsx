import type { ReactNode } from 'react'
import { hasAtLeastRole, type Role } from '@/types/api'
import { useAuthStore } from '@/store/auth.store'

/**
 * Renders children only when the signed-in user has at least `required` role.
 *
 * Named in ui-context.md §4.1 as `RoleGate`, and this is that component.
 *
 * **This is a usability affordance, not a control.** Every endpoint behind these
 * children carries its own `@PreAuthorize`, and the server is the only thing that
 * can decide. The gate exists so a cashier does not see an "Add medicine" button
 * that is guaranteed to answer 403 - a dead control is worse than an absent one,
 * because it teaches the user the app is broken rather than that they lack the
 * role. The reverse is not a concern: hiding is safe, showing is not, and a bug
 * here can only ever hide something that would have failed.
 */
export function RoleGate({
  required,
  children,
  /** Shown when the role check fails. Omit to render nothing. */
  fallback = null,
}: {
  required: Role
  children: ReactNode
  fallback?: ReactNode
}) {
  const roles = useAuthStore((state) => state.user?.roles ?? [])
  if (!hasAtLeastRole(roles, required)) return <>{fallback}</>
  return <>{children}</>
}

/** Imperative form of the same check, for computing a boolean. */
export function useHasRole(required: Role): boolean {
  const roles = useAuthStore((state) => state.user?.roles ?? [])
  return hasAtLeastRole(roles, required)
}
