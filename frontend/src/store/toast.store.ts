import { create } from 'zustand'

/**
 * Toast queue and an imperative `toast` helper.
 *
 * SCMS defines a toast system and never mounts <ToastContainer />, so
 * toast.success() is a silent no-op - a feature that looks implemented and is
 * not. The container is mounted in AppShell and the mount is verified in
 * context/specs/01-infrastructure/01c-frontend-shell.md. Do not remove it.
 */

export type ToastVariant = 'default' | 'success' | 'destructive' | 'warning'

export interface Toast {
  id: string
  title: string
  description?: string
  variant: ToastVariant
  duration: number
}

interface ToastState {
  toasts: Toast[]
  push: (toast: Omit<Toast, 'id'>) => string
  dismiss: (id: string) => void
  dismissAll: () => void
}

let counter = 0

export const useToastStore = create<ToastState>()((set) => ({
  toasts: [],

  push: (toast) => {
    const id = `toast-${++counter}`
    set((state) => ({ toasts: [...state.toasts, { ...toast, id }] }))
    return id
  },

  dismiss: (id) =>
    set((state) => ({ toasts: state.toasts.filter((t) => t.id !== id) })),

  dismissAll: () => set({ toasts: [] }),
}))

const DEFAULT_DURATION = 4000
const ERROR_DURATION = 6000

/**
 * Imperative API, mirroring shadcn/ui's `toast()` so existing muscle memory
 * works: `toast.success('Saved')`.
 */
export const toast = {
  show: (title: string, options: Partial<Omit<Toast, 'title'>> = {}): string =>
    useToastStore.getState().push({
      title,
      variant: 'default',
      duration: DEFAULT_DURATION,
      ...options,
    }),

  success: (title: string, description?: string): string =>
    toast.show(title, { description, variant: 'success' }),

  /** Errors linger longer - they usually need reading, and often contain a code. */
  error: (title: string, description?: string): string =>
    toast.show(title, { description, variant: 'destructive', duration: ERROR_DURATION }),

  warning: (title: string, description?: string): string =>
    toast.show(title, { description, variant: 'warning', duration: ERROR_DURATION }),

  info: (title: string, description?: string): string =>
    toast.show(title, { description, variant: 'default' }),

  dismiss: (id: string): void => useToastStore.getState().dismiss(id),
}
