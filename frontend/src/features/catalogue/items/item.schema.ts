import { z } from 'zod'
import { ITEM_CATEGORIES, ITEM_UNITS } from '@/types/api'

/**
 * Validation for the non-medical item form.
 *
 * `UpdateNonMedicalItemRequest` accepts only `name` and `reorderLevel` -
 * `category` and `unit` are immutable after create. The form keeps all four
 * fields so create and edit are one component, and disables the two immutable
 * ones in edit mode rather than hiding them: a hidden field reads as a bug, and a
 * pharmacist comparing "Surgical Gloves / PAIR" against "Surgical Gloves / BOX"
 * needs to see which unit the record actually holds.
 */

const nameField = z
  .string()
  .trim()
  .min(1, 'name is required')
  .max(255, 'name must be at most 255 characters')

const categoryField = z.enum(ITEM_CATEGORIES, {
  errorMap: () => ({ message: 'category is required' }),
})

const unitField = z.enum(ITEM_UNITS, {
  errorMap: () => ({ message: 'unit is required' }),
})

/** Server: @Min(0). */
const reorderLevelField = z
  .number({ invalid_type_error: 'reorderLevel must be a number' })
  .int('reorderLevel must be a whole number')
  .min(0, 'reorderLevel must not be negative')

export interface NonMedicalItemFormValues {
  name: string
  category: (typeof ITEM_CATEGORIES)[number]
  unit: (typeof ITEM_UNITS)[number]
  reorderLevel: number
}

export function makeNonMedicalItemSchema(): z.ZodType<NonMedicalItemFormValues> {
  return z.object({
    name: nameField,
    category: categoryField,
    unit: unitField,
    reorderLevel: reorderLevelField,
  })
}

export function toCreateNonMedicalItemRequest(values: NonMedicalItemFormValues) {
  return {
    name: values.name,
    category: values.category,
    unit: values.unit,
    reorderLevel: values.reorderLevel,
  }
}

/** `category` and `unit` are omitted - the server has no such properties. */
export function toUpdateNonMedicalItemRequest(values: NonMedicalItemFormValues) {
  return {
    name: values.name,
    reorderLevel: values.reorderLevel,
  }
}
