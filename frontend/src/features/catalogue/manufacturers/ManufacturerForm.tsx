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
import { Field } from '@/components/forms/Field'
import {
  makeManufacturerSchema,
  type ManufacturerFormValues,
} from './manufacturer.schema'
import type { ManufacturerResponse } from '@/types/api'

const EMPTY: ManufacturerFormValues = { name: '', licenseNumber: '', country: '' }

interface ManufacturerFormProps {
  open: boolean
  onOpenChange: (open: boolean) => void
  mode: 'create' | 'edit'
  manufacturer?: ManufacturerResponse
  isPending: boolean
  onSubmit: (values: ManufacturerFormValues) => Promise<void>
}

export function ManufacturerForm({
  open,
  onOpenChange,
  mode,
  manufacturer,
  isPending,
  onSubmit,
}: ManufacturerFormProps) {
  const isEdit = mode === 'edit'

  const form = useForm<ManufacturerFormValues>({
    resolver: zodResolver(makeManufacturerSchema()),
    defaultValues: EMPTY,
    mode: 'onBlur',
  })
  const { register, handleSubmit, reset, formState } = form
  const { errors, isSubmitting } = formState

  useEffect(() => {
    if (!open) return
    reset(
      isEdit && manufacturer
        ? {
            name: manufacturer.name,
            licenseNumber: manufacturer.licenseNumber ?? '',
            country: manufacturer.country ?? '',
          }
        : EMPTY,
    )
  }, [open, isEdit, manufacturer, reset])

  const busy = isSubmitting || isPending

  return (
    <Dialog open={open} onOpenChange={busy ? undefined : onOpenChange}>
      <DialogContent className="max-w-lg">
        <DialogHeader>
          <DialogTitle>{isEdit ? 'Edit manufacturer' : 'Add manufacturer'}</DialogTitle>
          <DialogDescription>
            A manufacturer can only be deleted while no medicine references it.
          </DialogDescription>
        </DialogHeader>

        <form onSubmit={handleSubmit(onSubmit)} className="space-y-4">
          <Field
            id="mfr-name"
            label="Name"
            error={errors.name?.message}
            required
            description="Case-insensitively unique. Two manufacturers cannot differ only by case."
          >
            <Input {...register('name')} disabled={busy} placeholder="e.g. Sun Pharmaceutical" />
          </Field>

          <Field id="mfr-licenseNumber" label="Licence number" error={errors.licenseNumber?.message}>
            <Input {...register('licenseNumber')} disabled={busy} placeholder="e.g. MFG/2001/1234" />
          </Field>

          <Field id="mfr-country" label="Country" error={errors.country?.message}>
            <Input {...register('country')} disabled={busy} placeholder="e.g. India" />
          </Field>

          <DialogFooter>
            <Button type="button" variant="outline" onClick={() => onOpenChange(false)} disabled={busy}>
              Cancel
            </Button>
            <Button type="submit" disabled={busy}>
              {busy && <Loader2 className="h-4 w-4 animate-spin motion-reduce:animate-none" aria-hidden />}
              {isEdit ? 'Save changes' : 'Add manufacturer'}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  )
}
