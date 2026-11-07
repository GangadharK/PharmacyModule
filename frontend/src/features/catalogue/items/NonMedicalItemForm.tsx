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
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select'
import { Field } from '@/components/forms/Field'
import { itemCategoryOptions, itemUnitOptions } from '@/features/shared/labels'
import {
  makeNonMedicalItemSchema,
  type NonMedicalItemFormValues,
} from './item.schema'
import type { NonMedicalItemResponse } from '@/types/api'

/**
 * Create and edit form for a non-medical item.
 *
 * The distinguishing behaviour is the two immutable fields. On edit, `category`
 * and `unit` are rendered disabled with an explanation. They are not hidden,
 * because a user who cannot see the unit of the record they are editing cannot
 * tell whether it is right - and a glove recorded as BOX when the shelf holds
 * PAIRs is exactly the kind of error this screen exists to prevent.
 */

const DEFAULT_VALUES: NonMedicalItemFormValues = {
  name: '',
  category: 'CONSUMABLE',
  unit: 'PIECE',
  reorderLevel: 10,
}

interface NonMedicalItemFormProps {
  open: boolean
  onOpenChange: (open: boolean) => void
  mode: 'create' | 'edit'
  item?: NonMedicalItemResponse
  isPending: boolean
  onSubmit: (values: NonMedicalItemFormValues) => Promise<void>
}

export function NonMedicalItemForm({
  open,
  onOpenChange,
  mode,
  item,
  isPending,
  onSubmit,
}: NonMedicalItemFormProps) {
  const isEdit = mode === 'edit'

  const form = useForm<NonMedicalItemFormValues>({
    resolver: zodResolver(makeNonMedicalItemSchema()),
    defaultValues: DEFAULT_VALUES,
    mode: 'onBlur',
  })
  const { register, handleSubmit, reset, setValue, watch, formState } = form
  const { errors, isSubmitting } = formState

  useEffect(() => {
    if (!open) return
    reset(
      isEdit && item
        ? {
            name: item.name,
            category: item.category,
            unit: item.unit,
            reorderLevel: item.reorderLevel,
          }
        : DEFAULT_VALUES,
    )
  }, [open, isEdit, item, reset])

  const busy = isSubmitting || isPending
  const immutableNote = 'Cannot be changed once the item is created.'

  return (
    <Dialog open={open} onOpenChange={busy ? undefined : onOpenChange}>
      <DialogContent className="max-w-lg">
        <DialogHeader>
          <DialogTitle>{isEdit ? 'Edit item' : 'Add non-medical item'}</DialogTitle>
          <DialogDescription>
            Consumables and equipment sold alongside medicine - gloves, syringes, masks.
          </DialogDescription>
        </DialogHeader>

        <form onSubmit={handleSubmit(onSubmit)} className="space-y-4">
          <Field id="item-name" label="Name" error={errors.name?.message} required>
            <Input {...register('name')} disabled={busy} placeholder="e.g. Surgical Gloves" />
          </Field>

          <div className="grid gap-4 sm:grid-cols-2">
            <Field
              id="item-category"
              label="Category"
              error={errors.category?.message}
              required
              description={isEdit ? immutableNote : undefined}
            >
              <Select
                value={watch('category')}
                onValueChange={(value) =>
                  setValue('category', value as NonMedicalItemFormValues['category'], {
                    shouldValidate: true,
                  })
                }
                disabled={isEdit || busy}
              >
                <SelectTrigger id="item-category" aria-label="Category">
                  <SelectValue />
                </SelectTrigger>
                <SelectContent>
                  {itemCategoryOptions.map((option) => (
                    <SelectItem key={option.value} value={option.value}>
                      {option.label}
                    </SelectItem>
                  ))}
                </SelectContent>
              </Select>
            </Field>

            <Field
              id="item-unit"
              label="Selling unit"
              error={errors.unit?.message}
              required
              description={isEdit ? immutableNote : undefined}
            >
              <Select
                value={watch('unit')}
                onValueChange={(value) =>
                  setValue('unit', value as NonMedicalItemFormValues['unit'], {
                    shouldValidate: true,
                  })
                }
                disabled={isEdit || busy}
              >
                <SelectTrigger id="item-unit" aria-label="Selling unit">
                  <SelectValue />
                </SelectTrigger>
                <SelectContent>
                  {itemUnitOptions.map((option) => (
                    <SelectItem key={option.value} value={option.value}>
                      {option.label}
                    </SelectItem>
                  ))}
                </SelectContent>
              </Select>
            </Field>
          </div>

          <Field
            id="item-reorderLevel"
            label="Reorder level"
            error={errors.reorderLevel?.message}
            description="Stock alerts fire at or below this quantity."
          >
            <Input
              type="number"
              min={0}
              step={1}
              {...register('reorderLevel', { valueAsNumber: true })}
              disabled={busy}
            />
          </Field>

          <DialogFooter>
            <Button type="button" variant="outline" onClick={() => onOpenChange(false)} disabled={busy}>
              Cancel
            </Button>
            <Button type="submit" disabled={busy}>
              {busy && <Loader2 className="h-4 w-4 animate-spin motion-reduce:animate-none" aria-hidden />}
              {isEdit ? 'Save changes' : 'Add item'}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  )
}
