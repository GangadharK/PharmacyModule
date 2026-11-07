import { Badge } from '@/components/ui/badge'
import { daysUntil } from '@/lib/utils'
import { formatDate } from '@/features/shared/format'

/**
 * How long a batch has left, as a countdown rather than a date.
 *
 * A date makes the reader do the comparison: they have to know today, subtract,
 * and decide whether the result is alarming. A pharmacist scanning a column of
 * batches is answering "is this a problem", not "what is the date" - so the
 * comparison happens here and the reader is given the answer.
 *
 * `daysUntilExpiry` is used in preference to recomputing from `expiryDate`, so
 * the countdown agrees with the value the server computed rather than depending
 * on the browser's clock and timezone. The two can disagree, and the browser is
 * the less authoritative of the pair: `formatTimestamp` documents why the server's
 * clock is the one shown. `daysUntil` is the fallback for a payload that omits it.
 */

const CRITICAL_DAYS = 30
const WARNING_DAYS = 90
const CALM_DAYS = 180

export type ExpiryTone = 'expired' | 'critical' | 'warning' | 'calm' | 'unknown'

export function expiryTone(days: number | null): ExpiryTone {
  if (days === null) return 'unknown'
  if (days < 0) return 'expired'
  if (days <= CRITICAL_DAYS) return 'critical'
  if (days <= WARNING_DAYS) return 'warning'
  if (days <= CALM_DAYS) return 'calm'
  return 'calm'
}

const TONE_VARIANT: Record<ExpiryTone, 'destructive' | 'warning' | 'secondary'> = {
  expired: 'destructive',
  critical: 'destructive',
  warning: 'warning',
  calm: 'secondary',
  unknown: 'secondary',
}

export interface ExpiryBadgeProps {
  expiryDate?: string | null
  daysUntilExpiry?: number | null
}

export function ExpiryBadge({ expiryDate, daysUntilExpiry }: ExpiryBadgeProps) {
  const days = daysUntilExpiry ?? daysUntil(expiryDate)
  const tone = expiryTone(days)

  // No date at all, or one the browser cannot parse. A muted "No expiry" is a
  // visible data-quality problem; coercing it to a calm 365-day badge would hide
  // a missing field behind a reassuring-looking number.
  if (tone === 'unknown') {
    return (
      <Badge variant="secondary" title="No expiry date recorded for this batch">
        No expiry
      </Badge>
    )
  }

  // Past the calm horizon a countdown stops being information and becomes noise:
  // "312d" is not actionable and it pushes the batches that matter off the
  // screen. The date is the honest rendering for those.
  if (days !== null && days > CALM_DAYS) {
    return (
      <Badge variant="secondary" title={`Expires ${formatDate(expiryDate ?? '')}`}>
        {expiryDate ? formatDate(expiryDate) : '—'}
      </Badge>
    )
  }

  const label = days === null ? '—' : days < 0 ? `Expired ${Math.abs(days)}d ago` : `${days}d`
  const title = expiryDate ? `Expires ${formatDate(expiryDate)}` : undefined

  return (
    <Badge variant={TONE_VARIANT[tone]} title={title}>
      {label}
    </Badge>
  )
}
