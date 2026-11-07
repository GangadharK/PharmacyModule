import { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { Plus, RotateCcw, ShieldOff, SquarePen } from 'lucide-react'

import type { Column } from '@/components/data/DataTable'
import { ConfirmDialog } from '@/components/data/ConfirmDialog'
import { ActiveBadge } from '@/components/data/StatusBadges'
import { Button } from '@/components/ui/button'
import { NativeSelect } from '@/components/ui/native-select'
import { useCreate, useDelete, useReactivate, useUpdate } from '@/hooks/useMutation'
import { useResourceScreen } from '@/features/shared/useResourceScreen'
import { ResourceListView } from '@/features/shared/ResourceListView'
import { RoleBadge, roleOptions } from '@/features/shared/labels'
import { UserForm } from './UserForm'
import { toCreateUserRequest, toUpdateUserRequest, type UserFormValues } from './user.schema'
import { formatTimestamp } from '@/features/shared/format'
import type { CreateUserRequest, Role, UpdateUserRequest, UserResponse } from '@/types/api'

/**
 * Staff user list, super admin only - the backend's `@PreAuthorize` and the
 * route guard both enforce that, so there is no third check here.
 */
export function UsersPage() {
  const navigate = useNavigate()
  const [editing, setEditing] = useState<UserResponse | null>(null)
  const [formOpen, setFormOpen] = useState(false)
  const [deactivating, setDeactivating] = useState<UserResponse | null>(null)
  const [roleFilter, setRoleFilter] = useState<Role | ''>('')

  const screen = useResourceScreen<UserResponse>('/users', {
    // This endpoint's status filter is `active`, not `activeOnly`.
    statusParam: 'active',
    filters: { role: roleFilter || undefined },
  })
  const list = screen.list

  const { create, isPending: isCreating } = useCreate<CreateUserRequest, UserResponse>('/users', {
    successMessage: 'User created',
    onSuccess: () => {
      setFormOpen(false)
      list.reload()
    },
  })

  const { update, isPending: isUpdating } = useUpdate<UpdateUserRequest, UserResponse>('/users', {
    successMessage: 'User updated',
    onSuccess: () => {
      setFormOpen(false)
      setEditing(null)
      list.reload()
    },
  })

  const { remove, isPending: isDeleting } = useDelete<void>('/users', {
    successMessage: 'User deactivated',
    onSuccess: () => {
      setDeactivating(null)
      // The row is gone (or has changed status) as far as the server is
      // concerned. Without this the table keeps showing a record that no
      // longer exists in the current filter, which reads as the delete failing.
      list.reload()
    },
  })

  const { reactivate, isPending: isReactivating } = useReactivate('/users', list.reload)

  const columns: Column<UserResponse>[] = [
    {
      key: 'fullName',
      header: 'Name',
      cell: (row) => (
        <div>
          <p className="font-medium">{row.fullName}</p>
          <p className="text-xs text-muted-foreground">{row.username}</p>
        </div>
      ),
    },
    { key: 'role', header: 'Role', cell: (row) => <RoleBadge role={row.role} /> },
    { key: 'status', header: 'Status', cell: (row) => <ActiveBadge active={row.active} /> },
    {
      key: 'lastLoginAt',
      header: 'Last sign-in',
      cell: (row) =>
        row.lastLoginAt ? (
          <span className="text-sm">{formatTimestamp(row.lastLoginAt)}</span>
        ) : (
          <span className="text-sm text-muted-foreground">Never</span>
        ),
    },
    {
      key: 'actions',
      header: '',
      align: 'right',
      cell: (row) => (
        <div className="flex justify-end gap-1">
          <Button
            variant="ghost"
            size="sm"
            onClick={() => {
              setEditing(row)
              setFormOpen(true)
            }}
            aria-label={`Edit ${row.fullName}`}
          >
            <SquarePen className="h-4 w-4" aria-hidden />
            Edit
          </Button>
          {row.active ? (
            <Button
              variant="ghost"
              size="sm"
              onClick={() => setDeactivating(row)}
              aria-label={`Deactivate ${row.fullName}`}
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
              aria-label={`Reactivate ${row.fullName}`}
            >
              <RotateCcw className="h-4 w-4" aria-hidden />
              Reactivate
            </Button>
          )}
        </div>
      ),
    },
  ]

  return (
    <>
      <ResourceListView
        screen={screen}
        list={list}
        title="Users"
        description="Staff accounts that can sign in to the pharmacy system."
        columns={columns}
        rowKey={(row) => row.id}
        searchPlaceholder="Search name or username…"
        onRowClick={(row) => navigate(`/settings/users/${row.id}`)}
        actions={
          <Button
            onClick={() => {
              setEditing(null)
              setFormOpen(true)
            }}
          >
            <Plus className="h-4 w-4" aria-hidden />
            Add user
          </Button>
        }
        extraFilters={
          <NativeSelect
            value={roleFilter}
            onValueChange={(value) => {
              setRoleFilter(value as Role | '')
              // Same rule as every other filter: a narrowed result set can be
              // shorter than the current page number.
              screen.setPage(0)
            }}
            options={roleOptions}
            placeholder="All roles"
            aria-label="Filter by role"
          />
        }
        emptyTitle={screen.hasActiveFilters || roleFilter ? 'No users match' : 'No users yet'}
        emptyDescription={
          screen.hasActiveFilters || roleFilter
            ? 'Try a different search, or clear the filters to see everyone.'
            : 'Add the first staff account to get started.'
        }
      />

      <UserForm
        open={formOpen}
        onOpenChange={setFormOpen}
        mode={editing ? 'edit' : 'create'}
        user={editing ?? undefined}
        isPending={isCreating || isUpdating}
        onSubmit={async (values: UserFormValues) => {
          if (editing) {
            await update(editing.id, toUpdateUserRequest(values) as UpdateUserRequest)
            return
          }
          await create(toCreateUserRequest(values) as CreateUserRequest)
        }}
      />

      <ConfirmDialog
        open={deactivating !== null}
        onOpenChange={(open) => {
          if (!open) setDeactivating(null)
        }}
        title="Deactivate user?"
        description={
          <>
            <p>
              <strong>{deactivating?.fullName}</strong> ({deactivating?.username}) will no longer
              be able to sign in.
            </p>
            <p>The account is kept, so it can be reactivated without losing history.</p>
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
