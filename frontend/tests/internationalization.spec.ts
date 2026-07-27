import { expect, Page, test } from '@playwright/test'

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

async function selectLocale(page: Page, currentLabel: RegExp, localeName: string) {
  await page.getByRole('button', { name: currentLabel }).click()
  await page.getByRole('menuitemradio', { name: new RegExp(localeName, 'i') }).click()
}

test('the public login screen exposes language selection before authentication', async ({ page }) => {
  await page.goto('/login')
  await selectLocale(page, /Language: English/i, 'Deutsch')

  await expect(page.locator('html')).toHaveAttribute('lang', 'de')
  await expect(page.getByRole('heading', { name: 'Bei Axiom anmelden' })).toBeVisible()
  await expect(page.getByRole('button', { name: 'Mit Single Sign-on fortfahren' })).toBeVisible()
  await expect(page.getByText('Noch kein Konto?', { exact: true })).toBeVisible()
})

test('locale selection translates grids, reports, navigation and the documentation drawer', async ({ page }) => {
  await signIn(page)

  await selectLocale(page, /Language: English/i, 'Deutsch')
  await expect(page.locator('html')).toHaveAttribute('lang', 'de')
  await expect(page.getByRole('button', { name: 'Benutzerhandbuch' })).toBeVisible()
  await expect(page.getByText('Kunden', { exact: true }).first()).toBeVisible()

  await page.goto('/reports')
  await page.getByRole('button', { name: 'Bildschirmdaten laden' }).click()
  await expect(page.getByRole('heading', { name: 'CRM-Berichte' })).toBeVisible()
  await expect(page.getByText('Berichtsstudio', { exact: true }).first()).toBeVisible()
  await expect(page.getByText('Dokumentvorschau', { exact: true }).first()).toBeVisible()
  await expect(page.getByRole('heading', { name: 'Zusammenfassung der Umsatzsteuerung' }).first()).toBeVisible()
  await expect(page.getByText('Wie ist die aktuelle Geschäftslage dieses Unternehmens?', { exact: true }).first()).toBeVisible()

  await page.getByRole('button', { name: 'Benutzerhandbuch' }).click()
  await expect(page.getByRole('dialog', { name: 'Axiom Benutzerhandbuch' })).toBeVisible()
  await expect(page.getByText('Ihr schnellster Weg', { exact: true })).toBeVisible()
  await page.getByRole('button', { name: 'Anleitung schließen' }).click()

  await selectLocale(page, /Sprache: Deutsch/i, 'Русский')
  await expect(page.locator('html')).toHaveAttribute('lang', 'ru')
  await expect(page.getByRole('button', { name: 'Руководство пользователя' })).toBeVisible()
  await expect(page.getByText('Отчеты CRM', { exact: true })).toBeVisible()
  await expect(page.getByRole('heading', { name: 'Сводка управления выручкой' }).first()).toBeVisible()
})

test('long translated header labels never overlap notification or language controls', async ({ page }) => {
  await page.setViewportSize({ width: 1690, height: 760 })
  await signIn(page)
  await selectLocale(page, /Language: English/i, 'Русский')

  const bell = await page.locator('.notification-control').boundingBox()
  const manual = await page.locator('.manual-button').boundingBox()
  const language = await page.locator('.locale-switch').boundingBox()
  expect(bell).not.toBeNull()
  expect(manual).not.toBeNull()
  expect(language).not.toBeNull()
  expect(bell!.x + bell!.width).toBeLessThanOrEqual(manual!.x)
  expect(manual!.x + manual!.width).toBeLessThanOrEqual(language!.x)

  const labelIsContained = await page.locator('.manual-button-label').evaluate((label) =>
    label.scrollWidth <= label.clientWidth,
  )
  expect(labelIsContained).toBe(true)
})
