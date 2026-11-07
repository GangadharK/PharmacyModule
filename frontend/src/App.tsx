import { useEffect } from 'react'
import { Navigate, createBrowserRouter, RouterProvider } from 'react-router-dom'
import { AppShell } from '@/components/layout/AppShell'
import { ProtectedRoute, PublicOnlyRoute } from '@/components/layout/ProtectedRoute'
import { ToastContainer } from '@/components/ui/ToastContainer'
import { restoreSession } from '@/features/auth/api/auth.api'
import { LoginPage } from '@/features/auth/pages/LoginPage'
import {
  MedicineDetail,
  MedicinesPage,
  ManufacturerDetail,
  ManufacturersPage,
  NonMedicalItemDetail,
  NonMedicalItemsPage,
  SupplierDetail,
  SuppliersPage,
} from '@/features/catalogue'
import { UserDetail, UsersPage } from '@/features/users'
import { BatchesPage, FefoPreviewPage } from '@/features/inventory'
import { LowStockPage } from '@/features/alerts/LowStockPage'
import { NearExpiryPage } from '@/features/alerts/NearExpiryPage'
import { CycleCountsPage } from '@/features/cycle-count/CycleCountsPage'
import { DashboardPage } from '@/pages/DashboardPage'
import { NotAvailablePage } from '@/pages/NotAvailablePage'
import { NAV_GROUPS, isShipped } from '@/config/navigation'
import { useAuthStore } from '@/store/auth.store'
import { RoleRoute } from '@/components/layout/RoleRoute'

/**
 * Routes are generated from the nav table so the two cannot drift.
 *
 * A hand-written route list and a hand-written sidebar inevitably disagree about
 * which pages exist - someone adds a nav item and forgets the route, or adds a
 * route and forgets the nav entry. Deriving the placeholders from the same
 * source means a feature added to navigation gets its route automatically.
 *
 * Only UNSHIPPED items are generated. Including shipped ones would produce a
 * duplicate /dashboard route, and React Router resolves duplicates by order, so
 * the placeholder would silently win.
 */
const placeholderRoutes = NAV_GROUPS.flatMap((group) => group.items)
  .filter((item) => !isShipped(item))
  .map((item) => ({ path: item.path, element: <NotAvailablePage /> }))

const router = createBrowserRouter([
  {
    element: <PublicOnlyRoute />,
    children: [{ path: '/login', element: <LoginPage /> }],
  },
  {
    element: <ProtectedRoute />,
    children: [
      {
        element: <AppShell />,
        children: [
          { path: '/', element: <Navigate to="/dashboard" replace /> },
          { path: '/dashboard', element: <DashboardPage /> },

          /*
           * Catalogue and user routes are declared explicitly, not generated.
           *
           * The placeholder generator in config/navigation.ts derives routes for
           * UNSHIPPED nav items, so it deliberately skips these - but it also
           * cannot know about the `/:id` detail routes, which have no nav entry.
           * Declaring the list and detail route together here keeps a resource's
           * two pages adjacent, which is what makes an omission visible: a
           * half-declared resource shows a working list and a dead link.
           *
           * Each route is gated by role to match the backend's @PreAuthorize.
           * A user reaching one of these by typing a URL gets sent to the
           * dashboard rather than a 403 page, because the nav already hides the
           * link - being here means a stale bookmark or a bad guess, not a
           * permission question worth a screen.
           */
          {
            element: <RoleRoute required="CASHIER" />,
            children: [
              { path: '/medicines', element: <MedicinesPage /> },
              { path: '/medicines/:id', element: <MedicineDetail /> },
              { path: '/manufacturers', element: <ManufacturersPage /> },
              { path: '/manufacturers/:id', element: <ManufacturerDetail /> },
              { path: '/non-medical-items', element: <NonMedicalItemsPage /> },
              { path: '/non-medical-items/:id', element: <NonMedicalItemDetail /> },
              // CASHIER+, matching InventoryBatchController: handing over stock
              // is the counter's job, and the cost column is redacted server-side
              // rather than hidden here.
              { path: '/inventory/batches', element: <BatchesPage /> },
              { path: '/inventory/fefo', element: <FefoPreviewPage /> },
              // Alerts are readable by CASHIER too: a cashier standing at the
              // counter is the person who notices a shelf is empty, and a
              // low-stock list they cannot see is a phone call instead of a
              // screen. Neither response carries a cost price.
              { path: '/alerts/low-stock', element: <LowStockPage /> },
              { path: '/alerts/near-expiry', element: <NearExpiryPage /> },
            ],
          },
          {
            element: <RoleRoute required="PHARMACIST" />,
            children: [
              { path: '/suppliers', element: <SuppliersPage /> },
              { path: '/suppliers/:id', element: <SupplierDetail /> },
              // MANAGER+, matching CycleCountController. A count asserts that
              // the books were wrong and who says so, and a cashier has no
              // authority to accuse the system.
              { path: '/inventory/cycle-counts', element: <CycleCountsPage /> },
            ],
          },
          {
            element: <RoleRoute required="SUPER_ADMIN" />,
            children: [
              { path: '/settings/users', element: <UsersPage /> },
              { path: '/settings/users/:id', element: <UserDetail /> },
            ],
          },

          ...placeholderRoutes,
          // Anything unrouted lands on the placeholder rather than a bare 404,
          // so a mistyped URL looks like the rest of the app.
          { path: '*', element: <NotAvailablePage /> },
        ],
      },
    ],
  },
])

/**
 * Recovers the session from the HttpOnly refresh cookie before the router makes
 * any auth decision.
 *
 * The access token lives in memory only, so a reload starts with none. This asks
 * the server to exchange the cookie the browser still holds for a new one, which
 * is what makes a refresh not feel like a logout.
 *
 * `setRestoring(false)` runs in a `finally` because `restoreSession` resolves
 * rather than rejects - a 401 is a legitimate "nobody is signed in" answer - and
 * the flag is what ProtectedRoute waits on. An exception escaping here would
 * leave the app stuck on the splash with no way back, since nothing else clears
 * the flag.
 */
function useSessionBootstrap(): void {
  const setRestoring = useAuthStore((state) => state.setRestoring)

  useEffect(() => {
    let active = true
    void restoreSession().finally(() => {
      if (active) setRestoring(false)
    })
    return () => {
      active = false
    }
  }, [setRestoring])
}

export function App() {
  useSessionBootstrap()

  return (
    /*
     * MOUNTED AT THE ROOT, above the router.
     *
     * The reference project in ../SCMS defines a toast system and never renders
     * its container, so every toast.success() there is a silent no-op - a
     * feature that looks implemented and is not. See ToastContainer.tsx.
     *
     * Root placement, not AppShell: a failed sign-in raises a toast from the
     * login page, which renders outside the authenticated shell. Verified by
     * scripts/verify-ui.mjs on /login, not only inside the shell.
     */
    <>
      <RouterProvider router={router} />
      <ToastContainer />
    </>
  )
}
