import type { ReactNode } from 'react'

export interface DetailItem {
  label: string
  value: ReactNode
  /** Rendered in a wider column, for addresses and long free text. */
  wide?: boolean
  /** Replaces the value with an em dash when empty, rather than showing "". */
  fallback?: string
}

/**
 * A read-only record view.
 *
 * Detail pages are the reason "get specific" exists, and they are all the same
 * shape: a definition list of labelled values. A missing optional field renders
 * as an em dash rather than an empty cell, because a blank next to a label reads
 * as a rendering bug rather than as "not recorded" - and "did we lose the phone
 * number or was it never entered" is exactly the question a detail page should
 * answer.
 */
export function DescriptionList({ items }: { items: DetailItem[] }) {
  return (
    <dl className="grid grid-cols-1 gap-x-8 gap-y-4 sm:grid-cols-2">
      {items.map((item) => {
        const isEmpty =
          item.value === null ||
          item.value === undefined ||
          item.value === '' ||
          item.fallback !== undefined
        return (
          <div key={item.label} className={item.wide ? 'sm:col-span-2' : undefined}>
            <dt className="text-xs font-medium uppercase tracking-wide text-muted-foreground">
              {item.label}
            </dt>
            <dd className="mt-1 text-sm">
              {isEmpty ? (
                <span className="text-muted-foreground">{item.fallback ?? '—'}</span>
              ) : (
                item.value
              )}
            </dd>
          </div>
        )
      })}
    </dl>
  )
}
