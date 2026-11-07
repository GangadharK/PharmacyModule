import { create } from 'zustand'
import { persist } from 'zustand/middleware'

/**
 * UI chrome state. Persisted, unlike auth.
 *
 * A collapsed sidebar is a display preference with no security implication, so
 * persisting it is correct. The persistence key is namespaced `pharmacy-ui-*`
 * so it can never collide with anything auth-related - if a token ever ended up
 * under a `persist`-ed store it would be readable from disk, which is exactly
 * what auth.store avoids.
 */

const STORAGE_KEY = 'pharmacy-ui-sidebar'

interface UiState {
  sidebarCollapsed: boolean
  toggleSidebar: () => void
  setSidebarCollapsed: (collapsed: boolean) => void
}

export const useUiStore = create<UiState>()(
  persist(
    (set) => ({
      sidebarCollapsed: false,
      toggleSidebar: () => set((state) => ({ sidebarCollapsed: !state.sidebarCollapsed })),
      setSidebarCollapsed: (sidebarCollapsed) => set({ sidebarCollapsed }),
    }),
    {
      name: STORAGE_KEY,
      // Only the preference. Never widen this without checking that no
      // credential has crept into the store.
      partialize: (state) => ({ sidebarCollapsed: state.sidebarCollapsed }),
    },
  ),
)
