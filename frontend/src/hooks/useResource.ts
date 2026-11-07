import { useCallback, useEffect, useRef, useState } from 'react'
import { apiClient, isApiRequestError } from '@/lib/api'
import type { PageResponse } from '@/types/api'

/**
 * Paged list loading, plus the create/update/delete calls behind a list screen.
 *
 * Every catalogue resource and the user list are the same shape: a search box, a
 * few filters, a page of rows, and mutations that reload the list. Writing that
 * five times produces five subtly different loading and error behaviours, so it
 * lives here once.
 *
 * Two decisions worth stating, because both were learned the hard way:
 *
 * **Requests are versioned, not cancelled blindly.** A user typing in a search
 * box issues a request per keystroke, and responses can arrive out of order -
 * the slowest early request can land after the fastest later one and repopulate
 * the list with stale results. Every request carries a sequence number and a
 * response is discarded unless it is the newest. `AbortController` alone does
 * not fix this: the server may already have sent the response, and there is a
 * window where both are in flight.
 *
 * **A failed reload keeps the previous rows.** Blanking the table on an error
 * makes a transient 500 look like the catalogue is empty, which is a very
 * different thing to tell a pharmacist. The error is surfaced alongside.
 */

export type QueryValue = string | number | boolean | undefined | null

export interface ListQuery {
  page: number
  size: number
  search?: string
  filters?: Record<string, QueryValue>
}

export interface ResourceList<T> {
  rows: T[]
  totalElements: number
  totalPages: number
  page: number
  size: number
  isLoading: boolean
  /** Non-null when the most recent load failed. */
  error: string | null
  reload: () => void
}

const DEFAULT_SIZE = 20

export function useResourceList<T>(endpoint: string, query: ListQuery): ResourceList<T> {
  const { page, size, search, filters } = query
  const [rows, setRows] = useState<T[]>([])
  const [totalElements, setTotalElements] = useState(0)
  const [totalPages, setTotalPages] = useState(0)
  const [isLoading, setIsLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)

  // Monotonic request id. Only the newest response may write state.
  const requestId = useRef(0)
  const [reloadToken, setReloadToken] = useState(0)

  // Serialised so the effect's dependency list stays flat. `filters` is a fresh
  // object literal on every render from a caller that inlines it, which would
  // otherwise re-fetch in a loop.
  const filterKey = JSON.stringify(filters ?? {})

  useEffect(() => {
    const id = ++requestId.current
    const controller = new AbortController()
    setIsLoading(true)

    apiClient
      .get<PageResponse<T>>(endpoint, {
        query: {
          page,
          size,
          // Blank is undefined, not '' - the backend treats a blank search as
          // absent, and sending '' would filter for the empty string.
          search: search?.trim() ? search.trim() : undefined,
          ...(JSON.parse(filterKey) as Record<string, QueryValue>),
        },
        signal: controller.signal,
      })
      .then((body) => {
        if (id !== requestId.current) return
        setRows(body.content)
        setTotalElements(body.totalElements)
        setTotalPages(body.totalPages)
        setError(null)
      })
      .catch((caught: unknown) => {
        if (id !== requestId.current) return
        // An abort is this component's own doing, not a fault to report.
        if (controller.signal.aborted) return
        setError(describeError(caught, 'Could not load this list.'))
        // Rows are deliberately left in place - see the note above.
      })
      .finally(() => {
        if (id !== requestId.current) return
        setIsLoading(false)
      })

    return () => controller.abort()
  }, [endpoint, page, size, search, filterKey, reloadToken])

  const reload = useCallback(() => setReloadToken((n) => n + 1), [])

  return {
    rows,
    totalElements,
    totalPages,
    page,
    size: size || DEFAULT_SIZE,
    isLoading,
    error,
    reload,
  }
}

/**
 * A single record by id.
 *
 * `undefined` id means "not requested yet", which is how a create page avoids
 * firing a GET for a record that does not exist. `notFound` is separated from
 * `error` because a 404 after a delete is an expected outcome, not a fault, and
 * the two deserve different copy.
 */
export function useResourceItem<T>(endpoint: string, id: string | undefined) {
  const [item, setItem] = useState<T | null>(null)
  const [isLoading, setIsLoading] = useState(Boolean(id))
  const [error, setError] = useState<string | null>(null)
  const [notFound, setNotFound] = useState(false)
  const [reloadToken, setReloadToken] = useState(0)

  useEffect(() => {
    if (!id) {
      setItem(null)
      setIsLoading(false)
      return
    }
    const controller = new AbortController()
    setIsLoading(true)
    setNotFound(false)

    apiClient
      .get<T>(`${endpoint}/${id}`, { signal: controller.signal })
      .then((body) => {
        if (controller.signal.aborted) return
        setItem(body)
        setError(null)
      })
      .catch((caught: unknown) => {
        if (controller.signal.aborted) return
        if (isApiRequestError(caught) && caught.status === 404) {
          setNotFound(true)
          return
        }
        setError(describeError(caught, 'Could not load this record.'))
      })
      .finally(() => {
        if (!controller.signal.aborted) setIsLoading(false)
      })

    return () => controller.abort()
  }, [endpoint, id, reloadToken])

  return {
    item,
    isLoading,
    error,
    notFound,
    reload: useCallback(() => setReloadToken((n) => n + 1), []),
  }
}

/**
 * A list endpoint that returns a plain array rather than a Spring `Page`.
 *
 * The two alert queries are the reason this exists. They return every row, and
 * paging them would be worse than useless: a low-stock list that stops at page
 * one is not a low-stock list, and a pharmacist cannot tell whether they have
 * seen all of it. The two lists are bounded by the size of the catalogue, so the
 * honest thing is to show all of it and let the screen sort.
 *
 * The request-id and abort discipline is copied from {@link useResourceList}
 * deliberately. Both exist to stop a slow earlier response overwriting a fast
 * later one, which on a filter-as-you-type control shows up as rows that do not
 * match the box.
 */
export interface ApiList<T> {
  rows: T[]
  isLoading: boolean
  /** Non-null when the most recent load failed. */
  error: string | null
  reload: () => void
}

export function useApiList<T>(endpoint: string, query?: Record<string, QueryValue>): ApiList<T> {
  const [rows, setRows] = useState<T[]>([])
  const [isLoading, setIsLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [reloadToken, setReloadToken] = useState(0)

  const requestId = useRef(0)
  const queryKey = JSON.stringify(query ?? {})

  useEffect(() => {
    const id = ++requestId.current
    const controller = new AbortController()
    setIsLoading(true)

    apiClient
      .get<T[]>(endpoint, {
        query: JSON.parse(queryKey) as Record<string, QueryValue>,
        signal: controller.signal,
      })
      .then((body) => {
        if (id !== requestId.current) return
        setRows(body)
        setError(null)
      })
      .catch((caught: unknown) => {
        if (id !== requestId.current) return
        if (controller.signal.aborted) return
        setError(describeError(caught, 'Could not load this list.'))
      })
      .finally(() => {
        if (id !== requestId.current) return
        setIsLoading(false)
      })

    return () => controller.abort()
  }, [endpoint, queryKey, reloadToken])

  const reload = useCallback(() => setReloadToken((n) => n + 1), [])

  return { rows, isLoading, error, reload }
}

/**
 * Turn any thrown value into display copy.
 *
 * Branches on `code`, never on `message` (AGENTS.md rule 10): the server's
 * message is for humans and may be reworded, but the codes are the contract.
 */
export function describeError(caught: unknown, fallback: string): string {
  if (isApiRequestError(caught)) {
    if (caught.code === 'NETWORK_ERROR') return 'Cannot reach the server.'
    if (caught.code === 'VALIDATION_FAILED' && caught.fieldErrors) {
      const first = Object.values(caught.fieldErrors)[0]
      if (first) return first
    }
    return caught.message || fallback
  }
  return fallback
}
