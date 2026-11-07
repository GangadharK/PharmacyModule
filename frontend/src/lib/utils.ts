import { clsx, type ClassValue } from 'clsx'
import { twMerge } from 'tailwind-merge'

/**
 * Merge Tailwind class names, letting a later class win over an earlier one of
 * the same kind.
 *
 * Always use this rather than string concatenation. `cn('p-2', isActive && 'p-4')`
 * resolves the conflict; `'p-2 ' + (isActive ? 'p-4' : '')` emits both and the
 * winner depends on stylesheet order.
 */
export function cn(...inputs: ClassValue[]): string {
  return twMerge(clsx(inputs))
}

/**
 * Format a BigDecimal-serialised money value for display.
 *
 * Accepts `number | string | null | undefined`. A server `BigDecimal` arrives as
 * a JSON number, but a large DECIMAL(10,2) can exceed Number.MAX_SAFE_INTEGER in
 * principle, so a string is accepted and handled exactly.
 *
 * Never use `toFixed()` on the raw value for arithmetic - it returns a string
 * and loses precision. This is presentation only.
 */
export function formatMoney(
  value: number | string | null | undefined,
  currency = 'INR',
): string {
  if (value === null || value === undefined) return '—'

  const numeric = typeof value === 'string' ? Number(value) : value
  if (Number.isNaN(numeric)) return '—'

  return new Intl.NumberFormat('en-IN', {
    style: 'currency',
    currency,
    minimumFractionDigits: 2,
    maximumFractionDigits: 2,
  }).format(numeric)
}

/**
 * Days between today and an ISO date. Negative when the date is in the past.
 *
 * Used by ExpiryBadge, where the answer - not the date - is what determines the
 * colour. Returns null for an unparseable input rather than NaN, so a bad value
 * renders as "unknown" instead of a misleading red badge.
 */
export function daysUntil(isoDate: string | null | undefined): number | null {
  if (!isoDate) return null

  const target = new Date(isoDate)
  if (Number.isNaN(target.getTime())) return null

  const today = new Date()
  today.setHours(0, 0, 0, 0)
  target.setHours(0, 0, 0, 0)

  return Math.round((target.getTime() - today.getTime()) / 86_400_000)
}
