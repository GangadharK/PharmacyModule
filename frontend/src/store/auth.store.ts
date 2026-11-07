import { create } from 'zustand'
import type { Role, User } from '@/types/api'

/**
 * Auth state. In memory only - NOT persisted.
 *
 * No localStorage, no sessionStorage. A JWT in web storage is readable by any
 * injected script, and XSS in a page that renders OCR-derived supplier names
 * and medicine data is not a theoretical risk. The access token lives here and
 * dies with the tab.
 *
 * The 15-minute access token is recovered from the HttpOnly refresh cookie on
 * load by `restoreSession` (features/auth/api/auth.api.ts), so a hard refresh
 * or a reopened tab resumes the session without the token ever being readable by
 * script. That split is the entire reason for the two-token design.
 *
 * See ui-context.md section 8.
 */

interface AuthState {
  accessToken: string | null
  user: User | null
  /**
   * True while the silent restore is in flight, so the router can wait.
   *
   * Starts TRUE on purpose. A fresh page load has no access token in memory, and
   * that is indistinguishable from "signed out" until the refresh cookie has
   * been tried. Initialising to false would let ProtectedRoute redirect to
   * /login during the round trip, bouncing a signed-in pharmacist out of the
   * page they refreshed on every reload. The bootstrap in App.tsx is what
   * clears it, and it is mounted unconditionally, so it cannot be left stuck.
   */
  isRestoring: boolean

  setSession: (accessToken: string, user: User) => void
  setUser: (user: User) => void
  setRestoring: (restoring: boolean) => void
  logout: () => void
}

export const useAuthStore = create<AuthState>()((set) => ({
  accessToken: null,
  user: null,
  isRestoring: true,

  setSession: (accessToken, user) => set({ accessToken, user }),
  setUser: (user) => set({ user }),
  setRestoring: (isRestoring) => set({ isRestoring }),
  logout: () => set({ accessToken: null, user: null }),
}))

/** Read the token outside React. Required by ApiClient, which is not a component. */
export const getAccessToken = (): string | null => useAuthStore.getState().accessToken

export const getCurrentUser = (): User | null => useAuthStore.getState().user

export const getRoles = (): Role[] => useAuthStore.getState().user?.roles ?? []
