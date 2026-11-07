import { useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { ArrowLeft, RotateCcw, ShieldOff, SquarePen } from 'lucide-react'

import { PageHeader } from '@/components/layout/PageHeader'
import { Card, CardContent } from '@/components/ui/card'
import { Button } from '@/components/ui/button'
import { DescriptionList } from '@/components/data/DescriptionList'
import { ConfirmDialog } from '@/components/data/ConfirmDialog'
import { ActiveBadge } from '@/components/data/StatusBadges'
import { RoleGate } from '@/components/auth/RoleGate'
import { useResourceItem } from '@/hooks/useResource'
import { useDelete, useReactivate, useUpdate } from '@/hooks/useMutation'
import { DetailSkeleton, ErrorState, NotFoundState } from '@/features/shared/ResourceStates'
import { itemCategoryLabel, itemUnitLabel } from '@/features/shared/labels'
import { formatTimestamp } from '@/features/shared/format'
import { NonMedicalItemForm } from './NonMedicalItemForm'
import {
  toUpdateNonMedicalItemRequest,
  type NonMedicalItemFormValues,
} from './item.schema'
import type { NonMedicalItemResponse } from '@/types/api'

/** A single non-medical item, by id. */
export function NonMedicalItemDetail() {
  const { id } = useParams<{ id: string }>()
  const navigate = useNavigate()
  const { item, isLoading, error, notFound, reload } = useResourceItem<NonMedicalItemResponse>(
    '/items',
    id,
  )
  const [editing, setEditing] = useState(false)
  const [deactivating, setDeactivating] = useState(false)

  const { update, isPending: isUpdating } = useUpdate<
    ReturnType<typeof toUpdateNonMedicalItemRequest>,
    NonMedicalItemResponse
  >('/items', { successMessage: 'Item updated', onSuccess: () => setEditing(false) })
  const { remove, isPending: isDeleting } = useDelete<void>('/items', {
    successMessage: 'Item deactivated',
    onSuccess: () => {
      setDeactivating(false)
      reload()
    },
  })
  const { reactivate, isPending: isReactivating } = useReactivate('/items', reload)

  if (isLoading) return <DetailSkeleton rows={5} />
  if (notFound) return <NotFoundState what="item" onBack={() => navigate('/non-medical-items')} />
  if (error || !item) {
    return (
      <ErrorState
        message={error ?? 'This item could not be loaded.'}
        onRetry={reload}
        onBack={() => navigate('/non-medical-items')}
      />
    )
  }

  return (
    <>
      <PageHeader
        title={item.name}
        description={`${itemCategoryLabel(item.category)} · sold per ${itemUnitLabel(item.unit)}`}
        actions={
          <>
            <RoleGate required="PHARMACY_MANAGER">
              <Button variant="outline" onClick={() => setEditing(true)}>
                <SquarePen className="h-4 w-4" aria-hidden />
                Edit
              </Button>
              {item.isActive ? (
                <Button variant="outline" onClick={() => setDeactivating(true)}>
                  <ShieldOff className="h-4 w-4" aria-hidden />
                  Deactivate
                </Button>
              ) : (
                <Button
                  variant="outline"
                  disabled={isReactivating}
                  onClick={() => void reactivate(item.id).catch(() => undefined)}
                >
                  <RotateCcw className="h-4 w-4" aria-hidden />
                  Reactivate
                </Button>
              )}
            </RoleGate>
            <Button variant="ghost" asChild>
              <Link to="/non-medical-items">
                <ArrowLeft className="h-4 w-4" aria-hidden />
                Back
              </Link>
            </Button>
          </>
        }
      />

      <div className="mb-4">
        <ActiveBadge active={item.isActive} />
      </div>

      <Card>
        <CardContent className="pt-6">
          <DescriptionList
            items={[
              { label: 'Name', value: item.name },
              { label: 'Category', value: itemCategoryLabel(item.category) },
              { label: 'Selling unit', value: itemUnitLabel(item.unit) },
              { label: 'Reorder level', value: item.reorderLevel },
              { label: 'Created', value: formatTimestamp(item.createdAt) },
              { label: 'Last updated', value: formatTimestamp(item.updatedAt) },
            ]}
          />
        </CardContent>
      </Card>

      <RoleGate required="PHARMACY_MANAGER">
        <NonMedicalItemForm
          open={editing}
          onOpenChange={setEditing}
          mode="edit"
          item={item}
          isPending={isUpdating}
          onSubmit={async (values: NonMedicalItemFormValues) => {
            await update(item.id, toUpdateNonMedicalItemRequest(values))
            reload()
          }}
        />

        <ConfirmDialog
          open={deactivating}
          onOpenChange={setDeactivating}
          title="Deactivate item?"
          description={
            <p>
              <strong>{item.name}</strong> will no longer be selectable for new stock. Existing
              stock and history are unaffected.
            </p>
          }
          confirmLabel="Deactivate"
          isPending={isDeleting}
          onConfirm={() => void remove(item.id).catch(() => undefined)}
        />
      </RoleGate>
    </>
  )
}
