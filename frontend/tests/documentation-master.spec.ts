import { expect, test } from '@playwright/test'

test('documentation drawer content is governed by the administration master', async ({ page }) => {
  test.skip(!process.env.AXIOM_E2E_EMAIL || !process.env.AXIOM_E2E_PASSWORD,
    'AXIOM_E2E_EMAIL and AXIOM_E2E_PASSWORD are required')
  await page.goto('/login')
  await page.locator('#sso-email').fill(process.env.AXIOM_E2E_EMAIL!)
  await page.getByRole('button', { name: 'Sign in with credentials', exact: true }).click()
  await page.locator('#c-password').fill(process.env.AXIOM_E2E_PASSWORD!)
  await page.getByRole('button', { name: 'Sign in', exact: true }).click()
  await expect(page).not.toHaveURL(/\/login(?:\?|$)/)

  await page.goto('/admin/documentation')
  await page.getByRole('button', { name: 'Load Screen Data' }).click()
  await expect(page.getByRole('heading', { name: 'Documentation Drawer Master' })).toBeVisible()
  await expect(page.getByRole('cell', { name: 'SCAN_HOME', exact: true })).toBeVisible()
  await expect(page.getByText(/Version 3 .* 10 sections .* 62 entries/)).toBeVisible()

  await page.getByRole('button', { name: 'Edit Entry' }).first().click()
  await expect(page.getByLabel('EN title')).toHaveValue('Your fastest route')

  await page.getByRole('button', { name: 'User Manual', exact: true }).click()
  const drawer = page.getByRole('dialog', { name: 'Axiom User Manual' })
  await expect(drawer).toBeVisible()
  await expect(drawer.getByText('Your fastest route', { exact: true })).toBeVisible()
})
