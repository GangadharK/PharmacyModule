#!/usr/bin/env node
/**
 * verify-alerts.mjs — end-to-end check of the unit 09 alert and cycle-count UI,
 * driven through the real UI against the real API.
 *
 * Same reasoning as verify-inventory: the failures these screens are prone to are
 * in the sequence, not in any one function. A shortfall that renders as a blank
 * cell, a severity badge that shows "Low" for a stock-out, a count that posts a
 * half-finished session — each is correct in isolation and wrong in sequence.
 *
 * Two checks here are correctness checks about a pharmacist's time and safety:
 *   - a CASHIER can read the alerts, because a cashier standing at the counter is
 *     the person who notices an empty shelf, and neither response carries a cost;
 *   - a count with an uncounted line cannot be posted, and says how many are
 *     missing, rather than quietly posting what it did manage to count.
 *
 * The suite creates its own medicine and batches, and leaves **no allocatable
 * stock** behind: cleanup drains every batch to DEPLETED through the real FEFO
 * endpoint. It cannot leave the database byte-identical — a batch that once held
 * stock cannot be deleted (invariant 2) — so each run leaves DEPLETED history
 * behind, which is the audit trail working rather than a leak.
 */

import { chromium } from 'playwright-core'
import { existsSync } from 'node:fs'

const BASE = process.env.APP_URL ?? 'http://localhost:5163'
const API = process.env.API_URL ?? 'http://localhost:8081'
const USERS = {
  superadmin: process.env.SUPERADMIN_PASSWORD ?? 'Pharmacy@Dev123',
  manager: process.env.MANAGER_PASSWORD ?? 'Pharmacy@Dev123',
  pharmacist: process.env.PHARMACIST_PASSWORD ?? 'Pharmacy@Dev123',
  cashier: process.env.CASHIER_PASSWORD ?? 'Pharmacy@Dev123',
}

let passed = 0
const failures = []
let page

function step(name) {
  console.log(`\n── ${name}`)
}

function check(label, condition, detail = '') {
  if (condition) {
    passed += 1
    console.log(`   ok  ${label}`)
  } else {
    failures.push(label)
    console.log(`   FAIL ${label}${detail ? ` — ${detail}` : ''}`)
  }
}

function chromePath() {
  if (process.env.CHROME_PATH) return process.env.CHROME_PATH
  const candidates = [
    '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome',
    '/Applications/Chromium.app/Contents/MacOS/Chromium',
    '/usr/bin/google-chrome',
    '/usr/bin/chromium',
  ]
  return candidates.find((candidate) => existsSync(candidate)) ?? undefined
}

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
const cleanup = { batches: [], medicines: [], counts: [] }

/**
 * Closes a count the suite opened, by counting every line as its own system
 * figure and posting. A zero-variance post writes no ledger row, so this leaves
 * a completed session rather than a plausible-looking adjustment.
 *
 * Needed because a run that throws part-way would otherwise abandon an OPEN
 * count, and the next run's list assertions would trip over it.
 */
async function closeCount(countId) {
  const fresh = await apiGet(`/inventory/cycle-counts/${countId}`)
  if (!fresh || fresh.status !== 'OPEN') return
  for (const line of fresh.lines) {
    if (line.countedQuantity === null || line.countedQuantity === undefined) {
      await apiSend(
        `/inventory/cycle-counts/${countId}/lines/${line.id}`,
        'PATCH',
        { countedQuantity: line.systemQuantity },
        'manager',
      )
    }
  }
  await apiSend(`/inventory/cycle-counts/${countId}/post`, 'POST', {}, 'manager')
}
let fixtureMedicineId = null

/**
 * Three batches whose combined holding is deliberately *above* the reorder level,
 * plus one line that is genuinely low.
 *
 * The first is the point: a naive per-batch check would flag all three, and the
 * screen would then teach a pharmacist to ignore it. The list has to show the sum.
 */
const fixture = {
  genericName: `VerifyAlerts ${tag}`,
  reorderLevel: 10,
  batches: [
    { batchNumber: `VA-SUM-${tag}`, days: 40, qty: 4 },
    { batchNumber: `VA-SUM2-${tag}`, days: 70, qty: 4 },
    { batchNumber: `VA-SUM3-${tag}`, days: 100, qty: 4 },
    { batchNumber: `VA-LOW-${tag}`, days: 12, qty: 3 },
  ],
}

try {
  step('create a fixture: one medicine summed above its level, one genuinely low')
  const medRes = await apiSend(
    '/medicines',
    'POST',
    {
      genericName: fixture.genericName,
      dosageForm: 'TABLET',
      scheduleClass: 'OTC',
      reorderLevel: fixture.reorderLevel,
    },
    'manager',
  )
  const medicine = await medRes.json()
  fixtureMedicineId = medicine.id
  cleanup.medicines.push(medicine.id)

  // A second medicine, so the "summed above the level" case has its own row to be
  // absent from rather than being masked by the low one.
  const lowRes = await apiSend(
    '/medicines',
    'POST',
    { genericName: `VA-Low ${tag}`, dosageForm: 'TABLET', scheduleClass: 'OTC', reorderLevel: 10 },
    'manager',
  )
  const lowMedicine = await lowRes.json()
  cleanup.medicines.push(lowMedicine.id)

  for (const band of fixture.batches) {
    const res = await apiSend(
      '/inventory',
      'POST',
      {
        medicineId: medicine.id,
        batchNumber: band.batchNumber,
        expiryDate: isoInDays(band.days),
        quantityReceived: band.qty,
        costPrice: '10.00',
        sellingPrice: '15.00',
        location: 'MAIN_PHARMACY',
      },
      'manager',
    )
    const created = await res.json()
    cleanup.batches.push(created.id)
  }

  const lowBatch = await apiSend(
    '/inventory',
    'POST',
    {
      medicineId: lowMedicine.id,
      batchNumber: `VA-LOWONLY-${tag}`,
      expiryDate: isoInDays(400),
      quantityReceived: 3,
      costPrice: '10.00',
      sellingPrice: '15.00',
      location: 'MAIN_PHARMACY',
    },
    'manager',
  )
  cleanup.batches.push((await lowBatch.json()).id)
  check('fixture created', cleanup.batches.length === fixture.batches.length + 1)

  /* --------------------------- low stock ---------------------------- */

  step('low stock sums across batches')
  const lowStock = await apiGet('/inventory/alerts/low-stock')
  const summed = (lowStock ?? []).find((row) => row.id === medicine.id)
  check(
    'a medicine holding 12 against a level of 10 is not listed',
    summed === undefined,
    summed ? `listed with ${summed.totalAvailable} held` : '',
  )
  const low = (lowStock ?? []).find((row) => row.id === lowMedicine.id)
  check('a medicine holding 3 against a level of 10 is listed', low !== undefined)
  check('its shortfall is the gap, not the holding', low?.shortfall === 7, `got ${low?.shortfall}`)
  check('its batch count is the number of batches, not units', low?.activeBatchCount === 1)
  check('a line with stock left is LOW, not OUT_OF_STOCK', low?.severity === 'LOW', low?.severity)

  step('low stock screen renders the alert')
  await signIn('manager')
  await page.goto(`${BASE}/alerts/low-stock`, { waitUntil: 'networkidle' })
  await page.waitForTimeout(800)
  let text = await bodyText()
  check('the low line appears', text.includes(`VA-Low ${tag}`))
  check('the summed line does not', !text.includes(`VerifyAlerts ${tag}`))
  // Scoped to the row's own cell, not the whole page: "Low Stock" in the sidebar
  // satisfies a bare includes('Low') and made this check pass without testing
  // anything at all.
  const lowRow = page
    .locator('tr')
    .filter({ hasText: `VA-Low ${tag}` })
    .first()
  const lowRowText = ((await lowRow.textContent()) ?? '').replace(/\s+/g, ' ').trim()
  check('the row is graded LOW, with stock left', lowRowText.includes('Low'), lowRowText)
  check('the row shows the level it is judged against', lowRowText.includes('10'), lowRowText)
  check(
    'the shortfall is rendered with its unit',
    lowRowText.includes('7 units short'),
    lowRowText,
  )
  check('no cost figure is rendered', !text.includes('₹10.00'))

  step('a CASHIER can read the alerts')
  const cashierToken = await tokenFor('cashier')
  const cashierRes = await fetch(`${API}/api/v1/inventory/alerts/low-stock`, {
    headers: { Authorization: `Bearer ${cashierToken}` },
  })
  check('the endpoint allows a cashier', cashierRes.ok, `HTTP ${cashierRes.status}`)
  await signIn('cashier')
  await page.goto(`${BASE}/alerts/low-stock`, { waitUntil: 'networkidle' })
  await page.waitForTimeout(800)
  const cashierText = await bodyText()
  check('the screen renders for a cashier', cashierText.includes(`VA-Low ${tag}`))
  check('the nav link is visible to a cashier', cashierText.includes('Low Stock'))

  /* -------------------------- near expiry --------------------------- */

  step('near expiry honours the window')
  const soon = await apiGet('/inventory/alerts/near-expiry', { withinDays: '30' })
  const soonBatches = new Set((soon ?? []).map((r) => r.batchNumber))
  check('a 12-day batch is inside a 30-day window', soonBatches.has(`VA-LOW-${tag}`))
  check(
    'a 40-day batch is not',
    !soonBatches.has(`VA-SUM-${tag}`),
    'the window ignored the batch',
  )
  const far = await apiGet('/inventory/alerts/near-expiry', { withinDays: '90' })
  check(
    'widening the window brings it in',
    (far ?? []).some((r) => r.batchNumber === `VA-SUM-${tag}`),
  )
  const capped = await apiGet('/inventory/alerts/near-expiry', { withinDays: '5000' })
  check('an absurd window is capped rather than rejected', capped !== null)

  step('near expiry screen renders a countdown')
  await signIn('manager')
  await page.goto(`${BASE}/alerts/near-expiry`, { waitUntil: 'networkidle' })
  await page.waitForTimeout(800)
  text = await bodyText()
  check('a batch inside the window appears', text.includes(`VA-SUM-${tag}`))
  const expiryRowText = ((await page
    .locator('tr')
    .filter({ hasText: `VA-SUM-${tag}` })
    .first()
    .textContent()) ?? '').replace(/\s+/g, ' ')
  check(
    'the countdown is rendered as days',
    expiryRowText.includes('40d'),
    expiryRowText,
  )

  /* -------------------------- cycle counts -------------------------- */

  step('a cycle count session records the system quantity')
  const openRes = await apiSend(
    '/inventory/cycle-counts',
    'POST',
    { location: 'MAIN_PHARMACY', notes: `verify ${tag}` },
    'manager',
  )
  const opened = await openRes.json()
  check('a count opens', openRes.ok && typeof opened.id === 'string')
  cleanup.counts.push(opened.id)
  check('it snapshots every countable batch at the location', opened.lineCount >= 4)
  const snapshotted = opened.lines.filter((l) => l.systemQuantity > 0)
  check('the snapshot carries a real balance', snapshotted.length >= 4)
  check('no line is counted yet', opened.uncountedLines === opened.lineCount)
  check('nothing is waiting to be posted', opened.pendingVariances === 0)

  step('a cashier cannot open a count')
  const cashierCount = await apiSend(
    '/inventory/cycle-counts',
    'POST',
    { location: 'MAIN_PHARMACY' },
    'cashier',
  )
  check(
    'the endpoint refuses a cashier',
    cashierCount.status === 403,
    `HTTP ${cashierCount.status}`,
  )

  step('a count with an uncounted line cannot be posted')
  // Deliberately not lines[0]: the count covers the whole location, including
  // zero-held batches left by earlier runs, and a shortage of -2 is invalid input
  // rather than a shortage. Counting the mistake as a passing test would hide it.
  const one = opened.lines.find((l) => l.systemQuantity > 0)
  check('the snapshot has a line with stock to be short of', one !== undefined)
  await apiSend(
    `/inventory/cycle-counts/${opened.id}/lines/${one.id}`,
    'PATCH',
    { countedQuantity: Math.max(0, one.systemQuantity - 2) },
    'manager',
  )
  const partial = await apiSend(`/inventory/cycle-counts/${opened.id}/post`, 'POST', {}, 'manager')
  const partialBody = await partial.json()
  check('the post is refused', !partial.ok, `HTTP ${partial.status}`)
  check(
    'it says how many lines are missing',
    typeof partialBody.message === 'string' && partialBody.message.includes('not been counted'),
    partialBody.message,
  )

  const afterPartial = await apiGet(`/inventory/cycle-counts/${opened.id}`)
  check('the session is still open', afterPartial.status === 'OPEN')
  check('the counted line was not applied', afterPartial.pendingVariances === 1)
  const batchAfter = await apiGet(`/inventory/${one.batchId}`)
  check(
    'and the batch balance is untouched',
    batchAfter.quantityAvailable === one.systemQuantity,
    `${batchAfter.quantityAvailable} vs ${one.systemQuantity}`,
  )

  step('finishing the count posts the variance')
  for (const line of opened.lines.slice(1)) {
    await apiSend(
      `/inventory/cycle-counts/${opened.id}/lines/${line.id}`,
      'PATCH',
      { countedQuantity: line.systemQuantity },
      'manager',
    )
  }
  const posted = await apiSend(`/inventory/cycle-counts/${opened.id}/post`, 'POST', {}, 'manager')
  const postedBody = await posted.json()
  check('the post succeeds', posted.ok, `HTTP ${posted.status}`)
  check('exactly one adjustment is applied', postedBody.variancesApplied === 1)
  check('and it is recorded as a shortage', postedBody.shortages === 1)

  const after = await apiGet(`/inventory/cycle-counts/${opened.id}`)
  check('the session is now posted', after.status === 'POSTED')
  check('nothing is left pending', after.pendingVariances === 0)

  // A plain array, not a Page: the batch ledger is unbounded and paged-by-nothing,
  // so an earlier version of this script read `.content` and found no rows at all,
  // then reported the adjust as unrecorded when it had been recorded correctly.
  const adjusted = await apiGet(`/inventory/${one.batchId}/history`, { size: '50' })
  const ledger = Array.isArray(adjusted) ? adjusted : []
  const adjusts = ledger.filter((t) => t.transactionType === 'CYCLE_COUNT_ADJUST')
  check('the ledger records a CYCLE_COUNT_ADJUST', adjusts.length > 0)
  check(
    'it points at the count that made it',
    adjusts.some((t) => t.referenceId === opened.id),
    adjusts.map((t) => t.referenceId).join(', '),
  )
  const adjustRow = adjusts.find((t) => t.referenceId === opened.id)
  check(
    'and it carries the shortage as a negative movement',
    adjustRow?.quantityChanged === Math.max(0, one.systemQuantity - 2) - one.systemQuantity,
    `got ${adjustRow?.quantityChanged}`,
  )

  step('counting a line in the browser moves the summary')
  // The regression this exists for: the session used to merge the saved line into
  // local state and leave `uncountedLines` at its opening value, so the Post
  // button could never enable no matter how many lines were counted. Asserted
  // through the UI, because the API was always correct — the staleness was local.
  const uiOpenRes = await apiSend(
    '/inventory/cycle-counts',
    'POST',
    { location: 'MAIN_PHARMACY' },
    'manager',
  )
  const uiOpen = await uiOpenRes.json()
  cleanup.counts.push(uiOpen.id)
  await signIn('manager')
  await page.goto(`${BASE}/inventory/cycle-counts`, { waitUntil: 'networkidle' })
  // Wait for the row, not for a fixed delay: the list refetches after the
  // session is created, and clicking a table that is mid-swap silently does
  // nothing, which showed up as a timeout on the dialog rather than as a
  // failure to open it.
  // `tbody`, not `tr`: a bare `tr` also matches the table's header row, and it
  // comes first in the DOM, so a filter that stopped being specific would click
  // the header — silently, since a header row has no click handler. Asserted
  // rather than assumed, because the symptom was a dialog that never opened.
  // `apiSend` hands back the raw Response, so this has to be awaited through
  // .json(). Reading `.body` instead yields a ReadableStream, which stringifies
  // to `{}` and answers every property access with undefined — a failure that
  // looks like a server bug and is not one.
  check(
    'the browser session was created with a reference',
    !!uiOpen.referenceNo,
    `HTTP ${uiOpenRes.status}`,
  )
  const uiRow = page.locator('tbody tr').filter({ hasText: uiOpen.referenceNo }).first()
  await uiRow.waitFor({ state: 'visible' })
  await uiRow.click()
  const sessionDialog = page.locator('[role="dialog"]')
  await sessionDialog.waitFor({ state: 'visible' })
  await sessionDialog.locator('input[type="number"]').first().waitFor()

  // Scoped to the dialog, and parsed rather than matched whole: the same words
  // also appear in the list behind it ("Open 6 (6 uncounted)"), so a page-wide
  // locator reads the list row it just navigated away from.
  const dialog = sessionDialog
  const uncountedIn = async () => {
    const shown = ((await dialog.getByText(/(\d+)\s*uncounted/).first().textContent()) ?? '')
    const found = shown.match(/(\d+)\s*uncounted/)
    return found ? Number(found[1]) : Number.NaN
  }
  const postButton = dialog.locator('button').filter({ hasText: /^Post/ }).first()

  const uncountedBefore = await uncountedIn()
  check('the session opens with every line uncounted', uncountedBefore === uiOpen.lineCount)

  const firstInput = dialog.locator('input[type="number"]').first()
  await firstInput.fill('1')
  await firstInput.blur()
  await page.waitForTimeout(1500)

  const uncountedAfter = await uncountedIn()
  check(
    'counting one line decrements the uncounted figure',
    uncountedAfter === uncountedBefore - 1,
    `${uncountedBefore} -> ${uncountedAfter}`,
  )
  check(
    'and Post stays refused while a line is still uncounted',
    await postButton.isDisabled(),
    'Post was enabled with lines outstanding',
  )
  await dialog.locator('button', { hasText: /^Close$/ }).first().click()
  await page.waitForTimeout(500)

  // Leave the session counted-but-unposted rather than open: an abandoned count
  // would sit in the list forever and be picked up by a later run's assertions.
  const uiLines = (await apiGet(`/inventory/cycle-counts/${uiOpen.id}`)).lines
  for (const line of uiLines.slice(1)) {
    await apiSend(
      `/inventory/cycle-counts/${uiOpen.id}/lines/${line.id}`,
      'PATCH',
      { countedQuantity: line.systemQuantity },
      'manager',
    )
  }
  const uiPosted = await apiSend(`/inventory/cycle-counts/${uiOpen.id}/post`, 'POST', {}, 'manager')
  check('the UI-touched session closes cleanly', uiPosted.ok, `HTTP ${uiPosted.status}`)

  step('cycle count screen lists the session')
  await signIn('manager')
  await page.goto(`${BASE}/inventory/cycle-counts`, { waitUntil: 'networkidle' })
  await page.waitForTimeout(800)
  text = await bodyText()
  check('the count appears in the list', text.includes(opened.referenceNo))
  check('its status is shown as posted', /Posted/i.test(text))
} catch (error) {
  failures.push(`threw: ${error.message}`)
  console.log(`\n   THREW ${error.message}`)
} finally {
  step('cleanup')
  if (fixtureMedicineId) {
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
  for (const id of cleanup.counts) await closeCount(id)
  for (const id of cleanup.medicines) {
    await apiSend(`/medicines/${id}`, 'DELETE', undefined, 'superadmin').catch(() => undefined)
  }
  await browser.close()
}

check('no uncaught page errors', pageErrors.length === 0, pageErrors.join('; '))

const failed = failures.length
console.log(`\n${passed + failed} checks: ${passed} passed, ${failed} failed`)
if (failed > 0) {
  console.log('\nFailed:')
  for (const name of failures) console.log(`  - ${name}`)
  process.exit(1)
}
