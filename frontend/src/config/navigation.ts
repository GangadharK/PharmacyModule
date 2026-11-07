import type { Role } from '@/types/api'

/**
 * Navigation definition, kept separate from the component so the route table is
 * readable in one place and the role filter is testable in isolation.
 *
 * `shippedIn` names the build unit that delivers the feature. Until then the
 * item renders dimmed and is not navigable - a visible-but-broken link is worse
 * than an absent one, because it reads as a bug rather than as upcoming work.
 */
export interface NavItem {
  label: string
  path: string
  icon: string
  /** Minimum role required to see the item at all. */
  requiresRole?: Role
  /** Build unit that ships this feature. */
  shippedIn: number
  /** Exact match instead of prefix match, for index routes. */
  exact?: boolean
}

export interface NavGroup {
  label: string
  items: NavItem[]
}

export const NAV_GROUPS: NavGroup[] = [
  {
    label: 'Overview',
    items: [
      { label: 'Dashboard', path: '/dashboard', icon: 'LayoutDashboard', shippedIn: 1, exact: true },
    ],
  },
  {
    label: 'Catalogue',
    items: [
      { label: 'Medicines', path: '/medicines', icon: 'Pill', shippedIn: 4 },
      {
        label: 'Suppliers',
        path: '/suppliers',
        icon: 'Truck',
        // Supplier records carry contact, address and GSTIN. SupplierController
        // gates reads to PHARMACIST+, so the nav must agree - otherwise a CASHIER
        // gets a link that resolves to a 403 the moment unit 4 ships. The backend
        // contract is the authority here, not the other way round.
        requiresRole: 'PHARMACIST',
        shippedIn: 4,
      },
      { label: 'Manufacturers', path: '/manufacturers', icon: 'Factory', shippedIn: 4 },
      { label: 'Non-Medical Items', path: '/non-medical-items', icon: 'Package', shippedIn: 4 },
    ],
  },
  {
    label: 'Inventory',
    items: [
      { label: 'Batches', path: '/inventory/batches', icon: 'Boxes', shippedIn: 7 },
      { label: 'FEFO Preview', path: '/inventory/fefo', icon: 'ListOrdered', shippedIn: 7 },
      // Manager-gated like the endpoint behind it. Without the gate this is a
      // link a CASHIER can click and be refused by a 403 — a dead end, which is
      // the one thing the shippedIn gate exists to avoid.
      {
        label: 'Cycle Counts',
        path: '/inventory/cycle-counts',
        icon: 'ClipboardCheck',
        shippedIn: 9,
        requiresRole: 'PHARMACIST',
      },
    ],
  },
  {
    label: 'Alerts',
    items: [
      // No requiresRole: the backend lets every role read both alert queries
      // (verified against a running app, CASHIER included) and neither response
      // carries a cost price. Gating the nav item to managers while the endpoint
      // is open would hide a shelf that is empty from the person standing at it.
      { label: 'Low Stock', path: '/alerts/low-stock', icon: 'TriangleAlert', shippedIn: 9 },
      { label: 'Near Expiry', path: '/alerts/near-expiry', icon: 'CalendarClock', shippedIn: 9 },
    ],
  },
  {
    label: 'Purchasing',
    items: [
      { label: 'Purchase Orders', path: '/purchase-orders', icon: 'ShoppingCart', shippedIn: 11 },
      {
        label: 'OCR Upload',
        path: '/purchase-orders/ocr',
        icon: 'ScanLine',
        requiresRole: 'PHARMACIST',
        shippedIn: 11,
      },
    ],
  },
  {
    label: 'Administration',
    items: [
      {
        label: 'Users',
        path: '/settings/users',
        icon: 'Users',
        requiresRole: 'SUPER_ADMIN',
        shippedIn: 2,
      },
    ],
  },
]

/**
 * The build unit currently implemented. Bumped by the shell each time a unit lands.
 *
 * 9. Unit 09 shipped the alerts and cycle-count screens against a verified
 * backend (unit 09, 206 tests). Still gated: the whole Purchasing group
 * (units 08 and 10), which has no backend yet, so raising this further would
 * turn dimmed links into dead ones, which is exactly what this gate prevents.
 */
export const CURRENT_BUILD_UNIT = 9

export function isShipped(item: NavItem): boolean {
  return item.shippedIn <= CURRENT_BUILD_UNIT
}

/**
 * Whether to show "Unit 7" style build-plan annotations in the UI.
 *
 * Off. `shippedIn` is build-tracking metadata, and a number like "Unit 11" next
 * to Purchase Orders is meaningless to anyone who does not have the build plan
 * open - it reads as a version, a price, or a bug. It also implies a promise
 * about sequencing that the user should not have to interpret: the nav entry is
 * either usable or it is not, and `aria-disabled` already says which.
 *
 * The data stays in the nav table either way, so this is a rendering switch, not
 * a deletion. Flip it back on to audit progress against the build plan.
 */
export const SHOW_UNIT_ANNOTATIONS = false

/**
 * Text for an unshipped nav item's tooltip, with or without the unit number.
 *
 * Kept as a function so the two variants cannot drift apart, and so the
 * annotation is omitted in both places rather than only the one that was
 * noticed.
 */
export function unavailableLabel(item: NavItem): string {
  return SHOW_UNIT_ANNOTATIONS
    ? `${item.label} — not available until unit ${item.shippedIn}`
    : `${item.label} — not available in this build yet`
}
