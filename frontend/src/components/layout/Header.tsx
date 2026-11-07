import { LogOut, User as UserIcon } from 'lucide-react'
import { useNavigate } from 'react-router-dom'
import { Avatar, AvatarFallback } from '@/components/ui/avatar'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuLabel,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu'
import { useAuthStore } from '@/store/auth.store'
import { toast } from '@/store/toast.store'
import { SHOW_UNIT_ANNOTATIONS } from '@/config/navigation'
import { logout as endSession } from '@/features/auth/api/auth.api'

/** Initials for the avatar fallback: "Aiswarya Rao" -> "AR". */
function initials(fullName: string): string {
  const parts = fullName.trim().split(/\s+/).filter(Boolean)
  const first = parts[0]?.[0] ?? '?'
  const last = parts.length > 1 ? (parts[parts.length - 1]?.[0] ?? '') : ''
  return (first + last).toUpperCase()
}

export function Header() {
  const navigate = useNavigate()
  const user = useAuthStore((state) => state.user)
  const logout = useAuthStore((state) => state.logout)

  const handleLogout = () => {
    /*
     * The server call is not optional. Clearing local state alone leaves the
     * HttpOnly refresh cookie valid on the server, and the silent restore in
     * App.tsx would exchange that still-good cookie on the next page load and
     * sign the user straight back in - so "sign out" would undo itself on
     * refresh, and the token would remain usable by anything holding the cookie.
     *
     * The local session is cleared immediately and the request is not awaited:
     * the user should reach the login form at once, and `endSession` never
     * rejects by design, so there is no error path to strand them on.
     */
    void endSession()
    logout()
    toast.info('Signed out', 'Your session has ended.')
    navigate('/login', { replace: true })
  }

  return (
    <header className="sticky top-0 z-30 flex h-14 shrink-0 items-center justify-end gap-3 border-b bg-background/95 px-6 backdrop-blur supports-[backdrop-filter]:bg-background/60">
      {user ? (
        <DropdownMenu>
          <DropdownMenuTrigger asChild>
            <Button variant="ghost" className="gap-2 px-2" aria-label="Account menu">
              <Avatar className="h-8 w-8">
                <AvatarFallback>{initials(user.fullName)}</AvatarFallback>
              </Avatar>
              <span className="hidden text-sm font-medium sm:inline">{user.fullName}</span>
            </Button>
          </DropdownMenuTrigger>

          <DropdownMenuContent align="end" className="w-64">
            <DropdownMenuLabel>
              <div className="flex flex-col gap-1">
                <span className="text-sm font-medium">{user.fullName}</span>
                <span className="text-xs font-normal text-muted-foreground">
                  {user.username}
                </span>
                <div className="mt-1 flex flex-wrap gap-1">
                  {user.roles.map((role) => (
                    <Badge
                      key={role}
                      variant={role === 'SUPER_ADMIN' ? 'default' : 'secondary'}
                      className="text-[10px]"
                    >
                      {role.replace(/_/g, ' ')}
                    </Badge>
                  ))}
                </div>
              </div>
            </DropdownMenuLabel>
            <DropdownMenuSeparator />
            <DropdownMenuItem disabled>
              <UserIcon aria-hidden />
              Profile
              {SHOW_UNIT_ANNOTATIONS && (
                <span className="ml-auto text-[10px] text-muted-foreground">unit 02</span>
              )}
            </DropdownMenuItem>
            <DropdownMenuSeparator />
            <DropdownMenuItem onSelect={handleLogout}>
              <LogOut aria-hidden />
              Sign out
            </DropdownMenuItem>
          </DropdownMenuContent>
        </DropdownMenu>
      ) : (
        <span className="text-sm text-muted-foreground">Not signed in</span>
      )}
    </header>
  )
}
