import { Outlet } from 'react-router-dom'
import { Header } from '@/components/layout/Header'
import { Sidebar } from '@/components/layout/Sidebar'
import { useUiStore } from '@/store/ui.store'
import { cn } from '@/lib/utils'

/**
 * Application frame: fixed sidebar, sticky header, and a single scrolling
 * <main>.
 *
 * Only <main> scrolls. If the page itself scrolled, the sidebar and header would
 * move out of view, and on a data grid the column headers would scroll away from
 * the rows. The pl-16 / lg:pl-64 offsets reserve the sidebar's width in the
 * layout rather than overlying the content.
 *
 * No ToastContainer here. It is mounted at the app root in App.tsx instead,
 * because unit 02 needs to raise a toast from the login page on bad credentials
 * - and the login page renders outside this shell. A container scoped to the
 * authenticated shell is the same class of bug as SCMS's unmounted container,
 * just harder to spot: it works everywhere except the one place it matters most.
 */
export function AppShell() {
  const collapsed = useUiStore((state) => state.sidebarCollapsed)

  return (
    <div className="min-h-screen bg-background">
      <Sidebar />

      <div className={cn('flex min-h-screen flex-col transition-[padding] duration-200', collapsed ? 'lg:pl-16' : 'lg:pl-64')}>
        <Header />

        {/* The only scroll container in the shell. */}
        <main className="flex-1 overflow-y-auto p-6">
          <Outlet />
        </main>
      </div>
    </div>
  )
}
