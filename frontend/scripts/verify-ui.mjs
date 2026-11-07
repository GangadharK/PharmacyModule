/**
 * Headless render check for the unit 01 frontend gates.
 *
 * Verifies the things a type check cannot: that the app mounts, that the login
 * screen renders with the expected content, that validation fires, that the
 * password toggle works, that the toast container is actually mounted, and that
 * the sidebar collapses. Run against the dev server with the backend up.
 *
 *   node scripts/verify-ui.mjs
 *
 * Not a replacement for a test suite - it is the "does the app actually work"
 * gate from 01c and 01d, executed rather than assumed.
 */
import { chromium } from 'playwright-core'

const BASE = process.env.UI_BASE ?? 'http://localhost:5163'
const results = []

function check(name, passed, detail = '') {
  results.push({ name, passed, detail })
  const mark = passed ? 'PASS' : 'FAIL'
  console.log(`${mark}  ${name}${detail ? ` — ${detail}` : ''}`)
}

const browser = await chromium.launch({
  executablePath: '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome',
  headless: true,
})

const context = await browser.newContext({ viewport: { width: 1280, height: 800 } })
const page = await context.newPage()

const consoleErrors = []
const pageErrors = []
page.on('console', (msg) => {
  if (msg.type() === 'error') consoleErrors.push(msg.text())
})
page.on('pageerror', (err) => pageErrors.push(err.message))

try {
  // ---------- mount ----------
  await page.goto(BASE, { waitUntil: 'networkidle' })
  await page.waitForTimeout(600)

  check('root unmounted to a URL (redirect from / to /login)',
    page.url().includes('/login'), page.url())

  const heading = await page.textContent('h1').catch(() => null)
  check('login screen renders the product heading', heading === 'PharmacyPMS', String(heading))

  // ---------- backend connectivity indicator ----------
  const indicator = page.locator('[data-testid="backend-status"]')
  await indicator.waitFor({ state: 'visible', timeout: 8000 })
  const indicatorText = (await indicator.textContent())?.trim() ?? ''
  check('backend connectivity indicator reads PONG',
    indicatorText.includes('PONG'), indicatorText)

  // ---------- submit is enabled and no longer carries a placeholder note ----------
  // Unit 01 shipped this button hardcoded `disabled` with an explanatory note,
  // because there was no auth endpoint to call. Both assertions are now inverted:
  // a button that cannot submit is the bug this gate exists to catch.
  const submit = page.getByRole('button', { name: 'Sign in' })
  check('submit button is enabled', !(await submit.isDisabled()))

  const note = await page.getByText('Authentication is wired in unit 02.').count()
  check('the unit 01 placeholder note is gone', note === 0, `${note} found`)

  // ---------- validation triggers on blur ----------
  // Targets inputs by id, not by label: the eye toggle's aria-label
  // ("Show password") contains the word "Password", so getByLabel('Password')
  // matches both the input and the button.
  const usernameInput = page.locator('#username')
  const passwordInput = page.locator('#password')

  // Count network requests to /auth to prove no auth call is attempted.
  let authCalls = 0
  page.on('request', (req) => {
    if (req.url().includes('/auth/')) authCalls += 1
  })

  // Blur an empty username -> its error appears, with no submit involved.
  await usernameInput.focus()
  await page.getByRole('heading', { name: 'PharmacyPMS' }).click()
  await page.waitForTimeout(300)

  const usernameError = await page.locator('#username-error').count()
  check('blurring an empty username shows its error', usernameError === 1)

  const usernameErrorText = usernameError
    ? (await page.locator('#username-error').textContent())?.trim()
    : ''
  check('username error message is specific',
    usernameErrorText === 'Username is required', String(usernameErrorText))

  // Blur an empty password too.
  await passwordInput.focus()
  await page.getByRole('heading', { name: 'PharmacyPMS' }).click()
  await page.waitForTimeout(300)

  const passwordError = await page.locator('#password-error').count()
  check('blurring an empty password shows its error', passwordError === 1)

  const alertRole = await page.locator('[role="alert"]').count()
  check('field errors are announced via role="alert"', alertRole === 2, `found ${alertRole}`)

  // aria-describedby wiring
  const describedBy = await passwordInput.getAttribute('aria-describedby')
  check('invalid field points at its error via aria-describedby',
    describedBy === 'password-error', String(describedBy))
  check('invalid field is marked aria-invalid',
    (await passwordInput.getAttribute('aria-invalid')) === 'true')

  // ---------- error clears once corrected ----------
  await passwordInput.fill('correct-horse')
  await page.getByRole('heading', { name: 'PharmacyPMS' }).click()
  await page.waitForTimeout(300)
  check('error clears after the field is corrected',
    (await page.locator('#password-error').count()) === 0)

  // ---------- implicit submission now reaches the API ----------
  // With the button permanently disabled, pressing Enter was blocked before it
  // could reach a handler, so this asserted zero auth calls. The submit button
  // being enabled is exactly what makes implicit submission work, so the
  // assertion inverts: Enter must now produce a login request.
  await usernameInput.fill('someuser')
  await passwordInput.fill('somepassword')
  await usernameInput.press('Enter')
  await page.waitForTimeout(600)
  check('Enter on a valid form fires an auth request', authCalls >= 1,
    `${authCalls} auth call(s)`)
  check('a rejected sign-in stays on /login', page.url().includes('/login'), page.url())

  // ---------- password visibility toggle ----------
  check('password starts masked',
    (await passwordInput.getAttribute('type')) === 'password')

  const toggle = page.getByRole('button', { name: 'Show password' })
  check('toggle has an accessible name', (await toggle.count()) === 1)
  check('toggle exposes aria-pressed', (await toggle.getAttribute('aria-pressed')) === 'false')

  await toggle.click()
  await page.waitForTimeout(150)
  check('toggle reveals the password',
    (await page.locator('#password').getAttribute('type')) === 'text')
  check('toggle name flips to Hide password',
    (await page.getByRole('button', { name: 'Hide password' }).count()) === 1)

  // ---------- theme applied ----------
  // Read the tokens and compare against rendered values. Do not hardcode an
  // expected rgb: the first version of this test asserted a colour typed from
  // memory, failed against the app's correct output, and proved only that the
  // assertion was wrong.
  const tokens = await page.evaluate(() => {
    const style = getComputedStyle(document.documentElement)
    const read = (name) => style.getPropertyValue(name).trim()
    return {
      primary: read('--primary'),
      background: read('--background'),
      destructive: read('--destructive'),
    }
  })

  check('teal primary token is defined', tokens.primary === '174 76% 36%', tokens.primary)

  // Resolve the token through a probe element so the assertion exercises the
  // same --variable -> hsl() chain Tailwind uses, not a literal I remembered.
  const expectedBg = await page.evaluate((token) => {
    const probe = document.createElement('div')
    probe.style.backgroundColor = `hsl(${token})`
    document.body.appendChild(probe)
    const value = getComputedStyle(probe).backgroundColor
    probe.remove()
    return value
  }, tokens.background)

  const bodyBg = await page.evaluate(() => getComputedStyle(document.body).backgroundColor)
  check('body background resolves from --background, not a literal',
    bodyBg === expectedBg, `body=${bodyBg} token=${expectedBg}`)

  check('destructive token is defined and distinct from the background',
    tokens.destructive !== '' && tokens.destructive !== tokens.background,
    `destructive=${tokens.destructive} background=${tokens.background}`)

  // ---------- toast container is mounted ----------
  // The single most important gate here. SCMS never mounts its container, so
  // toast.success() is a silent no-op there and nobody notices.
  //
  // This runs on /login, outside the authenticated shell, because unit 02 needs
  // to toast a bad-credentials failure from this page. A container mounted only
  // inside AppShell fails exactly that case.
  // Radix renders the viewport as <div role="region" aria-label="Notifications (F8)">.
  const toastViewport = page.locator('[role="region"][aria-label*="Notification"]')
  const viewportCount = await toastViewport.count()
  check('toast viewport is mounted outside the auth shell', viewportCount === 1,
    `${viewportCount} region(s)`)

  // Trigger a toast through the real store and confirm it renders.
  const toastAppeared = await page.evaluate(async () => {
    const mod = await import('/src/store/toast.store.ts')
    mod.toast.success('Verification', 'Toast container is mounted')
    await new Promise((r) => setTimeout(r, 400))
    return document.body.textContent?.includes('Toast container is mounted') ?? false
  })
  check('a queued toast actually renders on screen', toastAppeared)

  // ---------- no runtime errors ----------
  check('no uncaught page errors', pageErrors.length === 0, pageErrors.join(' | '))

  /*
   * One class of console error is expected, and it is filtered rather than
   * tolerated wholesale: the implicit-submission check signs in with
   * 'someuser'/'somepassword', and the browser logs "Failed to load resource:
   * 400" for the rejection. That is the assertion working - the request went out
   * and was refused.
   *
   * Filtering the exact 400-resource message rather than clearing the whole
   * array keeps the gate meaningful: any OTHER console error, including a second
   * failure from a call that should have succeeded, still fails here.
   */
  const expectedRejection = /Failed to load resource: the server responded with a status of 400/
  const unexpectedErrors = consoleErrors.filter((text) => !expectedRejection.test(text))
  check('no unexpected console errors', unexpectedErrors.length === 0,
    unexpectedErrors.join(' | '))
  check('the rejected sign-in produced exactly the expected 400s',
    consoleErrors.length === 2, `${consoleErrors.length} console error(s)`)
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
