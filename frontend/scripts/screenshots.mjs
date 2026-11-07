import { chromium } from 'playwright-core'
import { mkdirSync } from 'node:fs'

const OUT = '/var/folders/wy/95tc6kh92db8s5nzhw66f4m80000gn/T/opencode/shots'
mkdirSync(OUT, { recursive: true })

const browser = await chromium.launch({
  executablePath: '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome',
  headless: true,
})
const context = await browser.newContext({ viewport: { width: 1440, height: 900 } })
const page = await context.newPage()

// 1. Login, empty.
await page.goto('http://localhost:5163/login', { waitUntil: 'networkidle' })
await page.waitForTimeout(700)
await page.screenshot({ path: `${OUT}/01-login.png` })

// 2. Login, validation errors shown.
await page.locator('#username').focus()
await page.locator('#password').focus()
await page.locator('#username').focus()
await page.locator('#password').focus()
await page.waitForTimeout(400)
await page.screenshot({ path: `${OUT}/02-login-errors.png` })

// 3. Login, password revealed.
await page.locator('#password').fill('demo-password')
await page.getByRole('button', { name: 'Show password' }).click()
await page.waitForTimeout(300)
await page.screenshot({ path: `${OUT}/03-login-filled.png` })

// 4. Authenticated shell.
await page.evaluate(async () => {
  const { useAuthStore } = await import('/src/store/auth.store.ts')
  useAuthStore.getState().setSession('t', {
    id: 'x', username: 'pharmacist1', fullName: 'Aiswarya Rao',
    roles: ['PHARMACIST', 'PHARMACY_MANAGER'], active: true,
  })
})
await page.waitForURL('**/dashboard')
await page.waitForTimeout(700)
await page.screenshot({ path: `${OUT}/04-dashboard.png` })

// 5. Account menu open.
await page.getByRole('button', { name: 'Account menu' }).click()
await page.waitForTimeout(400)
await page.screenshot({ path: `${OUT}/05-account-menu.png` })
await page.keyboard.press('Escape')
await page.waitForTimeout(300)

// 6. Sidebar collapsed.
await page.getByRole('button', { name: 'Collapse sidebar' }).click()
await page.waitForTimeout(500)
await page.screenshot({ path: `${OUT}/06-sidebar-collapsed.png` })
await page.getByRole('button', { name: 'Expand sidebar' }).click()
await page.waitForTimeout(400)

// 7. Placeholder route.
await page.evaluate(() => {
  window.history.pushState({}, '', '/medicines')
  window.dispatchEvent(new PopStateEvent('popstate', { state: {} }))
})
await page.waitForTimeout(600)
await page.screenshot({ path: `${OUT}/07-placeholder.png` })

// 8. Toast rendered.
await page.evaluate(() => {
  window.history.pushState({}, '', '/dashboard')
  window.dispatchEvent(new PopStateEvent('popstate', { state: {} }))
})
await page.waitForTimeout(500)
await page.evaluate(async () => {
  const { toast } = await import('/src/store/toast.store.ts')
  toast.success('Purchase order saved', 'PO-2026-0042 · 14 lines, 1 rejected')
  toast.error('Supplier not matched', 'No supplier matched "Aurobindo Pharma" in the OCR text')
})
await page.waitForTimeout(700)
await page.screenshot({ path: `${OUT}/08-toasts.png` })

// 9. Dark mode.
// Toggle the .dark class on the root. The dark tokens in index.css are declared
// under `.dark`, not behind a prefers-color-scheme media query, so
// emulateMedia({ colorScheme: 'dark' }) produces a byte-identical screenshot -
// verified by md5. Class-driven is deliberate: it lets a component be previewed
// in dark with one class, and unit 02's toggle will set it.
await page.evaluate(() => {
  document.documentElement.classList.add('dark')
  window.localStorage.setItem('pharmacy-ui', JSON.stringify({ state: { sidebarCollapsed: false }, version: 0 }))
})
await page.waitForTimeout(600)
await page.screenshot({ path: `${OUT}/09-dark.png` })

const darkApplied = await page.evaluate(() => {
  const style = getComputedStyle(document.documentElement)
  return {
    hasClass: document.documentElement.classList.contains('dark'),
    background: getComputedStyle(document.body).backgroundColor,
    primary: style.getPropertyValue('--primary').trim(),
    text: style.getPropertyValue('--foreground').trim(),
  }
})
console.log('dark check:', JSON.stringify(darkApplied))

console.log('screenshots written to', OUT)
await browser.close()
