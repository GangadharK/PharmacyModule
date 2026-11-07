import { useEffect, useState } from 'react'
import { useForm } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { Eye, EyeOff, Loader2, Pill } from 'lucide-react'
import { z } from 'zod'
import { useNavigate, useSearchParams } from 'react-router-dom'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { apiClient, isApiRequestError } from '@/lib/api'
import { useAuthStore } from '@/store/auth.store'
import { toast } from '@/store/toast.store'
import { login } from '@/features/auth/api/auth.api'
import { cn } from '@/lib/utils'

/**
 * Username is `min(1)`, deliberately not an email rule.
 *
 * FDD 1 says staff sign in with a username. A pharmacist's username is
 * frequently not their email address, and an email-shaped rule would reject a
 * legitimate account at the login form with no explanation.
 *
 * No password-complexity rules here either. The server is the authority on
 * password policy; duplicating it on the client invites the two to drift, and a
 * client-side rule that passes while the server rejects is worse than none.
 */
const loginSchema = z.object({
  username: z.string().min(1, 'Username is required'),
  password: z.string().min(1, 'Password is required'),
})

type LoginForm = z.infer<typeof loginSchema>

type BackendStatus = 'checking' | 'up' | 'down'

export function LoginPage() {
  const [searchParams] = useSearchParams()
  const navigate = useNavigate()
  const [showPassword, setShowPassword] = useState(false)
  const [backend, setBackend] = useState<BackendStatus>('checking')
  const setSession = useAuthStore((state) => state.setSession)

  /*
   * mode: 'onTouched' with reValidateMode: 'onChange'.
   *
   * Validate a field once it has been blurred, then correct it live. 'onSubmit'
   * alone would leave a pharmacist who mistyped their password staring at an
   * unvalidated form until they submit; validating on every keystroke would
   * flag the empty box before they have finished typing into it.
   */
  const {
    register,
    handleSubmit,
    formState: { errors, isSubmitting },
  } = useForm<LoginForm>({
    resolver: zodResolver(loginSchema),
    mode: 'onTouched',
    reValidateMode: 'onChange',
  })

  /*
   * Connectivity check. This is the unit 01 integration gate: it proves the
   * Vite proxy, the ApiClient and the backend are all wired, without needing
   * authentication. Replaced by a real auth flow in unit 02.
   */
  useEffect(() => {
    let cancelled = false

    const controller = new AbortController()
    apiClient
      .get<{ status: string }>('/system/ping', { signal: controller.signal })
      .then(() => {
        if (!cancelled) setBackend('up')
      })
      .catch((error: unknown) => {
        if (cancelled) return
        if (isApiRequestError(error) && error.code === 'NETWORK_ERROR') {
          setBackend('down')
        } else {
          // Reached the server, so it is up even if the response was unexpected.
          setBackend('up')
        }
      })

    return () => {
      cancelled = true
      controller.abort()
    }
  }, [])

  /*
   * The `next` param is set by ProtectedRoute when it bounces an unauthenticated
   * user off a deep link, so signing in returns them where they were going
   * instead of dumping them on the dashboard.
   *
   * Guarded against an absolute URL: `new URL()` below accepts any origin, so an
   * attacker-supplied `?next=https://evil.example` would otherwise turn a
   * successful sign-in into a redirect off-site carrying no token, but still
   * landing the user on a convincing copy of this page. Same-origin paths only.
   */
  const nextPath = (() => {
    const next = searchParams.get('next')
    if (!next) return '/dashboard'
    if (!next.startsWith('/') || next.startsWith('//')) return '/dashboard'
    return next
  })()

  const onSubmit = async (values: LoginForm) => {
    try {
      const session = await login(values)
      setSession(session.accessToken, session.user)
      toast.success('Signed in', `Welcome back, ${session.user.fullName}.`)
      // replace: true so the browser Back button does not return to the login
      // form of a session that is already authenticated, where PublicOnlyRoute
      // would immediately bounce them forward again.
      navigate(nextPath, { replace: true })
    } catch (error) {
      /*
       * Branch on `code`, never on `message` (AGENTS.md rule 10) - a copy edit
       * to a display string must not change control flow.
       *
       * `NETWORK_ERROR` is the one case worth its own message: it means the
       * request never reached the server, so retrying is the useful advice, and
       * the "Invalid username or password" the server would have sent is not
       * what went wrong. Everything else surfaces the server's own text, which
       * for a rejected sign-in is deliberately identical whether the username
       * is unknown or the password is wrong.
       */
      if (isApiRequestError(error) && error.code === 'NETWORK_ERROR') {
        toast.error('Cannot reach the server', 'Check that the backend is running, then try again.')
        return
      }
      const message = isApiRequestError(error)
        ? error.message
        : 'Something went wrong while signing in. Please try again.'
      toast.error('Sign-in failed', message)
    }
  }

  return (
    <div className="flex min-h-screen items-center justify-center bg-muted/40 p-4">
      <div className="w-full max-w-md">
        <div className="mb-6 flex flex-col items-center gap-2 text-center">
          <div className="flex h-11 w-11 items-center justify-center rounded-xl bg-primary text-primary-foreground">
            <Pill className="h-6 w-6" aria-hidden />
          </div>
          <div>
            <h1 className="text-xl font-semibold tracking-tight">PharmacyPMS</h1>
            <p className="text-sm text-muted-foreground">Sign in to continue</p>
          </div>
        </div>

        <form
          onSubmit={handleSubmit(onSubmit)}
          className="space-y-4 rounded-xl border bg-card p-6 shadow-lg"
          noValidate
        >
          <div className="space-y-2">
            <Label htmlFor="username">Username</Label>
            <Input
              id="username"
              type="text"
              autoComplete="username"
              autoFocus
              placeholder="e.g. pharmacist"
              aria-invalid={Boolean(errors.username)}
              aria-describedby={errors.username ? 'username-error' : undefined}
              {...register('username')}
            />
            {errors.username ? (
              <p id="username-error" role="alert" className="text-sm text-destructive">
                {errors.username.message}
              </p>
            ) : null}
          </div>

          <div className="space-y-2">
            <Label htmlFor="password">Password</Label>
            <div className="relative">
              <Input
                id="password"
                type={showPassword ? 'text' : 'password'}
                autoComplete="current-password"
                placeholder="••••••••"
                className="pr-10"
                aria-invalid={Boolean(errors.password)}
                aria-describedby={errors.password ? 'password-error' : undefined}
                {...register('password')}
              />
              <Button
                type="button"
                variant="ghost"
                size="icon"
                onClick={() => setShowPassword((visible) => !visible)}
                className="absolute right-0 top-0 h-9 w-9 text-muted-foreground hover:bg-transparent"
                aria-label={showPassword ? 'Hide password' : 'Show password'}
                aria-pressed={showPassword}
                tabIndex={-1}
              >
                {showPassword ? (
                  <EyeOff className="h-4 w-4" aria-hidden />
                ) : (
                  <Eye className="h-4 w-4" aria-hidden />
                )}
              </Button>
            </div>
            {errors.password ? (
              <p id="password-error" role="alert" className="text-sm text-destructive">
                {errors.password.message}
              </p>
            ) : null}
          </div>

          {/*
           * Disabled only while the request is in flight, and only on
           * isSubmitting - not on backend reachability. A submit button that
           * refuses to work because a health probe has not returned yet is a
           * button that looks broken; the probe is advisory, and if the backend
           * really is down the attempt will fail with a message that says so.
           *
           * Disabling during submit also stops a double-click producing two
           * logins, which would leave an orphaned refresh-token row behind and
           * make the replay-detection log harder to read.
           */}
          <Button type="submit" className="w-full" disabled={isSubmitting} aria-busy={isSubmitting}>
            {isSubmitting ? (
              <>
                <Loader2 className="mr-2 h-4 w-4 animate-spin" aria-hidden />
                Signing in…
              </>
            ) : (
              'Sign in'
            )}
          </Button>
        </form>

        <div className="mt-4 text-center">
          <BackendIndicator status={backend} />
        </div>
      </div>
    </div>
  )
}

function BackendIndicator({ status }: { status: BackendStatus }) {
  const label: Record<BackendStatus, string> = {
    checking: 'Backend: checking…',
    up: 'Backend: PONG',
    down: 'Backend: unreachable',
  }

  return (
    <p
      // data-testid because SessionSplash also renders role="status" - during
      // the silent restore a [role="status"] locator matches two nodes and reads
      // the splash's text, which fails the PONG assertion intermittently.
      data-testid="backend-status"
      className={cn(
        'inline-flex items-center gap-2 text-xs',
        status === 'down' ? 'text-destructive' : 'text-muted-foreground',
      )}
      role="status"
      aria-live="polite"
    >
      <span
        className={cn(
          'inline-block h-1.5 w-1.5 rounded-full',
          status === 'up' && 'bg-success',
          status === 'checking' && 'bg-muted-foreground',
          status === 'down' && 'bg-destructive',
        )}
        aria-hidden
      />
      {label[status]}
    </p>
  )
}
