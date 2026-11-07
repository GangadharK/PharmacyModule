import { useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { ArrowLeft, SquarePen, Trash2 } from 'lucide-react'

import { PageHeader } from '@/components/layout/PageHeader'
import { Card, CardContent } from '@/components/ui/card'
import { Button } from '@/components/ui/button'
import { DescriptionList } from '@/components/data/DescriptionList'
import { ConfirmDialog } from '@/components/data/ConfirmDialog'
import { RoleGate } from '@/components/auth/RoleGate'
import { useResourceItem } from '@/hooks/useResource'
import { useDelete, useUpdate } from '@/hooks/useMutation'
import { DetailSkeleton, ErrorState, NotFoundState } from '@/features/shared/ResourceStates'
import { formatTimestamp } from '@/features/shared/format'
import { ManufacturerForm } from './ManufacturerForm'
import { toManufacturerRequest, type ManufacturerFormValues } from './manufacturer.schema'
import type { ManufacturerResponse } from '@/types/api'

/** A single manufacturer, by id. */
export function ManufacturerDetail() {
  const { id } = useParams<{ id: string }>()
  const navigate = useNavigate()
  const { item, isLoading, error, notFound, reload } = useResourceItem<ManufacturerResponse>(
    '/manufacturers',
    id,
  )
  const [editing, setEditing] = useState(false)
  const [deleting, setDeleting] = useState(false)

  const { update, isPending: isUpdating } = useUpdate<
    ReturnType<typeof toManufacturerRequest>,
    ManufacturerResponse
  >('/manufacturers', { successMessage: 'Manufacturer updated', onSuccess: () => setEditing(false) })
  const { remove, isPending: isDeleting } = useDelete<void>('/manufacturers', {
    successMessage: 'Manufacturer deleted',
    onSuccess: () => {
      // A successful hard delete removes the row, so the detail page has nothing
      // left to show and would sit on a 404 it caused itself.
      navigate('/manufacturers')
    },
  })

  if (isLoading) return <DetailSkeleton rows={4} />
  if (notFound) {
    return <NotFoundState what="manufacturer" onBack={() => navigate('/manufacturers')} />
  }
  if (error || !item) {
    return (
      <ErrorState
        message={error ?? 'This manufacturer could not be loaded.'}
        onRetry={reload}
        onBack={() => navigate('/manufacturers')}
      />
    )
  }

  return (
    <>
      <PageHeader
        title={item.name}
        description={item.country ? `Made in ${item.country}` : undefined}
        actions={
          <>
            <RoleGate required="PHARMACY_MANAGER">
              <Button variant="outline" onClick={() => setEditing(true)}>
                <SquarePen className="h-4 w-4" aria-hidden />
                Edit
              </Button>
              <Button variant="outline" onClick={() => setDeleting(true)}>
                <Trash2 className="h-4 w-4" aria-hidden />
                Delete
              </Button>
            </RoleGate>
            <Button variant="ghost" asChild>
              <Link to="/manufacturers">
                <ArrowLeft className="h-4 w-4" aria-hidden />
                Back
              </Link>
            </Button>
          </>
        }
      />

      <Card>
        <CardContent className="pt-6">
          <DescriptionList
            items={[
              { label: 'Name', value: item.name },
              { label: 'Licence number', value: item.licenseNumber },
              { label: 'Country', value: item.country },
              { label: 'Created', value: formatTimestamp(item.createdAt) },
            ]}
          />
        </CardContent>
      </Card>

      <RoleGate required="PHARMACY_MANAGER">
        <ManufacturerForm
          open={editing}
          onOpenChange={setEditing}
          mode="edit"
          manufacturer={item}
          isPending={isUpdating}
          onSubmit={async (values: ManufacturerFormValues) => {
            await update(item.id, toManufacturerRequest(values))
            reload()
          }}
        />

        <ConfirmDialog
          open={deleting}
          onOpenChange={setDeleting}
          title="Delete manufacturer?"
          description={
            <p>
              <strong>{item.name}</strong> will be removed permanently. If any medicine still
              references it the server will refuse.
            </p>
          }
          confirmLabel="Delete permanently"
          isPending={isDeleting}
          onConfirm={() => void remove(item.id).catch(() => undefined)}
        />
      </RoleGate>
    </>
  )
}
