import { Link } from 'react-router-dom'
import { Boxes, ListOrdered, Pill, Truck } from 'lucide-react'

import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { PageHeader } from '@/components/layout/PageHeader'
import { CURRENT_BUILD_UNIT } from '@/config/navigation'

/**
 * Still not a dashboard.
 *
 * A dashboard with invented numbers - "142 SKUs", "₹1.2L stock value" - is worse
 * than no dashboard: it looks finished, it gets screenshotted, and every figure
 * on it is false. That reasoning still holds at unit 7, where there *is* stock
 * to count but no aggregate endpoint to count it with - the only numbers
 * available are the ones a list endpoint already returns a page of.
 *
 * So this stays a build-status card. The alternative - querying every list
 * endpoint and summing the pages - would be a real number computed from partial
 * data, which is the same lie with extra requests.
 *
 * Real metrics arrive with Unit 09's alert queries, which are aggregates by
 * design.
 */

interface Shortcut {
  to: string
  label: string
  detail: string
  icon: typeof Boxes
}

const SHIPPED: Shortcut[] = [
  {
    to: '/inventory/batches',
    label: 'Batches',
    detail: 'Stock on hand, soonest expiry first',
    icon: Boxes,
  },
  {
    to: '/inventory/fefo',
    label: 'FEFO preview',
    detail: 'See which batches a quantity comes from, then dispense',
    icon: ListOrdered,
  },
  { to: '/medicines', label: 'Medicines', detail: 'The catalogue', icon: Pill },
  { to: '/suppliers', label: 'Suppliers', detail: 'Who we buy from', icon: Truck },
]

export function DashboardPage() {
  return (
    <div>
      <PageHeader
        title="Dashboard"
        description="Pharmacy Management System — inventory and catalogue"
      />

      <Card>
        <CardHeader>
          <CardTitle>Build {CURRENT_BUILD_UNIT} — Inventory UI</CardTitle>
          <CardDescription>
            Catalogue, batch stock with a reconciled ledger, and FEFO dispensing are
            live. Alerts and purchasing are still to come.
          </CardDescription>
        </CardHeader>
        <CardContent>
          <div className="grid gap-3 sm:grid-cols-2">
            {SHIPPED.map((shortcut) => {
              const Icon = shortcut.icon
              return (
                <Link
                  key={shortcut.to}
                  to={shortcut.to}
                  className="flex items-start gap-3 rounded-md border p-3 transition-colors hover:bg-muted"
                >
                  <Icon className="mt-0.5 h-4 w-4 shrink-0 text-muted-foreground" aria-hidden />
                  <span>
                    <span className="block text-sm font-medium">{shortcut.label}</span>
                    <span className="block text-xs text-muted-foreground">
                      {shortcut.detail}
                    </span>
                  </span>
                </Link>
              )
            })}
          </div>
        </CardContent>
      </Card>
    </div>
  )
}
