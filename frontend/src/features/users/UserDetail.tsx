import { useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { ArrowLeft, RotateCcw, ShieldOff, SquarePen } from 'lucide-react'

import { PageHeader } from '@/components/layout/PageHeader'
import { Card, CardContent } from '@/components/ui/card'
import { Button } from '@/components/ui/button'
import { DescriptionList } from '@/components/data/DescriptionList'
import { ConfirmDialog } from '@/components/data/ConfirmDialog'
import { ActiveBadge } from '@/components/data/StatusBadges'
import { useResourceItem } from '@/hooks/useResource'
import { useDelete, useReactivate, useUpdate } from '@/hooks/useMutation'
import { RoleBadge } from '@/features/shared/labels'
import { DetailSkeleton, ErrorState, NotFoundState } from '@/features/shared/ResourceStates'
import { UserForm } from './UserForm'
import type { UpdateUserRequest, UserResponse } from '@/types/api'
import { formatTimestamp } from '@/features/shared/format'
import { toUpdateUserRequest, type UserFormValues } from './user.schema'

/**
 * A single user, by id.
 *
 * The "get specific" half of the feature, and the reason list rows navigate
 * here rather than opening an inline panel: a URL means a user record can be
 * linked, bookmarked and reloaded, and it matches the REST surface the backend
 * already provides.
 */
export function UserDetail() {
  const { id } = useParams<{ id: string }>()
  const navigate = useNavigate()
  const { item, isLoading, error, notFound, reload } = useResourceItem<UserResponse>(
    '/users',
    id,
  )
  const [editing, setEditing] = useState(false)
  const [deactivating, setDeactivating] = useState(false)

  const { update, isPending: isUpdating } = useUpdate<UpdateUserRequest, UserResponse>('/users', {
    successMessage: 'User updated',
    onSuccess: () => setEditing(false),
  })
  const { remove, isPending: isDeleting } = useDelete<void>('/users', {
    successMessage: 'User deactivated',
    onSuccess: () => {
      setDeactivating(false)
      // The user is still readable after deactivation, so the detail view stays
      // put and shows the new status rather than bouncing back to the list.
      reload()
    },
  })
  const { reactivate, isPending: isReactivating } = useReactivate('/users', reload)

  if (isLoading) return <DetailSkeleton />
  if (notFound) {
    return <NotFoundState what="user" onBack={() => navigate('/settings/users')} />
  }
  if (error || !item) {
    return (
      <ErrorState
        message={error ?? 'This user could not be loaded.'}
        onRetry={reload}
        onBack={() => navigate('/settings/users')}
      />
    )
  }

  return (
    <>
      <PageHeader
        title={item.fullName}
        description={`@${item.username}`}
        actions={
          <>
            <Button variant="outline" onClick={() => setEditing(true)}>
              <SquarePen className="h-4 w-4" aria-hidden />
              Edit
            </Button>
            {item.active ? (
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
            <Button variant="ghost" asChild>
              <Link to="/settings/users">
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
              { label: 'Username', value: item.username },
              { label: 'Full name', value: item.fullName },
              { label: 'Role', value: <RoleBadge role={item.role} /> },
              { label: 'Status', value: <ActiveBadge active={item.active} /> },
              { label: 'Created', value: formatTimestamp(item.createdAt) },
              { label: 'Last sign-in', value: item.lastLoginAt ? formatTimestamp(item.lastLoginAt) : undefined, fallback: 'Never signed in' },
            ]}
          />
        </CardContent>
      </Card>

      <UserForm
        open={editing}
        onOpenChange={setEditing}
        mode="edit"
        user={item}
        isPending={isUpdating}
        onSubmit={async (values: UserFormValues) => {
          await update(item.id, toUpdateUserRequest(values))
          reload()
        }}
      />

      <ConfirmDialog
        open={deactivating}
        onOpenChange={setDeactivating}
        title="Deactivate user?"
        description={
          <p>
            <strong>{item.fullName}</strong> will no longer be able to sign in. The account is kept
            and can be reactivated later.
          </p>
        }
        confirmLabel="Deactivate"
        isPending={isDeleting}
        onConfirm={() => void remove(item.id).catch(() => undefined)}
      />
    </>
  )
}
