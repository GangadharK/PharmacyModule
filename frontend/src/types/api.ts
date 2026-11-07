/**
 * Shared API types. These mirror the backend's ApiError and PageResponse
 * envelopes, which are records in common/dto/.
 *
 * A branded Uuid is used instead of a bare string so an arbitrary string
 * cannot be passed where an id is expected. The brand is compile-time only - it
 * is not a runtime check, and it does not survive JSON.parse, so a DTO field
 * must be cast on the way in.
 */

/** A UUID as serialised by the API. Branded to prevent accidental use as a plain string. */
export type Uuid = string & { readonly __brand: 'Uuid' }

/**
 * The error envelope. Every non-2xx response from the API has this shape.
 *
 * `code` is the stable contract; `message` is for humans. Branch on `code`,
 * never on `message` - an edit to a display string would otherwise silently
 * break error handling. See AGENTS.md rule 10 and common/dto/ApiError.java.
 */
export interface ApiError {
  timestamp: string
  status: number
  code: string
  message: string
  path: string
  fieldErrors?: Record<string, string>
}

/** The pagination envelope. Mirrors common/dto/PageResponse.java. */
export interface PageResponse<T> {
  content: T[]
  page: number
  size: number
  totalElements: number
  totalPages: number
  first: boolean
  last: boolean
}

/** The four roles, UPPER_CASE to match the backend exactly. */
export const ROLES = [
  'SUPER_ADMIN',
  'PHARMACY_MANAGER',
  'PHARMACIST',
  'CASHIER',
] as const

export type Role = (typeof ROLES)[number]

/** Descending authority. Mirrors the RoleHierarchy bean in unit 02. */
export const ROLE_RANK: Record<Role, number> = {
  SUPER_ADMIN: 4,
  PHARMACY_MANAGER: 3,
  PHARMACIST: 2,
  CASHIER: 1,
}

export function hasAtLeastRole(userRoles: readonly Role[], required: Role): boolean {
  return userRoles.some((role) => ROLE_RANK[role] >= ROLE_RANK[required])
}

/**
 * The session user exactly as the backend serialises it.
 *
 * Two fields are easy to get wrong here, and both are silent:
 *
 * - `authorities`, not `roles`. Spring emits `ROLE_SUPER_ADMIN` style strings,
 *   and the backend derives the list from the single `role` column, so it
 *   already contains every role the hierarchy implies. A `SUPER_ADMIN` gets
 *   four entries, not one.
 * - the prefixed form. Stripping `ROLE_` is this layer's job, not the caller's.
 *
 * Mirrors `TokenResponse.SessionUser` in dto/auth/TokenResponse.java.
 */
export interface SessionUserDto {
  id: Uuid
  username: string
  fullName: string
  role: Role
  /** Spring-style `ROLE_`-prefixed, ordered most- to least-privileged. */
  authorities: string[]
  lastLoginAt: string | null
}

/**
 * A session user after normalisation.
 *
 * `SessionUserDto` is the wire shape and `User` is what the app works with, kept
 * separate on purpose. `SessionUserDto.authorities` is an untyped `string[]`;
 * spreading it into components would push `ROLE_`-stripping and validation into
 * every call site, and one forgotten strip is a silently empty role check that
 * hides nav items rather than throwing.
 *
 * @see toUser - the only place the two meet
 */
export interface User {
  id: Uuid
  username: string
  fullName: string
  role: Role
  /** Unprefixed, validated against ROLES. Never trust an unrecognised entry. */
  roles: Role[]
  lastLoginAt: string | null
}

const AUTHORITY_PREFIX = 'ROLE_'

/**
 * Narrow one authority string to a Role, or null.
 *
 * An authority the app does not recognise is dropped rather than passed
 * through. A future backend that adds a role this build has never heard of must
 * not be able to make the UI treat it as `SUPER_ADMIN` just because the string
 * happens to sort high somewhere.
 */
function toRole(authority: string): Role | null {
  const candidate = authority.startsWith(AUTHORITY_PREFIX)
    ? authority.slice(AUTHORITY_PREFIX.length)
    : authority
  return (ROLES as readonly string[]).includes(candidate) ? (candidate as Role) : null
}

/**
 * The single point where the wire shape becomes the app shape.
 *
 * `role` is always included even if `authorities` is missing or empty, because
 * the backend derives authorities from it - an absent list is a contract change
 * to investigate, not a reason to leave the user with no roles at all.
 */
export function toUser(dto: SessionUserDto): User {
  const fromAuthorities = (dto.authorities ?? [])
    .map(toRole)
    .filter((role): role is Role => role !== null)
  return {
    id: dto.id,
    username: dto.username,
    fullName: dto.fullName,
    role: dto.role,
    roles: fromAuthorities.includes(dto.role) ? fromAuthorities : [dto.role, ...fromAuthorities],
    lastLoginAt: dto.lastLoginAt ?? null,
  }
}

/**
 * A login response, as the backend actually sends it.
 *
 * `expiresAt` is an ISO-8601 instant, not a duration. Mirrors
 * dto/auth/TokenResponse.java - note there is no `passwordHash` field, and no
 * `costPrice` on any catalogue DTO: a field the caller is not entitled to is
 * absent rather than null.
 */
export interface LoginResponse {
  accessToken: string
  tokenType: string
  expiresAt: string
  user: SessionUserDto
}


/* ------------------------------------------------------------------ *
 * Catalogue and user DTOs
 *
 * Mirrored from the live OpenAPI on 2026-09-26. Two things about these
 * shapes are easy to get wrong and are the reason for the note below.
 * ------------------------------------------------------------------ */

/**
 * Optional response fields are **absent**, not `null`.
 *
 * Jackson is configured with NON_NULL inclusion, so an empty `brandName` is
 * omitted from the payload entirely. Verified against a live record that has
 * no brand or manufacturer: those keys are missing, not present-and-null.
 * That is why these are `?:` rather than `| null` - `row.brandName ?? '—'`
 * handles both, whereas `| null` would be a lie about the wire format and would
 * still need `??` at every call site.
 *
 * The OpenAPI document does not declare nullability at all (no `nullable: true`
 * on any of these properties), so it cannot be used to derive this. The source
 * of truth was a live response.
 */

export const DOSAGE_FORMS = [
  'TABLET',
  'SYRUP',
  'INJECTION',
  'CAPSULE',
  'DROPS',
  'CREAM',
  'OINTMENT',
  'INHALER',
] as const
export type DosageForm = (typeof DOSAGE_FORMS)[number]

export const SCHEDULE_CLASSES = ['OTC', 'H', 'H1'] as const
export type ScheduleClass = (typeof SCHEDULE_CLASSES)[number]

export const ITEM_CATEGORIES = ['CONSUMABLE', 'EQUIPMENT'] as const
export type ItemCategory = (typeof ITEM_CATEGORIES)[number]

export const ITEM_UNITS = ['PIECE', 'BOX', 'PACK', 'PAIR', 'SET'] as const
export type ItemUnit = (typeof ITEM_UNITS)[number]

export interface UserResponse {
  id: string
  username: string
  fullName: string
  role: Role
  active: boolean
  createdAt: string
  lastLoginAt?: string
}

export interface CreateUserRequest {
  username: string
  password: string
  fullName: string
  role: Role
}

export interface UpdateUserRequest {
  fullName?: string
  password?: string
  /** Required by the server even on a partial update - the form always sends it. */
  role: Role
  active?: boolean
}

export interface MedicineResponse {
  id: string
  genericName: string
  brandName?: string
  strength?: string
  dosageForm: DosageForm
  scheduleClass: ScheduleClass
  requiresPrescription: boolean
  manufacturerId?: string
  manufacturerName?: string
  reorderLevel: number
  isActive: boolean
  createdAt: string
  updatedAt: string
}

export interface CreateMedicineRequest {
  genericName: string
  brandName?: string
  strength?: string
  dosageForm: DosageForm
  scheduleClass?: ScheduleClass
  requiresPrescription?: boolean
  manufacturerId?: string
  reorderLevel?: number
}

export interface UpdateMedicineRequest {
  genericName?: string
  brandName?: string
  strength?: string
  scheduleClass?: ScheduleClass
  requiresPrescription?: boolean
  manufacturerId?: string
  reorderLevel?: number
}

export interface SupplierResponse {
  id: string
  name: string
  drugLicenseNo?: string
  contactPerson?: string
  phone?: string
  email?: string
  address?: string
  gstNumber?: string
  isActive: boolean
  createdAt: string
  updatedAt: string
}

export interface CreateSupplierRequest {
  name: string
  drugLicenseNo?: string
  contactPerson?: string
  phone?: string
  email?: string
  address?: string
  gstNumber?: string
}

export type UpdateSupplierRequest = CreateSupplierRequest

export interface ManufacturerResponse {
  id: string
  name: string
  licenseNumber?: string
  country?: string
  createdAt: string
}

export interface CreateManufacturerRequest {
  name: string
  licenseNumber?: string
  country?: string
}

export type UpdateManufacturerRequest = CreateManufacturerRequest

export interface NonMedicalItemResponse {
  id: string
  name: string
  category: ItemCategory
  unit: ItemUnit
  reorderLevel: number
  isActive: boolean
  createdAt: string
  updatedAt: string
}

export interface CreateNonMedicalItemRequest {
  name: string
  category: ItemCategory
  unit: ItemUnit
  reorderLevel?: number
}

/** Only `name` and `reorderLevel`; category and unit are immutable after create. */
export interface UpdateNonMedicalItemRequest {
  name?: string
  reorderLevel?: number
}

// ==================== Inventory (Unit 05 + 06) ====================

export const BATCH_STATUSES = ['ACTIVE', 'QUARANTINED', 'EXPIRED', 'DEPLETED'] as const
export type BatchStatus = (typeof BATCH_STATUSES)[number]

export const BATCH_LOCATIONS = ['MAIN_PHARMACY', 'OPD_COUNTER', 'EMERGENCY', 'ICU'] as const
export type BatchLocation = (typeof BATCH_LOCATIONS)[number]

export const TRANSACTION_TYPES = [
  'PURCHASE_IN',
  'SALE_OUT',
  'RETURN_RESTOCK',
  'DAMAGE_WRITE_OFF',
  'EXPIRY_WRITE_OFF',
  'TRANSFER_OUT',
  'TRANSFER_IN',
  'CYCLE_COUNT_ADJUST',
  'QUARANTINE',
  'RELEASE',
] as const
export type TransactionType = (typeof TRANSACTION_TYPES)[number]

export const REFERENCE_TYPES = ['PURCHASE_ORDER', 'INVOICE', 'RETURN', 'CYCLE_COUNT'] as const
export type ReferenceType = (typeof REFERENCE_TYPES)[number]

/**
 * One inventory batch.
 *
 * `costPrice` is `number | null` and that is load-bearing, not sloppiness.
 * AGENTS.md rule 7 requires a CASHIER's response body to contain no cost field
 * at all, and the backend omits it rather than nulling it, so the key is absent
 * from the JSON and arrives here as undefined - which `null` covers. Rendering
 * must branch on the field being absent, never on the user's role, so a stale
 * cached role cannot reveal a price the server deliberately withheld.
 */
export interface InventoryBatchResponse {
  id: string
  medicineId?: string
  medicineName?: string
  itemId?: string
  itemName?: string
  supplierId?: string
  supplierName?: string
  batchNumber: string
  manufacturingDate?: string
  expiryDate?: string
  quantityReceived?: number
  quantityAvailable: number
  costPrice?: number | null
  sellingPrice?: number | null
  location: BatchLocation
  status: BatchStatus
  /** Server-computed against today. Null when no expiry is recorded. */
  daysUntilExpiry?: number | null
  createdAt: string
  updatedAt: string
}

/** One row of a batch's append-only ledger. */
export interface StockTransactionResponse {
  id: string
  batchId: string
  transactionType: TransactionType
  quantityChanged: number
  /** Absent for a sale - see 06-fefo-engine.md section 7.4. */
  referenceType?: ReferenceType | null
  referenceId?: string | null
  note?: string | null
  performedBy: string
  actorName: string
  createdAt: string
}

/** One batch the FEFO engine would draw from. */
export interface FefoAllocation {
  batchId: string
  batchNumber: string
  expiryDate: string
  quantity: number
  unitPrice: number
}

/**
 * The FEFO answer, from both `/fefo-preview` and `/dispense`.
 *
 * `shortfall` is present so a caller can render "requested 20, only 12
 * available" from a successful preview rather than from a 400. A preview that
 * cannot fill the request has *succeeded*; treating that as an error is what
 * teaches a user to distrust the preview.
 */
export interface FefoResponse {
  allocations: FefoAllocation[]
  totalAllocated: number
  shortfall: number
  complete: boolean
}

/**
 * A FEFO request body.
 *
 * `referenceType` is deliberately absent from the UI even though the endpoint
 * accepts it: no value in the enum is a truthful cause of a sale, so a sale posts
 * with a null reference. See 06-fefo-engine.md section 7.4.
 */
export interface FefoRequest {
  medicineId: string
  requestedQuantity: number
  location: BatchLocation
}

/**
 * A line below its reorder level, aggregated across its batches.
 *
 * `shortfall` is computed server-side because it is the number a purchase order
 * needs, and a client that re-derived it from two fields could disagree with the
 * server about the same row.
 */
export interface StockAlertResponse {
  id: string
  itemName: string
  itemType: string
  form?: string | null
  strength?: string | null
  totalAvailable: number
  reorderLevel: number
  shortfall: number
  activeBatchCount: number
  severity: StockAlertSeverity
}

/**
 * `OUT_OF_STOCK` is zero sellable, `CRITICAL` is at or under a quarter of the
 * reorder level, `LOW` is at or under the level with stock left.
 *
 * The quarter is a judgement made in one place on the server, not a derivation
 * the client should repeat. Two implementations of the same threshold drift, and
 * the one that drifts is the badge a pharmacist triages from.
 */
export type StockAlertSeverity = 'OUT_OF_STOCK' | 'CRITICAL' | 'LOW'

/**
 * A batch at or near its expiry date.
 *
 * `daysRemaining` is computed against the server's today, so a client clock skew
 * cannot make a batch look safer than it is. It is negative for a batch past its
 * date that has not been written off yet, and those rows are still returned on
 * purpose: they are still on the shelf and still need attention.
 */
export interface ExpiryAlertResponse {
  batchId: string
  batchNumber: string
  medicineId?: string | null
  medicineName?: string | null
  quantityAvailable: number
  expiryDate?: string | null
  daysRemaining: number
  status: BatchStatus
}

export type CycleCountStatus = 'OPEN' | 'POSTED'

export interface CycleCountLineResponse {
  id: string
  batchId: string
  referenceNo: string
  batchNumber: string
  medicineId?: string | null
  medicineName?: string | null
  medicineForm?: string | null
  batchStatus: BatchStatus
  /** What the books claimed when the count opened. Never client-writable. */
  systemQuantity: number
  /** Null until a human enters a figure. */
  countedQuantity?: number | null
  /** Read live at render time, so a sale during the count is visible as one. */
  liveQuantity?: number | null
  variance?: number | null
}

export interface CycleCountResponse {
  id: string
  referenceNo: string
  status: CycleCountStatus
  location: string
  countedBy: string
  notes?: string | null
  createdAt: string
  postedAt?: string | null
  postedBy?: string | null
  lineCount: number
  uncountedLines: number
  varianceLines: number
  /** Ledger rows posting will write, not lines in the session. */
  pendingVariances: number
  lines: CycleCountLineResponse[]
}

export interface CreateCycleCountRequest {
  location: BatchLocation
  /** One specific carton, e.g. a damaged lot. */
  batchIds?: string[]
  /** Every batch of these medicines. */
  medicineIds?: string[]
  notes?: string
}

export interface PostCycleCountResponse {
  cycleCountId: string
  referenceNo: string
  variancesApplied: number
  shortages: number
  surpluses: number
  netUnitChange: number
}
