import { z } from 'zod'

/** Mirrors `CreateManufacturerRequest` / `UpdateManufacturerRequest`. */

const nameField = z
  .string()
  .trim()
  .min(1, 'name is required')
  .max(255, 'name must be at most 255 characters')

const licenseNumberField = z
  .string()
  .trim()
  .max(100, 'licenseNumber must be at most 100 characters')

const countryField = z.string().trim().max(100, 'country must be at most 100 characters')

export interface ManufacturerFormValues {
  name: string
  licenseNumber: string
  country: string
}

export function makeManufacturerSchema(): z.ZodType<ManufacturerFormValues> {
  return z.object({
    name: nameField,
    licenseNumber: licenseNumberField,
    country: countryField,
  })
}

function blankToUndefined(value: string): string | undefined {
  return value.trim() === '' ? undefined : value
}

/** Identical for create and update - the two records have the same three fields. */
export function toManufacturerRequest(values: ManufacturerFormValues) {
  return {
    name: values.name,
    licenseNumber: blankToUndefined(values.licenseNumber),
    country: blankToUndefined(values.country),
  }
}
