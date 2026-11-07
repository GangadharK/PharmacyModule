import { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { Plus, SquarePen, Trash2 } from 'lucide-react'

import type { Column } from '@/components/data/DataTable'
import { ConfirmDialog } from '@/components/data/ConfirmDialog'
import { Button } from '@/components/ui/button'
import { RoleGate } from '@/components/auth/RoleGate'
import { useCreate, useDelete, useUpdate } from '@/hooks/useMutation'
import { useResourceScreen } from '@/features/shared/useResourceScreen'
import { ResourceListView } from '@/features/shared/ResourceListView'
import { ManufacturerForm } from './ManufacturerForm'
import {
  toManufacturerRequest,
  type ManufacturerFormValues,
} from './manufacturer.schema'
import type { ManufacturerResponse } from '@/types/api'

/**
 * The manufacturer catalogue.
 *
 * **Manufacturers are hard-deleted, unlike every other catalogue resource.**
 * A medicine holds a plain `manufacturer_id` with `ON DELETE SET NULL`, so
 * removing one silently detaches it from every medicine that referenced it - the
 * row survives, but the link and the manufacturer name on the medicine are gone,
 * and there is no way to tell afterwards which medicine was made by whom. The
 * server refuses the delete while any medicine still references the
 * manufacturer, and this screen surfaces that refusal rather than working around
 * it.
 *
 * There is also no active/inactive filter here, because there is no
 * `isActive` column and no reactivate endpoint on this resource. Showing the
 * status control anyway would be a control that does nothing.
 */
export function ManufacturersPage() {
  const navigate = useNavigate()
  const [formOpen, setFormOpen] = useState(false)
  const [editing, setEditing] = useState<ManufacturerResponse | null>(null)
  const [deleting, setDeleting] = useState<ManufacturerResponse | null>(null)

  // No status filter: this resource is hard-deleted and has no isActive column.
  const screen = useResourceScreen<ManufacturerResponse>('/manufacturers', { statusFilter: false })
  const list = screen.list

  const { create, isPending: isCreating } = useCreate<
    ReturnType<typeof toManufacturerRequest>,
    ManufacturerResponse
  >('/manufacturers', { successMessage: 'Manufacturer added', onSuccess: () => setFormOpen(false) })
  const { update, isPending: isUpdating } = useUpdate<
    ReturnType<typeof toManufacturerRequest>,
    ManufacturerResponse
  >('/manufacturers', { successMessage: 'Manufacturer updated', onSuccess: () => setFormOpen(false) })
  const { remove, isPending: isDeleting } = useDelete<void>('/manufacturers', {
    successMessage: 'Manufacturer deleted',
    onSuccess: () => {
      setDeleting(null)
      // The row is gone (or has changed status) as far as the server is
      // concerned. Without this the table keeps showing a record that no
      // longer exists in the current filter, which reads as the delete failing.
      list.reload()
    },
  })

  const columns: Column<ManufacturerResponse>[] = [
    {
      key: 'name',
      header: 'Manufacturer',
      cell: (row) => <span className="font-medium">{row.name}</span>,
    },
    {
      key: 'licenseNumber',
      header: 'Licence number',
      cell: (row) => row.licenseNumber ?? <span className="text-muted-foreground">—</span>,
    },
    {
      key: 'country',
      header: 'Country',
      cell: (row) => row.country ?? <span className="text-muted-foreground">—</span>,
    },
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
            aria-label={`Edit ${row.name}`}
          >
            <SquarePen className="h-4 w-4" aria-hidden />
            Edit
          </Button>
          <Button
            variant="ghost"
            size="sm"
            onClick={() => setDeleting(row)}
            aria-label={`Delete ${row.name}`}
          >
            <Trash2 className="h-4 w-4" aria-hidden />
            Delete
          </Button>
        </div>
        </RoleGate>
      ),
    },
  ]

  return (
    <>
      <ResourceListView
        screen={screen}
        list={list}
        title="Manufacturers"
        description="Drug manufacturers. A manufacturer cannot be deleted while a medicine still references it."
        columns={columns}
        rowKey={(row) => row.id}
        searchPlaceholder="Search manufacturer…"
        onRowClick={(row) => navigate(`/manufacturers/${row.id}`)}
        actions={
          <RoleGate required="PHARMACY_MANAGER">
            <Button
              onClick={() => {
                setEditing(null)
                setFormOpen(true)
              }}
            >
              <Plus className="h-4 w-4" aria-hidden />
              Add manufacturer
            </Button>
          </RoleGate>
        }
        emptyTitle={screen.hasActiveFilters ? 'No manufacturers match' : 'No manufacturers yet'}
        emptyDescription={
          screen.hasActiveFilters
            ? 'Try a different search.'
            : 'Add manufacturers so medicines can be linked to the company that makes them.'
        }
      />

      <RoleGate required="PHARMACY_MANAGER">
        <ManufacturerForm
          open={formOpen}
          onOpenChange={setFormOpen}
          mode={editing ? 'edit' : 'create'}
          manufacturer={editing ?? undefined}
          isPending={isCreating || isUpdating}
          onSubmit={async (values: ManufacturerFormValues) => {
            const body = toManufacturerRequest(values)
            if (editing) await update(editing.id, body)
            else await create(body)
            setEditing(null)
            list.reload()
          }}
        />
      </RoleGate>

      <ConfirmDialog
        open={deleting !== null}
        onOpenChange={(open) => {
          if (!open) setDeleting(null)
        }}
        title="Delete manufacturer?"
        description={
          <>
            <p>
              <strong>{deleting?.name}</strong> will be removed permanently.
            </p>
            <p>
              If any medicine still references it the server will refuse, rather than detaching
              the link and leaving the medicine with no manufacturer on record.
            </p>
          </>
        }
        confirmLabel="Delete permanently"
        isPending={isDeleting}
        onConfirm={() => {
          if (deleting) void remove(deleting.id).catch(() => undefined)
        }}
      />
    </>
  )
}
