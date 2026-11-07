import { Button } from '@/components/ui/button'
import { cn } from '@/lib/utils'

/**
 * Active / Deactivated / All selector for the soft-deletable resources.
 *
 * "All" is the reason this is a control rather than a checkbox. The endpoint
 * defaults `activeOnly=true`, so a checkbox starting off would need a third
 * "show everything" state anyway - and a filter that can only narrow is awkward
 * when the question being asked is "what did we retire last month?".
 *
 * `value === undefined` means the parameter is not sent, which the endpoint reads
 * as "no filter on isActive" and answers with both active and deactivated rows.
 * That is the whole reason the server default is not applied here: with a
 * `defaultValue = "true"` parameter the controller substitutes `true` for an
 * absent value, and "All" would silently behave like "Active". The client still
 * sends an explicit `true` for the Active state rather than relying on that
 * default, so the two can never be confused.
 */
export type ActiveFilterValue = 'active' | 'inactive' | 'all'

const OPTIONS: { value: ActiveFilterValue; label: string }[] = [
  { value: 'active', label: 'Active' },
  { value: 'inactive', label: 'Deactivated' },
  { value: 'all', label: 'All' },
]

interface ActiveFilterProps {
  value: ActiveFilterValue
  onChange: (value: ActiveFilterValue) => void
  className?: string
}

export function ActiveFilter({ value, onChange, className }: ActiveFilterProps) {
  return (
    <div
      className={cn('inline-flex rounded-md border p-0.5', className)}
      role="group"
      aria-label="Filter by status"
    >
      {OPTIONS.map((option) => (
        <Button
          key={option.value}
          type="button"
          size="sm"
          variant={value === option.value ? 'secondary' : 'ghost'}
          aria-pressed={value === option.value}
          onClick={() => onChange(option.value)}
          className="h-7 px-2.5 text-xs"
        >
          {option.label}
        </Button>
      ))}
    </div>
  )
}

/** Maps the control's value to the `activeOnly` query parameter. */
export function activeOnlyParam(value: ActiveFilterValue): boolean | undefined {
  if (value === 'active') return true
  if (value === 'inactive') return false
  return undefined
}
