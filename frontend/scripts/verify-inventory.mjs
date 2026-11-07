#!/usr/bin/env node
/**
 * verify-inventory.mjs — end-to-end check of the unit 07 inventory UI, driven
 * through the real UI against the real API.
 *
 * Why a browser suite rather than more unit tests: the failures these screens are
 * prone to are not in any single function. A cost cell that renders a price to a
 * CASHIER because a role was cached, a shortfall that raises an error state
 * instead of answering the question, a preview that fires per keystroke — each is
 * correct in isolation and wrong in sequence. Only driving the actual page finds
 * them.
 *
 * Two checks here are security checks, not cosmetics, and are the reason this
 * suite exists at all:
 *   - a CASHIER must see no cost figure anywhere, in any role;
 *   - a preview that cannot be filled must render an ANSWER, not an ERROR.
 *
 * The suite creates its own medicine and batches, and leaves **no allocatable
 * stock** behind: cleanup drains every batch to DEPLETED through the real FEFO
 * endpoint.
 *
 * It does not, and cannot, leave the database byte-identical. A batch that once
 * held stock cannot be deleted — stock is moved, never removed (invariant 2) —
 * and a medicine with batch history is rightly not hard-deletable either. So each
 * run leaves one DEPLETED medicine and its ledger rows behind, which is the audit
 * trail working as intended rather than a leak. An earlier version of this script
 * claimed it cleaned up completely while calling a DELETE endpoint that does not
 * exist; the calls 404'd, the script reported success, and 21 batches of live
 * stock accumulated. The claim is now one the script can actually keep.
 *
 * Run: npm run verify:inventory   (needs the backend on 8081)
 */
import { chromium } from 'playwright-core'
import { existsSync, mkdirSync } from 'node:fs'
import { homedir } from 'node:os'
import { join } from 'node:path'

const BASE = process.env.APP_URL ?? 'http://localhost:5163'
const API = process.env.API_URL ?? 'http://localhost:8081'
const PASSWORD = 'Pharmacy@Dev123'

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
let page

function step(name) {
  currentStep = name
  console.log(`\n--- ${name}`)
}

async function dumpFailure() {
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
    if (page) void dumpFailure()
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

/* ---------------------------- API helpers ---------------------------- */

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

async function apiSend(path, method, body, username = 'manager') {
  return fetch(`${API}/api/v1${path}`, {
    method,
    headers: {
      'Content-Type': 'application/json',
      Authorization: `Bearer ${await tokenFor(username)}`,
    },
    body: body === undefined ? undefined : JSON.stringify(body),
  })
}

async function apiGet(path, params = {}, username = 'manager') {
  const query = new URLSearchParams({ size: '50', ...params })
  const res = await fetch(`${API}/api/v1${path}?${query}`, {
    headers: { Authorization: `Bearer ${await tokenFor(username)}` },
  })
  return res.ok ? res.json() : null
}

const isoInDays = (days) => {
  const d = new Date()
  d.setDate(d.getDate() + days)
  return d.toISOString().slice(0, 10)
}

/* ------------------------------ browser ------------------------------ */

let browser
try {
  browser = await chromium.launch({ executablePath: chromePath() })
} catch (error) {
  console.error(String(error))
  process.exit(1)
}

const context = await browser.newContext()
page = await context.newPage()
const pageErrors = []
page.on('pageerror', (error) => pageErrors.push(error.message))

async function signIn(username) {
  await context.clearCookies()
  await page.goto(`${BASE}/login`, { waitUntil: 'networkidle' })
  await page.fill('#username', username)
  await page.fill('#password', USERS[username])
  await page.click('button[type="submit"]')
  await page.waitForURL((url) => !url.pathname.includes('/login'), { timeout: 20000 })
  await page.waitForLoadState('networkidle')
}

const bodyText = async () => ((await page.locator('body').textContent()) ?? '').replace(/\s+/g, ' ')

/* ------------------------------- fixture ----------------------------- */

const tag = String(Date.now() % 1000000)
const cleanup = { batches: [], medicines: [] }

/** Hoisted so the cleanup block can drain the fixture; see bands. */
let fixtureMedicineId = null

/**
 * Three batches in three expiry bands, so ExpiryBadge has a distinct tone for
 * each: critical (<=30d), warning (<=90d) and a date-rendered calm band.
 *
 * Declared out here rather than inside the try because the cleanup block needs
 * the quantities to drain the fixture, and a `finally` cannot see a `const` that
 * was scoped to the `try`.
 */
const bands = [
  { batchNumber: '', days: 12, qty: 30, cost: '12.00', sell: '18.00' },
  { batchNumber: '', days: 75, qty: 50, cost: '11.00', sell: '17.00' },
  { batchNumber: '', days: 200, qty: 40, cost: '10.00', sell: '16.00' },
].map((band) => ({ ...band, batchNumber: `VI-${band.days < 30 ? 'CRIT' : band.days < 90 ? 'WARN' : 'CALM'}-${tag}` }))

try {
  step('create a medicine and three batches with staggered expiries')
  const medName = `VerifyInv ${tag}`
  const medRes = await apiSend(
    '/medicines',
    'POST',
    { genericName: medName, dosageForm: 'TABLET', scheduleClass: 'OTC', reorderLevel: 10 },
    'manager',
  )
  const medicine = await medRes.json()
  fixtureMedicineId = medicine.id
  cleanup.medicines.push(medicine.id)

  for (const band of bands) {
    const res = await apiSend(
      '/inventory',
      'POST',
      {
        medicineId: medicine.id,
        batchNumber: band.batchNumber,
        expiryDate: isoInDays(band.days),
        quantityReceived: band.qty,
        costPrice: band.cost,
        sellingPrice: band.sell,
        location: 'MAIN_PHARMACY',
      },
      'manager',
    )
    const created = await res.json()
    cleanup.batches.push(created.id)
  }
  check('fixture created', cleanup.batches.length === 3)

  /* -------------------------- batches list -------------------------- */

  step('batches list renders the seeded batches')
  await signIn('manager')
  await page.goto(`${BASE}/inventory/batches`, { waitUntil: 'networkidle' })
  await page.fill('#resource-search', `VI-CRIT-${tag}`)
  await page.waitForTimeout(1200)
  let text = await bodyText()
  check('a seeded batch appears in the list', text.includes(`VI-CRIT-${tag}`))
  check('the list shows the available quantity', /\b30\b/.test(text))

  step('expiry badge shows a countdown, not a bare date')
  // Asserted on the badge cell itself, not on the whole page's text. `textContent`
  // concatenates adjacent cells with no separator, so a regex over the body sees
  // "...20819412d30" - the batch number's last digits run into the countdown -
  // and a word-boundary match on "12d" fails against a string that does contain
  // it. The cell is the unit under test.
  const expiryCell = await page.locator('table tbody tr td').nth(2).textContent()
  check('a near-expiry batch renders a day countdown', /^\d+d$/.test((expiryCell ?? '').trim()),
    `cell reads "${(expiryCell ?? '').trim()}"`)

  step('status filter narrows the list')
  await page.fill('#resource-search', '')
  await page.waitForTimeout(800)
  await page.selectOption('select[aria-label="Filter by status"]', 'ACTIVE')
  await page.waitForTimeout(1200)
  text = await bodyText()
  check('filtering by ACTIVE keeps the seeded batch', text.includes(`VI-CRIT-${tag}`))
  // Scoped to the table body. The page text also contains the filter dropdown's
  // own option labels - "Quarantined", "Depleted" - so a body-wide assertion
  // would fail on the control rather than on any rendered badge.
  const tableText = ((await page.locator('table').textContent()) ?? '')
  check('the rendered rows show only the active badge',
    tableText.includes('In stock') && !tableText.includes('Quarantined') && !tableText.includes('Depleted'))

  step('cost redaction — the security check')
  // A manager is entitled to cost and must actually see it, or the redaction
  // checks below would pass for the wrong reason. The fixture's cost is 12.00 and
  // its selling price 18.00, so the two figures are distinguishable in the text.
  check('a manager sees the cost figure', text.includes('₹12.00'), 'cost rendered')
  check('a manager sees the selling figure', text.includes('₹18.00'), 'selling rendered')

  await signIn('cashier')
  await page.goto(`${BASE}/inventory/batches`, { waitUntil: 'networkidle' })
  await page.fill('#resource-search', `VI-CRIT-${tag}`)
  await page.waitForTimeout(1200)
  const cashierText = await bodyText()
  // Precisely: the COST must be absent and the SELLING price present. Asserting
  // "no currency symbol at all" would be wrong - a cashier is entitled to what a
  // thing sells for, and rule 7 redacts the cost only.
  check('a cashier sees no cost figure', !cashierText.includes('₹12.00'), 'cost withheld')
  check('a cashier still sees the selling price', cashierText.includes('₹18.00'), 'selling shown')
  check("the cashier's cost cell renders an em dash",
    (await page.locator('table tbody tr td').nth(6).textContent())?.trim() === '—')

  const cashierPayload = await apiGet('/inventory', { search: `VI-CRIT-${tag}` }, 'cashier')
  const managerPayload = await apiGet('/inventory', { search: `VI-CRIT-${tag}` }, 'manager')
  check('the API omits costPrice for a cashier',
    !('costPrice' in (cashierPayload?.content?.[0] ?? {})))
  check('the API includes costPrice for a manager',
    'costPrice' in (managerPayload?.content?.[0] ?? {}))

  step('a cashier can open the batches list at all (CASHIER+)')
  check('the batches route renders for a cashier', cashierText.includes('Batches'))

  /* --------------------------- ledger drawer -------------------------- */

  step('row click opens the batch ledger')
  await page.click('table tbody tr')
  await page.waitForTimeout(1200)
  const drawerText = await bodyText()
  check('the ledger drawer opens', drawerText.includes('Ledger'))
  check('the receiving movement is in the ledger', /Received/.test(drawerText))
  check('the ledger shows a running balance', /\b30\b/.test(drawerText))
  await page.keyboard.press('Escape')
  await page.waitForTimeout(500)

  /* ---------------------------- FEFO preview -------------------------- */

  step('FEFO preview splits across batches in expiry order')
  await signIn('pharmacist')
  await page.goto(`${BASE}/inventory/fefo`, { waitUntil: 'networkidle' })
  step('the medicine picker finds a medicine by name')
  // Typed into the search box rather than selected from a pre-loaded list,
  // because the picker's contract is that any medicine in the catalogue is
  // reachable. Selecting by value here would pass even with a broken search.
  await page.fill('#fefo-medicine-search', medName)
  await page.waitForTimeout(1200)
  const optionValues = await page
    .locator('#fefo-medicine option')
    .evaluateAll((nodes) => nodes.map((n) => n.value))
  check('the search returns the fixture medicine', optionValues.includes(medicine.id),
    `${optionValues.length} option(s)`)
  await page.selectOption('#fefo-medicine', medicine.id)
  await page.fill('#fefo-quantity', '70')
  await page.click('button:has-text("Preview allocation")')
  await page.waitForTimeout(1500)
  text = await bodyText()
  check('the critical-expiry batch is allocated first', text.indexOf(`VI-CRIT-${tag}`) !== -1)
  check('the warning-expiry batch is allocated second', text.includes(`VI-WARN-${tag}`))
  check('the calm batch is not touched', !text.includes(`VI-CALM-${tag}`))
  check('the critical batch comes first in the table',
    text.indexOf(`VI-CRIT-${tag}`) < text.indexOf(`VI-WARN-${tag}`))
  check('a complete allocation offers dispense', /Dispense 70/.test(text))

  step('a shortfall is an answer, not an error')
  // 130 against the fixture's 30 + 50 + 40 = 120, so the split fills all three
  // bands and still comes up 10 short. An earlier version asked for 90, which is
  // a *complete* allocation of 120 available — the screen was right and the
  // expectation was wrong.
  await page.fill('#fefo-quantity', '130')
  await page.click('button:has-text("Preview allocation")')
  await page.waitForTimeout(1500)
  text = await bodyText()
  check('the shortfall is stated in the table', /Short by 10/.test(text))
  check('all three bands are shown', text.includes(`VI-CRIT-${tag}`) && text.includes(`VI-WARN-${tag}`) && text.includes(`VI-CALM-${tag}`))
  check('no error state is raised for an unfillable preview',
    !/could not|failed|something went wrong/i.test(text))
  check('dispense is blocked while the allocation is short',
    /Cannot dispense/.test(text))

  step('zero is a valid request, not an error')
  await page.fill('#fefo-quantity', '0')
  await page.click('button:has-text("Preview allocation")')
  await page.waitForTimeout(1500)
  text = await bodyText()
  check('zero renders an empty allocation without an error',
    !/could not|failed/i.test(text))

  step('dispense moves stock and reflects it')
  await page.fill('#fefo-quantity', '10')
  await page.click('button:has-text("Preview allocation")')
  await page.waitForTimeout(1500)
  await page.click('button:has-text("Dispense 10")')
  await page.waitForTimeout(1800)
  text = await bodyText()
  check('the dispense is confirmed on screen', /Dispensed 10/.test(text))

  const after = await apiGet('/inventory', { search: `VI-CRIT-${tag}` })
  const batch = after?.content?.[0]
  check('the balance fell by exactly 10', batch?.quantityAvailable === 20, `now ${batch?.quantityAvailable}`)

  const history = await fetch(`${API}/api/v1/inventory/${batch.id}/history`, {
    headers: { Authorization: `Bearer ${await tokenFor('pharmacist')}` },
  }).then((r) => r.json())
  const saleOut = history.filter((t) => t.transactionType === 'SALE_OUT')
  check('a SALE_OUT row was written', saleOut.length === 1, `${saleOut.length} row(s)`)
  check('the SALE_OUT delta is negative', saleOut[0]?.quantityChanged === -10)

  step('reconciliation is clean after dispensing')
  const recon = await apiGet('/inventory/reconciliation', {}, 'manager')
  check('no mismatches', Array.isArray(recon?.mismatches) && recon.mismatches.length === 0,
    `${recon?.mismatches?.length ?? '?'} mismatch(es)`)

  /* ------------------------------- nav ------------------------------- */

  step('the inventory nav links are live, not dimmed')
  const navText = await (async () => {
    await page.goto(`${BASE}/dashboard`, { waitUntil: 'networkidle' })
    return bodyText()
  })()
  check('the sidebar shows Inventory', navText.includes('Inventory'))
  await page.click('a[href="/inventory/batches"]')
  await page.waitForTimeout(1200)
  check('the Batches nav link navigates to a real page',
    page.url().includes('/inventory/batches') && (await bodyText()).includes('Batches'))
  check('no "not available" placeholder is shown for Batches',
    !/not available in this build/i.test(await bodyText()))

  check('no uncaught page errors', pageErrors.length === 0, pageErrors.join(' | '))
} catch (error) {
  failures.push(`threw: ${String(error).split('\n')[0]}`)
  await dumpFailure()
  throw error
} finally {
  /*
   * Cleanup, and the constraint that shapes it.
   *
   * There is no `DELETE /inventory/{id}`, and adding one would be wrong: stock is
   * never deleted, only moved (architecture-context.md invariant 2). An earlier
   * version of this script called a delete endpoint that does not exist, so the
   * calls 404'd, the script reported success, and 21 batches with live stock
   * accumulated in the dev database across runs.
   *
   * So the fixture is drained instead of deleted: each batch is dispensed to zero
   * through the real FEFO endpoint, which leaves it DEPLETED and therefore
   * invisible to allocation. Rows remain - that is the ledger working as designed -
   * but no run leaves allocatable stock behind, and the medicine is still
   * hard-deletable by the catalogue endpoint, which is why the medicine is
   * created at all.
   */
  if (fixtureMedicineId) {
    /*
     * Drain by summing the allocatable balance and requesting exactly that.
     *
     * The obvious shortcut — request a huge number and ignore the error — does
     * not work, and the reason is worth stating: `dispense` is all-or-nothing.
     * An over-request is refused with INSUFFICIENT_STOCK and **nothing** is
     * written, so a loop of over-requests drains nothing at all. The quantity has
     * to be right.
     *
     * Looped, because the run's own dispense has already moved stock, so the
     * balance at cleanup is not the balance at fixture creation.
     */
    for (let attempt = 0; attempt < 10; attempt += 1) {
      const remaining = await apiGet(
        '/inventory',
        { medicineId: fixtureMedicineId, status: 'ACTIVE', size: '50' },
        'manager',
      )
      const allocatable = (remaining?.content ?? []).filter((b) => b.quantityAvailable > 0)
      if (allocatable.length === 0) break
      const total = allocatable.reduce((sum, b) => sum + b.quantityAvailable, 0)
      const res = await apiSend(
        '/inventory/dispense',
        'POST',
        { medicineId: fixtureMedicineId, requestedQuantity: total, location: 'MAIN_PHARMACY' },
        'pharmacist',
      )
      if (!res.ok) break
    }
  }
  for (const id of cleanup.medicines) {
    await apiSend(`/medicines/${id}`, 'DELETE', undefined, 'superadmin').catch(() => undefined)
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
