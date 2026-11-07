/**
 * Theme regression gate.
 *
 * Guards the failure where `.dark` was declared in index.css, looked correct,
 * and did not exist in the compiled stylesheet at all. Tailwind v3 purges class
 * selectors not found in the `content` globs, and no .tsx mentioned "dark", so
 * the whole token block was dropped. Adding the class to <html> then did
 * nothing, and a dark screenshot came out byte-identical to the light one.
 *
 * Nothing about that is visible from reading the source. It is only visible by
 * rendering. So it is asserted here.
 *
 *   node scripts/verify-theme.mjs
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
const page = await browser.newPage()
await page.goto(BASE, { waitUntil: 'networkidle' })
await page.waitForTimeout(500)

// ---------- the .dark rule must exist in the stylesheet ----------
const darkRule = await page.evaluate(() => {
  for (const sheet of Array.from(document.styleSheets)) {
    let rules
    try {
      rules = Array.from(sheet.cssRules)
    } catch {
      continue
    }
    for (const rule of rules) {
      if (rule.selectorText === '.dark') {
        return {
          exists: true,
          background: rule.style.getPropertyValue('--background').trim(),
          primary: rule.style.getPropertyValue('--primary').trim(),
        }
      }
    }
  }
  return { exists: false }
})

check(
  '.dark token rule is present in the compiled stylesheet',
  darkRule.exists,
  darkRule.exists ? `bg=${darkRule.background}` : 'purged out of the CSS',
)
check(
  '.dark declares a different background from :root',
  darkRule.exists && darkRule.background !== '',
  darkRule.background,
)

// ---------- and it must actually take effect when toggled ----------
const read = () =>
  page.evaluate(() => ({
    background: getComputedStyle(document.documentElement)
      .getPropertyValue('--background')
      .trim(),
    primary: getComputedStyle(document.documentElement)
      .getPropertyValue('--primary')
      .trim(),
    bodyBg: getComputedStyle(document.body).backgroundColor,
  }))

const light = await read()
check('light tokens resolve', light.background !== '', light.background)

await page.evaluate(() => document.documentElement.classList.add('dark'))
await page.waitForTimeout(300)
const dark = await read()

check('toggling .dark changes --background',
  dark.background !== light.background, `${light.background} -> ${dark.background}`)
check('toggling .dark changes --primary',
  dark.primary !== light.primary, `${light.primary} -> ${dark.primary}`)
check('body background actually repaints in dark',
  dark.bodyBg !== light.bodyBg, `${light.bodyBg} -> ${dark.bodyBg}`)
check('dark body background is darker than light',
  (() => {
    const lum = (rgb) => {
      const m = rgb.match(/\d+/g).map(Number)
      return 0.2126 * m[0] + 0.7152 * m[1] + 0.0722 * m[2]
    }
    return lum(dark.bodyBg) < lum(light.bodyBg)
  })(), `${light.bodyBg} -> ${dark.bodyBg}`)

// ---------- contrast: text must be legible on the dark surface ----------
const contrast = await page.evaluate(() => {
  const parse = (value) => value.match(/[\d.]+/g).map(Number)
  const lum = ([r, g, b]) => {
    const f = (c) => {
      const s = c / 255
      return s <= 0.03928 ? s / 12.92 : ((s + 0.055) / 1.055) ** 2.4
    }
    return 0.2126 * f(r) + 0.7152 * f(g) + 0.0722 * f(b)
  }
  const bg = parse(getComputedStyle(document.body).backgroundColor)
  const fg = parse(getComputedStyle(document.body).color)
  const l1 = lum(fg)
  const l2 = lum(bg)
  const [hi, lo] = l1 > l2 ? [l1, l2] : [l2, l1]
  return (hi + 0.05) / (lo + 0.05)
})

// WCAG AA for body text. A pharmacist reads this under bad lighting; a theme
// that technically "works" but renders grey-on-grey is not a pass.
check('dark foreground/background contrast >= 4.5:1 (WCAG AA)',
  contrast >= 4.5, `${contrast.toFixed(2)}:1`)

await browser.close()

const failed = results.filter((r) => !r.passed)
console.log(`\n${results.length - failed.length}/${results.length} checks passed`)
if (failed.length > 0) {
  console.log('\nFailed:')
  failed.forEach((f) => console.log(`  - ${f.name} ${f.detail}`))
  process.exit(1)
}
