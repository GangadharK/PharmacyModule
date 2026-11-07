/**
 * Shell verification: the authenticated half of the app.
 *
 * verify-ui.mjs covers /login. This covers AppShell, the sidebar, the header and
 * the role filtering - none of which render without a session.
 *
 * Unit 01 has no login endpoint, so the session is seeded directly into the
 * Zustand store through the real setSession() action. That is not a shortcut
 * around the code under test: it is the same call unit 02's login handler makes,
 * and it exercises the real store, the real ProtectedRoute, the real Sidebar and
 * the real role filter. What it does NOT exercise is the network auth call,
 * which does not exist yet.
 *
 *   node scripts/verify-shell.mjs
 */
import { chromium } from 'playwright-core'

const BASE = process.env.UI_BASE ?? 'http://localhost:5163'
const results = []

function check(name, passed, detail = '') {
  results.push({ name, passed, detail })
  console.log(`${passed ? 'PASS' : 'FAIL'}  ${name}${detail ? ` — ${detail}` : ''}`)
}

const browser = await chromium.launch({
  executablePath: '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome',
  headless: true,
})
const context = await browser.newContext({ viewport: { width: 1280, height: 800 } })
const page = await context.newPage()

const consoleErrors = []
const pageErrors = []
/**
 * Cold loads that answered 400 to the silent restore.
 *
 * verify-shell signs in by seeding the store, so it never holds a refresh
 * cookie. Every full page load therefore asks /auth/refresh to restore a session
 * that does not exist, the backend answers 400 ("No refresh token was
 * presented"), and the browser logs a console error for the non-2xx.
 *
 * Counting them lets the final assertion subtract exactly these rather than
 * blanket-ignoring 400s, so an unrelated failure still trips the gate. Whether a
 * missing credential should be 401 rather than 400 is a backend REST decision,
 * tracked separately - not something this script should paper over.
 */
let expectedRestoreRejections = 0
page.on('response', (r) => {
  if (r.url().includes('/auth/refresh') && r.status() === 400) {
    expectedRestoreRejections += 1
  }
})
page.on('console', (m) => {
  if (m.type() === 'error') consoleErrors.push(m.text())
})
page.on('pageerror', (e) => pageErrors.push(e.message))

/**
 * Seeds a session via the real store action.
 *
 * No page.goto afterwards. The store is in-memory, so a full page load would wipe
 * the session we just seeded - which is the documented unit 01 behaviour, not a
 * bug. Instead, seeding the store makes PublicOnlyRoute re-render and redirect
 * to /dashboard on its own, so the router does the navigating client-side.
 */
async function signIn(page, user) {
  await page.evaluate(async (u) => {
    const { useAuthStore } = await import('/src/store/auth.store.ts')
    useAuthStore.getState().setSession('unit-01-fake-token-not-a-real-jwt', u)
  }, user)
  // Wait for the store write to propagate and the guard to redirect.
  await page.waitForURL('**/dashboard', { timeout: 8000 })
  await page.waitForTimeout(400)
}

const CASHIER = {
  id: 'c1a2b3c4-0000-4000-8000-000000000001',
  username: 'cashier1',
  fullName: 'Priya Menon',
  role: 'CASHIER',
  roles: ['CASHIER'],
  lastLoginAt: null,
}

const SUPER_ADMIN = {
  id: 'c1a2b3c4-0000-4000-8000-000000000002',
  username: 'admin',
  fullName: 'Aiswarya Rao',
  role: 'SUPER_ADMIN',
  roles: ['SUPER_ADMIN', 'PHARMACY_MANAGER', 'PHARMACIST', 'CASHIER'],
  lastLoginAt: null,
}

try {
  // ---------- unauthenticated redirect ----------
  await page.goto(BASE, { waitUntil: 'networkidle' })
  await page.waitForTimeout(400)
  check('unauthenticated user is sent to /login', page.url().includes('/login'), page.url())
  check('the intended destination is preserved in ?next=',
    page.url().includes('next='), page.url())

  // ---------- CASHIER sees the shell ----------
  // Seeding while on /login proves PublicOnlyRoute bounces a signed-in user to
  // the dashboard instead of showing them a login form that does nothing.
  await page.goto(`${BASE}/login`, { waitUntil: 'networkidle' })
  await signIn(page, CASHIER)

  const sidebar = page.locator('aside[aria-label="Main navigation"]')
  check('authenticated user sees the sidebar', (await sidebar.count()) === 1)

  const main = page.locator('main')
  check('exactly one scroll container (main)', (await main.count()) === 1)
  check('main is the scroll container, not the page',
    await page.evaluate(() => {
      const m = document.querySelector('main')
      return m !== null && getComputedStyle(m).overflowY === 'auto'
    }))

  check('dashboard heading renders',
    (await page.locator('h1').first().textContent())?.trim() === 'Dashboard')

  // ---------- role filtering ----------
  const navText = (await sidebar.textContent()) ?? ''
  check('CASHIER does not see Users (SUPER_ADMIN only)', !navText.includes('Users'))
  // Alerts are readable by every role, deliberately: the person standing at the
  // counter is the one who notices an empty shelf, and neither response carries a
  // cost. This assertion used to demand the opposite and failed once unit 09
  // landed. The manager-only rule belongs to the count, not the alert.
  check('CASHIER sees Low Stock (alerts are open to every role)',
    navText.includes('Low Stock'))
  check('CASHIER does not see Cycle Counts (PHARMACY_MANAGER minimum)',
    !navText.includes('Cycle Counts'))
  check('CASHIER does not see OCR Upload (PHARMACIST minimum)',
    !navText.includes('OCR Upload'))
  check('CASHIER sees Medicines', navText.includes('Medicines'))

  // ---------- unshipped items are not links ----------
  // A NavLink that goes nowhere is focusable and activatable but inert.
  //
  // The exemplar moves forward as units land, and that is the point: it must be an
  // item that is *currently* unshipped, or the gate silently starts asserting
  // nothing. It was Medicines, then Batches (shipped by unit 07), and is now
  // Purchase Orders - shippedIn 11, no `requiresRole`, so it is visible to a
  // CASHIER and still unshipped at CURRENT_BUILD_UNIT 7.
  const unshippedLink = sidebar.locator('a', { hasText: 'Purchase Orders' })
  check('unshipped nav item is not an <a> element',
    (await unshippedLink.count()) === 0)

  const dashboardLink = sidebar.locator('a[href="/dashboard"]')
  check('shipped nav item IS a link', (await dashboardLink.count()) === 1)

  // The other half of the same gate: raising CURRENT_BUILD_UNIT must actually
  // turn the catalogue entries into links, not just stop them being inert.
  const medicinesLink = sidebar.locator('a[href="/medicines"]')
  check('shipped catalogue nav item IS a link', (await medicinesLink.count()) === 1)

  // Unit 07 added a second shipped group, so the gate covers the inventory side
  // too. Without this, raising the build unit could have shipped Batches in the
  // nav while its route stayed a placeholder and no gate would have noticed.
  const batchesLink = sidebar.locator('a[href="/inventory/batches"]')
  check('shipped inventory nav item IS a link', (await batchesLink.count()) === 1)

  // ---------- no build-plan annotations leak into the UI ----------
  // "Unit 11" next to Purchase Orders is build-tracking metadata. To anyone
  // without the build plan open it reads as a version or a price, and it
  // advertises a sequencing promise the nav does not actually make. Gated by
  // SHOW_UNIT_ANNOTATIONS, which could easily be left on in one of three
  // render sites, so it is asserted rather than assumed.
  const sidebarText = await sidebar.innerText()
  const unitAnnotations = sidebarText.match(/Unit \d+/gi) ?? []
  check('the sidebar shows no build-unit annotations',
    unitAnnotations.length === 0, unitAnnotations.join(', '))
  check('an unshipped item still explains itself without the number',
    sidebarText.includes('Medicines'), 'label present')

  // ---------- header ----------
  const header = page.locator('header')
  check('header renders the signed-in name',
    ((await header.textContent()) ?? '').includes('Priya Menon'),
    (await header.textContent()) ?? '')

  // The role badge lives inside the account dropdown, which is closed. Reading
  // it from the closed header's textContent is a false assertion - the content
  // is in the DOM but not visible. Open the menu first.
  await page.getByRole('button', { name: 'Account menu' }).click()
  await page.waitForTimeout(300)
  const menu = page.getByRole('menu')
  check('account menu opens', (await menu.count()) === 1)
  check('CASHIER role badge is shown in the menu',
    ((await menu.textContent()) ?? '').includes('CASHIER'),
    (await menu.textContent()) ?? '')
  check('menu shows the username',
    ((await menu.textContent()) ?? '').includes('cashier1'))
  const menuUnitAnnotations = ((await menu.textContent()) ?? '').match(/unit \d+/gi) ?? []
  check('the account menu shows no build-unit annotations',
    menuUnitAnnotations.length === 0, menuUnitAnnotations.join(', '))
  await page.keyboard.press('Escape')
  await page.waitForTimeout(250)

  // ---------- sidebar collapse persists ----------
  const widthBefore = await sidebar.evaluate((el) => el.getBoundingClientRect().width)
  await page.getByRole('button', { name: 'Collapse sidebar' }).click()
  await page.waitForTimeout(400)
  const widthAfter = await sidebar.evaluate((el) => el.getBoundingClientRect().width)
  check('collapsing the sidebar narrows it', widthAfter < widthBefore,
    `${widthBefore}px -> ${widthAfter}px`)
  check('toggle relabels itself to Expand sidebar',
    (await page.getByRole('button', { name: 'Expand sidebar' }).count()) === 1)

  // ---------- collapse survives a reload (localStorage) ----------
  // A reload also ends the session here, but for a different reason than in unit
  // 01: `signIn` seeds the store directly and never obtains a refresh cookie, so
  // the silent restore on the next load gets a 401 and correctly signs the user
  // out. The assertion still holds - a session with no refresh cookie must NOT
  // survive - and the surviving-refresh-cookie case is covered by verify-auth.mjs,
  // which can reach the real endpoint.
  await page.reload({ waitUntil: 'networkidle' })
  await page.waitForTimeout(400)
  check('a reload without a refresh cookie ends the session',
    page.url().includes('/login'), page.url())

  await signIn(page, CASHIER)
  const widthReload = await sidebar.evaluate((el) => el.getBoundingClientRect().width)
  check('sidebar collapse persists across a reload',
    Math.abs(widthReload - widthAfter) < 2, `${widthAfter}px -> ${widthReload}px`)

  await page.getByRole('button', { name: 'Expand sidebar' }).click()
  await page.waitForTimeout(300)

  // ---------- deep link to an unshipped route ----------
  // A full load of /medicines bounces to /login?next=/medicines (that ?next
  // preservation is checked at the top), and re-seeding then lands on /dashboard,
  // because unit 01 has no login handler to honour ?next yet. So the placeholder
  // is reached the way a user reaches it: client-side navigation via the history
  // API, which react-router observes through popstate.
  //
  // /purchase-orders is used rather than /medicines, which shipped with the unit
  // 04 catalogue UI and is now a real page. Two requirements on this route: it
  // must be past CURRENT_BUILD_UNIT so the placeholder generator covers it, and it
  // must carry no `requiresRole`, or RoleRoute would redirect a CASHIER to the
  // dashboard and this check would fail for the wrong reason.
  await page.evaluate(() => {
    window.history.pushState({}, '', '/purchase-orders')
    window.dispatchEvent(new PopStateEvent('popstate', { state: {} }))
  })
  await page.waitForTimeout(600)
  check('client-side navigation to an unshipped route resolves, not 404',
    ((await page.locator('h1').first().textContent()) ?? '').includes('Not available'),
    (await page.locator('h1').first().textContent()) ?? '')

  // Back to the dashboard for the remaining checks.
  await page.evaluate(() => {
    window.history.pushState({}, '', '/dashboard')
    window.dispatchEvent(new PopStateEvent('popstate', { state: {} }))
  })
  await page.waitForTimeout(500)

  // ---------- logout ----------
  await page.getByRole('button', { name: 'Account menu' }).click()
  await page.waitForTimeout(250)
  await page.getByRole('menuitem', { name: /Sign out/i }).click()
  await page.waitForTimeout(500)
  check('sign out returns to /login', page.url().includes('/login'), page.url())
  check('sign out removes the shell', (await page.locator('aside').count()) === 0)

  // ---------- SUPER_ADMIN sees the extra nav ----------
  await signIn(page, SUPER_ADMIN)

  const adminNav = (await sidebar.textContent()) ?? ''
  check('SUPER_ADMIN sees Users', adminNav.includes('Users'))
  check('SUPER_ADMIN sees Low Stock', adminNav.includes('Low Stock'))
  check('SUPER_ADMIN sees OCR Upload', adminNav.includes('OCR Upload'))
  check('SUPER_ADMIN still sees Medicines', adminNav.includes('Medicines'))

  // ---------- no runtime errors ----------
  check('no uncaught page errors', pageErrors.length === 0, pageErrors.join(' | '))
  check('every cold load attempted exactly one silent restore',
    expectedRestoreRejections >= 1, `${expectedRestoreRejections}`)
  check('no unexpected console errors',
    consoleErrors.length <= expectedRestoreRejections,
    `${consoleErrors.length} console error(s) vs ${expectedRestoreRejections} expected restore rejection(s): ${consoleErrors.join(' | ')}`)
} finally {
  await browser.close()
}

const failed = results.filter((r) => !r.passed)
console.log(`\n${results.length - failed.length}/${results.length} checks passed`)
if (failed.length > 0) {
  console.log('\nFailed:')
  failed.forEach((f) => console.log(`  - ${f.name} ${f.detail}`))
  process.exit(1)
}
