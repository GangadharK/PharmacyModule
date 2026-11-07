import { useState } from 'react'

import type { Column } from '@/components/data/DataTable'
import { Button } from '@/components/ui/button'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import { NativeSelect } from '@/components/ui/native-select'
import { Textarea } from '@/components/ui/textarea'
import { Badge } from '@/components/ui/badge'
import { useResourceScreen } from '@/features/shared/useResourceScreen'
import { ResourceListView } from '@/features/shared/ResourceListView'
import { locationOptions, locationLabel } from '@/features/shared/labels'
import { formatTimestamp } from '@/features/shared/format'
import { apiClient } from '@/lib/api'
import { describeError } from '@/hooks/useResource'
import { CycleCountSession } from '@/features/cycle-count/CycleCountSession'
import type { BatchLocation, CycleCountResponse, CycleCountStatus } from '@/types/api'

const STATUS_OPTIONS = [
  { value: 'OPEN', label: 'Open' },
  { value: 'POSTED', label: 'Posted' },
]

/**
 * Cycle count sessions.
 *
 * The list is the audit trail: it shows that a count happened even when nothing
 * was wrong, which a stock-adjustment flow cannot record at all. That is the
 * reason this feature exists rather than a form that calls `adjust` per line, so
 * the "we counted and the books were right" case has to be visible here.
 *
 * Opening a count covers a whole location by default. Counting one damaged
 * carton is a legitimate, much smaller job, and the API can do it - but the
 * button starts a full count because that is the ordinary case, and a
 * three-line spot check offered as the default would be filed as a full
 * reconciliation.
 */
export function CycleCountsPage() {
  const [statusFilter, setStatusFilter] = useState<CycleCountStatus | ''>('')
  const [openSession, setOpenSession] = useState<string | null>(null)
  const [isStartOpen, setIsStartOpen] = useState(false)
  const [isStarting, setIsStarting] = useState(false)
  const [location, setLocation] = useState<BatchLocation>('MAIN_PHARMACY')
  const [notes, setNotes] = useState('')
  const [startError, setStartError] = useState<string | null>(null)

  const screen = useResourceScreen<CycleCountResponse>('/inventory/cycle-counts', {
    statusFilter: false,
    filters: { status: statusFilter || undefined },
  })

  const startCount = async () => {
    setIsStarting(true)
    setStartError(null)
    try {
      const created = await apiClient.post<CycleCountResponse>('/inventory/cycle-counts', {
        location,
        notes: notes.trim() || undefined,
      })
      setNotes('')
      setIsStartOpen(false)
      screen.list.reload()
      setOpenSession(created.id)
    } catch (caught: unknown) {
      setStartError(describeError(caught, 'Could not open a count.'))
    } finally {
      setIsStarting(false)
    }
  }

  const columns: Column<CycleCountResponse>[] = [
    {
      key: 'reference',
      header: 'Count',
      cell: (row) => (
        <div>
          <div className="font-medium">{row.referenceNo}</div>
          <div className="text-xs text-muted-foreground">
            {formatTimestamp(row.createdAt)}
          </div>
        </div>
      ),
    },
    {
      key: 'location',
      header: 'Location',
      cell: (row) => locationLabel(row.location as BatchLocation),
    },
    {
      key: 'status',
      header: 'Status',
      cell: (row) => (
        <Badge variant={row.status === 'OPEN' ? 'warning' : 'secondary'}>
          {row.status === 'OPEN' ? 'Open' : 'Posted'}
        </Badge>
      ),
    },
    {
      key: 'lines',
      header: 'Lines',
      align: 'right',
      cell: (row) => row.lineCount,
    },
    {
      key: 'pending',
      header: 'To post',
      align: 'right',
      cell: (row) =>
        row.status === 'OPEN' ? (
          <span>
            {row.pendingVariances}
            {row.uncountedLines > 0 ? (
              <span className="ml-1 text-xs text-muted-foreground">
                ({row.uncountedLines} uncounted)
              </span>
            ) : null}
          </span>
        ) : (
          <span className="text-muted-foreground">—</span>
        ),
    },
  ]

  return (
    <>
      <ResourceListView
        screen={screen}
        list={screen.list}
        title="Cycle Counts"
        description="Every count session, including the ones where the books were right. Click a row to enter or review it."
        columns={columns}
        rowKey={(row) => row.id}
        searchPlaceholder="Search reference number…"
        emptyTitle="No counts yet"
        emptyDescription="Start a count to record that the books were checked, even when nothing was wrong."
        onRowClick={(row) => setOpenSession(row.id)}
        extraFilters={
          <div className="flex flex-wrap items-center gap-2">
            <NativeSelect
              value={statusFilter}
              onValueChange={(value) => {
                setStatusFilter(value as CycleCountStatus | '')
                screen.setPage(0)
              }}
              placeholder="All statuses"
              options={STATUS_OPTIONS}
              aria-label="Filter by status"
            />
            <Button onClick={() => setIsStartOpen(true)}>Start a count</Button>
          </div>
        }
      />

      <Dialog open={isStartOpen} onOpenChange={setIsStartOpen}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>Start a count</DialogTitle>
            <DialogDescription>
              Counts every countable batch at one location. The system quantity is
              recorded now, so a sale during the count is not counted as an error.
            </DialogDescription>
          </DialogHeader>

          <div className="flex flex-col gap-3">
            <div className="flex flex-col gap-1">
              <label className="text-sm font-medium" htmlFor="count-location">
                Location
              </label>
              <NativeSelect
                value={location}
                onValueChange={(value) => setLocation(value as BatchLocation)}
                options={locationOptions}
                aria-label="Location to count"
              />
            </div>

            <div className="flex flex-col gap-1">
              <label className="text-sm font-medium" htmlFor="count-notes">
                Notes
              </label>
              <Textarea
                id="count-notes"
                value={notes}
                onChange={(event) => setNotes(event.target.value)}
                placeholder="Why this count was needed, if anyone asks later"
                rows={3}
              />
            </div>

            {startError ? <p className="text-sm text-destructive">{startError}</p> : null}
          </div>

          <DialogFooter>
            <Button variant="outline" onClick={() => setIsStartOpen(false)}>
              Cancel
            </Button>
            <Button onClick={() => void startCount()} disabled={isStarting}>
              {isStarting ? 'Opening…' : 'Open count'}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      <CycleCountSession
        countId={openSession}
        onClose={() => setOpenSession(null)}
        onPosted={() => {
          setOpenSession(null)
          screen.list.reload()
        }}
      />
    </>
  )
}
