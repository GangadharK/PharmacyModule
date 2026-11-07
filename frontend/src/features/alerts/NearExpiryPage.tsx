import { useMemo, useState } from 'react'

import type { Column } from '@/components/data/DataTable'
import { DataTable } from '@/components/data/DataTable'
import { NativeSelect } from '@/components/ui/native-select'
import { useApiList } from '@/hooks/useResource'
import { ExpiryBadge } from '@/features/inventory/components/ExpiryBadge'
import { batchStatusLabel } from '@/features/shared/labels'
import type { BatchStatus, ExpiryAlertResponse } from '@/types/api'

/**
 * Window options, not a free-text day count.
 *
 * This control answers "show me what needs attention soon", so the useful
 * answers are the horizons a pharmacist already thinks in - this month, this
 * quarter, this year - and a typed 37 answers a question nobody asks while
 * making those three harder to reach. The backend accepts any integer up to 365,
 * so this is a UI choice rather than a contract limit.
 */
const WINDOW_OPTIONS = [
  { value: '30', label: 'Next 30 days' },
  { value: '60', label: 'Next 60 days' },
  { value: '90', label: 'Next 90 days' },
  { value: '180', label: 'Next 180 days' },
  { value: '365', label: 'Next year' },
]

/**
 * Batches at or near expiry, soonest first.
 *
 * Quarantined batches are on this list deliberately, and the status column is
 * what makes that legible rather than confusing. A quarantined batch is not
 * sellable but it is not gone either, and it still has to be physically
 * destroyed on its date - dropping it would hide a pile of stock somebody has to
 * deal with. A batch past its date that has not been written off yet also appears,
 * with a negative countdown, because it is exactly the thing that needs attention.
 */
export function NearExpiryPage() {
  const [windowDays, setWindowDays] = useState('90')

  const list = useApiList<ExpiryAlertResponse>('/inventory/alerts/near-expiry', {
    withinDays: windowDays,
  })

  const columns = useMemo<Column<ExpiryAlertResponse>[]>(
    () => [
      {
        key: 'medicine',
        header: 'Item',
        cell: (row) => (
          <div>
            <div className="font-medium">{row.medicineName ?? '(non-medicine)'}</div>
            <div className="text-xs text-muted-foreground">{row.batchNumber}</div>
          </div>
        ),
      },
      {
        key: 'expiry',
        header: 'Expires in',
        cell: (row) => (
          <ExpiryBadge expiryDate={row.expiryDate} daysUntilExpiry={row.daysRemaining} />
        ),
      },
      {
        key: 'quantity',
        header: 'Quantity',
        align: 'right',
        cell: (row) => row.quantityAvailable,
      },
      {
        key: 'status',
        header: 'Status',
        cell: (row) => batchStatusLabel(row.status as BatchStatus),
      },
    ],
    [],
  )

  return (
    <div className="flex flex-col gap-3">
      <div className="flex items-center gap-2">
        <NativeSelect
          value={windowDays}
          onValueChange={setWindowDays}
          options={WINDOW_OPTIONS}
          aria-label="Expiry window"
        />
      </div>

      <DataTable
        columns={columns}
        rows={list.rows}
        isLoading={list.isLoading}
        error={list.error}
        rowKey={(row) => row.batchId}
        emptyTitle="Nothing is expiring in this window"
        emptyDescription="Widen the window to look further ahead."
        onRetry={list.reload}
      />
    </div>
  )
}
