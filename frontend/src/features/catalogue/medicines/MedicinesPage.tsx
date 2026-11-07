import { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { Plus, RotateCcw, ShieldOff, SquarePen } from 'lucide-react'

import type { Column } from '@/components/data/DataTable'
import { ConfirmDialog } from '@/components/data/ConfirmDialog'
import { ActiveBadge, PrescriptionBadge, ScheduleBadge } from '@/components/data/StatusBadges'
import { Button } from '@/components/ui/button'
import { NativeSelect } from '@/components/ui/native-select'
import { RoleGate } from '@/components/auth/RoleGate'
import { useCreate, useDelete, useReactivate, useUpdate } from '@/hooks/useMutation'
import { useResourceScreen } from '@/features/shared/useResourceScreen'
import { ResourceListView } from '@/features/shared/ResourceListView'
import { dosageFormLabel, dosageFormOptions, scheduleClassOptions } from '@/features/shared/labels'
import { MedicineForm } from './MedicineForm'
import {
  toCreateMedicineRequest,
  toUpdateMedicineRequest,
  type MedicineFormValues,
} from './medicine.schema'
import type { DosageForm, MedicineResponse, ScheduleClass } from '@/types/api'

/**
 * The medicine catalogue.
 *
 * Read access is CASHIER+ and write access is PHARMACY_MANAGER+, both enforced by
 * the backend. `RoleGate` hides the write controls from a cashier rather than
 * showing buttons that would answer 403 - see that component for why hiding is
 * the safe direction.
 */
export function MedicinesPage() {
  const navigate = useNavigate()
  const [formOpen, setFormOpen] = useState(false)
  const [editing, setEditing] = useState<MedicineResponse | null>(null)
  const [deactivating, setDeactivating] = useState<MedicineResponse | null>(null)
  const [dosageFormFilter, setDosageFormFilter] = useState<DosageForm | ''>('')
  const [scheduleFilter, setScheduleFilter] = useState<ScheduleClass | ''>('')

  const screen = useResourceScreen<MedicineResponse>('/medicines', {
    filters: {
      dosageForm: dosageFormFilter || undefined,
      scheduleClass: scheduleFilter || undefined,
    },
  })
  const list = screen.list

  const { create, isPending: isCreating } = useCreate<ReturnType<typeof toCreateMedicineRequest>, MedicineResponse>(
    '/medicines',
    { successMessage: 'Medicine added', onSuccess: () => setFormOpen(false) },
  )
  const { update, isPending: isUpdating } = useUpdate<ReturnType<typeof toUpdateMedicineRequest>, MedicineResponse>(
    '/medicines',
    { successMessage: 'Medicine updated', onSuccess: () => setFormOpen(false) },
  )
  const { remove, isPending: isDeleting } = useDelete<void>('/medicines', {
    successMessage: 'Medicine deactivated',
    onSuccess: () => {
      setDeactivating(null)
      // The row is gone (or has changed status) as far as the server is
      // concerned. Without this the table keeps showing a record that no
      // longer exists in the current filter, which reads as the delete failing.
      list.reload()
    },
  })
  const { reactivate, isPending: isReactivating } = useReactivate('/medicines', list.reload)

  const columns: Column<MedicineResponse>[] = [
    {
      key: 'name',
      header: 'Medicine',
      cell: (row) => (
        <div>
          <p className="font-medium">{row.genericName}</p>
          <p className="text-xs text-muted-foreground">
            {[row.brandName, row.strength].filter(Boolean).join(' · ') || 'Generic'}
          </p>
        </div>
      ),
    },
    { key: 'dosageForm', header: 'Form', cell: (row) => <span className="text-sm">{dosageFormLabel(row.dosageForm)}</span> },
    {
      key: 'scheduleClass',
      header: 'Schedule',
      cell: (row) => <ScheduleBadge scheduleClass={row.scheduleClass} />,
    },
    {
      key: 'requiresPrescription',
      header: 'Rx',
      cell: (row) => <PrescriptionBadge required={row.requiresPrescription} />,
    },
    {
      key: 'manufacturerName',
      header: 'Manufacturer',
      cell: (row) => row.manufacturerName ?? <span className="text-muted-foreground">—</span>,
    },
    { key: 'reorderLevel', header: 'Reorder at', align: 'right', cell: (row) => row.reorderLevel },
    { key: 'status', header: 'Status', cell: (row) => <ActiveBadge active={row.isActive} /> },
    {
      key: 'actions',
      header: '',
      align: 'right',
      cell: (row) => (
        <RoleGate
          required="PHARMACY_MANAGER"
          fallback={<span className="text-xs text-muted-foreground">View only</span>}
        >
        <div className="flex justify-end gap-1">
          <Button
            variant="ghost"
            size="sm"
            onClick={() => {
              setEditing(row)
              setFormOpen(true)
            }}
            aria-label={`Edit ${row.genericName}`}
          >
            <SquarePen className="h-4 w-4" aria-hidden />
            Edit
          </Button>
          {row.isActive ? (
            <Button
              variant="ghost"
              size="sm"
              onClick={() => setDeactivating(row)}
              aria-label={`Deactivate ${row.genericName}`}
            >
              <ShieldOff className="h-4 w-4" aria-hidden />
              Deactivate
            </Button>
          ) : (
            <Button
              variant="ghost"
              size="sm"
              disabled={isReactivating}
              onClick={() => void reactivate(row.id).catch(() => undefined)}
              aria-label={`Reactivate ${row.genericName}`}
            >
              <RotateCcw className="h-4 w-4" aria-hidden />
              Reactivate
            </Button>
          )}
        </div>
        </RoleGate>
      ),
    },
  ]

  const hasFilters = screen.hasActiveFilters || Boolean(dosageFormFilter || scheduleFilter)

  return (
    <>
      <ResourceListView
        screen={screen}
        list={list}
        title="Medicines"
        description="The medicine catalogue. Inactive entries stay for stock history."
        columns={columns}
        rowKey={(row) => row.id}
        searchPlaceholder="Search generic or brand name…"
        onRowClick={(row) => navigate(`/medicines/${row.id}`)}
        actions={
          <RoleGate required="PHARMACY_MANAGER">
            <Button
              onClick={() => {
                setEditing(null)
                setFormOpen(true)
              }}
            >
              <Plus className="h-4 w-4" aria-hidden />
              Add medicine
            </Button>
          </RoleGate>
        }
        extraFilters={
          <>
            <NativeSelect
              value={dosageFormFilter}
              onValueChange={(value) => {
                setDosageFormFilter(value as DosageForm | '')
                screen.setPage(0)
              }}
              options={dosageFormOptions}
              placeholder="All forms"
              aria-label="Filter by dosage form"
            />
            <NativeSelect
              value={scheduleFilter}
              onValueChange={(value) => {
                setScheduleFilter(value as ScheduleClass | '')
                screen.setPage(0)
              }}
              options={scheduleClassOptions}
              placeholder="All schedules"
              aria-label="Filter by schedule class"
            />
          </>
        }
        emptyTitle={hasFilters ? 'No medicines match' : 'No medicines yet'}
        emptyDescription={
          hasFilters
            ? 'Try a different search, or clear the filters to see the whole catalogue.'
            : 'Add the first medicine to start building the catalogue.'
        }
      />

      <RoleGate required="PHARMACY_MANAGER">
        <MedicineForm
          open={formOpen}
          onOpenChange={setFormOpen}
          mode={editing ? 'edit' : 'create'}
          medicine={editing ?? undefined}
          isPending={isCreating || isUpdating}
          onSubmit={async (values: MedicineFormValues) => {
            if (editing) {
              await update(editing.id, toUpdateMedicineRequest(values))
            } else {
              await create(toCreateMedicineRequest(values))
            }
            setEditing(null)
            list.reload()
          }}
        />
      </RoleGate>

      <ConfirmDialog
        open={deactivating !== null}
        onOpenChange={(open) => {
          if (!open) setDeactivating(null)
        }}
        title="Deactivate medicine?"
        description={
          <>
            <p>
              <strong>{deactivating?.genericName}</strong> will no longer be selectable for new
              purchases or prescriptions.
            </p>
            <p>
              It stays linked to existing stock and history, and can be reactivated at any time.
            </p>
          </>
        }
        confirmLabel="Deactivate"
        isPending={isDeleting}
        onConfirm={() => {
          if (deactivating) void remove(deactivating.id).catch(() => undefined)
        }}
      />
    </>
  )
}
