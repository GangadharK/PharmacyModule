import { NavLink } from 'react-router-dom'
import {
  Boxes,
  CalendarClock,
  ClipboardCheck,
  Factory,
  LayoutDashboard,
  ListOrdered,
  Package,
  Pill,
  ScanLine,
  ShoppingCart,
  TriangleAlert,
  Truck,
  Users,
  type LucideIcon,
} from 'lucide-react'
import { NAV_GROUPS, isShipped, unavailableLabel, SHOW_UNIT_ANNOTATIONS, type NavItem } from '@/config/navigation'
import { hasAtLeastRole } from '@/types/api'
import { useAuthStore } from '@/store/auth.store'
import { useUiStore } from '@/store/ui.store'
import { Button } from '@/components/ui/button'
import { Tooltip, TooltipContent, TooltipProvider, TooltipTrigger } from '@/components/ui/tooltip'
import { cn } from '@/lib/utils'

const ICONS: Record<string, LucideIcon> = {
  Boxes,
  CalendarClock,
  ClipboardCheck,
  Factory,
  LayoutDashboard,
  ListOrdered,
  Package,
  Pill,
  ScanLine,
  ShoppingCart,
  TriangleAlert,
  Truck,
  Users,
}

export function Sidebar() {
  const collapsed = useUiStore((state) => state.sidebarCollapsed)
  const toggle = useUiStore((state) => state.toggleSidebar)
  const roles = useAuthStore((state) => state.user?.roles) ?? []

  // Groups are dropped entirely when collapsed - six groups of one item each is
  // a tall unusable rail. Only the icons remain.
  const visibleGroups = NAV_GROUPS.map((group) => ({
    ...group,
    items: group.items.filter(
      (item) => !item.requiresRole || hasAtLeastRole(roles, item.requiresRole),
    ),
  })).filter((group) => group.items.length > 0)

  return (
    <TooltipProvider delayDuration={200}>
      <aside
        className={cn(
          'fixed inset-y-0 left-0 z-40 hidden flex-col border-r border-sidebar-border bg-sidebar text-sidebar-foreground transition-[width] duration-200 ease-in-out lg:flex',
          collapsed ? 'w-16' : 'w-64',
        )}
        aria-label="Main navigation"
      >
        <div className="flex h-14 shrink-0 items-center gap-2 border-b border-sidebar-border px-4">
          <div className="flex h-7 w-7 shrink-0 items-center justify-center rounded-md bg-primary text-primary-foreground">
            <Pill className="h-4 w-4" aria-hidden />
          </div>
          {!collapsed && (
            <span className="truncate text-sm font-semibold">PharmacyPMS</span>
          )}
        </div>

        <nav className="scrollbar-thin flex-1 space-y-4 overflow-y-auto p-2">
          {visibleGroups.map((group) => (
            <div key={group.label}>
              {!collapsed && (
                <p className="px-2 pb-1 text-xs font-medium uppercase tracking-wider text-sidebar-foreground/50">
                  {group.label}
                </p>
              )}
              <ul className="space-y-0.5">
                {group.items.map((item) => (
                  <li key={item.path}>
                    <SidebarLink item={item} collapsed={collapsed} />
                  </li>
                ))}
              </ul>
            </div>
          ))}
        </nav>

        <div className="border-t border-sidebar-border p-2">
          <Button
            variant="ghost"
            size={collapsed ? 'icon' : 'sm'}
            onClick={toggle}
            className={cn(
              'w-full text-sidebar-foreground hover:bg-sidebar-accent hover:text-sidebar-foreground',
              !collapsed && 'justify-start gap-2',
            )}
            aria-label={collapsed ? 'Expand sidebar' : 'Collapse sidebar'}
            aria-expanded={!collapsed}
          >
            <svg
              className={cn('h-4 w-4 transition-transform', !collapsed && 'rotate-180')}
              viewBox="0 0 24 24"
              fill="none"
              stroke="currentColor"
              strokeWidth="2"
              aria-hidden
            >
              <path d="M15 18l-6-6 6-6" />
            </svg>
            {!collapsed && <span>Collapse</span>}
          </Button>
        </div>
      </aside>
    </TooltipProvider>
  )
}

function SidebarLink({ item, collapsed }: { item: NavItem; collapsed: boolean }) {
  const shipped = isShipped(item)
  const Icon = ICONS[item.icon] ?? LayoutDashboard

  const classes = cn(
    'flex items-center gap-3 rounded-md px-2 py-2 text-sm transition-colors',
    shipped
      ? 'text-sidebar-foreground hover:bg-sidebar-accent hover:text-sidebar-foreground'
      : 'cursor-not-allowed text-sidebar-foreground/35',
    collapsed && 'justify-center px-0',
  )

  const content = (
    <>
      <Icon className="h-4 w-4 shrink-0" aria-hidden />
      {!collapsed && <span className="truncate">{item.label}</span>}
      {!collapsed && !shipped && SHOW_UNIT_ANNOTATIONS && (
        <span className="ml-auto text-[10px] uppercase tracking-wide text-sidebar-foreground/40">
          Unit {item.shippedIn}
        </span>
      )}
    </>
  )

  // An unshipped item is a button, not a link. Rendering a NavLink that goes
  // nowhere produces a focusable, activatable control that does nothing.
  if (!shipped) {
    const body = (
      <span className={classes} aria-disabled="true">
        {content}
      </span>
    )
    if (!collapsed) return body
    return (
      <Tooltip>
        <TooltipTrigger asChild>{body}</TooltipTrigger>
        <TooltipContent side="right">{unavailableLabel(item)}</TooltipContent>
      </Tooltip>
    )
  }

  return (
    <NavLink
      to={item.path}
      end={item.exact ?? false}
      className={({ isActive }) =>
        cn(
          classes,
          isActive && 'bg-primary/90 font-medium text-primary-foreground hover:bg-primary/90',
        )
      }
    >
      {content}
    </NavLink>
  )
}
