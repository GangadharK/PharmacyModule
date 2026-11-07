import { z } from 'zod'
import { DOSAGE_FORMS, SCHEDULE_CLASSES } from '@/types/api'

/**
 * Validation for the medicine form, mirroring `CreateMedicineRequest` /
 * `UpdateMedicineRequest`.
 *
 * **Optional fields are validated as strings, not as `string | undefined`.** A
 * controlled form always holds a string - a cleared field is `''`, not `absent` -
 * so typing them as optional made the schema's output disagree with the values
 * type and forced a cast. The conversion to `undefined` happens once, in
 * `toCreateMedicineRequest` / `toUpdateMedicineRequest`, which is the only place
 * that knows what the API expects.
 */

const UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i

const genericNameField = z
  .string()
  .trim()
  .min(1, 'genericName is required')
  .max(255, 'genericName must be at most 255 characters')

const brandNameField = z.string().trim().max(255, 'brandName must be at most 255 characters')
const strengthField = z.string().trim().max(100, 'strength must be at most 100 characters')

const dosageFormField = z.enum(DOSAGE_FORMS, {
  errorMap: () => ({ message: 'dosageForm is required' }),
})

const scheduleField = z.enum(SCHEDULE_CLASSES)

/** Server: @Min(0). A negative reorder level is meaningless. */
const reorderLevelField = z
  .number({ invalid_type_error: 'reorderLevel must be a number' })
  .int('reorderLevel must be a whole number')
  .min(0, 'reorderLevel must not be negative')

/** Empty means "not linked to a manufacturer", which is legal. */
const manufacturerField = z
  .string()
  .refine((value) => value === '' || UUID_RE.test(value), {
    message: 'Select a manufacturer from the list',
  })

export interface MedicineFormValues {
  genericName: string
  brandName: string
  strength: string
  dosageForm: (typeof DOSAGE_FORMS)[number]
  scheduleClass: (typeof SCHEDULE_CLASSES)[number]
  requiresPrescription: boolean
  manufacturerId: string
  reorderLevel: number
}

/**
 * The same schema serves create and edit.
 *
 * The two requests differ in which fields may be *omitted*, not in which are
 * required, and every field here is always on screen - so requiring them on both
 * is honest. What genuinely differs is the payload: `UpdateMedicineRequest` has
 * no `dosageForm` at all. That is handled in `toUpdateMedicineRequest`, not here,
 * because validation should describe the form and the payload builder should
 * describe the API.
 */
export function makeMedicineSchema(): z.ZodType<MedicineFormValues> {
  return z.object({
    genericName: genericNameField,
    brandName: brandNameField,
    strength: strengthField,
    dosageForm: dosageFormField,
    scheduleClass: scheduleField,
    requiresPrescription: z.boolean(),
    manufacturerId: manufacturerField,
    reorderLevel: reorderLevelField,
  })
}

/** `''` becomes `undefined` so the key is absent rather than present-and-blank. */
function blankToUndefined(value: string): string | undefined {
  return value.trim() === '' ? undefined : value
}

export function toCreateMedicineRequest(values: MedicineFormValues) {
  return {
    genericName: values.genericName,
    brandName: blankToUndefined(values.brandName),
    strength: blankToUndefined(values.strength),
    dosageForm: values.dosageForm,
    scheduleClass: values.scheduleClass,
    requiresPrescription: values.requiresPrescription,
    manufacturerId: blankToUndefined(values.manufacturerId),
    reorderLevel: values.reorderLevel,
  }
}

/**
 * `dosageForm` is deliberately absent: `UpdateMedicineRequest` has no such
 * property, so the dosage form of an existing medicine is immutable. Sending it
 * would be silently dropped by the server, which would leave the form showing a
 * value that was never saved.
 */
export function toUpdateMedicineRequest(values: MedicineFormValues) {
  return {
    genericName: values.genericName,
    brandName: blankToUndefined(values.brandName),
    strength: blankToUndefined(values.strength),
    scheduleClass: values.scheduleClass,
    requiresPrescription: values.requiresPrescription,
    manufacturerId: blankToUndefined(values.manufacturerId),
    reorderLevel: values.reorderLevel,
  }
}
