import { useEffect } from 'react'
import { useToastStore, type Toast as ToastItem } from '@/store/toast.store'
import {
  Toast,
  ToastClose,
  ToastDescription,
  ToastProvider,
  ToastTitle,
  ToastViewport,
} from '@/components/ui/toast'

/**
 * Bridges the Zustand toast queue to Radix's toast primitives.
 *
 * <strong>MOUNT THIS.</strong> SCMS defines a toast system and never renders its
 * container, so every `toast.success()` call there is a silent no-op - a feature
 * that looks implemented and is not, and which nobody notices because nothing
 * fails. It is mounted in AppShell and the mount is verified in
 * `context/specs/01-infrastructure/01c-frontend-shell.md`.
 */
function ToastList() {
  const toasts = useToastStore((state) => state.toasts)
  const dismiss = useToastStore((state) => state.dismiss)

  return (
    <>
      {toasts.map((item: ToastItem) => (
        <Toast
          key={item.id}
          variant={item.variant}
          duration={item.duration}
          onOpenChange={(open) => {
            if (!open) dismiss(item.id)
          }}
        >
          <div className="grid gap-1">
            <ToastTitle>{item.title}</ToastTitle>
            {item.description ? (
              <ToastDescription>{item.description}</ToastDescription>
            ) : null}
          </div>
          <ToastClose />
        </Toast>
      ))}
    </>
  )
}

/**
 * Auto-dismiss each toast when its timer expires. Radix fires onOpenChange on
 * expiry, which routes back through dismiss - so a dismissed toast leaves the
 * queue rather than being hidden in place and reappearing on the next render.
 */
function AutoDismiss() {
  const toasts = useToastStore((state) => state.toasts)
  const dismiss = useToastStore((state) => state.dismiss)

  useEffect(() => {
    if (toasts.length === 0) return

    const timers = toasts.map((item) =>
      window.setTimeout(() => dismiss(item.id), item.duration),
    )
    return () => timers.forEach(window.clearTimeout)
  }, [toasts, dismiss])

  return null
}

export function ToastContainer() {
  return (
    <ToastProvider swipeDirection="right">
      <ToastList />
      <AutoDismiss />
      <ToastViewport />
    </ToastProvider>
  )
}
