import { useEffect, useMemo } from 'react'
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
import { Label } from '@/components/ui/label'
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select'
import { Field } from '@/components/forms/Field'
import { dosageFormOptions, scheduleClassOptions } from '@/features/shared/labels'
import { useResourceList } from '@/hooks/useResource'
import {
  makeMedicineSchema,
  type MedicineFormValues,
} from './medicine.schema'
import type { MedicineResponse } from '@/types/api'

/**
 * Create and edit form for a medicine.
 *
 * Two behaviours here are clinical, not cosmetic:
 *
 * **`scheduleClass` and `requiresPrescription` are both sent, always.** The
 * backend resolves them independently (`resolvedScheduleClass()`,
 * `resolvedRequiresPrescription()`), so a record can be H while
 * `requiresPrescription` is false. The form shows both and warns when they
 * disagree, because a pharmacist scanning a shelf for a controlled drug relies
 * on this row being honest. It is a warning rather than a block - the data may
 * legitimately say H for a pharmacist-only preparation, and the backend allows
 * it. AGENTS.md rule 14 applies: do not guess at behaviour a pharmacist acts on,
 * so the inconsistency is surfaced, not silently resolved.
 *
 * **`dosageForm` is immutable after create**, matching `UpdateMedicineRequest`,
 * which has no such property. On edit it is rendered disabled with an
 * explanation rather than hidden - a hidden field is indistinguishable from a
 * forgotten one, and a pharmacist comparing a tablet against a syrup needs to
 * see which it is.
 */

/**
 * Sentinel for "no manufacturer". Not a UUID - see the onValueChange handler.
 */
const NONE_MANUFACTURER = '__none__'

const DEFAULT_VALUES: MedicineFormValues = {
  genericName: '',
  brandName: '',
  strength: '',
  dosageForm: 'TABLET',
  scheduleClass: 'H',
  requiresPrescription: false,
  manufacturerId: '',
  reorderLevel: 10,
}

interface MedicineFormProps {
  open: boolean
  onOpenChange: (open: boolean) => void
  mode: 'create' | 'edit'
  medicine?: MedicineResponse
  isPending: boolean
  onSubmit: (values: MedicineFormValues) => Promise<void>
}

export function MedicineForm({
  open,
  onOpenChange,
  mode,
  medicine,
  isPending,
  onSubmit,
}: MedicineFormProps) {
  const isEdit = mode === 'edit'

  const form = useForm<MedicineFormValues>({
    resolver: zodResolver(makeMedicineSchema()),
    defaultValues: DEFAULT_VALUES,
    mode: 'onBlur',
  })
  const { register, handleSubmit, reset, setValue, watch, formState } = form
  const { errors, isSubmitting } = formState

  // Manufacturers are a select, not free text. The medicine table has a real FK
  // to `manufacturer`, so a typed name would be rejected - and worse, a
  // misspelling would create a near-duplicate manufacturer downstream.
  const manufacturers = useResourceList<{ id: string; name: string }>(
    '/manufacturers',
    { page: 0, size: 200 },
  )

  useEffect(() => {
    if (!open) return
    reset(
      isEdit && medicine
        ? {
            genericName: medicine.genericName,
            brandName: medicine.brandName ?? '',
            strength: medicine.strength ?? '',
            dosageForm: medicine.dosageForm,
            scheduleClass: medicine.scheduleClass,
            requiresPrescription: medicine.requiresPrescription,
            manufacturerId: medicine.manufacturerId ?? '',
            reorderLevel: medicine.reorderLevel,
          }
        : DEFAULT_VALUES,
    )
  }, [open, isEdit, medicine, reset])

  const scheduleClass = watch('scheduleClass')
  const requiresPrescription = watch('requiresPrescription')

  /**
   * A scheduled drug that is not marked prescription-only is the case worth
   * flagging. The reverse is routine - many OTC products carry an H schedule for
   * pharmacist-record reasons - so only the risky direction is called out.
   */
  const scheduleMismatch = useMemo(
    () => scheduleClass !== 'OTC' && !requiresPrescription,
    [scheduleClass, requiresPrescription],
  )

  const busy = isSubmitting || isPending

  return (
    <Dialog open={open} onOpenChange={busy ? undefined : onOpenChange}>
      <DialogContent className="max-w-2xl">
        <DialogHeader>
          <DialogTitle>{isEdit ? 'Edit medicine' : 'Add medicine'}</DialogTitle>
          <DialogDescription>
            A medicine is identified by its generic name, dosage form and strength together.
          </DialogDescription>
        </DialogHeader>

        <form onSubmit={handleSubmit(onSubmit)} className="space-y-4">
          <div className="grid gap-4 sm:grid-cols-2">
            <Field
              id="med-genericName"
              label="Generic name"
              error={errors.genericName?.message}
              required
            >
              <Input {...register('genericName')} disabled={busy} placeholder="e.g. Amoxicillin" />
            </Field>

            <Field
              id="med-brandName"
              label="Brand name"
              error={errors.brandName?.message}
              description="Optional. Generics-only entries are common."
            >
              <Input {...register('brandName')} disabled={busy} placeholder="e.g. Moxatag" />
            </Field>

            <Field id="med-strength" label="Strength" error={errors.strength?.message}>
              <Input {...register('strength')} disabled={busy} placeholder="e.g. 500 mg" />
            </Field>

            <Field
              id="med-dosageForm"
              label="Dosage form"
              error={errors.dosageForm?.message}
              required
              description={isEdit ? 'Cannot be changed after a medicine is created.' : undefined}
            >
              <Select
                value={watch('dosageForm')}
                onValueChange={(value) =>
                  setValue('dosageForm', value as MedicineFormValues['dosageForm'], {
                    shouldValidate: true,
                  })
                }
                disabled={isEdit || busy}
              >
                <SelectTrigger id="med-dosageForm" aria-label="Dosage form">
                  <SelectValue />
                </SelectTrigger>
                <SelectContent>
                  {dosageFormOptions.map((option) => (
                    <SelectItem key={option.value} value={option.value}>
                      {option.label}
                    </SelectItem>
                  ))}
                </SelectContent>
              </Select>
            </Field>

            <Field id="med-scheduleClass" label="Schedule class" error={errors.scheduleClass?.message}>
              <Select
                value={watch('scheduleClass')}
                onValueChange={(value) =>
                  setValue(
                    'scheduleClass',
                    value as MedicineFormValues['scheduleClass'],
                    { shouldValidate: true },
                  )
                }
                disabled={busy}
              >
                <SelectTrigger id="med-scheduleClass" aria-label="Schedule class">
                  <SelectValue />
                </SelectTrigger>
                <SelectContent>
                  {scheduleClassOptions.map((option) => (
                    <SelectItem key={option.value} value={option.value}>
                      {option.label}
                    </SelectItem>
                  ))}
                </SelectContent>
              </Select>
            </Field>

            <Field id="med-manufacturer" label="Manufacturer" error={errors.manufacturerId?.message}>
              <Select
                value={watch('manufacturerId') || undefined}
                onValueChange={(value) =>
                  // 'none' is the sentinel item below, not a real id. Storing it
                  // would fail the UUID check and, if it slipped through, be
                  // sent to the server as a manufacturerId.
                  setValue('manufacturerId', value === NONE_MANUFACTURER ? '' : value, {
                    shouldValidate: true,
                  })
                }
                disabled={busy}
              >
                {/* A placeholder is mandatory here: Radix Select has no way to
                    clear its value back to empty, so without an explicit "none"
                    item a manufacturer could never be removed from a medicine. */}
                <SelectTrigger id="med-manufacturer" aria-label="Manufacturer">
                  <SelectValue placeholder="Not linked to a manufacturer" />
                </SelectTrigger>
                <SelectContent>
                  <SelectItem value={NONE_MANUFACTURER}>Not linked to a manufacturer</SelectItem>
                  {manufacturers.rows.map((manufacturer) => (
                    <SelectItem key={manufacturer.id} value={manufacturer.id}>
                      {manufacturer.name}
                    </SelectItem>
                  ))}
                </SelectContent>
              </Select>
            </Field>

            <Field
              id="med-reorderLevel"
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
          </div>

          <div className="space-y-2 rounded-md border p-3">
            <div className="flex items-center gap-2">
              <input
                id="med-requiresPrescription"
                type="checkbox"
                checked={requiresPrescription}
                disabled={busy}
                onChange={(event) => setValue('requiresPrescription', event.target.checked)}
                className="h-4 w-4 rounded border-input"
              />
              <Label htmlFor="med-requiresPrescription">Requires a prescription</Label>
            </div>
            {scheduleMismatch && (
              <p role="status" className="text-xs font-medium text-warning">
                Schedule {scheduleClass} with no prescription requirement. Confirm this is
                intentional - a scheduled drug is usually recorded against a prescription.
              </p>
            )}
          </div>

          <DialogFooter>
            <Button type="button" variant="outline" onClick={() => onOpenChange(false)} disabled={busy}>
              Cancel
            </Button>
            <Button type="submit" disabled={busy}>
              {busy && <Loader2 className="h-4 w-4 animate-spin motion-reduce:animate-none" aria-hidden />}
              {isEdit ? 'Save changes' : 'Add medicine'}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  )
}
