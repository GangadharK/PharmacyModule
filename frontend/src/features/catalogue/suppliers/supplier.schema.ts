import { z } from 'zod'

/**
 * Validation for the supplier form, mirroring `CreateSupplierRequest` /
 * `UpdateSupplierRequest`.
 *
 * GSTIN is the field worth explaining. The server's rule is
 * `^$|^[0-9A-Z]{15}$` - empty, or exactly 15 uppercase alphanumerics. The
 * client matches it rather than tightening it: a stricter client rule (demanding
 * the real 15-digit GSTIN checksum) would reject a number the API accepts, and
 * a pharmacist in India typing a supplier's GSTIN off an invoice should not be
 * told it is invalid because of a checksum the backend does not enforce.
 *
 * The `toUpperCase()` is applied through `transform` rather than silently on
 * input, so the field displays what the user typed and the payload is what the
 * API requires.
 */

const GSTIN_RE = /^[0-9A-Z]{15}$/

const nameField = z
  .string()
  .trim()
  .min(1, 'name is required')
  .max(255, 'name must be at most 255 characters')

const drugLicenseNoField = z
  .string()
  .trim()
  .max(100, 'drugLicenseNo must be at most 100 characters')

const contactPersonField = z.string().trim().max(255, 'contactPerson must be at most 255 characters')

const phoneField = z
  .string()
  .trim()
  .max(20, 'phone must be at most 20 characters')

const emailField = z
  .string()
  .trim()
  .max(255, 'email must be at most 255 characters')
  .refine((value) => value === '' || /^[^@\s]+@[^@\s]+\.[^@\s]+$/.test(value), {
    message: 'email must be a valid address',
  })

const addressField = z.string().trim().max(500, 'address must be at most 500 characters')

const gstNumberField = z
  .string()
  .trim()
  .transform((value) => value.toUpperCase())
  .refine((value) => value === '' || GSTIN_RE.test(value), {
    message: 'gstNumber must be 15 uppercase alphanumeric characters',
  })

export interface SupplierFormValues {
  name: string
  drugLicenseNo: string
  contactPerson: string
  phone: string
  email: string
  address: string
  gstNumber: string
}

/**
 * The same schema for create and edit.
 *
 * Only `name` is required, and it is required in both - `CreateSupplierRequest`
 * has `@NotBlank`, `UpdateSupplierRequest` has `@Size(min = 1, max = 255)`. Every
 * other field is optional with the same bounds either way, so there is nothing
 * for a mode flag to switch.
 */
export function makeSupplierSchema(): z.ZodType<SupplierFormValues> {
  return z.object({
    name: nameField,
    drugLicenseNo: drugLicenseNoField,
    contactPerson: contactPersonField,
    phone: phoneField,
    email: emailField,
    address: addressField,
    gstNumber: gstNumberField,
  })
}

function blankToUndefined(value: string): string | undefined {
  return value.trim() === '' ? undefined : value
}

export function toSupplierRequest(values: SupplierFormValues) {
  return {
    name: values.name,
    drugLicenseNo: blankToUndefined(values.drugLicenseNo),
    contactPerson: blankToUndefined(values.contactPerson),
    phone: blankToUndefined(values.phone),
    email: blankToUndefined(values.email),
    address: blankToUndefined(values.address),
    gstNumber: blankToUndefined(values.gstNumber),
  }
}
