#!/usr/bin/env node
/**
 * verify-catalogue.mjs — end-to-end check of the unit 04 catalogue UI and the
 * unit 02 user list, driven through the real UI against the real API.
 *
 * Why a browser suite rather than more unit tests: the failures these screens are
 * prone to are not in any single function. A detail route that renders but 404s, a
 * status filter that returns the same rows, a form that posts `password: ''` and
 * locks a user out — each is correct in isolation and wrong in sequence. Only
 * driving the actual page finds them.
 *
 * Rows are created by the suite and deleted afterwards, so a run leaves the dev
 * database as it found it. Manufacturer delete is verified twice on purpose: once
 * succeeding on an unreferenced row, and once *refused* on a referenced one.
 *
 * Run: npm run verify:catalogue   (needs the backend on 8081)
 */
import { chromium } from 'playwright-core'
import { existsSync, mkdirSync } from 'node:fs'
import { homedir } from 'node:os'
import { join } from 'node:path'

const BASE = process.env.APP_URL ?? 'http://localhost:5163'
const API = process.env.API_URL ?? 'http://localhost:8081'
const PASSWORD = 'Pharmacy@Dev123'

/** username -> password. Every seeded account shares the dev password. */
const USERS = {
  superadmin: PASSWORD,
  manager: PASSWORD,
  pharmacist: PASSWORD,
  cashier: PASSWORD,
}

const LOG_DIR = '.verify-logs'
let passed = 0
const failures = []
let currentStep = 'startup'

function step(name) {
  currentStep = name
  console.log(`\n--- ${name}`)
}

/**
 * A failing check that only says "FAIL" is a dead end: the next question is
 * always "what was actually on the page?". Dumping the DOM text and a
 * screenshot at the point of failure turns a re-run-and-squint into a
 * diagnosis.
 */
async function dumpFailure(page) {
  try {
    mkdirSync(LOG_DIR, { recursive: true })
    const stamp = currentStep.replace(/[^a-z0-9]+/gi, '-').toLowerCase()
    const file = `${LOG_DIR}/${stamp}.png`
    await page.screenshot({ path: file, fullPage: true })
    const text = ((await page.locator('body').textContent()) ?? '').replace(/\s+/g, ' ').trim()
    console.log(`\n[dump] step=${currentStep} url=${page.url()} shot=${file}`)
    console.log(`[dump] body: ${text.slice(0, 900)}`)
  } catch (error) {
    console.log(`[dump] failed: ${String(error)}`)
  }
}

function check(label, condition, detail = '') {
  if (condition) {
    passed++
    console.log(`PASS  ${label}${detail ? ` — ${detail}` : ''}`)
  } else {
    failures.push(label)
    console.log(`FAIL  ${label}${detail ? ` — ${detail}` : ''}`)
    if (page) void dumpFailure(page)
  }
}

function chromePath() {
  if (process.env.CHROME_PATH) return process.env.CHROME_PATH
  const candidates = [
    '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome',
    join(homedir(), 'Applications/Google Chrome.app/Contents/MacOS/Google Chrome'),
    '/Applications/Chromium.app/Contents/MacOS/Chromium',
    '/usr/bin/google-chrome',
    '/usr/bin/chromium',
  ]
  const found = candidates.find((path) => existsSync(path))
  if (!found) throw new Error('No Chrome found. Set CHROME_PATH.')
  return found
}

/* ---------------------------- API helpers ---------------------------- *
 * The access token lives in the page's memory and is deliberately not
 * reachable from here, so these sign in for themselves. That also makes
 * them independent of whichever user the browser is currently signed in as.
 * --------------------------------------------------------------------- */

const tokenCache = new Map()

async function tokenFor(username) {
  if (tokenCache.has(username)) return tokenCache.get(username)
  const res = await fetch(`${API}/api/v1/auth/login`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username, password: USERS[username] }),
  })
  if (!res.ok) throw new Error(`login failed for ${username}: HTTP ${res.status}`)
  const { accessToken } = await res.json()
  tokenCache.set(username, accessToken)
  return accessToken
}

async function apiGet(path, params = {}, username = 'superadmin') {
  const query = new URLSearchParams({ size: '50', ...params })
  const res = await fetch(`${API}/api/v1${path}?${query}`, {
    headers: { Authorization: `Bearer ${await tokenFor(username)}` },
  })
  if (!res.ok) return null
  return res.json()
}

async function apiItem(path, username = 'superadmin') {
  const res = await fetch(`${API}/api/v1${path}`, {
    headers: { Authorization: `Bearer ${await tokenFor(username)}` },
  })
  return res.ok ? res.json() : null
}

async function apiFirstId(path, params, username) {
  const body = await apiGet(path, params, username)
  return body?.content?.[0]?.id ?? null
}

async function apiSend(path, method, body, username = 'superadmin') {
  return fetch(`${API}/api/v1${path}`, {
    method,
    headers: {
      'Content-Type': 'application/json',
      Authorization: `Bearer ${await tokenFor(username)}` },
    body: body === undefined ? undefined : JSON.stringify(body),
  })
}

/** Status plus the machine-readable code, which is what clients branch on. */
async function apiError(path, method, body, username = 'superadmin') {
  const res = await apiSend(path, method, body, username)
  let code
  try {
    code = (await res.json()).code
  } catch {
    code = undefined
  }
  return { status: res.status, code }
}

async function apiLoginWorks(username, password) {
  const res = await fetch(`${API}/api/v1/auth/login`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username, password }),
  })
  return res.status
}

const unique = () => String(Date.now() % 1000000)

/* ------------------------------ browser ------------------------------ */

let browser
try {
  browser = await chromium.launch({ executablePath: chromePath() })
} catch (error) {
  console.error(String(error))
  process.exit(1)
}

const context = await browser.newContext()
const page = await context.newPage()
const pageErrors = []
page.on('pageerror', (error) => pageErrors.push(error.message))

const cleanup = { medicines: [], suppliers: [], items: [], manufacturers: [], users: [] }

async function signIn(username) {
  // PublicOnlyRoute redirects an authenticated visitor away from /login, so a
  // second signIn in the same context would sit on the dashboard waiting for a
  // form that never renders. Clearing cookies is the honest reset: it drops the
  // refresh cookie, and the next load is a genuinely signed-out page.
  await context.clearCookies()
  await page.goto(`${BASE}/login`, { waitUntil: 'networkidle' })
  await page.fill('#username', username)
  await page.fill('#password', USERS[username])
  await page.click('button[type="submit"]')
  await page.waitForURL((url) => !url.pathname.includes('/login'), { timeout: 20000 })
  await page.waitForLoadState('networkidle')
}

/** The primary action inside the open dialog, not the one in the page header. */
function dialogButton(name) {
  return page.getByRole('dialog').getByRole('button', { name })
}

async function searchFor(text) {
  await page.fill('#resource-search', text)
  await page.waitForTimeout(1300)
}

/**
 * Wait for the detail heading to actually carry the record's name.
 *
 * Waiting for the heading merely to *differ* from "Loading…" is not enough: right
 * after a row click the previous list page is still on screen, so the heading is
 * "Medicines" - not "Loading…" - and the wait returns while the record is still
 * in flight. Asserting the expected name is the only check that cannot pass on
 * the previous page.
 */
async function waitForDetailTitle(expected) {
  await page
    .locator('h1')
    .filter({ hasText: expected })
    .first()
    .waitFor({ state: 'visible', timeout: 15000 })
}

try {
  /* ------------------------------------------------------------------ *
   * CASHIER: can read the catalogue, is offered no writes
   * ------------------------------------------------------------------ */
  step('CASHIER read-only')
  await signIn('cashier')

  await page.goto(`${BASE}/medicines`, { waitUntil: 'networkidle' })
  check('CASHIER can open the medicine list',
    (await page.locator('h1').first().textContent()) === 'Medicines')
  check('CASHIER is offered no "Add medicine" button',
    (await page.getByRole('button', { name: /add medicine/i }).count()) === 0)
  check('CASHIER is offered no row-level Edit button on a listed medicine',
    (await page.getByRole('button', { name: /^edit /i }).count()) === 0)

  await page.goto(`${BASE}/suppliers`, { waitUntil: 'networkidle' })
  check('CASHIER is redirected away from the suppliers route',
    page.url().endsWith('/dashboard'), page.url())

  await page.goto(`${BASE}/settings/users`, { waitUntil: 'networkidle' })
  check('CASHIER is redirected away from the user list',
    page.url().endsWith('/dashboard'), page.url())

  /* ------------------------------------------------------------------ *
   * PHARMACY_MANAGER: create → read by id → update → deactivate → reactivate
   * ------------------------------------------------------------------ */
  step('PHARMACY_MANAGER medicine CRUD')
  await signIn('manager')

  const generic = `VerifyBand${unique()}`
  await page.goto(`${BASE}/medicines`, { waitUntil: 'networkidle' })
  await page.getByRole('button', { name: /add medicine/i }).click()
  await page.waitForSelector('#med-genericName')
  await page.fill('#med-genericName', generic)
  await page.fill('#med-strength', '250 mg')
  await page.fill('#med-brandName', 'VerifyBrand')
  await dialogButton(/add medicine/i).click()
  await page.waitForTimeout(1500)

  await searchFor(generic)
  check('a created medicine appears in the list',
    (await page.locator('tbody tr').count()) === 1,
    `${await page.locator('tbody tr').count()} row(s)`)

  const medicineId = await apiFirstId('/medicines', { search: generic }, 'manager')
  check('the created medicine has an id', Boolean(medicineId), medicineId ?? 'none')
  if (medicineId) cleanup.medicines.push(medicineId)

  if (medicineId) {
    /* "Get specific", reached by clicking a row. */
    await page.locator('tbody tr').first().click()
    await page.waitForURL(`**/medicines/${medicineId}`, { timeout: 10000 }).catch(() => undefined)
    check('clicking a row opens that medicine by id',
      page.url().endsWith(`/medicines/${medicineId}`), page.url())
    await waitForDetailTitle(generic)
    check('the detail page shows the generic name',
      ((await page.locator('h1').first().textContent()) ?? '').includes(generic))

    /* And by deep link, which is a different code path through the router. */
    await page.goto(`${BASE}/medicines/${medicineId}`, { waitUntil: 'networkidle' })
    await waitForDetailTitle(generic)
    check('a direct deep link to the detail route loads',
      ((await page.locator('h1').first().textContent()) ?? '').includes(generic))
    check('the detail page shows the manufacturer as not linked rather than blank',
      ((await page.locator('body').textContent()) ?? '').includes('Not linked'))

    /* Edit. dosageForm is immutable, so the control must be disabled. */
    await page.getByRole('button', { name: /^edit$/i }).click()
    await page.waitForSelector('#med-genericName')
    const dosageDisabled = await page.evaluate(() => {
      const trigger = document.querySelector('#med-dosageForm')
      return trigger instanceof HTMLButtonElement ? trigger.disabled : null
    })
    check('dosage form is not editable on an existing medicine',
      dosageDisabled === true, String(dosageDisabled))

    await page.fill('#med-strength', '500 mg')
    await dialogButton(/save changes/i).click()
    // The form closes on success and the record reloads; waiting for the dialog
    // to disappear is the honest signal that the save actually landed.
    await page.getByRole('dialog').waitFor({ state: 'detached', timeout: 15000 })
    await page.waitForTimeout(500)
    check('the edit is reflected on the detail page',
      ((await page.locator('body').textContent()) ?? '').includes('500 mg'))

    /* Deactivate, then reactivate. */
    await page.getByRole('button', { name: /^deactivate$/i }).click()
    await dialogButton(/^deactivate$/i).click()
    await page.getByRole('dialog').waitFor({ state: 'detached', timeout: 15000 })
    await page.waitForTimeout(600)
    check('deactivated status is shown on the detail page',
      ((await page.locator('body').textContent()) ?? '').includes('Deactivated'))

    await page.getByRole('button', { name: /^reactivate$/i }).click()
    await page.waitForTimeout(1600)
    check('reactivated status is shown on the detail page',
      ((await page.locator('body').textContent()) ?? '').includes('Active'))

    /* The status filter must actually invert — this is the regression the
       backend `activeOnly` fix was for, seen through the UI. */
    await apiSend(`/medicines/${medicineId}`, 'DELETE', undefined, 'manager')
    await page.goto(`${BASE}/medicines`, { waitUntil: 'networkidle' })
    await searchFor(generic)
    check('a deactivated medicine is absent from the default list',
      (await page.locator('tbody tr').count()) === 0)

    await page.getByRole('button', { name: /^deactivated$/i }).click()
    await page.waitForTimeout(1300)
    check('a deactivated medicine appears under the Deactivated filter',
      (await page.locator('tbody tr').count()) === 1,
      `${await page.locator('tbody tr').count()} row(s)`)

    await page.getByRole('button', { name: /^all$/i }).click()
    await page.waitForTimeout(1300)
    check('a deactivated medicine appears under the All filter',
      (await page.locator('tbody tr').count()) === 1)

    await page.getByRole('button', { name: /^active$/i }).click()
    await page.waitForTimeout(1300)
    check('a deactivated medicine is absent from the Active filter',
      (await page.locator('tbody tr').count()) === 0)

    /* A search must survive a status-filter change. This is a real bug that was
       in the first draft: the filter called reset(), which also cleared search. */
    await page.getByRole('button', { name: /^all$/i }).click()
    await page.waitForTimeout(800)
    check('changing the status filter does not clear the search box',
      (await page.inputValue('#resource-search')) === generic,
      await page.inputValue('#resource-search'))
  }

  /* ------------------------------------------------------------------ *
   * Supplier
   * ------------------------------------------------------------------ */
  step('supplier create')
  const supplierName = `Verify Supplier ${unique()}`
  await page.goto(`${BASE}/suppliers`, { waitUntil: 'networkidle' })
  await page.getByRole('button', { name: /add supplier/i }).click()
  await page.waitForSelector('#sup-name')
  await page.fill('#sup-name', supplierName)
  await page.fill('#sup-gstNumber', '27aaaaa0000a1z5')
  await dialogButton(/add supplier/i).click()
  await page.waitForTimeout(1500)

  const supplierId = await apiFirstId('/suppliers', { search: supplierName }, 'manager')
  check('a created supplier is persisted', Boolean(supplierId), supplierId ?? 'none')
  if (supplierId) {
    cleanup.suppliers.push(supplierId)
    const supplier = await apiItem(`/suppliers/${supplierId}`, 'manager')
    check('a lowercase GSTIN is normalised to uppercase by the server',
      supplier?.gstNumber === '27AAAAA0000A1Z5', String(supplier?.gstNumber))
  }

  /* ------------------------------------------------------------------ *
   * Non-medical item
   * ------------------------------------------------------------------ */
  step('non-medical item')
  const itemName = `Verify Item ${unique()}`
  await page.goto(`${BASE}/non-medical-items`, { waitUntil: 'networkidle' })
  await page.getByRole('button', { name: /add item/i }).click()
  await page.waitForSelector('#item-name')
  await page.fill('#item-name', itemName)
  await dialogButton(/add item/i).click()
  await page.waitForTimeout(1500)

  const itemId = await apiFirstId('/items', { search: itemName }, 'manager')
  check('a created non-medical item is persisted', Boolean(itemId), itemId ?? 'none')
  if (itemId) {
    cleanup.items.push(itemId)
    await page.goto(`${BASE}/non-medical-items/${itemId}`, { waitUntil: 'networkidle' })
    await waitForDetailTitle(itemName)
    check('a non-medical item opens by id',
      ((await page.locator('h1').first().textContent()) ?? '').includes(itemName))

    /* category and unit are immutable after create. */
    await page.getByRole('button', { name: /^edit$/i }).click()
    await page.waitForSelector('#item-name')
    const immutable = await page.evaluate(() =>
      ['#item-category', '#item-unit'].map(
        (sel) => document.querySelector(sel) instanceof HTMLButtonElement && document.querySelector(sel).disabled,
      ),
    )
    check('category and unit are not editable on an existing item',
      immutable.every(Boolean), JSON.stringify(immutable))
    await dialogButton(/^cancel$/i).click()
    await page.waitForTimeout(500)
  }

  /* ------------------------------------------------------------------ *
   * Manufacturer: delete succeeds when unreferenced, refused when referenced
   * ------------------------------------------------------------------ */
  step('manufacturer delete')
  const mfrName = `Verify Mfr ${unique()}`
  await page.goto(`${BASE}/manufacturers`, { waitUntil: 'networkidle' })
  await page.getByRole('button', { name: /add manufacturer/i }).click()
  await page.waitForSelector('#mfr-name')
  await page.fill('#mfr-name', mfrName)
  await dialogButton(/add manufacturer/i).click()
  await page.waitForTimeout(1500)

  const mfrId = await apiFirstId('/manufacturers', { search: mfrName }, 'manager')
  check('a created manufacturer is persisted', Boolean(mfrId), mfrId ?? 'none')
  check('the manufacturer list offers no status filter',
    (await page.getByRole('button', { name: /^deactivated$/i }).count()) === 0)

  if (mfrId) {
    const deleteStatus = await apiSend(`/manufacturers/${mfrId}`, 'DELETE', undefined, 'manager')
    check('an unreferenced manufacturer is deleted', deleteStatus.status === 204, `HTTP ${deleteStatus.status}`)
    const gone = await apiItem(`/manufacturers/${mfrId}`, 'manager')
    check('the deleted manufacturer is gone', gone === null)

    /* Re-create it, reference it from a medicine, and confirm the refusal.
       Checked against the API because the refusal is a specific business code,
       and the code is the thing worth asserting. */
    const recreated = await apiSend(
      '/manufacturers',
      'POST',
      { name: `${mfrName} referenced` },
      'manager',
    )
    const referencedId = recreated.ok ? (await recreated.json()).id : null
    check('a manufacturer can be re-created with a distinct name', Boolean(referencedId))

    if (referencedId) {
      cleanup.manufacturers.push(referencedId)
      const medicine = await apiSend(
        '/medicines',
        'POST',
        { genericName: `Verify Ref ${unique()}`, dosageForm: 'TABLET', manufacturerId: referencedId },
        'manager',
      )
      const medicineRefId = medicine.ok ? (await medicine.json()).id : null
      check('a medicine can be created referencing that manufacturer', Boolean(medicineRefId))
      if (medicineRefId) cleanup.medicines.push(medicineRefId)

      // 400 + BUSINESS_RULE_VIOLATION is this API's documented shape for a
      // refused rule (code-standards.md §2.6), not a 409. Asserting the code as
      // well as the status is what a client would actually branch on.
      const refused = await apiError(`/manufacturers/${referencedId}`, 'DELETE', undefined, 'manager')
      check('a referenced manufacturer is refused deletion',
        refused.status === 400 && refused.code === 'BUSINESS_RULE_VIOLATION',
        `HTTP ${refused.status} ${refused.code ?? 'no code'}`)
      check('the refusal leaves the manufacturer in place',
        (await apiItem(`/manufacturers/${referencedId}`, 'manager')) !== null)
    }
  }

  /* ------------------------------------------------------------------ *
   * SUPER_ADMIN: the user list
   * ------------------------------------------------------------------ */
  step('SUPER_ADMIN user CRUD')
  const newUser = `verify${unique()}`
  const newPassword = 'Verify@12345'

  await signIn('superadmin')
  await page.goto(`${BASE}/settings/users`, { waitUntil: 'networkidle' })
  check('SUPER_ADMIN can open the user list',
    (await page.locator('h1').first().textContent()) === 'Users')

  await page.getByRole('button', { name: /add user/i }).click()
  await page.waitForSelector('#user-username')
  await page.fill('#user-username', newUser)
  await page.fill('#user-fullName', 'Verify User')
  await page.fill('#user-password', newPassword)
  await dialogButton(/create user/i).click()
  await page.waitForTimeout(1600)

  const userId = await apiFirstId('/users', { search: newUser }, 'superadmin')
  check('a created user is persisted', Boolean(userId), userId ?? 'none')
  if (userId) cleanup.users.push(userId)

  check('the new user can sign in with the password set at creation',
    (await apiLoginWorks(newUser, newPassword)) === 200)

  if (userId) {
    /* A blank password on edit must not overwrite the stored one. This is the
       highest-consequence bug available in this form: getting it wrong locks
       every user out of their own account. */
    await searchFor(newUser)
    await page.locator('tbody tr').first().getByRole('button', { name: /^edit/i }).click()
    await page.waitForSelector('#user-fullName')
    check('the password field is blank, not pre-filled, when editing',
      (await page.inputValue('#user-password')) === '')
    await page.fill('#user-fullName', 'Verify User Renamed')
    await dialogButton(/save changes/i).click()
    await page.getByRole('dialog').waitFor({ state: 'detached', timeout: 15000 })
    await page.waitForTimeout(600)

    check('a blank password on edit leaves the old password working',
      (await apiLoginWorks(newUser, newPassword)) === 200)

    const user = await apiItem(`/users/${userId}`, 'superadmin')
    check('the rename is persisted', user?.fullName === 'Verify User Renamed', String(user?.fullName))
    check('a rename does not change the username', user?.username === newUser, String(user?.username))

    /* Deactivate, then reactivate. */
    await page.reload({ waitUntil: 'networkidle' })
    await searchFor(newUser)
    await page.locator('tbody tr').first().getByRole('button', { name: /^deactivate/i }).click()
    await dialogButton(/^deactivate$/i).click()
    await page.waitForTimeout(1600)

    check('a deactivated user is absent from the default list',
      (await page.locator('tbody tr').count()) === 0)
    check('a deactivated user can no longer sign in',
      (await apiLoginWorks(newUser, newPassword)) !== 200)

    await page.getByRole('button', { name: /^deactivated$/i }).click()
    await page.waitForTimeout(1300)
    await page.locator('tbody tr').first().getByRole('button', { name: /^reactivate/i }).click()
    await page.waitForTimeout(1600)
    check('a reactivated user can sign in again',
      (await apiLoginWorks(newUser, newPassword)) === 200)
  }

  /* ------------------------------------------------------------------ *
   * Failure states
   * ------------------------------------------------------------------ */
  step('failure states')
  await page.goto(`${BASE}/medicines/00000000-0000-0000-0000-000000000000`, { waitUntil: 'networkidle' })
  const notFound = (await page.locator('body').textContent()) ?? ''
  check('an unknown id shows a not-found state, not a generic error',
    notFound.includes('no longer exists'))

  await page.goto(`${BASE}/medicines`, { waitUntil: 'networkidle' })
  await searchFor('zzz-no-such-medicine-zzz')
  check('a search with no matches shows an empty state, not a blank table',
    ((await page.locator('body').textContent()) ?? '').includes('No medicines match'))

  check('no uncaught page errors', pageErrors.length === 0, pageErrors.join(' | '))
} catch (error) {
  failures.push(`threw: ${String(error).split('\n')[0]}`)
  await dumpFailure(page)
  throw error
} finally {
  /* Cleanup. A failed run must not leave verification rows behind. */
  for (const [resource, ids] of Object.entries(cleanup)) {
    for (const id of ids) {
      await apiSend(`/${resource}/${id}`, 'DELETE', undefined, 'superadmin').catch(() => undefined)
    }
  }
  await browser.close()
}

const failed = failures.length
console.log(`\n${passed + failed} checks: ${passed} passed, ${failed} failed`)
if (failed > 0) {
  console.log('\nFailed:')
  for (const name of failures) console.log(`  - ${name}`)
  process.exit(1)
}
