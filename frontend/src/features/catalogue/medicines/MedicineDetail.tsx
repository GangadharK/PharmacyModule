import { useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { ArrowLeft, RotateCcw, ShieldOff, SquarePen } from 'lucide-react'

import { PageHeader } from '@/components/layout/PageHeader'
import { Card, CardContent } from '@/components/ui/card'
import { Button } from '@/components/ui/button'
import { DescriptionList } from '@/components/data/DescriptionList'
import { ConfirmDialog } from '@/components/data/ConfirmDialog'
import { ActiveBadge, PrescriptionBadge, ScheduleBadge } from '@/components/data/StatusBadges'
import { RoleGate } from '@/components/auth/RoleGate'
import { useResourceItem } from '@/hooks/useResource'
import { useDelete, useReactivate, useUpdate } from '@/hooks/useMutation'
import { DetailSkeleton, ErrorState, NotFoundState } from '@/features/shared/ResourceStates'
import { dosageFormLabel, scheduleLabel } from '@/features/shared/labels'
import { formatTimestamp } from '@/features/shared/format'
import { MedicineForm } from './MedicineForm'
import { toUpdateMedicineRequest, type MedicineFormValues } from './medicine.schema'
import type { MedicineResponse } from '@/types/api'

/** A single medicine, by id. */
export function MedicineDetail() {
  const { id } = useParams<{ id: string }>()
  const navigate = useNavigate()
  const { item, isLoading, error, notFound, reload } = useResourceItem<MedicineResponse>(
    '/medicines',
    id,
  )
  const [editing, setEditing] = useState(false)
  const [deactivating, setDeactivating] = useState(false)

  const { update, isPending: isUpdating } = useUpdate<
    ReturnType<typeof toUpdateMedicineRequest>,
    MedicineResponse
  >('/medicines', { successMessage: 'Medicine updated', onSuccess: () => setEditing(false) })
  const { remove, isPending: isDeleting } = useDelete<void>('/medicines', {
    successMessage: 'Medicine deactivated',
    onSuccess: () => {
      setDeactivating(false)
      reload()
    },
  })
  const { reactivate, isPending: isReactivating } = useReactivate('/medicines', reload)

  if (isLoading) return <DetailSkeleton />
  if (notFound) {
    return <NotFoundState what="medicine" onBack={() => navigate('/medicines')} />
  }
  if (error || !item) {
    return (
      <ErrorState
        message={error ?? 'This medicine could not be loaded.'}
        onRetry={reload}
        onBack={() => navigate('/medicines')}
      />
    )
  }

  return (
    <>
      <PageHeader
        title={item.genericName}
        description={[item.brandName, item.strength].filter(Boolean).join(' · ') || 'Generic'}
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
              <Link to="/medicines">
                <ArrowLeft className="h-4 w-4" aria-hidden />
                Back
              </Link>
            </Button>
          </>
        }
      />

      <div className="mb-4 flex flex-wrap items-center gap-2">
        <ScheduleBadge scheduleClass={item.scheduleClass} />
        <PrescriptionBadge required={item.requiresPrescription} />
        <ActiveBadge active={item.isActive} />
      </div>

      <Card>
        <CardContent className="pt-6">
          <DescriptionList
            items={[
              { label: 'Generic name', value: item.genericName },
              { label: 'Brand name', value: item.brandName },
              { label: 'Strength', value: item.strength },
              { label: 'Dosage form', value: dosageFormLabel(item.dosageForm) },
              { label: 'Schedule class', value: scheduleLabel(item.scheduleClass) },
              {
                label: 'Prescription',
                value: item.requiresPrescription ? 'Required' : 'Not required',
              },
              { label: 'Manufacturer', value: item.manufacturerName, fallback: 'Not linked' },
              { label: 'Reorder level', value: item.reorderLevel },
              { label: 'Created', value: formatTimestamp(item.createdAt) },
              { label: 'Last updated', value: formatTimestamp(item.updatedAt) },
            ]}
          />
        </CardContent>
      </Card>

      <RoleGate required="PHARMACY_MANAGER">
        <MedicineForm
          open={editing}
          onOpenChange={setEditing}
          mode="edit"
          medicine={item}
          isPending={isUpdating}
          onSubmit={async (values: MedicineFormValues) => {
            await update(item.id, toUpdateMedicineRequest(values))
            reload()
          }}
        />

        <ConfirmDialog
          open={deactivating}
          onOpenChange={setDeactivating}
          title="Deactivate medicine?"
          description={
            <p>
              <strong>{item.genericName}</strong> will no longer be selectable for new purchases
              or prescriptions. Existing stock and history are unaffected.
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
