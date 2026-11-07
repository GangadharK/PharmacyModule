import { Badge } from '@/components/ui/badge'
import type { StockAlertSeverity } from '@/types/api'

/**
 * How urgent a low-stock line is, as one badge.
 *
 * The three tiers are defined once, on the server, and rendered here rather than
 * recomputed. That is deliberate: this badge is what a pharmacist triages from,
 * and two implementations of the same threshold eventually disagree - at which
 * point the badge and the underlying number tell different stories on the same
 * row, which is worse than having no badge at all.
 */
export function AlertSeverityBadge({ severity }: { severity: StockAlertSeverity }) {
  return <Badge variant={severityVariant(severity)}>{severityLabel(severity)}</Badge>
}

export function severityLabel(severity: StockAlertSeverity): string {
  switch (severity) {
    case 'OUT_OF_STOCK':
      return 'Out of stock'
    case 'CRITICAL':
      return 'Critical'
    case 'LOW':
      return 'Low'
  }
}

/**
 * `CRITICAL` borrows the `warning` variant rather than getting its own colour.
 *
 * Three severities do not justify three new tokens, and the difference that
 * matters - sellable or not - is already carried by the label. A destructive
 * badge on a line that still has stock trains people to stop reading the badge.
 */
export function severityVariant(
  severity: StockAlertSeverity,
): 'destructive' | 'warning' | 'secondary' {
  switch (severity) {
    case 'OUT_OF_STOCK':
      return 'destructive'
    case 'CRITICAL':
      return 'warning'
    case 'LOW':
      return 'secondary'
  }
}
