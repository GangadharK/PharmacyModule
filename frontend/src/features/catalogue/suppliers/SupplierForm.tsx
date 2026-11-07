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
import { Textarea } from '@/components/ui/textarea'
import { Field } from '@/components/forms/Field'
import { makeSupplierSchema, type SupplierFormValues } from './supplier.schema'
import type { SupplierResponse } from '@/types/api'

/**
 * Create and edit form for a supplier.
 *
 * All seven fields are optional except the name, in both modes, so one component
 * covers create and edit with no differing rules. The GSTIN field carries the
 * uppercase hint in its description because the server rejects a lowercase GSTIN -
 * a user who does not know that will get a validation error from an API rather
 * than from the form.
 */

const EMPTY: SupplierFormValues = {
  name: '',
  drugLicenseNo: '',
  contactPerson: '',
  phone: '',
  email: '',
  address: '',
  gstNumber: '',
}

interface SupplierFormProps {
  open: boolean
  onOpenChange: (open: boolean) => void
  mode: 'create' | 'edit'
  supplier?: SupplierResponse
  isPending: boolean
  onSubmit: (values: SupplierFormValues) => Promise<void>
}

export function SupplierForm({ open, onOpenChange, mode, supplier, isPending, onSubmit }: SupplierFormProps) {
  const isEdit = mode === 'edit'

  const form = useForm<SupplierFormValues>({
    resolver: zodResolver(makeSupplierSchema()),
    defaultValues: EMPTY,
    mode: 'onBlur',
  })
  const { register, handleSubmit, reset, formState } = form
  const { errors, isSubmitting } = formState

  useEffect(() => {
    if (!open) return
    reset(
      isEdit && supplier
        ? {
            name: supplier.name,
            drugLicenseNo: supplier.drugLicenseNo ?? '',
            contactPerson: supplier.contactPerson ?? '',
            phone: supplier.phone ?? '',
            email: supplier.email ?? '',
            address: supplier.address ?? '',
            gstNumber: supplier.gstNumber ?? '',
          }
        : EMPTY,
    )
  }, [open, isEdit, supplier, reset])

  const busy = isSubmitting || isPending

  return (
    <Dialog open={open} onOpenChange={busy ? undefined : onOpenChange}>
      <DialogContent className="max-w-2xl">
        <DialogHeader>
          <DialogTitle>{isEdit ? 'Edit supplier' : 'Add supplier'}</DialogTitle>
          <DialogDescription>
            Licence and contact details are optional, but a drug licence number is required for
            any supplier of prescription medicine.
          </DialogDescription>
        </DialogHeader>

        <form onSubmit={handleSubmit(onSubmit)} className="space-y-4">
          <div className="grid gap-4 sm:grid-cols-2">
            <Field
              id="sup-name"
              label="Supplier name"
              error={errors.name?.message}
              required
              className="sm:col-span-2"
            >
              <Input {...register('name')} disabled={busy} placeholder="e.g. Aurobindo Pharma" />
            </Field>

            <Field
              id="sup-drugLicenseNo"
              label="Drug licence number"
              error={errors.drugLicenseNo?.message}
            >
              <Input {...register('drugLicenseNo')} disabled={busy} placeholder="e.g. 21/B/2345" />
            </Field>

            <Field
              id="sup-gstNumber"
              label="GSTIN"
              error={errors.gstNumber?.message}
              description="15 uppercase letters and digits."
            >
              <Input
                {...register('gstNumber')}
                disabled={busy}
                placeholder="27AAAAA0000A1Z5"
                maxLength={15}
                className="uppercase"
              />
            </Field>

            <Field
              id="sup-contactPerson"
              label="Contact person"
              error={errors.contactPerson?.message}
            >
              <Input {...register('contactPerson')} disabled={busy} placeholder="e.g. S. Nair" />
            </Field>

            <Field id="sup-phone" label="Phone" error={errors.phone?.message}>
              <Input {...register('phone')} disabled={busy} type="tel" placeholder="e.g. +91 40 1234 5678" />
            </Field>

            <Field id="sup-email" label="Email" error={errors.email?.message}>
              <Input {...register('email')} disabled={busy} type="email" placeholder="e.g. orders@example.com" />
            </Field>

            <Field
              id="sup-address"
              label="Address"
              error={errors.address?.message}
              className="sm:col-span-2"
            >
              <Textarea {...register('address')} disabled={busy} rows={3} />
            </Field>
          </div>

          <DialogFooter>
            <Button type="button" variant="outline" onClick={() => onOpenChange(false)} disabled={busy}>
              Cancel
            </Button>
            <Button type="submit" disabled={busy}>
              {busy && <Loader2 className="h-4 w-4 animate-spin motion-reduce:animate-none" aria-hidden />}
              {isEdit ? 'Save changes' : 'Add supplier'}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  )
}
