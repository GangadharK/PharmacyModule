import { useMemo } from 'react'

import type { Column } from '@/components/data/DataTable'
import { DataTable } from '@/components/data/DataTable'
import { Badge } from '@/components/ui/badge'
import { useApiList } from '@/hooks/useResource'
import { severityLabel, severityVariant } from '@/features/alerts/components/AlertSeverityBadge'
import type { StockAlertResponse } from '@/types/api'

/**
 * Everything at or below its reorder level.
 *
 * Ordered by shortfall, which the backend already does, because the question
 * this screen answers is "what do I order today" - and the answer to that is the
 * biggest gap, not the alphabetically first line. A stock-out and a line one unit
 * short both belong here; only one of them is worth interrupting a shift for.
 *
 * No search box and no paging. The endpoint returns every low line, bounded by
 * the catalogue, so a search field would suggest the list is partial when it is
 * not - and the whole point of an alert screen is that you have seen all of it.
 */
export function LowStockPage() {
  const list = useApiList<StockAlertResponse>('/inventory/alerts/low-stock')

  const columns = useMemo<Column<StockAlertResponse>[]>(
    () => [
      {
        key: 'item',
        header: 'Item',
        cell: (row) => (
          <div>
            <div className="font-medium">{row.itemName}</div>
            {row.form || row.strength ? (
              <div className="text-xs text-muted-foreground">
                {[row.form, row.strength].filter(Boolean).join(' · ')}
              </div>
            ) : null}
          </div>
        ),
      },
      {
        key: 'severity',
        header: 'Severity',
        cell: (row) => (
          <Badge variant={severityVariant(row.severity)}>{severityLabel(row.severity)}</Badge>
        ),
      },
      {
        key: 'held',
        header: 'In stock',
        align: 'right',
        cell: (row) => row.totalAvailable,
      },
      {
        key: 'level',
        header: 'Reorder at',
        align: 'right',
        cell: (row) => row.reorderLevel,
      },
      {
        key: 'shortfall',
        header: 'Order',
        align: 'right',
        // The space is a real character, not a margin class. `ml-1` puts the gap
        // on screen and leaves the text as "7units short", which is what a screen
        // reader announces and what a test has to grep for.
        cell: (row) => (
          <span className="font-medium">
            {row.shortfall}{' '}
            <span className="text-xs font-normal text-muted-foreground">units short</span>
          </span>
        ),
      },
      {
        key: 'batches',
        header: 'Batches',
        align: 'right',
        cell: (row) => row.activeBatchCount,
      },
    ],
    [],
  )

  return (
    <DataTable
      columns={columns}
      rows={list.rows}
      isLoading={list.isLoading}
      error={list.error}
      rowKey={(row) => row.id}
      emptyTitle="Nothing is below its reorder level"
      emptyDescription="Every active line holds at least the quantity it should."
      onRetry={list.reload}
    />
  )
}
