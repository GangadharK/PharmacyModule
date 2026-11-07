import { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { Plus, RotateCcw, ShieldOff, SquarePen } from 'lucide-react'

import type { Column } from '@/components/data/DataTable'
import { ConfirmDialog } from '@/components/data/ConfirmDialog'
import { ActiveBadge } from '@/components/data/StatusBadges'
import { Button } from '@/components/ui/button'
import { NativeSelect } from '@/components/ui/native-select'
import { RoleGate } from '@/components/auth/RoleGate'
import { useCreate, useDelete, useReactivate, useUpdate } from '@/hooks/useMutation'
import { useResourceScreen } from '@/features/shared/useResourceScreen'
import { ResourceListView } from '@/features/shared/ResourceListView'
import { itemCategoryLabel, itemCategoryOptions, itemUnitLabel } from '@/features/shared/labels'
import { NonMedicalItemForm } from './NonMedicalItemForm'
import {
  toCreateNonMedicalItemRequest,
  toUpdateNonMedicalItemRequest,
  type NonMedicalItemFormValues,
} from './item.schema'
import type { ItemCategory, NonMedicalItemResponse } from '@/types/api'

/** Consumables and equipment: gloves, syringes, masks. Soft-deletable. */
export function NonMedicalItemsPage() {
  const navigate = useNavigate()
  const [formOpen, setFormOpen] = useState(false)
  const [editing, setEditing] = useState<NonMedicalItemResponse | null>(null)
  const [deactivating, setDeactivating] = useState<NonMedicalItemResponse | null>(null)
  const [categoryFilter, setCategoryFilter] = useState<ItemCategory | ''>('')

  const screen = useResourceScreen<NonMedicalItemResponse>('/items', {
    filters: { category: categoryFilter || undefined },
  })
  const list = screen.list

  const { create, isPending: isCreating } = useCreate<
    ReturnType<typeof toCreateNonMedicalItemRequest>,
    NonMedicalItemResponse
  >('/items', { successMessage: 'Item added', onSuccess: () => setFormOpen(false) })
  const { update, isPending: isUpdating } = useUpdate<
    ReturnType<typeof toUpdateNonMedicalItemRequest>,
    NonMedicalItemResponse
  >('/items', { successMessage: 'Item updated', onSuccess: () => setFormOpen(false) })
  const { remove, isPending: isDeleting } = useDelete<void>('/items', {
    successMessage: 'Item deactivated',
    onSuccess: () => {
      setDeactivating(null)
      // The row is gone (or has changed status) as far as the server is
      // concerned. Without this the table keeps showing a record that no
      // longer exists in the current filter, which reads as the delete failing.
      list.reload()
    },
  })
  const { reactivate, isPending: isReactivating } = useReactivate('/items', list.reload)

  const columns: Column<NonMedicalItemResponse>[] = [
    {
      key: 'name',
      header: 'Item',
      cell: (row) => <span className="font-medium">{row.name}</span>,
    },
    { key: 'category', header: 'Category', cell: (row) => itemCategoryLabel(row.category) },
    {
      key: 'unit',
      header: 'Unit',
      cell: (row) => <span className="text-sm text-muted-foreground">{itemUnitLabel(row.unit)}</span>,
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
        title="Non-medical items"
        description="Consumables and equipment sold alongside medicine."
        columns={columns}
        rowKey={(row) => row.id}
        searchPlaceholder="Search item…"
        onRowClick={(row) => navigate(`/non-medical-items/${row.id}`)}
        actions={
          <RoleGate required="PHARMACY_MANAGER">
            <Button
              onClick={() => {
                setEditing(null)
                setFormOpen(true)
              }}
            >
              <Plus className="h-4 w-4" aria-hidden />
              Add item
            </Button>
          </RoleGate>
        }
        extraFilters={
          <NativeSelect
            value={categoryFilter}
            onValueChange={(value) => {
              setCategoryFilter(value as ItemCategory | '')
              screen.setPage(0)
            }}
            options={itemCategoryOptions}
            placeholder="All categories"
            aria-label="Filter by category"
          />
        }
        emptyTitle={screen.hasActiveFilters || categoryFilter ? 'No items match' : 'No items yet'}
        emptyDescription={
          screen.hasActiveFilters || categoryFilter
            ? 'Try a different search, or clear the filters.'
            : 'Add gloves, syringes or other consumables to track alongside medicine.'
        }
      />

      <RoleGate required="PHARMACY_MANAGER">
        <NonMedicalItemForm
          open={formOpen}
          onOpenChange={setFormOpen}
          mode={editing ? 'edit' : 'create'}
          item={editing ?? undefined}
          isPending={isCreating || isUpdating}
          onSubmit={async (values: NonMedicalItemFormValues) => {
            if (editing) {
              await update(editing.id, toUpdateNonMedicalItemRequest(values))
            } else {
              await create(toCreateNonMedicalItemRequest(values))
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
        title="Deactivate item?"
        description={
          <>
            <p>
              <strong>{deactivating?.name}</strong> will no longer be selectable for new stock.
            </p>
            <p>Existing stock and history are unaffected, and it can be reactivated.</p>
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
