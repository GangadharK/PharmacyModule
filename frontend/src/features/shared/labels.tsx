import { Badge } from '@/components/ui/badge'
import {
  BATCH_LOCATIONS,
  BATCH_STATUSES,
  DOSAGE_FORMS,
  ITEM_CATEGORIES,
  ITEM_UNITS,
  REFERENCE_TYPES,
  ROLES,
  SCHEDULE_CLASSES,
  TRANSACTION_TYPES,
  type BatchLocation,
  type BatchStatus,
  type ItemCategory,
  type ItemUnit,
  type ReferenceType,
  type Role,
  type TransactionType,
} from '@/types/api'

/**
 * Human labels for the catalogue's UPPER_SNAKE_CASE enums.
 *
 * The API speaks UPPER_SNAKE_CASE because that is what the database CHECK
 * constraints allow, and it is what the staff will see in any export or report.
 * Rendering it raw is defensible but reads as machine output, and the label maps
 * live in one place so the wording does not drift between a medicine row, a
 * detail page and a filter dropdown.
 *
 * These are display labels only. Nothing maps back to an enum value for saving -
 * the form holds the enum string and this module only ever reads.
 */

function titleCase(value: string): string {
  return value
    .toLowerCase()
    .split(' ')
    .map((word) => word.charAt(0).toUpperCase() + word.slice(1))
    .join(' ')
}

const DOSAGE_FORM_LABELS: Record<(typeof DOSAGE_FORMS)[number], string> = {
  TABLET: 'Tablet',
  SYRUP: 'Syrup',
  INJECTION: 'Injection',
  CAPSULE: 'Capsule',
  DROPS: 'Drops',
  CREAM: 'Cream',
  OINTMENT: 'Ointment',
  INHALER: 'Inhaler',
}

const SCHEDULE_LABELS: Record<(typeof SCHEDULE_CLASSES)[number], string> = {
  OTC: 'Over the counter',
  H: 'Schedule H',
  H1: 'Schedule H1',
}

const CATEGORY_LABELS: Record<ItemCategory, string> = {
  CONSUMABLE: 'Consumable',
  EQUIPMENT: 'Equipment',
}

const UNIT_LABELS: Record<ItemUnit, string> = {
  PIECE: 'Piece',
  BOX: 'Box',
  PACK: 'Pack',
  PAIR: 'Pair',
  SET: 'Set',
}

const ROLE_LABELS: Record<Role, string> = {
  SUPER_ADMIN: 'Super admin',
  PHARMACY_MANAGER: 'Pharmacy manager',
  PHARMACIST: 'Pharmacist',
  CASHIER: 'Cashier',
}

const BATCH_STATUS_LABELS: Record<BatchStatus, string> = {
  // ACTIVE is the only status FEFO allocates from, so its label says so rather
  // than repeating the word "active" - a pharmacist reading a batch row is
  // deciding whether this stock can be handed over.
  ACTIVE: 'In stock',
  QUARANTINED: 'Quarantined',
  EXPIRED: 'Expired',
  DEPLETED: 'Depleted',
}

const LOCATION_LABELS: Record<BatchLocation, string> = {
  MAIN_PHARMACY: 'Main pharmacy',
  OPD_COUNTER: 'OPD counter',
  EMERGENCY: 'Emergency',
  ICU: 'ICU',
}

const TRANSACTION_TYPE_LABELS: Record<TransactionType, string> = {
  PURCHASE_IN: 'Received',
  SALE_OUT: 'Dispensed',
  RETURN_RESTOCK: 'Returned',
  DAMAGE_WRITE_OFF: 'Damaged',
  EXPIRY_WRITE_OFF: 'Expired',
  TRANSFER_OUT: 'Transferred out',
  TRANSFER_IN: 'Transferred in',
  CYCLE_COUNT_ADJUST: 'Count adjustment',
  QUARANTINE: 'Quarantined',
  RELEASE: 'Released',
}

const REFERENCE_TYPE_LABELS: Record<ReferenceType, string> = {
  PURCHASE_ORDER: 'Purchase order',
  INVOICE: 'Invoice',
  RETURN: 'Return',
  CYCLE_COUNT: 'Cycle count',
}

export function dosageFormLabel(form: (typeof DOSAGE_FORMS)[number]): string {
  return DOSAGE_FORM_LABELS[form] ?? titleCase(form)
}

export function scheduleLabel(schedule: (typeof SCHEDULE_CLASSES)[number]): string {
  return SCHEDULE_LABELS[schedule] ?? titleCase(schedule)
}

export function itemCategoryLabel(category: ItemCategory): string {
  return CATEGORY_LABELS[category] ?? titleCase(category)
}

export function itemUnitLabel(unit: ItemUnit): string {
  return UNIT_LABELS[unit] ?? titleCase(unit)
}

export function roleLabel(role: Role): string {
  return ROLE_LABELS[role] ?? titleCase(role)
}

export function batchStatusLabel(status: BatchStatus): string {
  return BATCH_STATUS_LABELS[status] ?? titleCase(status)
}

export function locationLabel(location: BatchLocation): string {
  return LOCATION_LABELS[location] ?? titleCase(location)
}

export function transactionTypeLabel(type: TransactionType): string {
  return TRANSACTION_TYPE_LABELS[type] ?? titleCase(type)
}

export function referenceTypeLabel(reference: ReferenceType): string {
  return REFERENCE_TYPE_LABELS[reference] ?? titleCase(reference)
}

/** Options for a filter dropdown, built from the same constant arrays as the labels. */
export const dosageFormOptions = DOSAGE_FORMS.map((value) => ({
  value,
  label: dosageFormLabel(value),
}))
export const scheduleClassOptions = SCHEDULE_CLASSES.map((value) => ({
  value,
  label: scheduleLabel(value),
}))
export const itemCategoryOptions = ITEM_CATEGORIES.map((value) => ({
  value,
  label: itemCategoryLabel(value),
}))
export const itemUnitOptions = ITEM_UNITS.map((value) => ({
  value,
  label: itemUnitLabel(value),
}))
export const roleOptions = ROLES.map((value) => ({ value, label: roleLabel(value) }))
export const batchStatusOptions = BATCH_STATUSES.map((value) => ({
  value,
  label: batchStatusLabel(value),
}))
export const locationOptions = BATCH_LOCATIONS.map((value) => ({
  value,
  label: locationLabel(value),
}))
export const transactionTypeOptions = TRANSACTION_TYPES.map((value) => ({
  value,
  label: transactionTypeLabel(value),
}))
export const referenceTypeOptions = REFERENCE_TYPES.map((value) => ({
  value,
  label: referenceTypeLabel(value),
}))

/** Status badge variant, kept beside the labels so the two cannot drift apart. */
const BATCH_STATUS_VARIANT: Record<BatchStatus, 'success' | 'warning' | 'destructive' | 'secondary'> = {
  ACTIVE: 'success',
  QUARANTINED: 'warning',
  EXPIRED: 'destructive',
  DEPLETED: 'secondary',
}

export function BatchStatusBadge({ status }: { status: BatchStatus }) {
  return <Badge variant={BATCH_STATUS_VARIANT[status]}>{batchStatusLabel(status)}</Badge>
}

export function RoleBadge({ role }: { role: Role }) {
  return <Badge variant={role === 'SUPER_ADMIN' ? 'default' : 'secondary'}>{roleLabel(role)}</Badge>
}
