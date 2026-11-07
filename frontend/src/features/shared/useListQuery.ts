import { useCallback, useState } from 'react'
import type { QueryValue } from '@/hooks/useResource'

/**
 * Page, size, search and filters for a server-paged list.
 *
 * Filters live here rather than in each screen so the rules are the same
 * everywhere, and two of them are not obvious:
 *
 * **Any filter change returns to page 0.** Staying on page 4 after narrowing a
 * filter produces an empty table, which reads as "no results" when the truth is
 * "page 4 of what used to be 8 pages". The user then blames the filter.
 *
 * **A new search also returns to page 0**, same reasoning.
 */
export function useListQuery(defaults: {
  search?: string
  filters?: Record<string, QueryValue>
  size?: number
} = {}) {
  const [page, setPage] = useState(0)
  const [size, setSize] = useState(defaults.size ?? 20)
  const [search, setSearch] = useState(defaults.search ?? '')
  const [filters, setFilters] = useState<Record<string, QueryValue>>(defaults.filters ?? {})

  const updateFilter = useCallback((key: string, value: QueryValue) => {
    setFilters((current) => {
      const next = { ...current }
      // Dropping the key entirely rather than sending null, so the request omits
      // the parameter. Sending activeOnly="" or "all" would reach the backend as a
      // value it has to special-case.
      if (value === undefined || value === '' || value === null) delete next[key]
      else next[key] = value
      return next
    })
    setPage(0)
  }, [])

  const onSearchChange = useCallback((value: string) => {
    setSearch(value)
    setPage(0)
  }, [])

  const reset = useCallback(() => {
    setSearch('')
    setFilters(defaults.filters ?? {})
    setPage(0)
  }, [defaults.filters])

  return {
    page,
    size,
    search,
    filters,
    setPage,
    setSize,
    onSearchChange,
    updateFilter,
    reset,
    hasActiveFilters: search !== '' || Object.keys(filters).length > 0,
  }
}
