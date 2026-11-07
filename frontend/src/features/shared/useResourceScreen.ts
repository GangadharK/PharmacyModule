import { useCallback, useState } from 'react'
import { useListQuery } from '@/features/shared/useListQuery'
import { activeOnlyParam, type ActiveFilterValue } from '@/components/data/ActiveFilter'
import { useResourceList, type ListQuery, type ResourceList } from '@/hooks/useResource'
import type { QueryValue } from '@/hooks/useResource'

/**
 * The state behind a catalogue list screen: page, size, search, status filter
 * and the resulting rows.
 *
 * This exists because the first version of the user list wired these by hand and
 * got it wrong immediately - changing the status filter called
 * `useListQuery().reset()`, which also wiped the search box. Clearing a status
 * filter should narrow the result set, not silently discard what the user typed.
 * With five resources to build, a mistake like that would be copied five times
 * rather than made once.
 *
 * So: filter changes go through `updateFilter`, which resets the page but leaves
 * search and the other filters alone. The feature pages no longer touch filter
 * state directly.
 */

/**
 * The query-parameter name for the status filter.
 *
 * The users endpoint takes `active`; the three soft-deletable catalogue
 * endpoints take `activeOnly`. That inconsistency is the server's and is
 * documented rather than papered over - sending `activeOnly` to `/users` would
 * be silently ignored and the filter would appear broken.
 */
export function useResourceScreen<T>(
  endpoint: string,
  options: {
    /** Defaults to `activeOnly`. */
    statusParam?: 'active' | 'activeOnly'
    /** Extra filters, e.g. dosageForm. Merged into the query. */
    filters?: Record<string, QueryValue>
    defaultStatus?: ActiveFilterValue
    /**
     * Set false for a resource that is not soft-deletable.
     *
     * Manufacturers are the case: they are hard-deleted, have no `isActive`
     * column and no reactivate endpoint, so `GET /manufacturers` takes no
     * `activeOnly` at all. Sending one is harmless but rendering a status filter
     * over it would be a control that silently does nothing - worse than no
     * control, because the user will believe the list is filtered when it is not.
     */
    statusFilter?: boolean
  } = {},
) {
  const {
    statusParam = 'activeOnly',
    defaultStatus = 'active',
    statusFilter = true,
  } = options

  const query = useListQuery()
  const [status, setStatus] = useState<ActiveFilterValue>(defaultStatus)

  const listQuery: ListQuery = {
    page: query.page,
    size: query.size,
    search: query.search,
    filters: {
      ...(statusFilter
        ? { [statusParam]: status === 'all' ? undefined : activeOnlyParam(status) }
        : {}),
      ...options.filters,
    },
  }

  const list: ResourceList<T> = useResourceList<T>(endpoint, listQuery)

  /** Page reset without touching search or the other filters. */
  const onStatusChange = useCallback(
    (next: ActiveFilterValue) => {
      setStatus(next)
      query.setPage(0)
    },
    [query],
  )

  const onExtraFilterChange = useCallback(
    (key: string, value: QueryValue) => query.updateFilter(key, value),
    [query],
  )

  return {
    ...query,
    list,
    status,
    statusFilter,
    onStatusChange,
    onExtraFilterChange,
  }
}

export type ResourceScreen = ReturnType<typeof useResourceScreen>
