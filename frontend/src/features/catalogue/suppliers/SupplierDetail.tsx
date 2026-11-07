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
import { formatTimestamp } from '@/features/shared/format'
import { SupplierForm } from './SupplierForm'
import { toSupplierRequest, type SupplierFormValues } from './supplier.schema'
import type { SupplierResponse } from '@/types/api'

/** A single supplier, by id. */
export function SupplierDetail() {
  const { id } = useParams<{ id: string }>()
  const navigate = useNavigate()
  const { item, isLoading, error, notFound, reload } = useResourceItem<SupplierResponse>(
    '/suppliers',
    id,
  )
  const [editing, setEditing] = useState(false)
  const [deactivating, setDeactivating] = useState(false)

  const { update, isPending: isUpdating } = useUpdate<ReturnType<typeof toSupplierRequest>, SupplierResponse>(
    '/suppliers',
    { successMessage: 'Supplier updated', onSuccess: () => setEditing(false) },
  )
  const { remove, isPending: isDeleting } = useDelete<void>('/suppliers', {
    successMessage: 'Supplier deactivated',
    onSuccess: () => {
      setDeactivating(false)
      reload()
    },
  })
  const { reactivate, isPending: isReactivating } = useReactivate('/suppliers', reload)

  if (isLoading) return <DetailSkeleton />
  if (notFound) {
    return <NotFoundState what="supplier" onBack={() => navigate('/suppliers')} />
  }
  if (error || !item) {
    return (
      <ErrorState
        message={error ?? 'This supplier could not be loaded.'}
        onRetry={reload}
        onBack={() => navigate('/suppliers')}
      />
    )
  }

  return (
    <>
      <PageHeader
        title={item.name}
        description={item.drugLicenseNo ? `Drug licence ${item.drugLicenseNo}` : 'No drug licence recorded'}
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
              <Link to="/suppliers">
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
              { label: 'Drug licence number', value: item.drugLicenseNo },
              { label: 'GSTIN', value: item.gstNumber },
              { label: 'Contact person', value: item.contactPerson },
              {
                label: 'Phone',
                value: item.phone ? (
                  <a href={`tel:${item.phone}`} className="underline-offset-2 hover:underline">
                    {item.phone}
                  </a>
                ) : undefined,
              },
              {
                label: 'Email',
                value: item.email ? (
                  <a href={`mailto:${item.email}`} className="underline-offset-2 hover:underline">
                    {item.email}
                  </a>
                ) : undefined,
              },
              { label: 'Address', value: item.address, wide: true },
              { label: 'Created', value: formatTimestamp(item.createdAt) },
              { label: 'Last updated', value: formatTimestamp(item.updatedAt) },
            ]}
          />
        </CardContent>
      </Card>

      <RoleGate required="PHARMACY_MANAGER">
        <SupplierForm
          open={editing}
          onOpenChange={setEditing}
          mode="edit"
          supplier={item}
          isPending={isUpdating}
          onSubmit={async (values: SupplierFormValues) => {
            await update(item.id, toSupplierRequest(values))
            reload()
          }}
        />

        <ConfirmDialog
          open={deactivating}
          onOpenChange={setDeactivating}
          title="Deactivate supplier?"
          description={
            <p>
              <strong>{item.name}</strong> will no longer be selectable on a purchase order. Past
              purchase orders are unaffected.
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
