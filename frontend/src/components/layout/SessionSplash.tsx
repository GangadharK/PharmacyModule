import { Loader2, Pill } from 'lucide-react'

/**
 * Shown while the silent session restore is in flight.
 *
 * Deliberately not a spinner over a blank page: it carries the product mark and
 * a status line, so a reload reads as the app starting rather than as a hang.
 *
 * `role="status"` with `aria-live="polite"` announces the wait to a screen
 * reader. Without it, the restore is a silent few hundred milliseconds during
 * which the whole app is replaced by an unlabelled empty region.
 */
export function SessionSplash() {
  return (
    <div
      className="flex min-h-screen flex-col items-center justify-center gap-4 bg-muted/40"
      role="status"
      aria-live="polite"
    >
      <div className="flex h-11 w-11 items-center justify-center rounded-xl bg-primary text-primary-foreground">
        <Pill className="h-6 w-6" aria-hidden />
      </div>
      <p className="flex items-center gap-2 text-sm text-muted-foreground">
        <Loader2 className="h-4 w-4 animate-spin motion-reduce:animate-none" aria-hidden />
        Restoring your session…
      </p>
    </div>
  )
}
