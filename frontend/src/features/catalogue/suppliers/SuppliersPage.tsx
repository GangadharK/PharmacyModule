import { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { Plus, RotateCcw, ShieldOff, SquarePen } from 'lucide-react'

import type { Column } from '@/components/data/DataTable'
import { ConfirmDialog } from '@/components/data/ConfirmDialog'
import { ActiveBadge } from '@/components/data/StatusBadges'
import { Button } from '@/components/ui/button'
import { RoleGate } from '@/components/auth/RoleGate'
import { useCreate, useDelete, useReactivate, useUpdate } from '@/hooks/useMutation'
import { useResourceScreen } from '@/features/shared/useResourceScreen'
import { ResourceListView } from '@/features/shared/ResourceListView'
import { SupplierForm } from './SupplierForm'
import { toSupplierRequest, type SupplierFormValues } from './supplier.schema'
import type { SupplierResponse } from '@/types/api'

/**
 * The supplier catalogue.
 *
 * Read access is PHARMACIST+ and write access is PHARMACY_MANAGER+. The read
 * restriction is why this sits under a nav item that already requires
 * PHARMACIST - a cashier who navigates straight to the URL is stopped by the
 * route guard and, failing that, by the API.
 */
export function SuppliersPage() {
  const navigate = useNavigate()
  const [formOpen, setFormOpen] = useState(false)
  const [editing, setEditing] = useState<SupplierResponse | null>(null)
  const [deactivating, setDeactivating] = useState<SupplierResponse | null>(null)

  const screen = useResourceScreen<SupplierResponse>('/suppliers')
  const list = screen.list

  const { create, isPending: isCreating } = useCreate<ReturnType<typeof toSupplierRequest>, SupplierResponse>(
    '/suppliers',
    { successMessage: 'Supplier added', onSuccess: () => setFormOpen(false) },
  )
  const { update, isPending: isUpdating } = useUpdate<ReturnType<typeof toSupplierRequest>, SupplierResponse>(
    '/suppliers',
    { successMessage: 'Supplier updated', onSuccess: () => setFormOpen(false) },
  )
  const { remove, isPending: isDeleting } = useDelete<void>('/suppliers', {
    successMessage: 'Supplier deactivated',
    onSuccess: () => {
      setDeactivating(null)
      // The row is gone (or has changed status) as far as the server is
      // concerned. Without this the table keeps showing a record that no
      // longer exists in the current filter, which reads as the delete failing.
      list.reload()
    },
  })
  const { reactivate, isPending: isReactivating } = useReactivate('/suppliers', list.reload)

  const columns: Column<SupplierResponse>[] = [
    {
      key: 'name',
      header: 'Supplier',
      cell: (row) => (
        <div>
          <p className="font-medium">{row.name}</p>
          {row.contactPerson && (
            <p className="text-xs text-muted-foreground">{row.contactPerson}</p>
          )}
        </div>
      ),
    },
    {
      key: 'drugLicenseNo',
      header: 'Drug licence',
      cell: (row) =>
        row.drugLicenseNo ?? <span className="text-muted-foreground">—</span>,
    },
    { key: 'gstNumber', header: 'GSTIN', cell: (row) => row.gstNumber ?? <span className="text-muted-foreground">—</span> },
    {
      key: 'phone',
      header: 'Contact',
      cell: (row) =>
        row.phone ? (
          <a href={`tel:${row.phone}`} className="text-sm underline-offset-2 hover:underline">
            {row.phone}
          </a>
        ) : (
          <span className="text-muted-foreground">—</span>
        ),
    },
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
            aria-label={`Edit ${row.name}`}
          >
            <SquarePen className="h-4 w-4" aria-hidden />
            Edit
          </Button>
          {row.isActive ? (
            <Button
              variant="ghost"
              size="sm"
              onClick={() => setDeactivating(row)}
              aria-label={`Deactivate ${row.name}`}
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
              aria-label={`Reactivate ${row.name}`}
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

  return (
    <>
      <ResourceListView
        screen={screen}
        list={list}
        title="Suppliers"
        description="Pharmaceutical suppliers and their licence details."
        columns={columns}
        rowKey={(row) => row.id}
        searchPlaceholder="Search supplier, licence or contact…"
        onRowClick={(row) => navigate(`/suppliers/${row.id}`)}
        actions={
          <RoleGate required="PHARMACY_MANAGER">
            <Button
              onClick={() => {
                setEditing(null)
                setFormOpen(true)
              }}
            >
              <Plus className="h-4 w-4" aria-hidden />
              Add supplier
            </Button>
          </RoleGate>
        }
        emptyTitle={screen.hasActiveFilters ? 'No suppliers match' : 'No suppliers yet'}
        emptyDescription={
          screen.hasActiveFilters
            ? 'Try a different search, or clear the filters.'
            : 'Add the first supplier to record purchase orders against.'
        }
      />

      <RoleGate required="PHARMACY_MANAGER">
        <SupplierForm
          open={formOpen}
          onOpenChange={setFormOpen}
          mode={editing ? 'edit' : 'create'}
          supplier={editing ?? undefined}
          isPending={isCreating || isUpdating}
          onSubmit={async (values: SupplierFormValues) => {
            const body = toSupplierRequest(values)
            if (editing) await update(editing.id, body)
            else await create(body)
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
        title="Deactivate supplier?"
        description={
          <>
            <p>
              <strong>{deactivating?.name}</strong> will no longer be selectable on a purchase
              order.
            </p>
            <p>Past purchase orders and invoice history are unaffected.</p>
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
