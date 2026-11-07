import { z } from 'zod'
import { ROLES } from '@/types/api'

/**
 * Client-side validation for the user form.
 *
 * These constraints are deliberately a copy of the server's, not a superset:
 * every rule here is in `CreateUserRequest`/`UpdateUserRequest`. Inventing a
 * stricter client rule means the user gets a message about a rule the API does
 * not have, and worse, a valid value could be blocked from ever being submitted.
 * The server remains authoritative - this exists so an obvious mistake is caught
 * before a round trip, not so the client can be trusted.
 *
 * Messages are the server's wording where one exists, so a field that fails both
 * sides says the same thing either way.
 */

export const USERNAME_PATTERN = /^[A-Za-z0-9._-]+$/

/** Server: @Size(min = 8, max = 72). 72 is bcrypt's input limit. */
export const PASSWORD_MIN = 8
export const PASSWORD_MAX = 72

const usernameField = z
  .string()
  .trim()
  .min(3, 'username must be 3 to 100 characters')
  .max(100, 'username must be 3 to 100 characters')
  .regex(
    USERNAME_PATTERN,
    'username may contain letters, digits, dot, underscore and hyphen only',
  )

const passwordField = z
  .string()
  .min(PASSWORD_MIN, `password must be ${PASSWORD_MIN} to ${PASSWORD_MAX} characters`)
  .max(PASSWORD_MAX, `password must be ${PASSWORD_MIN} to ${PASSWORD_MAX} characters`)

const fullNameField = z
  .string()
  .trim()
  .min(1, 'fullName is required')
  .max(255, 'fullName must be at most 255 characters')

const roleField = z.enum(ROLES, { errorMap: () => ({ message: 'role is required' }) })

/**
 * One values type for both create and edit.
 *
 * An earlier version typed the form as `CreateUserForm | EditUserForm` and swapped
 * the resolver by mode. That does not typecheck - react-hook-form needs one
 * values type and one resolver, and a union of two object types produces a
 * resolver whose `names` option is incompatible with the form's. The workaround
 * is always a cast, and a cast here would hide exactly the mistake that matters:
 * sending `username: ''` to a PATCH, or `password: ''` to a create.
 *
 * So both modes share the same four fields, and only the *rules* on `password`
 * differ. In edit mode the field is still bound, still validated, and simply
 * allows ''.
 */
export interface UserFormValues {
  username: string
  fullName: string
  password: string
  role: (typeof ROLES)[number]
}

/**
 * Builds the schema for the current mode.
 *
 * The return type is annotated rather than inferred. Inference yields a union of
 * two `ZodObject` types, and `zodResolver` then cannot prove its output matches
 * `UserFormValues` even though both branches are structurally identical. Naming
 * the type states the contract once, in a place a reader can check, instead of
 * casting at every call site.
 */
export function makeUserFormSchema(isEdit: boolean): z.ZodType<UserFormValues> {
  return z.object({
    username: usernameField,
    fullName: fullNameField,
    // Blank in edit mode means "keep the current password" and the key is dropped
    // from the payload. Empty is *not* accepted on create - there is no existing
    // password to keep.
    password: isEdit ? z.union([z.literal(''), passwordField]) : passwordField,
    role: roleField,
  })
}

/** Narrows form values to the create payload, dropping nothing. */
export function toCreateUserRequest(values: UserFormValues) {
  return { username: values.username, password: values.password, fullName: values.fullName, role: values.role }
}

/**
 * Narrows form values to the update payload.
 *
 * `role` is always sent, which looks wrong for a PATCH until you read
 * `UpdateUserRequest`: `role` is `@NotNull` there, so a partial update that
 * omits it is rejected outright. `username` is dropped - it is immutable, and
 * sending it would be ignored at best. A blank `password` is dropped rather than
 * sent as '', which would fail the min-8 rule and read as "set password to empty".
 */
export function toUpdateUserRequest(values: UserFormValues) {
  const body: { fullName: string; role: UserFormValues['role']; password?: string } = {
    fullName: values.fullName,
    role: values.role,
  }
  if (values.password) body.password = values.password
  return body
}
