import { useEffect } from 'react'
import { useForm } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { Loader2 } from 'lucide-react'

import { Button } from '@/components/ui/button'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import { Input } from '@/components/ui/input'
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select'
import { Field } from '@/components/forms/Field'
import { roleOptions } from '@/features/shared/labels'
import { useAuthStore } from '@/store/auth.store'
import {
  makeUserFormSchema,
  PASSWORD_MAX,
  PASSWORD_MIN,
  type UserFormValues,
} from './user.schema'
import type { Role, UserResponse } from '@/types/api'

/**
 * Create and edit form for a user, in one component.
 *
 * They share every field; the only differences are that `username` is immutable
 * and `password` is optional on edit. Two components would duplicate the field
 * layout, the a11y wiring and the submit path for the sake of those two
 * conditionals, and the shared parts are the parts most likely to drift.
 *
 * The mode selects the validation schema (see `makeUserFormSchema`), so the rules
 * on screen always match what will be sent.
 *
 * `mode` cannot change the resolver reactively on its own, so the form is keyed
 * by mode at the call site - `UserForm` is remounted when switching, which resets
 * the resolver cleanly. The `reset` effect below handles the other case: opening
 * the dialog for a different user.
 */

const EMPTY: UserFormValues = { username: '', fullName: '', password: '', role: 'CASHIER' }

interface UserFormProps {
  open: boolean
  onOpenChange: (open: boolean) => void
  mode: 'create' | 'edit'
  /** The record being edited. Ignored in create mode. */
  user?: UserResponse
  isPending: boolean
  onSubmit: (values: UserFormValues) => Promise<void>
}

export function UserForm({ open, onOpenChange, mode, user, isPending, onSubmit }: UserFormProps) {
  const isEdit = mode === 'edit'
  const currentUserId = useAuthStore((state) => state.user?.id)
  const currentUserRole = useAuthStore((state) => state.user?.role)

  const form = useForm<UserFormValues>({
    resolver: zodResolver(makeUserFormSchema(isEdit)),
    defaultValues: EMPTY,
    mode: 'onBlur',
  })

  const { register, handleSubmit, reset, setValue, watch, formState } = form
  const { errors, isSubmitting } = formState

  useEffect(() => {
    if (!open) return
    reset(
      isEdit
        ? { username: user?.username ?? '', fullName: user?.fullName ?? '', password: '', role: user?.role ?? 'CASHIER' }
        : EMPTY,
    )
  }, [open, isEdit, user, reset])

  /**
   * A super admin demoting themselves would lock the last admin out of user
   * management. The server allows it, so this warns rather than blocking - a
   * second admin may well exist, and silently refusing a legitimate action is
   * worse than a clear warning.
   */
  const isDemotingSelf = isEdit && user?.id === currentUserId && user?.role === 'SUPER_ADMIN'

  const busy = isSubmitting || isPending

  return (
    <Dialog open={open} onOpenChange={busy ? undefined : onOpenChange}>
      <DialogContent className="max-w-lg">
        <DialogHeader>
          <DialogTitle>{isEdit ? 'Edit user' : 'Add user'}</DialogTitle>
          <DialogDescription>
            {isEdit
              ? 'Leave the password blank to keep the current one.'
              : 'The new user can sign in immediately with these details.'}
          </DialogDescription>
        </DialogHeader>

        <form onSubmit={handleSubmit(onSubmit)} className="space-y-4">
          <Field id="user-username" label="Username" error={errors.username?.message} required>
            <Input {...register('username')} disabled={isEdit || busy} autoComplete="off" placeholder="e.g. r.mehta" />
          </Field>
          {isEdit && (
            <p className="-mt-2 text-xs text-muted-foreground">A username cannot be changed once set.</p>
          )}

          <Field id="user-fullName" label="Full name" error={errors.fullName?.message} required>
            <Input {...register('fullName')} disabled={busy} placeholder="e.g. Ritu Mehta" />
          </Field>

          <Field
            id="user-password"
            label={isEdit ? 'New password' : 'Password'}
            error={errors.password?.message}
            description={
              isEdit ? 'Optional. Leave blank to keep the current password.' : `At least ${PASSWORD_MIN} characters.`
            }
            required={!isEdit}
          >
            <Input
              type="password"
              {...register('password')}
              disabled={busy}
              autoComplete="new-password"
              maxLength={PASSWORD_MAX}
            />
          </Field>

          <Field
            id="user-role"
            label="Role"
            error={errors.role?.message}
            required
            description={isDemotingSelf ? 'You are removing your own super admin access.' : undefined}
          >
            <Select
              value={watch('role')}
              onValueChange={(value) => setValue('role', value as Role, { shouldValidate: true })}
              disabled={busy}
            >
              <SelectTrigger id="user-role" aria-label="Role">
                <SelectValue />
              </SelectTrigger>
              <SelectContent>
                {roleOptions.map((option) => (
                  <SelectItem key={option.value} value={option.value}>
                    {option.label}
                    {isDemotingSelf && option.value !== currentUserRole && ' (removes your access)'}
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
          </Field>

          {isDemotingSelf && (
            <p role="alert" className="text-xs font-medium text-destructive">
              You will lose the ability to manage users. Only continue if another super admin exists.
            </p>
          )}

          <DialogFooter>
            <Button type="button" variant="outline" onClick={() => onOpenChange(false)} disabled={busy}>
              Cancel
            </Button>
            <Button type="submit" disabled={busy}>
              {busy && <Loader2 className="h-4 w-4 animate-spin motion-reduce:animate-none" aria-hidden />}
              {isEdit ? 'Save changes' : 'Create user'}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  )
}
