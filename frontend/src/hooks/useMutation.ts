import { useState } from 'react'
import { apiClient } from '@/lib/api'
import { describeError } from '@/hooks/useResource'
import { toast } from '@/store/toast.store'

/**
 * Create, update and delete for a resource, as three separate hooks.
 *
 * The alternative - one `useMutation(method, endpoint)` taking `(body?, id?)` -
 * was tried and is worse than it looks. Optional positional arguments make
 * `remove('some-id')` type-check against a signature whose first parameter is a
 * body, so the compiler cannot catch a swapped argument, and every call site has
 * to know the calling convention. Three hooks means the argument list is what the
 * verb says it is.
 *
 * Every method resolves only after the server confirms. A failure raises a toast
 * and re-throws, so a caller cannot `await` a mutation, swallow the rejection and
 * navigate away as though it worked.
 */

interface Options<TRes> {
  successMessage: string
  /** Called after a confirmed success, typically a list reload or navigate. */
  onSuccess?: (result: TRes) => void
}

export function useCreate<TBody, TRes = unknown>(endpoint: string, options: Options<TRes>) {
  const [isPending, setIsPending] = useState(false)

  async function create(body: TBody): Promise<TRes> {
    setIsPending(true)
    try {
      const result = await apiClient.post<TRes>(endpoint, body)
      toast.success(options.successMessage)
      options.onSuccess?.(result)
      return result
    } catch (caught) {
      toast.error('Could not save', describeError(caught, 'The request failed.'))
      throw caught
    } finally {
      setIsPending(false)
    }
  }

  return { create, isPending }
}

export function useUpdate<TBody, TRes = unknown>(endpoint: string, options: Options<TRes>) {
  const [isPending, setIsPending] = useState(false)

  async function update(id: string, body: TBody): Promise<TRes> {
    setIsPending(true)
    try {
      const result = await apiClient.patch<TRes>(`${endpoint}/${id}`, body)
      toast.success(options.successMessage)
      options.onSuccess?.(result)
      return result
    } catch (caught) {
      toast.error('Could not save', describeError(caught, 'The request failed.'))
      throw caught
    } finally {
      setIsPending(false)
    }
  }

  return { update, isPending }
}

export function useDelete<TRes = unknown>(endpoint: string, options: Options<TRes>) {
  const [isPending, setIsPending] = useState(false)

  async function remove(id: string): Promise<TRes> {
    setIsPending(true)
    try {
      // Every soft delete here answers 204 No Content, so there is no body to
      // parse. Callers that need the deleted row should re-fetch the list.
      const result = (await apiClient.delete<TRes>(`${endpoint}/${id}`)) as TRes
      toast.success(options.successMessage)
      options.onSuccess?.(result)
      return result
    } catch (caught) {
      toast.error('Could not delete', describeError(caught, 'The request failed.'))
      throw caught
    } finally {
      setIsPending(false)
    }
  }

  return { remove, isPending }
}

/**
 * Reactivate a soft-deleted record.
 *
 * Separate from `useDelete` because it is a POST with no body and its own
 * success copy - "Deactivated" is wrong when reactivating, and reusing the
 * delete hook for both is how that mislabelling happens.
 */
export function useReactivate(basePath: string, onDone: () => void) {
  const [isPending, setIsPending] = useState(false)

  async function reactivate(id: string): Promise<void> {
    setIsPending(true)
    try {
      await apiClient.post<void>(`${basePath}/${id}/reactivate`)
      toast.success('Reactivated')
      onDone()
    } catch (caught) {
      toast.error('Could not reactivate', describeError(caught, 'The request failed.'))
      throw caught
    } finally {
      setIsPending(false)
    }
  }

  return { reactivate, isPending }
}
