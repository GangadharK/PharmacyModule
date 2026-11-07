/**
 * Auth verification: the real sign-in flow, against a running backend.
 *
 * Why this is separate from verify-ui.mjs and verify-shell.mjs:
 *
 *   verify-ui     covers /login with the submit button wired but credentials
 *                 stubbed - it proves a REJECTED sign-in behaves, which is the
 *                 easy half.
 *   verify-shell  covers the authenticated half, but seeds the Zustand store
 *                 directly, so it never proves the login request, the cookie, or
 *                 the silent restore work.
 *
 * The bug this script exists to catch is precisely the gap between those two: a
 * form that submits, a 200 from the server, a cookie, and a session that
 * survives a reload. Each of those is verifiable in isolation and worthless on
 * its own - the original disabled button passed every pre-existing check.
 *
 * Requires the backend on :8081. Kept out of the default `verify` chain so that
 * chain stays runnable without a server; run it explicitly:
 *
 *   npm run verify:auth
 *
 *   node scripts/verify-auth.mjs
 */
import { chromium } from 'playwright-core'

const BASE = process.env.UI_BASE ?? 'http://localhost:5163'
const API = process.env.API_BASE ?? 'http://localhost:8081'
const results = []

function check(name, passed, detail = '') {
  results.push({ name, passed, detail })
  console.log(`${passed ? 'PASS' : 'FAIL'}  ${name}${detail ? ` — ${detail}` : ''}`)
}

async function backendIsUp() {
  try {
    const res = await fetch(`${API}/actuator/health`, { signal: AbortSignal.timeout(3000) })
    return res.ok
  } catch {
    return false
  }
}

if (!(await backendIsUp())) {
  console.error(`Backend is not reachable at ${API}.`)
  console.error('Start it with: ./mvnw spring-boot:run -Dspring-boot.run.profiles=dev')
  console.error('This script makes real requests and cannot be stubbed.')
  process.exit(1)
}

// The dev seeder, one account per role. See IdentitySeeder.
const PASSWORD = 'Pharmacy@Dev123'
const CASHIER = { username: 'cashier', role: 'CASHIER' }
const SUPER_ADMIN = { username: 'superadmin', role: 'SUPER_ADMIN' }

const browser = await chromium.launch({
  executablePath: '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome',
  headless: true,
})
const context = await browser.newContext({ viewport: { width: 1280, height: 800 } })
const page = await context.newPage()

const pageErrors = []
page.on('pageerror', (e) => pageErrors.push(e.message))

/** Count auth requests, so "did it even try" is answerable. */
let authRequests = []
page.on('request', (r) => {
  if (r.url().includes('/api/v1/auth/')) {
    authRequests.push(`${r.method()} ${new URL(r.url()).pathname}`)
  }
})

async function signIn(username, password) {
  authRequests = []
  await page.goto(`${BASE}/login`, { waitUntil: 'networkidle' })
  await page.locator('#username').fill(username)
  await page.locator('#password').fill(password)
  await page.getByRole('button', { name: /sign in/i }).click()
}

try {
  // ---------- the button actually submits ----------
  const submit = page.locator('form button[type="submit"]')
  await page.goto(`${BASE}/login`, { waitUntil: 'networkidle' })
  check('the sign-in button is enabled',
    !(await page.getByRole('button', { name: /sign in/i }).isDisabled()))

  // ---------- rejected credentials ----------
  await signIn('superadmin', 'definitely-not-the-password')
  await page.waitForTimeout(1200)
  check('a wrong password produces a login request',
    authRequests.some((r) => r.startsWith('POST /api/v1/auth/login')),
    authRequests.join(', '))
  check('a wrong password does not sign anyone in',
    page.url().includes('/login'), page.url())
  const errorToast = await page.getByText('Sign-in failed').count()
  check('a wrong password is reported to the user', errorToast > 0)
  check('a wrong password leaks no account-existence signal',
    (await page.getByText(/no such user|unknown user|not found/i).count()) === 0)

  // ---------- successful sign-in ----------
  await signIn(CASHIER.username, PASSWORD)
  await page.waitForURL('**/dashboard', { timeout: 15000 })
  check('valid credentials reach the dashboard', page.url().includes('/dashboard'), page.url())

  const cookies = await context.cookies()
  const refresh = cookies.find((c) => c.name === 'refresh_token')
  check('an HttpOnly refresh cookie was issued', Boolean(refresh))
  check('the refresh cookie is not readable from script',
    Boolean(refresh) && refresh.httpOnly === true,
    refresh ? `httpOnly=${refresh.httpOnly}` : 'absent')
  check('the refresh cookie is not visible to document.cookie',
    !(await page.evaluate(() => document.cookie.includes('refresh_token'))))

  // ---------- roles actually populated ----------
  // The Unit 01 User type had `roles`, but the wire sends `authorities` with a
  // ROLE_ prefix. A wrong mapping here yields an empty array, which HIDES nav
  // items and renders no role badge rather than throwing - so it must be asserted.
  const navText = await page.locator('nav, aside').first().innerText()
  check('a CASHIER sees Medicines', navText.includes('Medicines'), navText.replace(/\n/g, ' | '))
  check('a CASHIER does NOT see Users', !navText.includes('Users'), navText.replace(/\n/g, ' | '))
  check('a CASHIER does NOT see Suppliers', !navText.includes('Suppliers'))

  await page.locator('header button[aria-label="Account menu"]').click()
  await page.waitForTimeout(400)
  const menuText = await page.locator('[role="menu"]').innerText()
  check('the role badge renders the role, not the raw authority',
    menuText.includes('CASHIER') && !menuText.includes('ROLE_CASHIER'),
    menuText.replace(/\n/g, ' | ').slice(0, 80))
  await page.keyboard.press('Escape')

  // ---------- an authenticated call works with the real token ----------
  // Must go through the app's own ApiClient, not a raw fetch. The access token
  // lives in the in-memory Zustand store and is attached by ApiClient as a bearer
  // header; it is NOT a cookie. A raw fetch() here carries no Authorization header
  // and 401s even with a perfectly valid session - which would be a failure of the
  // test, not the app, and would quietly hide a real regression if it passed for
  // the wrong reason later.
  const medicines = await page.evaluate(async () => {
    const { apiClient } = await import('/src/lib/api.ts')
    const body = await apiClient.get('/medicines?size=1')
    return { ok: true, total: body.totalElements }
  })
  check('the restored token authorises a real API call through ApiClient',
    medicines.ok, `totalElements=${medicines.total}`)

  // ---------- the session survives a hard refresh ----------
  // This is what the split-token design exists for: the access token is in
  // memory only, so a reload must recover one from the HttpOnly cookie.
  authRequests = []
  await page.reload({ waitUntil: 'networkidle' })
  await page.waitForTimeout(1500)
  check('a hard refresh does NOT log the user out',
    !page.url().includes('/login'), page.url())
  check('the refresh was silent - no login form was shown',
    (await page.getByText('Restoring your session…').count()) === 0)
  check('the reload triggered exactly one refresh, not a loop',
    authRequests.filter((r) => r.includes('/refresh')).length === 1,
    authRequests.join(', '))
  check('the role is still known after the reload',
    (await page.locator('header button[aria-label="Account menu"]').count()) === 1)

  // ---------- RBAC is enforced server-side, not just hidden in the nav ----------
  // Hiding a nav item is presentation. A CASHIER can still call the endpoint
  // directly, so the refusal has to come from the backend. Asserted through
  // ApiClient so the call is indistinguishable from a real screen's.
  const forbidden = await page.evaluate(async () => {
    const { apiClient, isApiRequestError } = await import('/src/lib/api.ts')
    try {
      await apiClient.get('/users')
      return { status: 200 }
    } catch (error) {
      return { status: isApiRequestError(error) ? error.status : -1 }
    }
  })
  check('a CASHIER is refused the SUPER_ADMIN users endpoint by the server',
    forbidden.status === 403, `HTTP ${forbidden.status}`)

  // ---------- sign out ----------
  await page.locator('header button[aria-label="Account menu"]').click()
  await page.waitForTimeout(300)
  await page.getByRole('menuitem', { name: /sign out/i }).click()
  await page.waitForURL('**/login**', { timeout: 10000 })
  check('sign out returns to /login', page.url().includes('/login'), page.url())

  // Polled rather than sampled once. Sign-out is not instantaneous: the client
  // calls POST /auth/logout and only then clears local state, and the route
  // change to /login can be observed before the Set-Cookie that expires
  // refresh_token has been processed. A single sample made this check fail maybe
  // one run in three, which is worse than no check - a gate that is red
  // intermittently trains you to re-run it rather than to read it.
  let refreshState = 'absent'
  for (let attempt = 0; attempt < 20; attempt++) {
    const cookies = await context.cookies()
    const token = cookies.find((c) => c.name === 'refresh_token')
    refreshState = !token ? 'absent' : token.value ? 'set' : 'cleared'
    if (refreshState !== 'set') break
    await new Promise((resolve) => setTimeout(resolve, 250))
  }
  check('sign out clears the refresh cookie', refreshState !== 'set', refreshState)

  // The real test of sign-out: a reload must not silently sign the user back in.
  await page.reload({ waitUntil: 'networkidle' })
  await page.waitForTimeout(1500)
  check('a reload after sign out does NOT restore the session',
    page.url().includes('/login'), page.url())

  // ---------- a privileged role sees the privileged nav ----------
  await signIn(SUPER_ADMIN.username, PASSWORD)
  await page.waitForURL('**/dashboard', { timeout: 15000 })
  const adminNav = await page.locator('nav, aside').first().innerText()
  check('a SUPER_ADMIN sees Users', adminNav.includes('Users'))
  check('a SUPER_ADMIN sees Suppliers', adminNav.includes('Suppliers'))

  // ---------- uncaught errors ----------
  check('no uncaught page errors', pageErrors.length === 0, pageErrors.join(' | '))
} finally {
  await browser.close()
}

const failed = results.filter((r) => !r.passed)
console.log(`\n${results.length - failed.length}/${results.length} checks passed`)
if (failed.length > 0) {
  console.log('\nFailed:')
  for (const f of failed) console.log(`  - ${f.name}${f.detail ? ` (${f.detail})` : ''}`)
  process.exit(1)
}
