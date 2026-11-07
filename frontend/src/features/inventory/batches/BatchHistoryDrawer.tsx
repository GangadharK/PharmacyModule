import { useCallback, useEffect, useState } from 'react'

import { Button } from '@/components/ui/button'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import { Badge } from '@/components/ui/badge'
import { Skeleton } from '@/components/ui/skeleton'
import { ErrorState } from '@/features/shared/ResourceStates'
import { formatTimestamp } from '@/features/shared/format'
import { referenceTypeLabel, transactionTypeLabel } from '@/features/shared/labels'
import { apiClient } from '@/lib/api'
import { describeError } from '@/hooks/useResource'
import type { StockTransactionResponse } from '@/types/api'

/**
 * A batch's append-only ledger.
 *
 * Opened from the batches list because "why does this say 8?" is a question about
 * the ledger, not about the batch. The batch row is a *result*; every number in
 * it is the sum of the rows here, and a pharmacist who cannot see the rows cannot
 * audit the number.
 *
 * The running balance is computed as it renders rather than stored, because the
 * ledger is append-only and the order is the server's (`createdAt` ascending). A
 * stored running total would be a second thing that can disagree with the
 * ledger, which is the exact class of bug this unit already found once - the
 * status that never persisted.
 */
export interface BatchHistoryDrawerProps {
  batchId: string | null
  batchNumber: string
  medicineName: string
  onOpenChange: (open: boolean) => void
}

export function BatchHistoryDrawer({
  batchId,
  batchNumber,
  medicineName,
  onOpenChange,
}: BatchHistoryDrawerProps) {
  const [rows, setRows] = useState<StockTransactionResponse[]>([])
  const [isLoading, setIsLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [reloadToken, setReloadToken] = useState(0)

  useEffect(() => {
    if (!batchId) {
      setRows([])
      return
    }
    const controller = new AbortController()
    setIsLoading(true)
    setError(null)

    apiClient
      .get<StockTransactionResponse[]>(`/inventory/${batchId}/history`, {
        signal: controller.signal,
      })
      .then((body) => {
        if (controller.signal.aborted) return
        setRows(body)
      })
      .catch((caught: unknown) => {
        if (controller.signal.aborted) return
        setError(describeError(caught, 'Could not load this batch’s ledger.'))
      })
      .finally(() => {
        if (!controller.signal.aborted) setIsLoading(false)
      })

    return () => controller.abort()
  }, [batchId, reloadToken])

  const reload = useCallback(() => setReloadToken((n) => n + 1), [])

  // The ledger is returned oldest-first, so walking it forwards accumulates the
  // balance at each point. Anything else would need the list reversed, and a
  // reversed ledger reads backwards.
  let running = 0
  const withBalance = rows.map((row) => {
    running += row.quantityChanged
    return { row, balance: running }
  })

  return (
    <Dialog open={Boolean(batchId)} onOpenChange={onOpenChange}>
      <DialogContent className="max-w-2xl">
        <DialogHeader>
          <DialogTitle>Ledger — {batchNumber}</DialogTitle>
          <DialogDescription>
            {medicineName}. Every movement in and out of this batch, oldest first.
          </DialogDescription>
        </DialogHeader>

        {isLoading ? (
          <div className="space-y-2">
            <Skeleton className="h-10 w-full" />
            <Skeleton className="h-10 w-full" />
            <Skeleton className="h-10 w-full" />
          </div>
        ) : error ? (
          <ErrorState message={error} onRetry={reload} />
        ) : withBalance.length === 0 ? (
          <p className="py-6 text-center text-sm text-muted-foreground">
            No movements recorded. A batch is created by a receipt, so this should
            not happen for a batch that exists.
          </p>
        ) : (
          <div className="max-h-80 overflow-y-auto">
            <table className="w-full text-sm">
              <thead className="sticky top-0 bg-background text-left text-xs text-muted-foreground">
                <tr>
                  <th className="py-2 pr-3 font-medium">When</th>
                  <th className="py-2 pr-3 font-medium">Movement</th>
                  <th className="py-2 pr-3 text-right font-medium">Change</th>
                  <th className="py-2 pr-3 text-right font-medium">Balance</th>
                  <th className="py-2 font-medium">By</th>
                </tr>
              </thead>
              <tbody>
                {withBalance.map(({ row, balance }) => (
                  <tr key={row.id} className="border-t">
                    <td className="py-2 pr-3 whitespace-nowrap text-muted-foreground">
                      {formatTimestamp(row.createdAt)}
                    </td>
                    <td className="py-2 pr-3">
                      <span className="font-medium">{transactionTypeLabel(row.transactionType)}</span>
                      {/*
                        A sale posts a null reference - 06-fefo-engine.md section
                        7.4 - so the absence is normal and is not rendered as a
                        missing-value dash that looks like a bug.
                      */}
                      {row.referenceType ? (
                        <Badge variant="secondary" className="ml-2">
                          {referenceTypeLabel(row.referenceType)}
                        </Badge>
                      ) : null}
                      {row.note ? (
                        <p className="text-xs text-muted-foreground">{row.note}</p>
                      ) : null}
                    </td>
                    <td
                      className={`py-2 pr-3 text-right tabular-nums ${
                        row.quantityChanged < 0 ? 'text-destructive' : 'text-success'
                      }`}
                    >
                      {row.quantityChanged > 0 ? '+' : ''}
                      {row.quantityChanged}
                    </td>
                    <td className="py-2 pr-3 text-right tabular-nums font-medium">{balance}</td>
                    <td className="py-2 text-muted-foreground">{row.actorName}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}

        <div className="flex justify-end">
          <Button variant="outline" onClick={() => onOpenChange(false)}>
            Close
          </Button>
        </div>
      </DialogContent>
    </Dialog>
  )
}
