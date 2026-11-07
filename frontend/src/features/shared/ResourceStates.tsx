import type { ReactNode } from 'react'
import { AlertTriangle, FileQuestion } from 'lucide-react'

import { Button } from '@/components/ui/button'
import { Card, CardContent } from '@/components/ui/card'
import { Skeleton } from '@/components/ui/skeleton'
import { PageHeader } from '@/components/layout/PageHeader'

/**
 * The three states every detail screen needs, in one place.
 *
 * "Not found" and "could not load" are separated deliberately. A 404 means the
 * record is gone - usually deleted by someone else while this page was open - and
 * the only useful action is to go back. A 500 or a dropped connection means the
 * record probably still exists, so retrying is worthwhile. Rendering both as
 * "something went wrong" teaches a user to reload a deleted record forever.
 */

interface StateProps {
  onBack?: () => void
  backLabel?: string
  children?: ReactNode
}

export function NotFoundState({
  what,
  onBack,
  backLabel = 'Back to list',
}: StateProps & { what: string }) {
  return (
    <div className="flex flex-col items-center gap-3 rounded-xl border border-dashed px-6 py-16 text-center">
      <FileQuestion className="h-7 w-7 text-muted-foreground" aria-hidden />
      <div className="space-y-1">
        <p className="font-medium">This {what} no longer exists</p>
        <p className="max-w-md text-sm text-muted-foreground">
          It may have been deleted. Nothing is recoverable from this page.
        </p>
      </div>
      {onBack && (
        <Button variant="outline" size="sm" onClick={onBack}>
          {backLabel}
        </Button>
      )}
    </div>
  )
}

export function ErrorState({
  message,
  onRetry,
  onBack,
  backLabel = 'Back to list',
}: StateProps & { message: string; onRetry?: () => void }) {
  return (
    <div
      role="alert"
      className="flex flex-col items-center gap-3 rounded-xl border border-destructive/40 bg-destructive/5 px-6 py-16 text-center"
    >
      <AlertTriangle className="h-7 w-7 text-destructive" aria-hidden />
      <div className="space-y-1">
        <p className="font-medium">Could not load this record</p>
        <p className="max-w-md text-sm text-muted-foreground">{message}</p>
      </div>
      <div className="flex gap-2">
        {onRetry && (
          <Button size="sm" onClick={onRetry}>
            Try again
          </Button>
        )}
        {onBack && (
          <Button variant="outline" size="sm" onClick={onBack}>
            {backLabel}
          </Button>
        )}
      </div>
    </div>
  )
}

/**
 * The pending state for a detail page.
 *
 * Mirrors the real layout - header, then a card of labelled rows - so the page
 * does not reflow when the record arrives, and so a slow response still looks
 * like the screen it is becoming rather than a spinner on a blank page.
 */
export function DetailSkeleton({ rows = 6 }: { rows?: number }) {
  return (
    <>
      <PageHeader title="Loading…" description="Fetching the record." />
      <Card>
        <CardContent className="space-y-4 pt-6">
          {Array.from({ length: rows }, (_, i) => (
            <div key={i} className="space-y-2">
              <Skeleton className="h-3 w-24" />
              <Skeleton className="h-4 w-48" />
            </div>
          ))}
        </CardContent>
      </Card>
    </>
  )
}
