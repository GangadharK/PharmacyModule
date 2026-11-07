import { useCallback, useEffect, useState } from 'react'

import { Button } from '@/components/ui/button'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import { Input } from '@/components/ui/input'
import { Badge } from '@/components/ui/badge'
import { Skeleton } from '@/components/ui/skeleton'
import { ErrorState } from '@/features/shared/ResourceStates'
import { formatTimestamp } from '@/features/shared/format'
import { apiClient } from '@/lib/api'
import { describeError } from '@/hooks/useResource'
import type { CycleCountLineResponse, CycleCountResponse } from '@/types/api'

/**
 * One count session: enter what is on the shelf, then post the differences.
 *
 * The layout keeps the system's figure and the counted figure side by side,
 * because the whole session is a comparison. A variance is an accusation that the
 * books were wrong, and the person making it has to be able to see the number
 * they are contradicting at the moment they contradict it.
 *
 * `liveQuantity` is shown too, and it is not redundant with `systemQuantity`. A
 * sale during a count moves the live figure but must not become a variance - the
 * snapshot is fixed when the count opens, and a discrepancy between the two is
 * expected, not an error. Hiding the live figure would make a correct count look
 * wrong every time someone sold something while counting.
 */
export interface CycleCountSessionProps {
  countId: string | null
  onClose: () => void
  onPosted: () => void
}

export function CycleCountSession({ countId, onClose, onPosted }: CycleCountSessionProps) {
  const [count, setCount] = useState<CycleCountResponse | null>(null)
  const [drafts, setDrafts] = useState<Record<string, string>>({})
  const [isLoading, setIsLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [isPosting, setIsPosting] = useState(false)

  const load = useCallback(() => {
    if (!countId) return
    setIsLoading(true)
    apiClient
      .get<CycleCountResponse>(`/inventory/cycle-counts/${countId}`)
      .then((body) => {
        setCount(body)
        setError(null)
      })
      .catch((caught: unknown) => setError(describeError(caught, 'Could not load this count.')))
      .finally(() => setIsLoading(false))
  }, [countId])

  useEffect(() => {
    if (!countId) {
      setCount(null)
      setDrafts({})
      setError(null)
      return
    }
    load()
  }, [countId, load])

  /**
   * Committed on blur, not on every keystroke.
   *
   * An earlier version saved on change, which meant one PATCH per digit and a
   * count of 12 sent 1, then 12, then 12 - recording a figure the counter never
   * meant to enter, on the way to the one they did.
   */
  /**
   * Re-reads the whole count after a line is saved.
   *
   * The summary counters are not patched locally on purpose. `uncountedLines` and
   * `pendingVariances` gate the one button on this screen that writes to the
   * stock ledger, so they have to be the server's own numbers — recomputing them
   * from the lines would let the button enable on a figure the server would
   * refuse, which is the failure mode worth spending a round trip to avoid.
   *
   * It used to merge just the updated line into local state, which left the
   * counters frozen at their opening values: count every line, and Post stayed
   * disabled forever unless the session was closed and reopened.
   */
  const saveLine = async (line: CycleCountLineResponse, value: string) => {
    if (countId === null) return
    const counted = value.trim()
    setDrafts((current) => ({ ...current, [line.id]: value }))

    // Blank means "I have not counted this", not "I counted zero". Sending a
    // zero here would silently file a variance of -50 on a shelf nobody looked
    // at, which is the one mistake this screen exists to prevent.
    if (counted === '') return

    try {
      await apiClient.patch<CycleCountLineResponse>(
        `/inventory/cycle-counts/${countId}/lines/${line.id}`,
        { countedQuantity: Number(counted) },
      )
      load()
    } catch (caught: unknown) {
      setError(describeError(caught, 'Could not save that line.'))
    }
  }

  const post = async () => {
    if (countId === null) return
    setIsPosting(true)
    setError(null)
    try {
      await apiClient.post(`/inventory/cycle-counts/${countId}/post`)
      onPosted()
    } catch (caught: unknown) {
      setError(describeError(caught, 'Could not post this count.'))
    } finally {
      setIsPosting(false)
    }
  }

  const isOpen = countId !== null
  const canPost =
    count !== null &&
    count.status === 'OPEN' &&
    count.uncountedLines === 0 &&
    !isPosting

  return (
    <Dialog open={isOpen} onOpenChange={(open) => !open && onClose()}>
      <DialogContent className="max-w-3xl">
        <DialogHeader>
          <DialogTitle>{count ? count.referenceNo : 'Cycle count'}</DialogTitle>
          <DialogDescription>
            {count
              ? `Opened ${formatTimestamp(count.createdAt)}. Enter what is on the shelf; the
                 system figure is what the books claimed when the count started.`
              : 'Loading…'}
          </DialogDescription>
        </DialogHeader>

        {isLoading ? (
          <Skeleton className="h-40 w-full" />
        ) : error ? (
          <ErrorState message={error} onRetry={load} />
        ) : count ? (
          <div className="flex flex-col gap-3">
            <div className="flex flex-wrap items-center gap-2 text-sm">
              <Badge variant="secondary">
                {count.uncountedLines} uncounted
              </Badge>
              <Badge variant={count.pendingVariances > 0 ? 'warning' : 'secondary'}>
                {count.pendingVariances} to post
              </Badge>
              {count.status === 'POSTED' ? <Badge variant="secondary">Posted</Badge> : null}
            </div>

            <div className="max-h-80 overflow-y-auto rounded-md border">
              <table className="w-full text-sm">
                <thead className="bg-muted/50 text-left">
                  <tr>
                    <th className="px-3 py-2 font-medium">Item</th>
                    <th className="px-3 py-2 text-right font-medium">System</th>
                    <th className="px-3 py-2 text-right font-medium">Live</th>
                    <th className="px-3 py-2 text-right font-medium">Counted</th>
                  </tr>
                </thead>
                <tbody>
                  {count.lines.map((line) => {
                    const variance =
                      line.countedQuantity === null || line.countedQuantity === undefined
                        ? null
                        : line.countedQuantity - line.systemQuantity
                    return (
                      <tr key={line.id} className="border-t">
                        <td className="px-3 py-2">
                          <div className="font-medium">{line.medicineName ?? '(non-medicine)'}</div>
                          <div className="text-xs text-muted-foreground">{line.batchNumber}</div>
                        </td>
                        <td className="px-3 py-2 text-right tabular-nums">
                          {line.systemQuantity}
                        </td>
                        <td className="px-3 py-2 text-right tabular-nums text-muted-foreground">
                          {line.liveQuantity ?? '—'}
                        </td>
                        <td className="px-3 py-2 text-right">
                          <div className="flex items-center justify-end gap-2">
                            {variance !== null && variance !== 0 ? (
                              <Badge variant={variance < 0 ? 'destructive' : 'warning'}>
                                {variance > 0 ? `+${variance}` : variance}
                              </Badge>
                            ) : null}
                            <Input
                              className="w-20 text-right"
                              type="number"
                              min={0}
                              aria-label={`Counted quantity for ${line.medicineName ?? line.batchNumber}`}
                              disabled={count.status === 'POSTED'}
                              value={
                                drafts[line.id] ??
                                (line.countedQuantity === null ||
                                line.countedQuantity === undefined
                                  ? ''
                                  : String(line.countedQuantity))
                              }
                              onChange={(event) =>
                                setDrafts((current) => ({
                                  ...current,
                                  [line.id]: event.target.value,
                                }))
                              }
                              onBlur={(event) => {
                                void saveLine(line, event.target.value)
                              }}
                            />
                          </div>
                        </td>
                      </tr>
                    )
                  })}
                </tbody>
              </table>
            </div>

            <DialogFooter>
              <Button variant="outline" onClick={onClose}>
                Close
              </Button>
              {count.status === 'OPEN' ? (
                <Button onClick={() => void post()} disabled={!canPost}>
                  {isPosting
                    ? 'Posting…'
                    : count.pendingVariances > 0
                      ? `Post ${count.pendingVariances} adjustment${
                          count.pendingVariances === 1 ? '' : 's'
                        }`
                      : 'Post count'}
                </Button>
              ) : null}
            </DialogFooter>
          </div>
        ) : null}
      </DialogContent>
    </Dialog>
  )
}
