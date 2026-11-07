import { useState } from 'react'

import type { Column } from '@/components/data/DataTable'
import { NativeSelect } from '@/components/ui/native-select'
import { useResourceScreen } from '@/features/shared/useResourceScreen'
import { ResourceListView } from '@/features/shared/ResourceListView'
import { formatMoney } from '@/lib/utils'
import { BatchStatusBadge, batchStatusOptions, locationLabel, locationOptions } from '@/features/shared/labels'
import { ExpiryBadge } from '@/features/inventory/components/ExpiryBadge'
import { CostPriceCell } from '@/features/inventory/components/CostPriceCell'
import { BatchHistoryDrawer } from '@/features/inventory/batches/BatchHistoryDrawer'
import type { BatchLocation, BatchStatus, InventoryBatchResponse } from '@/types/api'

/**
 * Expiry-window filter options.
 *
 * Fixed choices rather than a free-text day count: this is a "show me what needs
 * attention soon" control, and a typed 37 would answer a question nobody asks
 * while making the common cases (30/60/90) harder to reach. The backend takes any
 * integer, so this is a UI choice, not a contract limit.
 */
const EXPIRING_OPTIONS = [
  { value: '30', label: 'Expiring in 30 days' },
  { value: '60', label: 'Expiring in 60 days' },
  { value: '90', label: 'Expiring in 90 days' },
  { value: '180', label: 'Expiring in 180 days' },
]

/**
 * The stock list.
 *
 * Default order is expiry ascending, which the backend already applies, because
 * the question this screen exists to answer is "what runs out first" - the same
 * order FEFO itself uses. A pharmacist cross-checking the list against a
 * dispense would otherwise be comparing two different orderings.
 *
 * `statusFilter: false` because a batch is not soft-deletable. The three
 * catalogue resources are, and they get the tri-state active filter. Batches have
 * a four-value status enum instead, and rendering a control that silently does
 * nothing is worse than rendering none - the user would believe the list was
 * filtered when it was not.
 */
export function BatchesPage() {
  const [statusFilter, setStatusFilter] = useState<BatchStatus | ''>('')
  const [locationFilter, setLocationFilter] = useState<BatchLocation | ''>('')
  const [expiringFilter, setExpiringFilter] = useState<string>('')
  const [historyBatch, setHistoryBatch] = useState<InventoryBatchResponse | null>(null)

  const screen = useResourceScreen<InventoryBatchResponse>('/inventory', {
    statusFilter: false,
    filters: {
      status: statusFilter || undefined,
      location: locationFilter || undefined,
      expiringWithinDays: expiringFilter || undefined,
    },
  })

  const columns: Column<InventoryBatchResponse>[] = [
    {
      key: 'medicine',
      header: 'Medicine',
      cell: (row) => (
        <div>
          <p className="font-medium">{row.medicineName ?? row.itemName ?? '—'}</p>
          {row.supplierName ? (
            <p className="text-xs text-muted-foreground">{row.supplierName}</p>
          ) : null}
        </div>
      ),
    },
    { key: 'batchNumber', header: 'Batch', cell: (row) => <span className="text-sm">{row.batchNumber}</span> },
    {
      key: 'expiryDate',
      header: 'Expiry',
      cell: (row) => (
        <ExpiryBadge expiryDate={row.expiryDate} daysUntilExpiry={row.daysUntilExpiry} />
      ),
    },
    {
      key: 'quantityAvailable',
      header: 'Available',
      align: 'right',
      cell: (row) => (
        <span className="tabular-nums">
          {row.quantityAvailable}
          <span className="text-xs text-muted-foreground">
            {row.quantityReceived !== undefined ? ` / ${row.quantityReceived}` : ''}
          </span>
        </span>
      ),
    },
    {
      key: 'location',
      header: 'Location',
      cell: (row) => <span className="text-sm">{locationLabel(row.location)}</span>,
    },
    {
      key: 'status',
      header: 'Status',
      cell: (row) => <BatchStatusBadge status={row.status} />,
    },
    {
      key: 'costPrice',
      header: 'Cost',
      align: 'right',
      cell: (row) => <CostPriceCell costPrice={row.costPrice} />,
    },
    {
      key: 'sellingPrice',
      header: 'Selling',
      align: 'right',
      cell: (row) =>
        row.sellingPrice === null || row.sellingPrice === undefined ? (
          <span className="text-muted-foreground">—</span>
        ) : (
          <span className="tabular-nums">{formatMoney(row.sellingPrice)}</span>
        ),
    },
  ]

  return (
    <>
      <ResourceListView
        screen={screen}
        list={screen.list}
        title="Batches"
        description="Every stock batch, soonest expiry first. Click a row for its ledger."
        columns={columns}
        rowKey={(row) => row.id}
        searchPlaceholder="Search medicine or batch…"
        onRowClick={(row) => setHistoryBatch(row)}
        extraFilters={
          <div className="flex flex-wrap items-center gap-2">
            <NativeSelect
              value={statusFilter}
              onValueChange={(value) => {
                setStatusFilter(value as BatchStatus | '')
                screen.setPage(0)
              }}
              placeholder="All statuses"
              options={batchStatusOptions}
              aria-label="Filter by status"
            />

            <NativeSelect
              value={locationFilter}
              onValueChange={(value) => {
                setLocationFilter(value as BatchLocation | '')
                screen.setPage(0)
              }}
              placeholder="All locations"
              options={locationOptions}
              aria-label="Filter by location"
            />

            <NativeSelect
              value={expiringFilter}
              onValueChange={(value) => {
                setExpiringFilter(value)
                screen.setPage(0)
              }}
              placeholder="Any expiry"
              options={EXPIRING_OPTIONS}
              aria-label="Filter by expiry window"
            />
          </div>
        }
        emptyTitle="No batches"
        emptyDescription="Stock appears here once a delivery is received. Receiving happens against a purchase order."
      />

      <BatchHistoryDrawer
        batchId={historyBatch?.id ?? null}
        batchNumber={historyBatch?.batchNumber ?? ''}
        medicineName={historyBatch?.medicineName ?? historyBatch?.itemName ?? ''}
        onOpenChange={(open) => {
          if (!open) setHistoryBatch(null)
        }}
      />
    </>
  )
}
