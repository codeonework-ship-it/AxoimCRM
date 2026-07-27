import AxeBuilder from '@axe-core/playwright'
import { expect, Page, test } from '@playwright/test'

const authenticatedPages = ['/', '/accounts', '/leads', '/pipeline', '/reports', '/admin', '/mobile', '/packs/bfsi', '/packs/commodity']

async function assertWcag(page: Page, label: string) {
  const result = await new AxeBuilder({ page })
    .withTags(['wcag2a', 'wcag2aa', 'wcag21aa', 'wcag22aa'])
    .analyze()
  const failures = result.violations.map(v => ({
    id: v.id,
    impact: v.impact,
    help: v.help,
    nodes: v.nodes.slice(0, 3).map(n => n.target.join(' ')),
  }))
  expect(failures, `${label} must have no automated WCAG 2.2 A/AA violations`).toEqual([])
}

async function signIn(page: Page) {
  const email = process.env.AXIOM_E2E_EMAIL
  const password = process.env.AXIOM_E2E_PASSWORD
  test.skip(!email || !password, 'AXIOM_E2E_EMAIL and AXIOM_E2E_PASSWORD are required')
  await page.goto('/login')
  await page.locator('#sso-email').fill(email!)
  await page.getByRole('button', { name: 'Sign in with credentials', exact: true }).click()
  await page.locator('#c-password').fill(password!)
  await page.getByRole('button', { name: 'Sign in', exact: true }).click()
  await expect(page).not.toHaveURL(/\/login(?:\?|$)/)
}

async function loadScreen(page: Page) {
  const load = page.getByRole('button', { name: 'Load Screen Data' })
  if (await load.isVisible()) await load.click()
}

test('login is keyboard-usable and meets WCAG 2.2 AA automated rules', async ({ page }) => {
  await page.goto('/login')
  await expect(page.getByRole('heading', { name: 'Sign in to Axiom' })).toBeVisible()
  await assertWcag(page, 'Login')
  await page.keyboard.press('Tab')
  await expect(page.locator(':focus')).toBeVisible()
})

test('P0 operational pages meet WCAG 2.2 AA automated rules', async ({ page }) => {
  await signIn(page)
  for (const path of authenticatedPages) {
    await page.goto(path)
    await loadScreen(page)
    await expect(page.getByRole('main')).toBeVisible()
    await assertWcag(page, path)
  }
})
